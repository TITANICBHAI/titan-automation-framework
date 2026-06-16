package com.titan.automation.engine.watchdog

import android.util.Log
import com.titan.automation.telemetry.TelemetryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DeadlockDetector — detects stuck steps via heartbeat timeout heuristics.
 *
 * For each active step, callers call [stepStarted] when execution begins and
 * [stepCompleted] when it ends. The detector polls every [pollIntervalMs] and fires
 * [onDeadlockDetected] if the same step has been running > [stepTimeoutMultiplier] × stepTimeout.
 *
 * Also counts active coroutines via [reportActiveCoroutines] and alerts if the count
 * exceeds [maxActiveCoroutines] (coroutine leak detection).
 *
 * Pattern adapted from SmartAssistant WatchdogService deadlock detection.
 */
@Singleton
class DeadlockDetector @Inject constructor(
    private val telemetryManager: TelemetryManager
) {
    var pollIntervalMs          : Long  = 2_000L
    var stepTimeoutMultiplier   : Float = 2.0f
    var maxActiveCoroutines     : Int   = 50
    var onDeadlockDetected      : ((stepId: String) -> Unit)? = null
    var onCoroutineLeakDetected : ((count: Int)     -> Unit)? = null

    private data class ActiveStep(
        val stepId    : String,
        val startedAt : Long,
        val timeoutMs : Long
    )

    private val activeStep       = AtomicReference<ActiveStep?>(null)
    private val activeCoroutines = AtomicLong(0)
    private val scope            = CoroutineScope(SupervisorJob())
    private var pollJob          : Job? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun start() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive) {
                delay(pollIntervalMs)
                checkForDeadlock()
                checkCoroutineLeak()
            }
        }
        Log.d(TAG, "DeadlockDetector started (poll=${pollIntervalMs}ms)")
    }

    fun stop() {
        pollJob?.cancel()
        activeStep.set(null)
    }

    // ── Step tracking ─────────────────────────────────────────────────────────

    /**
     * Register a step as started. Call at the beginning of each [MacroEngine] step.
     * @param stepId    Identifier of the step.
     * @param timeoutMs Maximum allowed execution time for this step (from workflow definition).
     */
    fun stepStarted(stepId: String, timeoutMs: Long = 10_000L) {
        activeStep.set(ActiveStep(stepId, System.currentTimeMillis(), timeoutMs))
    }

    /**
     * Clear the active step. Call when a step completes (success or failure).
     */
    fun stepCompleted() {
        activeStep.set(null)
    }

    // ── Coroutine tracking ────────────────────────────────────────────────────

    fun reportActiveCoroutines(count: Long) {
        activeCoroutines.set(count)
    }

    // ── Internal polling ──────────────────────────────────────────────────────

    private fun checkForDeadlock() {
        val step = activeStep.get() ?: return
        val elapsed = System.currentTimeMillis() - step.startedAt
        val threshold = (step.timeoutMs * stepTimeoutMultiplier).toLong()

        if (elapsed > threshold) {
            Log.e(TAG, "Deadlock detected: step='${step.stepId}' elapsed=${elapsed}ms threshold=${threshold}ms")
            scope.launch {
                telemetryManager.log(
                    "DeadlockDetector",
                    "Deadlock: step='${step.stepId}' elapsed=${elapsed}ms threshold=${threshold}ms"
                )
            }
            onDeadlockDetected?.invoke(step.stepId)
            activeStep.set(null)  // Reset to prevent repeated callbacks
        }
    }

    private fun checkCoroutineLeak() {
        val count = activeCoroutines.get()
        if (count > maxActiveCoroutines) {
            Log.w(TAG, "Coroutine leak suspected: $count active coroutines (max=$maxActiveCoroutines)")
            scope.launch {
                telemetryManager.log(
                    "DeadlockDetector",
                    "CoroutineLeakWarning: $count active coroutines (max=$maxActiveCoroutines)"
                )
            }
            onCoroutineLeakDetected?.invoke(count.toInt())
        }
    }

    companion object { private const val TAG = "DeadlockDetector" }
}
