package com.titan.automation.debug

import android.graphics.PointF
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedDeque
import javax.inject.Inject
import javax.inject.Singleton

/**
 * GestureTimeline — ordered log of every gesture dispatched during automation.
 *
 * Designed for replay in the debug UI and for post-session timing analysis.
 *
 * Each [GestureEvent] records:
 *   - Absolute timestamp (epoch ms)
 *   - Gesture type + coordinates (normalised)
 *   - Duration + success/failure
 *   - Which workflow state triggered the gesture
 *   - Gaussian noise offset actually applied
 *
 * The timeline is exposed as [StateFlow<List<GestureEvent>>] for Compose collection.
 * It is bounded to [MAX_EVENTS] entries (FIFO eviction).
 */
@Singleton
class GestureTimeline @Inject constructor() {

    private val deque: ConcurrentLinkedDeque<GestureEvent> = ConcurrentLinkedDeque()

    private val _events = MutableStateFlow<List<GestureEvent>>(emptyList())
    val events: StateFlow<List<GestureEvent>> = _events.asStateFlow()

    fun record(event: GestureEvent) {
        deque.addLast(event)
        while (deque.size > MAX_EVENTS) deque.pollFirst()
        _events.value = deque.toList()
    }

    fun clear() {
        deque.clear()
        _events.value = emptyList()
    }

    /** Returns total gestures dispatched, split by type. */
    fun summary(): TimelineSummary {
        val all = deque.toList()
        return TimelineSummary(
            total          = all.size,
            taps           = all.count { it.type == GestureType.TAP },
            swipes         = all.count { it.type == GestureType.SWIPE },
            longPresses    = all.count { it.type == GestureType.LONG_PRESS },
            multiTouches   = all.count { it.type == GestureType.MULTI_TOUCH },
            failures       = all.count { !it.succeeded },
            avgDurationMs  = if (all.isEmpty()) 0L else all.sumOf { it.durationMs } / all.size,
            totalDurationMs= all.sumOf { it.durationMs }
        )
    }

    companion object {
        const val MAX_EVENTS = 1024
    }
}

enum class GestureType { TAP, SWIPE, LONG_PRESS, MULTI_TOUCH }

data class GestureEvent(
    val timestampMs  : Long,
    val type         : GestureType,
    val startPoint   : PointF,
    val endPoint     : PointF?        = null,
    val durationMs   : Long,
    val noiseOffsetPx: PointF         = PointF(0f, 0f),
    val workflowState: String         = "",
    val actionId     : String         = "",
    val succeeded    : Boolean        = true,
    val failureReason: String         = ""
)

data class TimelineSummary(
    val total          : Int,
    val taps           : Int,
    val swipes         : Int,
    val longPresses    : Int,
    val multiTouches   : Int,
    val failures       : Int,
    val avgDurationMs  : Long,
    val totalDurationMs: Long
)
