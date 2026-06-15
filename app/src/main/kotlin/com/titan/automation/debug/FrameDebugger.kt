package com.titan.automation.debug

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FrameDebugger — annotates captured bitmaps with vision/OCR/RL debug overlays.
 *
 * Renders:
 *   - Green bounding boxes for template matches (confidence ≥ threshold)
 *   - Red boxes for failed matches
 *   - Blue boxes for OCR regions + extracted text
 *   - Cyan dots for gesture tap targets
 *   - Yellow arrow lines for swipe vectors
 *   - Purple text for RL decision annotation
 *
 * Usage:
 *   val annotated = frameDebugger.annotate(rawBitmap, debugFrame)
 *   // Display annotated in overlay debug view
 *
 * Thread safety: all paint objects are allocated once at construction and
 * never mutated after init — safe to call from any thread.
 */
@Singleton
class FrameDebugger @Inject constructor() {

    private val matchPassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.GREEN; style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val matchFailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.RED; style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val ocrPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLUE; style = Paint.Style.STROKE; strokeWidth = 2f
    }
    private val gesturePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.CYAN; style = Paint.Style.FILL; strokeWidth = 4f
    }
    private val swipePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.YELLOW; style = Paint.Style.STROKE; strokeWidth = 3f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 20f; typeface = Typeface.MONOSPACE
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }
    private val rlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.MAGENTA; textSize = 18f; typeface = Typeface.MONOSPACE
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }
    private val bgPaint = Paint().apply {
        color = 0x88000000.toInt(); style = Paint.Style.FILL
    }

    /**
     * Returns a new annotated [Bitmap] (ARGB_8888) with all debug overlays drawn.
     * The input [source] is NOT mutated.
     */
    fun annotate(source: Bitmap, frame: DebugFrame): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val w = out.width.toFloat()
        val h = out.height.toFloat()

        // Template matches
        for (tm in frame.templateMatches) {
            val rect = tm.matchRegion.toPixels(w, h)
            val paint = if (tm.passed) matchPassPaint else matchFailPaint
            canvas.drawRect(rect, paint)
            canvas.drawText(
                "${tm.templateId} ${(tm.confidence * 100).toInt()}%",
                rect.left + 4f, rect.top - 4f, textPaint
            )
        }

        // OCR regions
        for (ocr in frame.ocrRegions) {
            val rect = ocr.region.toPixels(w, h)
            canvas.drawRect(rect, ocrPaint)
            val label = "OCR:${ocr.rawText.take(20)}"
            canvas.drawRect(rect.left, rect.bottom, rect.left + label.length * 11f, rect.bottom + 22f, bgPaint)
            canvas.drawText(label, rect.left + 2f, rect.bottom + 16f, textPaint)
        }

        // Gesture
        frame.gesture?.let { g ->
            val px = g.startPoint.x * w
            val py = g.startPoint.y * h
            canvas.drawCircle(px, py, 16f, gesturePaint)
            g.endPoint?.let { ep ->
                canvas.drawLine(px, py, ep.x * w, ep.y * h, swipePaint)
                canvas.drawCircle(ep.x * w, ep.y * h, 8f, gesturePaint)
            }
            canvas.drawText("${g.type} ${g.durationMs}ms", px + 18f, py - 8f, textPaint)
        }

        // RL decision
        frame.rlDecision?.let { rl ->
            val label = "RL:${rl.chosenAction} Q=${String.format("%.3f", rl.qValue)} ε=${String.format("%.2f", rl.epsilon)}"
            canvas.drawRect(4f, 4f, label.length * 10f + 8f, 26f, bgPaint)
            canvas.drawText(label, 6f, 20f, rlPaint)
        }

        // FPS + thermal HUD (bottom-left)
        val hud = "FPS:${frame.fps.toInt()} 🌡${String.format("%.2f", frame.thermalHeadroom)} 🔋${frame.batteryLevel}% MEM:${frame.memUsageMb.toInt()}MB"
        canvas.drawRect(0f, h - 26f, hud.length * 9.5f + 8f, h, bgPaint)
        canvas.drawText(hud, 4f, h - 8f, textPaint)

        return out
    }

    private fun RectF.toPixels(w: Float, h: Float) =
        RectF(left * w, top * h, right * w, bottom * h)
}
