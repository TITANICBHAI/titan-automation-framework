package com.titan.automation.performance

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.util.Log
import com.titan.automation.engine.capture.BitmapPool
import com.titan.automation.telemetry.TelemetryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MemoryPressureMonitor — responds to Android ComponentCallbacks2 memory trim events.
 *
 * Trim response table (from spec §10):
 *   TRIM_MEMORY_RUNNING_LOW      → flush [BitmapPool] to 2 entries
 *   TRIM_MEMORY_RUNNING_CRITICAL → flush [BitmapPool] entirely, pause ML inference
 *   TRIM_MEMORY_UI_HIDDEN        → no-op (we're a background service)
 *   onLowMemory()                → emergency pause + notify user via [TelemetryManager]
 *
 * Register by calling [register] inside Application.onTrimMemory or Service.onTrimMemory;
 * forward the trim level to [onTrimMemory].
 */
@Singleton
class MemoryPressureMonitor @Inject constructor(
    private val bitmapPool      : BitmapPool,
    private val telemetryManager: TelemetryManager
) : ComponentCallbacks2 {

    var onEmergencyPause: (() -> Unit)? = null
    var inferenceEnabled : Boolean = true
        private set

    private val scope = CoroutineScope(SupervisorJob())

    override fun onTrimMemory(level: Int) {
        val label = trimLevelLabel(level)
        Log.w(TAG, "onTrimMemory($label level=$level)")

        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                bitmapPool.trimToSize(0)
                inferenceEnabled = false
                scope.launch {
                    telemetryManager.log("MemoryPressureMonitor", "Critical memory: level=$label, inference disabled")
                }
                Log.e(TAG, "Critical memory: bitmap pool flushed, inference disabled")
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                bitmapPool.trimToSize(2)
                scope.launch {
                    telemetryManager.log("MemoryPressureMonitor", "Low memory: level=$label, pool trimmed to 2")
                }
                Log.w(TAG, "Low memory: bitmap pool trimmed to 2")
            }
            level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                // Background service — no UI to release
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                // We're backgrounded; reclaim optionally
                bitmapPool.trimToSize(4)
            }
        }
    }

    override fun onLowMemory() {
        Log.e(TAG, "onLowMemory() — emergency pause triggered")
        bitmapPool.trimToSize(0)
        inferenceEnabled = false
        onEmergencyPause?.invoke()
        scope.launch {
            telemetryManager.log("MemoryPressureMonitor", "onLowMemory: emergency pause triggered")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) { /* no-op */ }

    /** Restore inference after memory pressure subsides. */
    fun restoreInference() {
        inferenceEnabled = true
        Log.i(TAG, "Inference restored after memory pressure")
    }

    private fun trimLevelLabel(level: Int): String = when (level) {
        ComponentCallbacks2.TRIM_MEMORY_COMPLETE           -> "COMPLETE"
        ComponentCallbacks2.TRIM_MEMORY_MODERATE           -> "MODERATE"
        ComponentCallbacks2.TRIM_MEMORY_BACKGROUND         -> "BACKGROUND"
        ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN          -> "UI_HIDDEN"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL   -> "RUNNING_CRITICAL"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW        -> "RUNNING_LOW"
        ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE   -> "RUNNING_MODERATE"
        else                                               -> "UNKNOWN($level)"
    }

    companion object { private const val TAG = "MemoryPressureMonitor" }
}
