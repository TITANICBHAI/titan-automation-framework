package com.titan.automation.domain.model

import kotlinx.serialization.Serializable

/**
 * SimpleMacro — user-facing macro: an ordered list of actions with playback config.
 *
 * Stored as JSON in Room (SimpleMacroEntity.jsonPayload).
 * Coordinates are always normalized [0..1] screen-space so macros work across
 * different screen resolutions without modification.
 */
@Serializable
data class SimpleMacro(
    val id: String,
    val name: String,
    val actions: List<SimpleAction> = emptyList(),
    val playbackConfig: PlaybackConfig = PlaybackConfig(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class SimpleAction(
    val id: String,
    val type: SimpleActionType,
    val x: Float = 0.5f,          // normalized [0..1]
    val y: Float = 0.5f,
    val endX: Float = 0.5f,       // SWIPE end point
    val endY: Float = 0.5f,
    val durationMs: Long = 100L,   // hold duration (tap/long-press/swipe)
    val delayAfterMs: Long = 300L, // pause after this action before next
    val label: String = ""
)

@Serializable
enum class SimpleActionType {
    TAP, LONG_PRESS, SWIPE, WAIT;

    fun displayName() = when (this) {
        TAP         -> "Tap"
        LONG_PRESS  -> "Long Press"
        SWIPE       -> "Swipe"
        WAIT        -> "Wait"
    }

    fun emoji() = when (this) {
        TAP         -> "👆"
        LONG_PRESS  -> "✋"
        SWIPE       -> "👉"
        WAIT        -> "⏱"
    }
}

@Serializable
data class PlaybackConfig(
    val loopMode: LoopMode = LoopMode.ONCE,
    val loopCount: Int = 1,                        // used when loopMode = COUNT
    val loopDurationMs: Long = 60_000L,            // used when loopMode = DURATION
    val speedMultiplier: Float = 1.0f,             // 0.25x – 4x; applied to delayAfterMs
    val jitterEnabled: Boolean = true,             // random ±N px offset per tap
    val jitterRadiusPx: Float = 3f,               // σ for Gaussian noise
    val showTapDots: Boolean = true               // show visual circles at tap points
)

@Serializable
enum class LoopMode {
    ONCE,       // run once and stop
    COUNT,      // repeat loopCount times
    FOREVER,    // repeat until manually stopped
    DURATION;   // repeat for loopDurationMs milliseconds

    fun displayName() = when (this) {
        ONCE     -> "Once"
        COUNT    -> "Repeat N times"
        FOREVER  -> "Loop forever"
        DURATION -> "Loop for duration"
    }
}
