package com.titan.automation.core

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlin.math.roundToInt

// ── Bitmap utilities ──────────────────────────────────────────────────────────

/**
 * Crop a normalised [RectF] (0..1 in each axis) from this bitmap.
 * Returns null if the rect is out of bounds or produces a zero-area crop.
 */
fun Bitmap.cropNormalized(roi: RectF): Bitmap? {
    val l = (roi.left   * width ).roundToInt().coerceIn(0, width)
    val t = (roi.top    * height).roundToInt().coerceIn(0, height)
    val r = (roi.right  * width ).roundToInt().coerceIn(0, width)
    val b = (roi.bottom * height).roundToInt().coerceIn(0, height)
    val w = r - l; val h = b - t
    if (w <= 0 || h <= 0) return null
    return Bitmap.createBitmap(this, l, t, w, h)
}

/**
 * Crop an absolute [Rect] from this bitmap, clamped to bounds.
 */
fun Bitmap.cropAbsolute(roi: Rect): Bitmap? {
    val l = roi.left  .coerceIn(0, width)
    val t = roi.top   .coerceIn(0, height)
    val r = roi.right .coerceIn(0, width)
    val b = roi.bottom.coerceIn(0, height)
    val w = r - l; val h = b - t
    if (w <= 0 || h <= 0) return null
    return Bitmap.createBitmap(this, l, t, w, h)
}

/** Scale bitmap to [targetWidth] × [targetHeight], reusing [reuse] if dimensions match. */
fun Bitmap.scaleTo(targetWidth: Int, targetHeight: Int, reuse: Bitmap? = null): Bitmap {
    if (width == targetWidth && height == targetHeight) return this
    val out = if (reuse?.width == targetWidth && reuse.height == targetHeight &&
                  reuse.config == config) reuse
              else Bitmap.createBitmap(targetWidth, targetHeight, config ?: Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    val m = Matrix().also { it.setScale(targetWidth / width.toFloat(), targetHeight / height.toFloat()) }
    canvas.drawBitmap(this, m, null)
    return out
}

/** Returns mean-squared-error between this and [other] (both must be same size). */
fun Bitmap.mse(other: Bitmap): Double {
    if (width != other.width || height != other.height) return Double.MAX_VALUE
    var sum = 0.0
    val p1 = IntArray(width); val p2 = IntArray(width)
    for (y in 0 until height) {
        getPixels(p1, 0, width, 0, y, width, 1)
        other.getPixels(p2, 0, width, 0, y, width, 1)
        for (x in 0 until width) {
            val dr = ((p1[x] shr 16 and 0xFF) - (p2[x] shr 16 and 0xFF)).toDouble()
            val dg = ((p1[x] shr  8 and 0xFF) - (p2[x] shr  8 and 0xFF)).toDouble()
            val db = ((p1[x]        and 0xFF) - (p2[x]        and 0xFF)).toDouble()
            sum += dr * dr + dg * dg + db * db
        }
    }
    return sum / (width * height * 3.0)
}

// ── Coroutine utilities ───────────────────────────────────────────────────────

/**
 * Catches all non-cancellation throwables and emits them as a [TitanResult.Error].
 * Keeps the flow alive — useful for engine event streams.
 */
fun <T> Flow<TitanResult<T>>.catchAsError(): Flow<TitanResult<T>> =
    catch { t -> if (t !is CancellationException) emit(TitanResult.Error(t)) }

// ── Numeric utilities ─────────────────────────────────────────────────────────

/** Clamp [value] to [min]..[max]. */
fun Float.clamp(min: Float, max: Float): Float = coerceIn(min, max)
fun Double.clamp(min: Double, max: Double): Double = coerceIn(min, max)

/** Linear interpolation. */
fun Float.lerp(to: Float, t: Float): Float = this + (to - this) * t

/** Map a value from (inMin..inMax) to (outMin..outMax). */
fun Float.mapRange(inMin: Float, inMax: Float, outMin: Float, outMax: Float): Float {
    if (inMin == inMax) return outMin
    return ((this - inMin) / (inMax - inMin)) * (outMax - outMin) + outMin
}

// ── String utilities ──────────────────────────────────────────────────────────

/** Truncate to at most [max] characters, appending "…" if truncated. */
fun String.truncate(max: Int): String = if (length <= max) this else take(max - 1) + "…"
