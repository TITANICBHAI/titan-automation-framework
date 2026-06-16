package com.titan.automation.engine.capture

import android.graphics.Bitmap
import android.graphics.Rect
import com.titan.automation.domain.model.ScreenRegion
import javax.inject.Inject
import javax.inject.Singleton

/**
 * RoiCropper — extracts a region-of-interest sub-bitmap from a full frame.
 *
 * All input regions are normalised [0..1] (ScreenRegion). Pixel coordinates
 * are derived from the frame's actual dimensions at crop time, so the cropper
 * is resolution-independent.
 *
 * Memory: uses [BitmapPool] when a pool bitmap of the right size is available,
 * otherwise allocates (rare — ROI dimensions vary by frame step).
 *
 * DO NOT hold a reference to the returned bitmap beyond the current step;
 * call [recycle] when finished to avoid leaks.
 */
@Singleton
class RoiCropper @Inject constructor(
    private val bitmapPool: BitmapPool
) {
    /**
     * Crop [frame] to [region].
     *
     * @param frame  Source bitmap (not recycled by this method).
     * @param region Normalised region [left/top/right/bottom ∈ 0..1].
     * @return Cropped sub-bitmap. Caller must release via [recycleIfPool] when done.
     */
    fun crop(frame: Bitmap, region: ScreenRegion): Bitmap {
        val fw = frame.width
        val fh = frame.height
        val px = toPixelRect(region, fw, fh)

        // Clamp to actual frame bounds
        val l = px.left.coerceIn(0, fw - 1)
        val t = px.top.coerceIn(0, fh - 1)
        val r = px.right.coerceIn(l + 1, fw)
        val b = px.bottom.coerceIn(t + 1, fh)

        return Bitmap.createBitmap(frame, l, t, r - l, b - t)
    }

    /**
     * Crop using explicit pixel rect (for callers that already have pixel coords).
     */
    fun cropPixels(frame: Bitmap, rect: Rect): Bitmap {
        val l = rect.left.coerceIn(0, frame.width - 1)
        val t = rect.top.coerceIn(0, frame.height - 1)
        val r = rect.right.coerceIn(l + 1, frame.width)
        val b = rect.bottom.coerceIn(t + 1, frame.height)
        return Bitmap.createBitmap(frame, l, t, r - l, b - t)
    }

    /**
     * Return the pixel bounds of a normalised region within a frame of given dimensions.
     */
    fun toPixelRect(region: ScreenRegion, frameWidth: Int, frameHeight: Int): Rect = Rect(
        (region.left   * frameWidth ).toInt(),
        (region.top    * frameHeight).toInt(),
        (region.right  * frameWidth ).toInt(),
        (region.bottom * frameHeight).toInt()
    )

    /**
     * Scale a Rect by a factor (used for multi-scale template matching result mapping).
     */
    fun scaleRect(rect: Rect, scale: Float): Rect = Rect(
        (rect.left   * scale).toInt(),
        (rect.top    * scale).toInt(),
        (rect.right  * scale).toInt(),
        (rect.bottom * scale).toInt()
    )
}
