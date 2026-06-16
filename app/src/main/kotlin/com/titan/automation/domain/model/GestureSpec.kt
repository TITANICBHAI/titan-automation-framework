package com.titan.automation.domain.model

/**
 * GestureSpec — typed gesture descriptor used by GestureDispatcher / MacroEngine.
 *
 * All coordinates are normalised [0..1] relative to screen dimensions.
 * CoordinateNormalizer converts them to absolute pixels before dispatch.
 *
 * Pattern adapted from subway-surfers-bot swipe/tap coordinate system.
 */
sealed class GestureSpec {

    enum class Priority { CRITICAL, HIGH, NORMAL, LOW }

    data class Click(
        val nx          : Float,
        val ny          : Float,
        val durationMs  : Long     = 80L,
        val jitterSigma : Float    = 3f,
        val priority    : Priority = Priority.NORMAL
    ) : GestureSpec()

    data class LongPress(
        val nx         : Float,
        val ny         : Float,
        val durationMs : Long     = 600L,
        val priority   : Priority = Priority.NORMAL
    ) : GestureSpec()

    data class Swipe(
        val startNx    : Float,
        val startNy    : Float,
        val endNx      : Float,
        val endNy      : Float,
        val durationMs : Long     = 300L,
        val priority   : Priority = Priority.NORMAL
    ) : GestureSpec()

    data class MultiTouch(
        val pointers   : List<TouchPointer>,
        val durationMs : Long     = 300L,
        val priority   : Priority = Priority.NORMAL
    ) : GestureSpec()

    data class Batch(
        val gestures   : List<GestureSpec>,
        val cancelOnFirstFailure: Boolean = true
    ) : GestureSpec()
}

/**
 * A single pointer in a multi-touch gesture.
 * Start and end are normalised [0..1] screen coordinates.
 * [startTimeMs] / [endTimeMs] are relative to gesture start (default: both at 0 / durationMs).
 */
data class TouchPointer(
    val startNx    : Float,
    val startNy    : Float,
    val endNx      : Float,
    val endNy      : Float,
    val startTimeMs: Long = 0L,
    val endTimeMs  : Long = 300L
)

/**
 * Result of a dispatched gesture.
 */
data class GestureResult(
    val spec      : GestureSpec,
    val success   : Boolean,
    val latencyMs : Long,
    val retries   : Int = 0
)
