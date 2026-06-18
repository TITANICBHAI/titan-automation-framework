package com.titan.automation.engine.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CoordinatePicker — draggable crosshair circle overlaid on screen so users
 * can visually place tap targets without knowing pixel coordinates.
 *
 * Usage:
 *  1. Call [requestPick] with a label (e.g. "Set tap point")
 *  2. Observe [pickerResult] for the confirmed (nx, ny) coordinate
 *  3. User drags circle to target, taps the ✓ confirm button
 *  4. Overlay dismisses automatically after confirm or cancel
 */
@Singleton
class CoordinatePicker @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler   = Handler(Looper.getMainLooper())
    private val density       = context.resources.displayMetrics.density

    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    private val _pickerResult = MutableSharedFlow<PickerResult>(extraBufferCapacity = 4)
    val pickerResult: SharedFlow<PickerResult> = _pickerResult.asSharedFlow()

    private var pickerView: PickerView? = null
    private var pickerParams: WindowManager.LayoutParams? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /** Show the coordinate picker with an optional prompt label. */
    fun requestPick(label: String = "Drag to tap target") {
        if (_isActive.value) return
        mainHandler.post { showPicker(label) }
    }

    /** Dismiss the picker without emitting a result. */
    fun cancel() {
        mainHandler.post { hidePicker(cancelled = true) }
    }

    // ── Overlay ───────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun showPicker(label: String) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val (sw, sh) = screenSize()
        val sizePx = (96 * density).toInt()

        val params = WindowManager.LayoutParams(
            sizePx, sizePx, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (sw / 2 - sizePx / 2).toInt()
            y = (sh / 3 - sizePx / 2).toInt()
        }

        val view = PickerView(context, density, label,
            onConfirm = {
                val (pvSw, pvSh) = screenSize()
                val nx = (params.x + sizePx / 2f) / pvSw
                val ny = (params.y + sizePx / 2f) / pvSh
                _pickerResult.tryEmit(PickerResult.Confirmed(nx.coerceIn(0f,1f), ny.coerceIn(0f,1f)))
                hidePicker(cancelled = false)
            },
            onCancel = { hidePicker(cancelled = true) },
            onDrag = { dx, dy ->
                params.x = (params.x + dx.toInt())
                params.y = (params.y + dy.toInt())
                pickerView?.let { v -> runCatching { windowManager.updateViewLayout(v, params) } }
            }
        )

        runCatching { windowManager.addView(view, params) }
        pickerView   = view
        pickerParams = params
        _isActive.value = true
    }

    private fun hidePicker(cancelled: Boolean) {
        if (cancelled) _pickerResult.tryEmit(PickerResult.Cancelled)
        pickerView?.let { runCatching { windowManager.removeView(it) } }
        pickerView   = null
        pickerParams = null
        _isActive.value = false
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

    // ── Picker view ───────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private class PickerView(
        context: Context,
        private val density: Float,
        private val label: String,
        private val onConfirm: () -> Unit,
        private val onCancel: () -> Unit,
        private val onDrag: (Float, Float) -> Unit
    ) : View(context) {

        private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 0, 229, 255)
            style = Paint.Style.STROKE
            strokeWidth = 4f * density
        }
        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(60, 0, 229, 255)
            style = Paint.Style.FILL
        }
        private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 2f * density
        }
        private val confirmPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(220, 38, 180, 80)
            style = Paint.Style.FILL
        }
        private val cancelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(200, 180, 40, 40)
            style = Paint.Style.FILL
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 14f * density
            textAlign = Paint.Align.CENTER
        }

        private var lastX = 0f
        private var lastY = 0f

        init {
            setOnTouchListener { _, event ->
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { lastX = event.rawX; lastY = event.rawY }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - lastX; val dy = event.rawY - lastY
                        lastX = event.rawX; lastY = event.rawY
                        onDrag(dx, dy)
                    }
                    MotionEvent.ACTION_UP -> {
                        val dx = kotlin.math.abs(event.rawX - lastX)
                        val dy = kotlin.math.abs(event.rawY - lastY)
                        if (dx < 10 && dy < 10) {
                            // Check which zone was tapped
                            val cx = width / 2f; val cy = height / 2f
                            val btnR = 16f * density
                            val confirmCx = cx + 20f * density
                            val cancelCx  = cx - 20f * density
                            val btnY = cy + 30f * density
                            val touchX = event.x; val touchY = event.y
                            when {
                                dist(touchX, touchY, confirmCx, btnY) < btnR + 10f -> onConfirm()
                                dist(touchX, touchY, cancelCx,  btnY) < btnR + 10f -> onCancel()
                            }
                        }
                    }
                }
                true
            }
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f; val cy = height / 2f
            val r  = width / 2f - 8f * density

            canvas.drawCircle(cx, cy, r, fillPaint)
            canvas.drawCircle(cx, cy, r, circlePaint)

            // Crosshair
            canvas.drawLine(cx - r, cy, cx + r, cy, crossPaint)
            canvas.drawLine(cx, cy - r, cx, cy + r, crossPaint)

            // ✓ Confirm button (green)
            val btnR  = 16f * density
            val btnY  = cy + 30f * density
            canvas.drawCircle(cx + 20f * density, btnY, btnR, confirmPaint)
            textPaint.textSize = 16f * density
            canvas.drawText("✓", cx + 20f * density, btnY + 6f * density, textPaint)

            // ✕ Cancel button (red)
            canvas.drawCircle(cx - 20f * density, btnY, btnR, cancelPaint)
            canvas.drawText("✕", cx - 20f * density, btnY + 6f * density, textPaint)
        }

        private fun dist(x1: Float, y1: Float, x2: Float, y2: Float) =
            kotlin.math.hypot((x1 - x2).toDouble(), (y1 - y2).toDouble()).toFloat()
    }

    // ── Result type ───────────────────────────────────────────────────────────

    sealed class PickerResult {
        data class Confirmed(val nx: Float, val ny: Float) : PickerResult()
        data object Cancelled : PickerResult()
    }
}
