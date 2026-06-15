package com.titan.automation.engine.recorder

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.titan.automation.domain.model.SimpleAction
import com.titan.automation.domain.model.SimpleActionType
import com.titan.automation.engine.accessibility.MacroAccessibilityService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.hypot

/**
 * TouchRecorder — captures user touches during record mode and converts them
 * into [SimpleAction] objects for macro building.
 *
 * How it works:
 *  1. [startRecording] adds a full-screen transparent overlay via WindowManager.
 *  2. Every touch the user makes is intercepted, recorded, then immediately
 *     re-dispatched via [MacroAccessibilityService] so the underlying game/app
 *     still receives the input (typically < 16ms round-trip).
 *  3. [stopRecording] removes the overlay and returns the captured action list.
 *
 * Swipe detection: if finger travels > SWIPE_THRESHOLD_PX between DOWN and UP,
 * the event is recorded as SWIPE; otherwise as TAP (or LONG_PRESS if held > 500ms).
 *
 * Coordinates are stored normalized [0..1] for device-independence.
 */
@Singleton
class TouchRecorder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler   = Handler(Looper.getMainLooper())

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordedCount = MutableStateFlow(0)
    val recordedCount: StateFlow<Int> = _recordedCount.asStateFlow()

    private var overlayView: View? = null
    private val recorded = mutableListOf<SimpleAction>()

    // State for current gesture tracking
    private var downX = 0f; private var downY = 0f
    private var downTime = 0L
    private var lastUpTime = 0L
    private var prevActionEndTime = 0L

    companion object {
        private const val SWIPE_THRESHOLD_PX = 30f
        private const val LONG_PRESS_MS      = 500L
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun startRecording() {
        if (_isRecording.value) return
        recorded.clear()
        _recordedCount.value = 0
        prevActionEndTime = 0L
        mainHandler.post { addOverlay() }
        _isRecording.value = true
    }

    /**
     * Stop recording and return the list of captured [SimpleAction]s.
     * Returns empty list if nothing was recorded.
     */
    fun stopRecording(): List<SimpleAction> {
        if (!_isRecording.value) return emptyList()
        _isRecording.value = false
        mainHandler.post { removeOverlay() }
        return recorded.toList()
    }

    // ── Overlay ───────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun addOverlay() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha   = 0.01f   // nearly invisible — just here to intercept touches
        }

        val view = View(context)
        view.setOnTouchListener { _, event -> handleTouch(event) }
        windowManager.addView(view, params)
        overlayView = view
    }

    private fun removeOverlay() {
        overlayView?.let { runCatching { windowManager.removeView(it) } }
        overlayView = null
    }

    // ── Touch handling ────────────────────────────────────────────────────────

    private fun handleTouch(event: MotionEvent): Boolean {
        val sw = screenWidth(); val sh = screenHeight()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX    = event.x
                downY    = event.y
                downTime = event.eventTime
            }
            MotionEvent.ACTION_UP -> {
                val upX   = event.x
                val upY   = event.y
                val upTime = event.eventTime
                val holdMs = upTime - downTime

                // Delay since previous action ended
                val delayMs = if (prevActionEndTime > 0L)
                    (downTime - prevActionEndTime).coerceAtLeast(0L) else 0L

                val dist = hypot(upX - downX, upY - downY)

                val action = if (dist >= SWIPE_THRESHOLD_PX) {
                    // SWIPE
                    SimpleAction(
                        id           = UUID.randomUUID().toString(),
                        type         = SimpleActionType.SWIPE,
                        x            = (downX / sw).coerceIn(0f, 1f),
                        y            = (downY / sh).coerceIn(0f, 1f),
                        endX         = (upX / sw).coerceIn(0f, 1f),
                        endY         = (upY / sh).coerceIn(0f, 1f),
                        durationMs   = holdMs.coerceAtLeast(50L),
                        delayAfterMs = delayMs,
                        label        = "Swipe"
                    )
                } else if (holdMs >= LONG_PRESS_MS) {
                    // LONG PRESS
                    SimpleAction(
                        id           = UUID.randomUUID().toString(),
                        type         = SimpleActionType.LONG_PRESS,
                        x            = (downX / sw).coerceIn(0f, 1f),
                        y            = (downY / sh).coerceIn(0f, 1f),
                        durationMs   = holdMs,
                        delayAfterMs = delayMs,
                        label        = "Long Press"
                    )
                } else {
                    // TAP
                    SimpleAction(
                        id           = UUID.randomUUID().toString(),
                        type         = SimpleActionType.TAP,
                        x            = (downX / sw).coerceIn(0f, 1f),
                        y            = (downY / sh).coerceIn(0f, 1f),
                        durationMs   = holdMs.coerceIn(50L, 200L),
                        delayAfterMs = delayMs,
                        label        = "Tap"
                    )
                }

                recorded.add(action)
                _recordedCount.value = recorded.size
                prevActionEndTime = upTime

                // Re-dispatch to underlying app immediately
                redispatchToApp(event, action)
                lastUpTime = upTime
            }
        }
        return true
    }

    private fun redispatchToApp(event: MotionEvent, action: SimpleAction) {
        val svc = MacroAccessibilityService.get() ?: return
        when (action.type) {
            SimpleActionType.TAP, SimpleActionType.LONG_PRESS ->
                svc.dispatchClick(action.x, action.y)
            SimpleActionType.SWIPE ->
                svc.dispatchSwipe(action.x, action.y, action.endX, action.endY, action.durationMs)
            else -> Unit
        }
    }

    // ── Screen dimensions ─────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun screenWidth(): Float {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.width().toFloat()
        } else {
            val dm = android.util.DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels.toFloat()
        }
    }

    @Suppress("DEPRECATION")
    private fun screenHeight(): Float {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.height().toFloat()
        } else {
            val dm = android.util.DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(dm)
            dm.heightPixels.toFloat()
        }
    }
}
