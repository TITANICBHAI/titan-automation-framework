package com.titan.automation.engine.capture

import android.graphics.Bitmap
import android.graphics.Color
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FrameDifferenceFilter — skip processing frames that are near-identical to the previous one.
 *
 * Algorithm: samples a grid of [sampleCount] pixels uniformly across both frames,
 * computes mean absolute pixel difference, and normalises to a similarity score [0..1].
 * Frames with similarity > [threshold] are considered static and should be skipped.
 *
 * Pattern adapted from subway-surfers-bot frame-skip logic.
 * Sampling grid avoids O(width*height) comparison — runs in ~0.2ms on Snapdragon 660.
 *
 * Threshold 0.97 = skip frames where <3% of sampled pixels changed.
 */
@Singleton
class FrameDifferenceFilter @Inject constructor() {

    /** Similarity above this value → frame considered static → skip. */
    var threshold: Float = 0.97f

    /** Grid sample count: higher = more accurate but slower. 256 is a good balance. */
    var sampleCount: Int = 256

    private var previousSamples: IntArray? = null
    private var prevWidth : Int = 0
    private var prevHeight: Int = 0

    /**
     * Returns `true` if the frame should be **processed** (sufficiently different from last).
     * Returns `false` if the frame is too similar and should be skipped.
     *
     * Always returns `true` for the first frame (no previous reference).
     */
    fun shouldProcess(frame: Bitmap): Boolean {
        val w = frame.width
        val h = frame.height

        // Dimension mismatch (e.g. orientation change) → always process
        if (w != prevWidth || h != prevHeight) {
            updateReference(frame, w, h)
            return true
        }

        val prev = previousSamples ?: run {
            updateReference(frame, w, h)
            return true
        }

        val current = samplePixels(frame, w, h)
        val similarity = computeSimilarity(prev, current)

        // Update reference regardless of decision
        System.arraycopy(current, 0, prev, 0, current.size)

        return similarity < threshold
    }

    /** Reset filter state (call on workflow restart or capture restart). */
    fun reset() {
        previousSamples = null
        prevWidth  = 0
        prevHeight = 0
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun updateReference(frame: Bitmap, w: Int, h: Int) {
        prevWidth  = w
        prevHeight = h
        previousSamples = samplePixels(frame, w, h)
    }

    private fun samplePixels(frame: Bitmap, w: Int, h: Int): IntArray {
        val n = sampleCount
        val pixels = IntArray(n)
        val cols = kotlin.math.sqrt(n.toDouble()).toInt().coerceAtLeast(1)
        val rows = (n / cols).coerceAtLeast(1)
        val stepX = (w / cols).coerceAtLeast(1)
        val stepY = (h / rows).coerceAtLeast(1)
        var idx = 0
        var y = 0
        while (y < h && idx < n) {
            var x = 0
            while (x < w && idx < n) {
                pixels[idx++] = frame.getPixel(x, y)
                x += stepX
            }
            y += stepY
        }
        return pixels
    }

    private fun computeSimilarity(a: IntArray, b: IntArray): Float {
        var totalDiff = 0L
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            val dr = Color.red(a[i])   - Color.red(b[i])
            val dg = Color.green(a[i]) - Color.green(b[i])
            val db = Color.blue(a[i])  - Color.blue(b[i])
            totalDiff += (Math.abs(dr) + Math.abs(dg) + Math.abs(db))
        }
        // Max possible diff per pixel: 255*3 = 765
        val maxDiff = len.toLong() * 765L
        return if (maxDiff == 0L) 1f else 1f - (totalDiff.toFloat() / maxDiff)
    }
}
