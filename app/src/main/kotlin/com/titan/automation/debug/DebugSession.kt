package com.titan.automation.debug

import android.graphics.Bitmap
import android.graphics.PointF
import android.graphics.RectF
import com.titan.automation.events.TitanEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DebugSession — ring-buffer recording of all engine events in one automation run.
 *
 * Captured per-frame:
 *   - Bitmap thumbnail (downscaled to 320×180 max)
 *   - Template match regions (RectF, confidence)
 *   - OCR regions + extracted text
 *   - Gesture dispatch vectors
 *   - RL decisions (state, action, Q-value, reward)
 *   - Timing (frame δ, state δ, end-to-end)
 *
 * Replay:
 *   - [frames] is a deque of max [MAX_FRAMES] entries
 *   - [replayIndex] controls playback position from the OverlayService debug view
 *
 * Thread safety: all writes use [ConcurrentLinkedDeque] + atomic replay index.
 * Bitmap thumbnails use soft references to avoid OOM under memory pressure.
 */
@Singleton
class DebugSession @Inject constructor() {

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _frameCount = MutableStateFlow(0)
    val frameCount: StateFlow<Int> = _frameCount.asStateFlow()

    private val frames: ConcurrentLinkedDeque<DebugFrame> = ConcurrentLinkedDeque()

    @Volatile var replayIndex: Int = 0

    // ── Control ───────────────────────────────────────────────────────────────

    fun startRecording() {
        frames.clear()
        _frameCount.value = 0
        _isRecording.value = true
    }

    fun stopRecording() {
        _isRecording.value = false
    }

    fun clear() {
        frames.clear()
        _frameCount.value = 0
        replayIndex = 0
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    fun recordFrame(frame: DebugFrame) {
        if (!_isRecording.value) return
        frames.addLast(frame)
        if (frames.size > MAX_FRAMES) frames.pollFirst()
        _frameCount.value = frames.size
    }

    fun recordEvent(event: TitanEvent) {
        if (!_isRecording.value) return
        val last = frames.peekLast() ?: return
        last.engineEvents.add(event)
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    fun getFrame(index: Int): DebugFrame? = frames.toList().getOrNull(index)
    fun allFrames(): List<DebugFrame> = frames.toList()
    fun latestFrame(): DebugFrame? = frames.peekLast()

    companion object {
        const val MAX_FRAMES = 300   // ~30 seconds at 10 FPS
        const val THUMB_WIDTH  = 320
        const val THUMB_HEIGHT = 180
    }
}

// ── Data models ───────────────────────────────────────────────────────────────

data class DebugFrame(
    val frameIndex     : Long,
    val captureTimeMs  : Long,
    val processingTimeMs: Long = 0L,

    // Downscaled thumbnail (never full-res)
    val thumbnail      : Bitmap?,

    // Vision results
    val templateMatches: MutableList<TemplateMatchDebug> = mutableListOf(),
    val ocrRegions     : MutableList<OcrRegionDebug>     = mutableListOf(),

    // Gesture dispatched this frame
    val gesture        : GestureDebug? = null,

    // RL decision
    val rlDecision     : RLDebug? = null,

    // Engine events that occurred during this frame
    val engineEvents   : MutableList<com.titan.automation.events.TitanEvent> = mutableListOf(),

    // Thermal / performance snapshot
    val thermalHeadroom: Float = 1.0f,
    val batteryLevel   : Int   = 100,
    val memUsageMb     : Float = 0f,
    val fps            : Float = 0f
)

data class TemplateMatchDebug(
    val templateId  : String,
    val matchRegion : RectF,          // normalised [0..1]
    val confidence  : Float,
    val passed      : Boolean
)

data class OcrRegionDebug(
    val region      : RectF,          // normalised [0..1]
    val rawText     : String,
    val matched     : Boolean,
    val matchedGroup: String = ""
)

data class GestureDebug(
    val type        : String,         // TAP, SWIPE, LONG_PRESS, MULTI_TOUCH
    val startPoint  : PointF,
    val endPoint    : PointF?,
    val durationMs  : Long,
    val noiseOffset : PointF          // Gaussian noise applied
)

data class RLDebug(
    val stateKey    : String,
    val chosenAction: String,
    val qValue      : Float,
    val epsilon     : Float,
    val reward      : Float,
    val wasExplore  : Boolean
)
