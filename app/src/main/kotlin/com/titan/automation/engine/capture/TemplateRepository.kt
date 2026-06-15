package com.titan.automation.engine.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.titan.automation.data.db.MacroDatabase
import com.titan.automation.data.db.TemplateEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TemplateRepository — manages bitmap templates for vision matching.
 *
 * Storage hierarchy (checked in order):
 *   1. In-memory LRU cache (capacity 32) — zero-alloc hot path
 *   2. Room [TemplateEntity] table (binary blob) — persists across restarts
 *   3. assets/templates/<templateId>.png — bundled templates
 *   4. files/templates/<pluginId>/<templateId>.png — plugin-installed templates
 *
 * Adding a template:
 *   - Call [addTemplate] with a Bitmap — automatically stored in Room
 *   - Or bundle PNG in assets/templates/ — picked up automatically at runtime
 */
@Singleton
class TemplateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: MacroDatabase
) {
    // LRU cache: templateId → Bitmap (not thread-safe, but Dispatchers.Default handles it)
    private val cache = LinkedHashMap<String, Bitmap>(32, 0.75f, true)
    private val maxCache = 32

    suspend fun getTemplate(templateId: String): Bitmap? = withContext(Dispatchers.IO) {
        // 1. Memory cache
        cache[templateId]?.let { return@withContext it }

        // 2. Room database
        val entity = db.templateDao().getById(templateId)
        if (entity != null) {
            val bmp = BitmapFactory.decodeByteArray(entity.bitmapBytes, 0, entity.bitmapBytes.size)
            if (bmp != null) { putCache(templateId, bmp); return@withContext bmp }
        }

        // 3. Assets bundle
        runCatching {
            context.assets.open("templates/$templateId.png").use { stream ->
                val bmp = BitmapFactory.decodeStream(stream)
                if (bmp != null) putCache(templateId, bmp)
                bmp
            }
        }.getOrNull()?.let { return@withContext it }

        // 4. Plugin files directory
        runCatching {
            File(context.filesDir, "templates/$templateId.png").let { f ->
                if (!f.exists()) return@let null
                BitmapFactory.decodeFile(f.absolutePath)?.also { putCache(templateId, it) }
            }
        }.getOrNull()
    }

    suspend fun addTemplate(templateId: String, bitmap: Bitmap, label: String = "", pluginId: String = "") {
        withContext(Dispatchers.IO) {
            putCache(templateId, bitmap)
            val bytes = ByteArrayOutputStream().also { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }.toByteArray()
            db.templateDao().upsert(
                TemplateEntity(
                    templateId   = templateId,
                    label        = label,
                    bitmapBytes  = bytes,
                    width        = bitmap.width,
                    height       = bitmap.height,
                    pluginId     = pluginId
                )
            )
        }
    }

    suspend fun removeTemplate(templateId: String) {
        withContext(Dispatchers.IO) {
            cache.remove(templateId)
            db.templateDao().deleteById(templateId)
        }
    }

    private fun putCache(id: String, bmp: Bitmap) {
        if (cache.size >= maxCache) {
            val oldest = cache.keys.first()
            cache.remove(oldest)
        }
        cache[id] = bmp
    }
}
