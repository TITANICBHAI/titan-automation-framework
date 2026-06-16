package com.titan.automation.engine.capture

import android.graphics.Bitmap
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BitmapPool — thread-safe fixed-capacity Bitmap recycling pool.
 *
 * Prevents per-frame heap allocation in the frame acquisition hot path.
 * All bitmaps are identical dimensions and config (ARGB_8888).
 *
 * Pattern adapted from subway-surfers-bot BitmapPool (reuse via inBitmap).
 *
 * Usage:
 *   val bmp = pool.acquire() ?: return   // caller must handle null if pool is empty
 *   try { ... process bmp ... }
 *   finally { pool.release(bmp) }
 *
 * Memory cap: [maxSize] bitmaps held simultaneously.
 * On TRIM_MEMORY_RUNNING_CRITICAL flush to 0 via [trimToSize].
 */
@Singleton
class BitmapPool @Inject constructor() {

    private var poolWidth  : Int = 0
    private var poolHeight : Int = 0
    private var initialised: Boolean = false

    private val maxSize = 8
    private val pool    = ArrayBlockingQueue<Bitmap>(maxSize)

    /**
     * Must be called once before first use.
     * [width] and [height] define the single bitmap size this pool serves.
     */
    fun initialise(width: Int, height: Int) {
        if (initialised && poolWidth == width && poolHeight == height) return
        trimToSize(0)
        poolWidth  = width
        poolHeight = height
        initialised = true

        // Pre-warm half the pool slots
        val prewarm = (maxSize / 2).coerceAtLeast(2)
        repeat(prewarm) {
            pool.offer(allocate())
        }
        Log.d(TAG, "Initialised ${pool.size} bitmaps (${width}×${height})")
    }

    /**
     * Acquire a bitmap from the pool. Returns a new allocation if pool is empty.
     * Returns null only if dimensions have not been set yet.
     */
    fun acquire(): Bitmap? {
        if (!initialised) return null
        return pool.poll() ?: allocate()
    }

    /**
     * Return a bitmap to the pool. If pool is full the bitmap is recycled immediately.
     * Silently ignores recycled or wrong-size bitmaps.
     */
    fun release(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        if (bitmap.width != poolWidth || bitmap.height != poolHeight) {
            bitmap.recycle()
            return
        }
        if (!pool.offer(bitmap)) {
            bitmap.recycle()   // Pool full — discard
        }
    }

    /** Drain and recycle all pooled bitmaps (call on TRIM_MEMORY_RUNNING_CRITICAL). */
    fun trimToSize(target: Int) {
        while (pool.size > target) {
            pool.poll()?.recycle()
        }
        Log.d(TAG, "Trimmed pool to ${pool.size} entries")
    }

    val currentSize: Int get() = pool.size

    private fun allocate(): Bitmap =
        Bitmap.createBitmap(poolWidth, poolHeight, Bitmap.Config.ARGB_8888)

    companion object { private const val TAG = "BitmapPool" }
}
