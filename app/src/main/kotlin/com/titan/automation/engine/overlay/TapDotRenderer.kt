package com.titan.automation.engine.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TapDotRenderer — shows a brief visual circle at each tap/swipe location
 * during macro playback, matching the "show tap dots" feature of Macrorify/Karta.
 *
 * Each dot is a 44×44dp translucent circle added to WindowManager and
 * automatically removed after 400ms. Safe to call from any thread.
 */
@Singleton
class TapDotRenderer @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler   = Handler(Looper.getMainLooper())
    private val density       = context.resources.displayMetrics.density

    private val dotSizePx   = (44 * density).toInt()
    private val tapColor    = Color.argb(200, 0, 229, 255)    // cyan
    private val swipeColor  = Color.argb(160, 255, 152, 0)    // amber

    fun show(nx: Float, ny: Float)              = postDot(nx, ny, tapColor)
    fun showSwipeEnd(nx: Float, ny: Float)      = postDot(nx, ny, swipeColor)

    private fun postDot(nx: Float, ny: Float, color: Int) {
        mainHandler.post {
            val (screenW, screenH) = screenSize()
            val px = (nx * screenW).toInt() - dotSizePx / 2
            val py = (ny * screenH).toInt() - dotSizePx / 2

            val dotView = DotView(context, color)
            val params  = buildParams(px, py)

            runCatching { windowManager.addView(dotView, params) }

            mainHandler.postDelayed({
                runCatching { windowManager.removeView(dotView) }
            }, 400L)
        }
    }

    private fun buildParams(x: Int, y: Int): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        return WindowManager.LayoutParams(
            dotSizePx, dotSizePx, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x  = x
            this.y  = y
        }
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Float, Float> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = windowManager.currentWindowMetrics.bounds
            b.width().toFloat() to b.height().toFloat()
        } else {
            val dm = android.util.DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels.toFloat() to dm.heightPixels.toFloat()
        }
    }

    // ── Dot view ──────────────────────────────────────────────────────────────

    private class DotView(context: Context, color: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL
        }
        private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
            alpha = 180
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r  = width / 2f - 4f
            canvas.drawCircle(cx, cy, r, paint)
            canvas.drawCircle(cx, cy, r, ringPaint)
        }
    }
}
