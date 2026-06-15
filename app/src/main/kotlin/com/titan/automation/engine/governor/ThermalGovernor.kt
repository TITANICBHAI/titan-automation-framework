package com.titan.automation.engine.governor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.annotation.RequiresApi
import com.titan.automation.events.TitanEvent
import com.titan.automation.events.TitanEventBus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ThermalGovernor — real-time thermal and battery load-shedding.
 *
 * Thermal monitoring (three-tier, best available API):
 *
 *   Tier 1 — API 31+ (Android 12):
 *     [PowerManager.getThermalHeadroom(30)] polled every 5 s.
 *     Returns a [0..1] float; 1.0 = no thermal pressure, 0 = emergency.
 *
 *   Tier 2 — API 29–30 (Android 10–11):
 *     [PowerManager.addThermalStatusListener] callback (event-driven, no poll
 *     overhead). Fires exactly when the OS detects a status transition — more
 *     power-efficient than polling. Listener reference kept for clean removal.
 *
 *   Tier 3 — API < 29 (Android 9):
 *     Battery temperature from [ACTION_BATTERY_CHANGED] EXTRA_TEMPERATURE.
 *     Values in tenths of °C: 370 = 37.0°C.
 *     Thresholds: <35°C SAFE, 35–37°C LIGHT, 37–40°C MODERATE, 40–42°C SEVERE, ≥42°C CRITICAL.
 *
 * Degradation ladder:
 *   NORMAL   → fps=10 RL=on  scale=1.00
 *   LIGHT    → fps=8  RL=on  scale=1.00
 *   MODERATE → fps=5  RL=on  scale=0.75  (halved OCR frequency)
 *   SEVERE   → fps=2  RL=off scale=0.50
 *   CRITICAL → fps=0  RL=off scale=0.50  → [onHaltEngine] invoked
 *
 * Battery overlay (applied on top of thermal):
 *   < 30% → fps × 0.8
 *   < 15% → fps ≤ 3, RL off
 *   <  5% → [onHaltEngine] invoked immediately
 *
 * Extracted from: aria-ai-cpu-only ThermalGuard.kt (event-driven listener approach)
 * Changes: merged with TITAN's polling fallback for API31+, battery degradation
 *   overlay, coroutine-based polling loop, StateFlow for engine components to observe.
 */
@Singleton
class ThermalGovernor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventBus: TitanEventBus
) {
    private val scope        = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    private val _state = MutableStateFlow(GovernorState())
    val state: StateFlow<GovernorState> = _state.asStateFlow()

    val targetFps:    Int     get() = _state.value.targetFps
    val rlEnabled:    Boolean get() = _state.value.rlEnabled
    val isCritical:   Boolean get() = _state.value.thermalLevel == ThermalLevel.CRITICAL
    val captureScale: Float   get() = _state.value.captureScale

    var onHaltEngine: (() -> Unit)? = null

    /**
     * Stored listener reference — required for [PowerManager.removeThermalStatusListener].
     * The API has no "clearAll" method; removal is by exact instance reference.
     * (pattern from aria-ai-cpu-only ThermalGuard.kt)
     */
    @Volatile
    private var thermalStatusListener: PowerManager.OnThermalStatusChangedListener? = null

    // ── Battery broadcast receiver ────────────────────────────────────────────
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val pct   = if (scale > 0) level * 100 / scale else 100

            // Tier 3 thermal fallback: battery temperature in tenths of °C
            val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && tempTenths > 0) {
                applyThermalLevel(batteryTempToLevel(tempTenths))
            }

            onBatteryUpdate(pct)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    fun start() {
        context.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        when {
            // Tier 1: API 31+ — headroom polling
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                scope.launch { thermalPollingLoop() }
            }
            // Tier 2: API 29–30 — event-driven listener (no polling overhead)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                registerThermalStatusListener()
            }
            // Tier 3: API < 29 — battery temp from broadcast (handled in receiver)
            else -> {
                android.util.Log.i(TAG, "API < 29 — thermal via battery temperature broadcast")
            }
        }
    }

    fun stop() {
        runCatching { context.unregisterReceiver(batteryReceiver) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            unregisterThermalStatusListener()
        }
        scope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tier 2: PowerManager.OnThermalStatusChangedListener (API 29+)
    // ─────────────────────────────────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun registerThermalStatusListener() {
        try {
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                applyThermalLevel(thermalStatusToLevel(status))
            }
            thermalStatusListener = listener
            powerManager.addThermalStatusListener(context.mainExecutor, listener)
            android.util.Log.i(TAG, "ThermalStatusListener registered (API 29+)")

            // Read current status immediately so we don't wait for the first event
            @Suppress("DEPRECATION")
            applyThermalLevel(thermalStatusToLevel(powerManager.currentThermalStatus))
        } catch (e: Exception) {
            android.util.Log.w(TAG, "addThermalStatusListener unavailable: ${e.message}")
            // Fall back to polling if listener registration fails
            scope.launch { thermalPollingLoop() }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun unregisterThermalStatusListener() {
        try {
            thermalStatusListener?.let { powerManager.removeThermalStatusListener(it) }
            thermalStatusListener = null
        } catch (e: Exception) {
            android.util.Log.w(TAG, "removeThermalStatusListener failed: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun thermalStatusToLevel(status: Int): ThermalLevel = when (status) {
        PowerManager.THERMAL_STATUS_NONE      -> ThermalLevel.NORMAL
        PowerManager.THERMAL_STATUS_LIGHT     -> ThermalLevel.LIGHT
        PowerManager.THERMAL_STATUS_MODERATE  -> ThermalLevel.MODERATE
        PowerManager.THERMAL_STATUS_SEVERE    -> ThermalLevel.SEVERE
        PowerManager.THERMAL_STATUS_CRITICAL,
        PowerManager.THERMAL_STATUS_EMERGENCY,
        PowerManager.THERMAL_STATUS_SHUTDOWN  -> ThermalLevel.CRITICAL
        else                                  -> ThermalLevel.NORMAL
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tier 1: getThermalHeadroom polling (API 31+)
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun thermalPollingLoop() {
        while (true) {
            applyThermalLevel(readThermalLevelFromHeadroom())
            delay(POLL_INTERVAL_MS)
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun readThermalLevelFromHeadroom(): ThermalLevel {
        val headroom = powerManager.getThermalHeadroom(/* forecastSeconds= */ 30)
        return when {
            headroom.isNaN() || headroom >= 1.0f -> ThermalLevel.NORMAL
            headroom >= 0.85f                    -> ThermalLevel.LIGHT
            headroom >= 0.70f                    -> ThermalLevel.MODERATE
            headroom >= 0.50f                    -> ThermalLevel.SEVERE
            else                                 -> ThermalLevel.CRITICAL
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tier 3: battery temperature fallback (API < 29)
    // Tenths of °C: 370 = 37.0°C, 420 = 42.0°C
    // ─────────────────────────────────────────────────────────────────────────

    private fun batteryTempToLevel(tempTenths: Int): ThermalLevel = when {
        tempTenths >= 420 -> ThermalLevel.CRITICAL   // ≥ 42°C
        tempTenths >= 400 -> ThermalLevel.SEVERE     // ≥ 40°C
        tempTenths >= 370 -> ThermalLevel.MODERATE   // ≥ 37°C
        tempTenths >= 350 -> ThermalLevel.LIGHT      // ≥ 35°C
        else              -> ThermalLevel.NORMAL
    }

    // ─────────────────────────────────────────────────────────────────────────
    // State application
    // ─────────────────────────────────────────────────────────────────────────

    private fun applyThermalLevel(level: ThermalLevel) {
        val current = _state.value

        val (fps, rl, scale) = when (level) {
            ThermalLevel.NORMAL   -> Triple(10, true,  1.0f)
            ThermalLevel.LIGHT    -> Triple(8,  true,  1.0f)
            ThermalLevel.MODERATE -> Triple(5,  true,  0.75f)
            ThermalLevel.SEVERE   -> Triple(2,  false, 0.5f)
            ThermalLevel.CRITICAL -> Triple(0,  false, 0.5f)
        }

        val batteryFps = applyBatteryDegradation(fps, current.batteryPct)
        val batteryRl  = rl && current.batteryPct >= 15

        val newState = current.copy(
            thermalLevel = level,
            targetFps    = batteryFps,
            rlEnabled    = batteryRl,
            captureScale = scale
        )

        if (newState != current) {
            _state.value = newState
            eventBus.emit(
                TitanEvent.ThermalStatus(
                    level     = level.name,
                    targetFps = batteryFps,
                    rlEnabled = batteryRl
                )
            )
            android.util.Log.i(TAG,
                "Thermal: ${current.thermalLevel} → $level " +
                "fps=$batteryFps rl=$batteryRl scale=$scale batt=${current.batteryPct}%"
            )
        }

        if (level == ThermalLevel.CRITICAL) onHaltEngine?.invoke()
    }

    private fun applyBatteryDegradation(thermalFps: Int, batteryPct: Int): Int = when {
        batteryPct < 5  -> { onHaltEngine?.invoke(); 0 }
        batteryPct < 15 -> thermalFps.coerceAtMost(3)
        batteryPct < 30 -> (thermalFps * 0.8f).toInt().coerceAtLeast(1)
        else            -> thermalFps
    }

    private fun onBatteryUpdate(pct: Int) {
        val current = _state.value
        if (current.batteryPct != pct) {
            _state.value = current.copy(batteryPct = pct)
            // Re-apply current thermal level with new battery percentage
            applyThermalLevel(current.thermalLevel)
        }
    }

    companion object {
        private const val TAG             = "ThermalGovernor"
        private const val POLL_INTERVAL_MS = 5_000L
    }
}

enum class ThermalLevel { NORMAL, LIGHT, MODERATE, SEVERE, CRITICAL }

data class GovernorState(
    val thermalLevel : ThermalLevel = ThermalLevel.NORMAL,
    val targetFps    : Int          = 10,
    val rlEnabled    : Boolean      = true,
    val captureScale : Float        = 1.0f,
    val batteryPct   : Int          = 100
)
