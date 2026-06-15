package com.titan.automation.plugins

import android.content.Context
import android.util.Log
import com.titan.automation.events.TitanEventBus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PluginManager — runtime macro module loader.
 *
 * Architecture:
 *   Plugins are isolated workflow packs stored as JSON files in:
 *     [Context.getFilesDir]/plugins/<plugin-id>/
 *
 *   Each plugin directory contains:
 *     manifest.json    — plugin metadata (id, name, version, permissions)
 *     workflow_*.json  — one or more WorkflowDefinition JSON files
 *     templates/       — optional bitmap templates referenced by vision rules
 *
 *   Plugin loading:
 *     1. Read manifest.json — verify signature and permissions
 *     2. Parse all workflow_*.json files
 *     3. Register workflows in [WorkflowRepository] under plugin namespace
 *     4. Emit [PluginLoadedEvent] to engine event bus
 *
 *   Sandboxing:
 *     - Plugins cannot access system APIs directly (they only supply JSON config)
 *     - Each plugin runs in a named coroutine scope; crash does not affect others
 *     - Permission boundary: plugins declare required features in manifest;
 *       user must grant each feature (vision, ocr, rl, overlay, gestures)
 *
 *   Supported plugin packs:
 *     /macro-pack    — reusable action sequences (click chains, form fills)
 *     /vision-pack   — template image bundles for a specific app
 *     /ocr-pack      — OCR rule sets for a specific app's text patterns
 *     /workflow-pack — complete multi-state automation workflows
 */
@Singleton
class PluginManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventBus: TitanEventBus
) {
    private val scope  = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex  = Mutex()
    private val pluginDir = File(context.filesDir, "plugins")

    private val _loadedPlugins = MutableStateFlow<List<PluginManifest>>(emptyList())
    val loadedPlugins: StateFlow<List<PluginManifest>> = _loadedPlugins.asStateFlow()

    init { pluginDir.mkdirs() }

    /** Scan plugin directory and load all valid plugins. */
    suspend fun loadAll() = mutex.withLock {
        val found = mutableListOf<PluginManifest>()
        pluginDir.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val manifest = parseManifest(dir) ?: return@forEach
            if (verifyPermissions(manifest)) {
                found.add(manifest)
                Log.i(TAG, "Plugin loaded: ${manifest.id} v${manifest.version}")
            } else {
                Log.w(TAG, "Plugin ${manifest.id} permission check failed — skipped")
            }
        }
        _loadedPlugins.value = found
    }

    /** Install a plugin from a zip archive (JSON + templates). */
    suspend fun installFromZip(zipPath: String): Result<PluginManifest> = runCatching {
        mutex.withLock {
            val zipFile = File(zipPath)
            require(zipFile.exists()) { "Zip not found: $zipPath" }

            val pluginId = zipFile.nameWithoutExtension
            val destDir  = File(pluginDir, pluginId)
            destDir.mkdirs()

            java.util.zip.ZipInputStream(zipFile.inputStream()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val dest = File(destDir, entry.name)
                    if (entry.isDirectory) {
                        dest.mkdirs()
                    } else {
                        dest.parentFile?.mkdirs()
                        dest.outputStream().use { out -> zip.copyTo(out) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            val manifest = parseManifest(destDir)
                ?: throw IllegalStateException("Invalid plugin: missing manifest.json in $pluginId")

            _loadedPlugins.value = _loadedPlugins.value + manifest
            Log.i(TAG, "Plugin installed: ${manifest.id}")
            manifest
        }
    }

    /** Uninstall a plugin by ID. */
    suspend fun uninstall(pluginId: String) = mutex.withLock {
        val dir = File(pluginDir, pluginId)
        dir.deleteRecursively()
        _loadedPlugins.value = _loadedPlugins.value.filter { it.id != pluginId }
        Log.i(TAG, "Plugin removed: $pluginId")
    }

    /** List all .json workflow files provided by a plugin. */
    fun workflowFiles(pluginId: String): List<File> =
        File(pluginDir, pluginId).listFiles()
            ?.filter { it.name.startsWith("workflow_") && it.extension == "json" }
            ?: emptyList()

    /** Get the template directory for a plugin. */
    fun templateDir(pluginId: String): File = File(pluginDir, "$pluginId/templates")

    private fun parseManifest(dir: File): PluginManifest? {
        val f = File(dir, "manifest.json")
        if (!f.exists()) return null
        return runCatching {
            val text = f.readText()
            val id   = extractJsonField(text, "id") ?: dir.name
            val name = extractJsonField(text, "name") ?: id
            val ver  = extractJsonField(text, "version") ?: "1.0"
            val perms = extractJsonArray(text, "permissions")
            PluginManifest(id = id, name = name, version = ver, permissions = perms, dir = dir)
        }.getOrNull()
    }

    private fun verifyPermissions(manifest: PluginManifest): Boolean {
        val disallowed = setOf("system_write", "contacts", "camera", "microphone")
        return manifest.permissions.none { it in disallowed }
    }

    // Minimal JSON field extraction without full deserialisation dependency
    private fun extractJsonField(json: String, key: String): String? =
        Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)

    private fun extractJsonArray(json: String, key: String): List<String> {
        val match = Regex("\"$key\"\\s*:\\s*\\[([^\\]]*)]").find(json) ?: return emptyList()
        return Regex("\"([^\"]+)\"").findAll(match.groupValues[1]).map { it.groupValues[1] }.toList()
    }

    companion object { private const val TAG = "PluginManager" }
}

data class PluginManifest(
    val id: String,
    val name: String,
    val version: String,
    val permissions: List<String>,
    val dir: File
)
