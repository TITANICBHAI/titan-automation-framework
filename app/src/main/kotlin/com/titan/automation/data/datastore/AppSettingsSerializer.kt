package com.titan.automation.data.datastore

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.InputStream
import java.io.OutputStream

/**
 * AppSettings — typed settings model mirroring the spec §11 Proto DataStore schema.
 *
 * Uses Preferences DataStore (no protobuf compiler dependency required).
 * All keys are defined in [Keys] companion object.
 *
 * Spec-required fields:
 *   target_fps, default_template_threshold, default_ocr_confidence,
 *   rl_enabled, charging_only_mode, battery_pause_threshold,
 *   jitter_sigma, min/max_gesture_delay_ms, active_profile_id,
 *   overlay_x/y, overlay_minimized, max_retry_count, retry_backoff_ms
 */
data class AppSettings(
    val targetFps                : Int     = 8,
    val defaultTemplateThreshold : Float   = 0.80f,
    val defaultOcrConfidence     : Float   = 0.70f,
    val rlEnabled                : Boolean = true,
    val chargingOnlyMode         : Boolean = false,
    val batteryPauseThreshold    : Int     = 10,
    val jitterSigma              : Float   = 0.003f,
    val minGestureDelayMs        : Long    = 15L,
    val maxGestureDelayMs        : Long    = 55L,
    val activeProfileId          : String  = "",
    val overlayX                 : Int     = 16,
    val overlayY                 : Int     = 200,
    val overlayMinimized         : Boolean = true,
    val maxRetryCount            : Int     = 3,
    val retryBackoffMs           : Long    = 500L,
    val showTapDots              : Boolean = true,
    val jitterEnabled            : Boolean = true,
    val jitterRadiusPx           : Int     = 4,
    val captureScale             : Float   = 0.5f,
    val respectThermal           : Boolean = true,
    val touchNoiseStdDev         : Float   = 0.002f
) {
    companion object Keys {
        val TARGET_FPS                  = intPreferencesKey("target_fps")
        val DEFAULT_TEMPLATE_THRESHOLD  = floatPreferencesKey("default_template_threshold")
        val DEFAULT_OCR_CONFIDENCE      = floatPreferencesKey("default_ocr_confidence")
        val RL_ENABLED                  = booleanPreferencesKey("rl_enabled")
        val CHARGING_ONLY_MODE          = booleanPreferencesKey("charging_only_mode")
        val BATTERY_PAUSE_THRESHOLD     = intPreferencesKey("battery_pause_threshold")
        val JITTER_SIGMA                = floatPreferencesKey("jitter_sigma")
        val MIN_GESTURE_DELAY_MS        = longPreferencesKey("min_gesture_delay_ms")
        val MAX_GESTURE_DELAY_MS        = longPreferencesKey("max_gesture_delay_ms")
        val ACTIVE_PROFILE_ID           = stringPreferencesKey("active_profile_id")
        val OVERLAY_X                   = intPreferencesKey("overlay_x")
        val OVERLAY_Y                   = intPreferencesKey("overlay_y")
        val OVERLAY_MINIMIZED           = booleanPreferencesKey("overlay_minimized")
        val MAX_RETRY_COUNT             = intPreferencesKey("max_retry_count")
        val RETRY_BACKOFF_MS            = longPreferencesKey("retry_backoff_ms")
        val SHOW_TAP_DOTS               = booleanPreferencesKey("show_tap_dots")
        val JITTER_ENABLED              = booleanPreferencesKey("jitter_enabled")
        val JITTER_RADIUS_PX            = intPreferencesKey("jitter_radius_px")
        val CAPTURE_SCALE               = floatPreferencesKey("capture_scale")
        val RESPECT_THERMAL             = booleanPreferencesKey("respect_thermal")
        val TOUCH_NOISE_STD_DEV         = floatPreferencesKey("touch_noise_std_dev")
    }
}

/**
 * Extension: convert a [Preferences] object to a typed [AppSettings].
 */
fun Preferences.toAppSettings(): AppSettings = AppSettings(
    targetFps                = this[AppSettings.TARGET_FPS]                 ?: 8,
    defaultTemplateThreshold = this[AppSettings.DEFAULT_TEMPLATE_THRESHOLD] ?: 0.80f,
    defaultOcrConfidence     = this[AppSettings.DEFAULT_OCR_CONFIDENCE]     ?: 0.70f,
    rlEnabled                = this[AppSettings.RL_ENABLED]                 ?: true,
    chargingOnlyMode         = this[AppSettings.CHARGING_ONLY_MODE]         ?: false,
    batteryPauseThreshold    = this[AppSettings.BATTERY_PAUSE_THRESHOLD]    ?: 10,
    jitterSigma              = this[AppSettings.JITTER_SIGMA]               ?: 0.003f,
    minGestureDelayMs        = this[AppSettings.MIN_GESTURE_DELAY_MS]       ?: 15L,
    maxGestureDelayMs        = this[AppSettings.MAX_GESTURE_DELAY_MS]       ?: 55L,
    activeProfileId          = this[AppSettings.ACTIVE_PROFILE_ID]          ?: "",
    overlayX                 = this[AppSettings.OVERLAY_X]                  ?: 16,
    overlayY                 = this[AppSettings.OVERLAY_Y]                  ?: 200,
    overlayMinimized         = this[AppSettings.OVERLAY_MINIMIZED]          ?: true,
    maxRetryCount            = this[AppSettings.MAX_RETRY_COUNT]            ?: 3,
    retryBackoffMs           = this[AppSettings.RETRY_BACKOFF_MS]           ?: 500L,
    showTapDots              = this[AppSettings.SHOW_TAP_DOTS]              ?: true,
    jitterEnabled            = this[AppSettings.JITTER_ENABLED]             ?: true,
    jitterRadiusPx           = this[AppSettings.JITTER_RADIUS_PX]           ?: 4,
    captureScale             = this[AppSettings.CAPTURE_SCALE]              ?: 0.5f,
    respectThermal           = this[AppSettings.RESPECT_THERMAL]            ?: true,
    touchNoiseStdDev         = this[AppSettings.TOUCH_NOISE_STD_DEV]        ?: 0.002f
)
