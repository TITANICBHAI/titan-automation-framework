package com.titan.automation.performance

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * BatteryMonitor — real-time battery level, charging state, and power-saver status.
 *
 * Triggers engine degradation via [DegradationLevel] based on combined
 * battery + power-saver signals:
 *
 *   NOMINAL    — battery ≥ 30%, not in low-power mode
 *   REDUCED    — battery 20–30% OR power-saver active
 *   MINIMAL    — battery 10–20% → disable RL, reduce FPS to 5
 *   CRITICAL   — battery < 10% → halt engine, wait for charge
 *   CHARGING   — any charge level on AC/USB → full performance allowed
 *
 * Spec reference (textfile 1, section 9):
 *   Battery < 15% → reduce FPS → disable RL → downscale capture → reduce OCR frequency
 *
 * Registered as sticky broadcast receiver ([ACTION_BATTERY_CHANGED]) — no
 * registration/unregistration required for sticky broadcasts on Android 10+.
 */
@Singleton
class BatteryMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _state = MutableStateFlow(BatteryState())
    val state: StateFlow<BatteryState> = _state.asStateFlow()

    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            intent ?: return
            _state.value = parseBatteryIntent(intent)
        }
    }

    fun register() {
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        // Also read current state immediately from sticky broadcast
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent != null) _state.value = parseBatteryIntent(intent)
    }

    fun unregister() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    private fun parseBatteryIntent(intent: Intent): BatteryState {
        val level   = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale   = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val pct     = if (scale > 0) (level * 100 / scale) else 50
        val status  = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == BatteryManager.BATTERY_STATUS_FULL
        val isLowPower = powerManager.isPowerSaveMode
        val health  = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)

        val degradation = when {
            isCharging                  -> DegradationLevel.CHARGING
            pct < 10                    -> DegradationLevel.CRITICAL
            pct < 20                    -> DegradationLevel.MINIMAL
            pct < 30 || isLowPower      -> DegradationLevel.REDUCED
            else                        -> DegradationLevel.NOMINAL
        }

        return BatteryState(
            levelPercent  = pct,
            isCharging    = isCharging,
            isLowPower    = isLowPower,
            pluggedType   = plugged,
            health        = health,
            degradation   = degradation
        )
    }
}

// ── Data models ───────────────────────────────────────────────────────────────

data class BatteryState(
    val levelPercent  : Int              = 100,
    val isCharging    : Boolean          = false,
    val isLowPower    : Boolean          = false,
    val pluggedType   : Int              = 0,
    val health        : Int              = BatteryManager.BATTERY_HEALTH_GOOD,
    val degradation   : DegradationLevel = DegradationLevel.NOMINAL
)

/**
 * Engine degradation levels driven by battery state.
 * Each level maps to concrete engine constraints defined in [ThermalGovernor].
 */
enum class DegradationLevel {
    /** Full performance — battery healthy or charging. */
    CHARGING,
    NOMINAL,
    /** FPS reduced to 8, RL budget reduced by 50%. */
    REDUCED,
    /** FPS reduced to 5, RL disabled, capture downscaled to 75%. */
    MINIMAL,
    /** Engine halted — battery critically low. */
    CRITICAL
}
