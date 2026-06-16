package com.titan.automation.domain.model

import com.titan.automation.core.TitanConstants

/**
 * ThermalProfile — enumerated constraint model for thermal + battery degradation.
 *
 * Maps PowerManager thermal status and BatteryManager level to a unified
 * performance tier that all subsystems query before doing CPU-intensive work.
 *
 * Degradation ladder (ascending severity):
 *   NORMAL   → full speed, all subsystems enabled
 *   WARM     → FPS −30%, no other changes
 *   HOT      → FPS −50%, RL training disabled, inference downgraded to INT8
 *   CRITICAL → FPS minimum (3), RL + inference disabled, user notified
 *   EMERGENCY→ macro halted immediately (THERMAL_STATUS_SHUTDOWN or battery < 5%)
 */
enum class ThermalProfile(
    val targetFps: Int,
    val rlEnabled: Boolean,
    val inferenceEnabled: Boolean,
    val ocrEnabled: Boolean,
    val captureScaleFactor: Float
) {
    NORMAL(
        targetFps          = TitanConstants.DEFAULT_FPS,
        rlEnabled          = true,
        inferenceEnabled   = true,
        ocrEnabled         = true,
        captureScaleFactor = 1.0f
    ),
    WARM(
        targetFps          = (TitanConstants.DEFAULT_FPS * 0.7f).toInt().coerceAtLeast(TitanConstants.MIN_FPS),
        rlEnabled          = true,
        inferenceEnabled   = true,
        ocrEnabled         = true,
        captureScaleFactor = 1.0f
    ),
    HOT(
        targetFps          = TitanConstants.DEFAULT_FPS / 2,
        rlEnabled          = false,
        inferenceEnabled   = true,
        ocrEnabled         = true,
        captureScaleFactor = 0.75f
    ),
    CRITICAL(
        targetFps          = TitanConstants.MIN_FPS,
        rlEnabled          = false,
        inferenceEnabled   = false,
        ocrEnabled         = true,
        captureScaleFactor = 0.5f
    ),
    EMERGENCY(
        targetFps          = 0,
        rlEnabled          = false,
        inferenceEnabled   = false,
        ocrEnabled         = false,
        captureScaleFactor = 0f
    );

    /** Returns true when the macro engine may proceed with the next step. */
    val canContinue: Boolean
        get() = this != EMERGENCY

    /** Returns the frame interval in milliseconds for this profile's target FPS. */
    val frameIntervalMs: Long
        get() = if (targetFps > 0) (1000L / targetFps) else Long.MAX_VALUE

    companion object {
        /**
         * Maps Android PowerManager thermal status integer to a [ThermalProfile].
         * Accepts constants from PowerManager.THERMAL_STATUS_*.
         */
        fun fromThermalStatus(status: Int): ThermalProfile = when (status) {
            0    -> NORMAL    // THERMAL_STATUS_NONE
            1    -> WARM      // THERMAL_STATUS_LIGHT
            2    -> WARM      // THERMAL_STATUS_MODERATE (still operable)
            3    -> HOT       // THERMAL_STATUS_SEVERE
            4    -> CRITICAL  // THERMAL_STATUS_CRITICAL
            5    -> EMERGENCY // THERMAL_STATUS_EMERGENCY
            6    -> EMERGENCY // THERMAL_STATUS_SHUTDOWN
            else -> NORMAL
        }

        /**
         * Derives an additional battery-pressure overlay on top of thermal profile.
         * The more severe profile wins.
         */
        fun fromBatteryLevel(batteryPct: Int, isCharging: Boolean): ThermalProfile {
            if (isCharging) return NORMAL
            return when {
                batteryPct <= 5  -> EMERGENCY
                batteryPct <= TitanConstants.BATTERY_PAUSE_THRESHOLD             -> CRITICAL
                batteryPct <= TitanConstants.BATTERY_DISABLE_INFERENCE_THRESHOLD -> HOT
                batteryPct <= TitanConstants.BATTERY_MIN_FPS_THRESHOLD           -> WARM
                else                                                              -> NORMAL
            }
        }

        /** Returns the more severe of two profiles. */
        fun worst(a: ThermalProfile, b: ThermalProfile): ThermalProfile =
            if (a.ordinal >= b.ordinal) a else b
    }
}
