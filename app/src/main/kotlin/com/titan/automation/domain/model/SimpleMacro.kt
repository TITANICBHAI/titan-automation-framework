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
    val x: Float = 0.5f,                   // normalized [0..1]
    val y: Float = 0.5f,
    val endX: Float = 0.5f,                // SWIPE end point
    val endY: Float = 0.5f,
    val durationMs: Long = 100L,            // hold duration (tap/long-press/swipe) or wait ms
    val delayAfterMs: Long = 300L,          // pause after this action before next
    val label: String = "",
    // ── Conditional fields (WAIT_FOR_IMAGE / WAIT_FOR_OCR_TEXT) ──────────────
    val templateId: String = "",            // template asset ID for WAIT_FOR_IMAGE
    val minConfidence: Float = 0.80f,       // minimum match confidence
    val ocrPattern: String = "",            // text to search for (contains, case-insensitive)
    val ocrRegionX: Float = 0f,            // OCR/image scan region (normalized, 0 = full screen)
    val ocrRegionY: Float = 0f,
    val ocrRegionW: Float = 1f,
    val ocrRegionH: Float = 1f,
    val conditionTimeoutMs: Long = 15_000L, // fail-safe timeout for conditional steps
    val tapWhenFound: Boolean = false,      // WAIT_FOR_IMAGE: also tap the matched location
    // ── Scroll fields ─────────────────────────────────────────────────────────
    val scrollDirection: String = "DOWN",    // UP / DOWN / LEFT / RIGHT
    val scrollDistance: Float = 0.5f,        // 0.05–0.9: fraction of screen to scroll
    // ── Key press fields ──────────────────────────────────────────────────────
    val keyCode: String = "BACK",            // BACK / HOME / RECENTS / NOTIFICATIONS / VOL_UP / VOL_DOWN
    // ── Repeat tap fields ─────────────────────────────────────────────────────
    val repeatCount: Int = 5,                // number of rapid taps
    val repeatIntervalMs: Long = 80L         // ms between taps (scaled by speedMultiplier)
)

@Serializable
enum class SimpleActionType {
    TAP, LONG_PRESS, SWIPE, WAIT,
    WAIT_FOR_IMAGE,    // pause until a template appears on screen (VisionEngine)
    WAIT_FOR_OCR_TEXT, // pause until OCR finds matching text on screen
    SCROLL,            // swipe-based content scroll (UP / DOWN / LEFT / RIGHT)
    KEY_PRESS,         // system key action (HOME / BACK / RECENTS / VOL_UP / VOL_DOWN)
    REPEAT_TAP;        // rapid multi-tap at same location (N times)

    fun displayName() = when (this) {
        TAP               -> "Tap"
        LONG_PRESS        -> "Long Press"
        SWIPE             -> "Swipe"
        WAIT              -> "Wait"
        WAIT_FOR_IMAGE    -> "Wait for Image"
        WAIT_FOR_OCR_TEXT -> "Wait for Text"
        SCROLL            -> "Scroll"
        KEY_PRESS         -> "Key Press"
        REPEAT_TAP        -> "Repeat Tap"
    }

    fun emoji() = when (this) {
        TAP               -> "👆"
        LONG_PRESS        -> "✋"
        SWIPE             -> "👉"
        WAIT              -> "⏱"
        WAIT_FOR_IMAGE    -> "👁"
        WAIT_FOR_OCR_TEXT -> "🔤"
        SCROLL            -> "⬇"
        KEY_PRESS         -> "🔑"
        REPEAT_TAP        -> "🔁"
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
    val showTapDots: Boolean = true,              // show visual circles at tap points
    val respectThermal: Boolean = true,           // pause when device thermal is CRITICAL
    val scheduleMode: ScheduleMode = ScheduleMode.MANUAL,
    val scheduleIntervalMs: Long = 0L,            // interval in ms (INTERVAL/REPEAT modes)
    val scheduleRepeatCount: Int = 1              // number of runs (REPEAT mode)
)

@Serializable
enum class ScheduleMode {
    MANUAL,   // only starts when user taps play
    ONCE,     // auto-start once after delay
    INTERVAL, // auto-start every N ms
    REPEAT;   // auto-start N times with interval

    fun displayName() = when (this) {
        MANUAL   -> "Manual only"
        ONCE     -> "Run once (delayed)"
        INTERVAL -> "Repeat every N seconds"
        REPEAT   -> "Repeat N times"
    }
}

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
