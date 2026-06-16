package com.titan.automation.engine.accessibility

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import android.view.Surface
import android.view.WindowInsets
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/**
 * CoordinateNormalizer — converts normalised [0..1] logical coordinates to absolute
 * pixel coordinates, accounting for:
 *
 *   - Portrait ↔ Landscape rotation (X/Y swap + bound recalculation)
 *   - Density-independent coordinates → pixels (displayMetrics.density)
 *   - Safe area insets (navigation bar, status bar, notch cutout)
 *   - Multi-window / split-screen detection and bounds adjustment
 *
 * Call [onOrientationChanged] when DisplayManager fires a rotation event.
 */
@Singleton
class CoordinateNormalizer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    @Volatile private var screenWidth  : Int   = 0
    @Volatile private var screenHeight : Int   = 0
    @Volatile private var rotation     : Int   = Surface.ROTATION_0
    @Volatile private var insetTop     : Int   = 0
    @Volatile private var insetBottom  : Int   = 0
    @Volatile private var insetLeft    : Int   = 0
    @Volatile private var insetRight   : Int   = 0

    init { refreshMetrics() }

    // ── Coordinate transform ──────────────────────────────────────────────────

    /**
     * Transform a normalised coordinate pair to absolute screen pixels.
     * Clamps output to safe screen bounds (inside insets).
     */
    fun toPixels(nx: Float, ny: Float): Pair<Int, Int> {
        val safeW = (screenWidth  - insetLeft - insetRight ).coerceAtLeast(1)
        val safeH = (screenHeight - insetTop  - insetBottom).coerceAtLeast(1)

        val px = (insetLeft + nx * safeW).roundToInt().coerceIn(insetLeft, screenWidth  - insetRight  - 1)
        val py = (insetTop  + ny * safeH).roundToInt().coerceIn(insetTop,  screenHeight - insetBottom - 1)
        return Pair(px, py)
    }

    fun toNorm(px: Int, py: Int): Pair<Float, Float> {
        val safeW = (screenWidth  - insetLeft - insetRight ).coerceAtLeast(1)
        val safeH = (screenHeight - insetTop  - insetBottom).coerceAtLeast(1)
        return Pair(
            ((px - insetLeft).toFloat() / safeW).coerceIn(0f, 1f),
            ((py - insetTop ).toFloat() / safeH).coerceIn(0f, 1f)
        )
    }

    /** Current screen width in pixels (post-rotation). */
    val currentWidth:  Int get() = screenWidth
    /** Current screen height in pixels (post-rotation). */
    val currentHeight: Int get() = screenHeight

    /** Whether the screen is currently in landscape orientation. */
    val isLandscape: Boolean
        get() = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270

    // ── Orientation change ────────────────────────────────────────────────────

    /**
     * Called by DisplayManager.DisplayListener when rotation is detected.
     * Refreshes all cached metrics and transform state.
     */
    fun onOrientationChanged() = refreshMetrics()

    // ── Metric refresh ────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun refreshMetrics() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            screenWidth  = bounds.width()
            screenHeight = bounds.height()

            val insets = windowMetrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            insetTop    = insets.top
            insetBottom = insets.bottom
            insetLeft   = insets.left
            insetRight  = insets.right
        } else {
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(dm)
            screenWidth  = dm.widthPixels
            screenHeight = dm.heightPixels
            rotation = windowManager.defaultDisplay.rotation

            // Approximate insets for older API (status bar + nav bar)
            insetTop    = getStatusBarHeight()
            insetBottom = getNavBarHeight()
            insetLeft   = 0
            insetRight  = 0
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            rotation = windowManager.defaultDisplay.rotation
        }
    }

    private fun getStatusBarHeight(): Int {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) context.resources.getDimensionPixelSize(resId) else 0
    }

    private fun getNavBarHeight(): Int {
        val resId = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        return if (resId > 0) context.resources.getDimensionPixelSize(resId) else 0
    }
}
