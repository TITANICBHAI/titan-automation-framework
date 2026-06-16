package com.titan.automation.performance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.titan.automation.telemetry.TelemetryManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BatteryGuard — battery-level-aware capability degradation controller.
 *
 * Registers BroadcastReceiver for ACTION_BATTERY_CHANGED and implements the
 * spec degradation ladder:
 *   < 30%:              disable RL training
 *   < 20%:              reduce FPS to minimum (5)
 *   < 15%:              disable ML inference; OCR only
 *   < 10%:              pause macro, notify user
 *   isCharging = true:  restore ALL capabilities regardless of level
 *
 * Emits [BatteryEvent] to [TelemetryManager] on state transitions.
 */
@Singleton
class BatteryGuard @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetryManager: TelemetryManager
) {
    data class BatteryState(
        val level      : Int     = 100,
        val isCharging : Boolean = false,
        val tempCelsius: Float   = 25f
    )

    enum class CapabilityLevel {
        FULL,           // > 30% or charging
        NO_RL_TRAINING, // 20-30%
        REDUCED_FPS,    // 15-20%
        OCR_ONLY,       // 10-15%
        PAUSED          // < 10%
    }

    private val _batteryState = MutableStateFlow(BatteryState())
    val batteryState: StateFlow<BatteryState> = _batteryState.asStateFlow()

    val capabilityLevel: CapabilityLevel
        get() {
            val s = _batteryState.value
            if (s.isCharging) return CapabilityLevel.FULL
            return when {
                s.level < 10  -> CapabilityLevel.PAUSED
                s.level < 15  -> CapabilityLevel.OCR_ONLY
                s.level < 20  -> CapabilityLevel.REDUCED_FPS
                s.level < 30  -> CapabilityLevel.NO_RL_TRAINING
                else          -> CapabilityLevel.FULL
            }
        }

    val rlTrainingAllowed  : Boolean get() = capabilityLevel <= CapabilityLevel.NO_RL_TRAINING || _batteryState.value.isCharging
    val inferenceAllowed   : Boolean get() = capabilityLevel <= CapabilityLevel.REDUCED_FPS    || _batteryState.value.isCharging
    val macroRunAllowed    : Boolean get() = capabilityLevel != CapabilityLevel.PAUSED         || _batteryState.value.isCharging
    val targetFpsOverride  : Int?    get() = when (capabilityLevel) {
        CapabilityLevel.REDUCED_FPS -> 5
        CapabilityLevel.OCR_ONLY    -> 3
        CapabilityLevel.PAUSED      -> 0
        else                        -> null
    }

    private val scope = CoroutineScope(SupervisorJob())
    private var previousLevel: CapabilityLevel = CapabilityLevel.FULL

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_BATTERY_CHANGED) return

            val level      = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100)
            val scale      = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val pct        = (level * 100 / scale.coerceAtLeast(1))
            val status     = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging   = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL
            val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 250)
            val tempC      = tempTenths / 10f

            val newState = BatteryState(pct, charging, tempC)
            _batteryState.value = newState

            val newLevel = capabilityLevel
            if (newLevel != previousLevel) {
                previousLevel = newLevel
                scope.launch {
                    telemetryManager.log(
                        "BatteryGuard",
                        "CapabilityChanged → $newLevel (battery=$pct%, charging=$charging)"
                    )
                }
                Log.i(TAG, "Capability changed → $newLevel (battery=$pct%, charging=$charging)")
            }
        }
    }

    fun register() {
        context.registerReceiver(
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        Log.d(TAG, "BatteryGuard registered")
    }

    fun unregister() {
        try { context.unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
    }

    companion object { private const val TAG = "BatteryGuard" }
}

private operator fun BatteryGuard.CapabilityLevel.compareTo(other: BatteryGuard.CapabilityLevel): Int =
    this.ordinal.compareTo(other.ordinal)
