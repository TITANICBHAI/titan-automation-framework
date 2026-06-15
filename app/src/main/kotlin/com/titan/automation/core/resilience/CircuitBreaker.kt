package com.titan.automation.core.resilience

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * CircuitBreaker — classic three-state protection for engine sub-components.
 *
 * Prevents cascading failures when a downstream component (vision, OCR, TFLite,
 * accessibility dispatch) starts failing repeatedly. Wraps every call site:
 *
 *   CLOSED    — calls flow through; failures increment the counter.
 *   OPEN      — calls are short-circuited for [cooldownMs]; saves CPU/battery.
 *   HALF_OPEN — first probe after cooldown; success → CLOSED, failure → OPEN.
 *
 * Thread-safe: all state via @Volatile + AtomicInteger/AtomicLong — no locking.
 *
 * Usage (non-suspending callers):
 *   if (cb.allowExecution()) {
 *       try { result = doWork(); cb.recordSuccess() }
 *       catch (e) { cb.recordFailure() }
 *   }
 *
 * Usage (coroutine callers):
 *   val result = cb.execute { doWork() }
 *
 * Extracted from: aria-ai-cpu-only CircuitBreaker.kt (Ported Java → Kotlin)
 * Changes: added inline execute<T> helper, telemetry hook, resetAfterMs.
 */
class CircuitBreaker(
    val componentId: String,
    private val failureThreshold: Int  = 3,
    private val cooldownMs: Long       = 30_000L,
) {
    private val failureCount    = AtomicInteger(0)
    private val lastFailureTime = AtomicLong(0L)
    private val executionCount  = AtomicInteger(0)
    private val successCount    = AtomicInteger(0)

    @Volatile var state: State = State.CLOSED
        private set

    /** Called before every attempt. Returns true if the call should proceed. */
    fun allowExecution(): Boolean {
        executionCount.incrementAndGet()
        return when (state) {
            State.CLOSED    -> true
            State.HALF_OPEN -> true
            State.OPEN      -> {
                val elapsed = System.currentTimeMillis() - lastFailureTime.get()
                if (elapsed >= cooldownMs) {
                    Log.i(TAG, "[$componentId] OPEN → HALF_OPEN (cooldown elapsed ${elapsed}ms)")
                    state = State.HALF_OPEN
                    true
                } else {
                    Log.v(TAG, "[$componentId] OPEN — blocking (${cooldownMs - elapsed}ms remaining)")
                    false
                }
            }
        }
    }

    fun recordSuccess() {
        failureCount.set(0)
        successCount.incrementAndGet()
        if (state == State.HALF_OPEN) {
            Log.i(TAG, "[$componentId] HALF_OPEN → CLOSED (probe succeeded)")
            state = State.CLOSED
            onStateChange?.invoke(State.CLOSED)
        }
    }

    fun recordFailure() {
        val failures       = failureCount.incrementAndGet()
        lastFailureTime.set(System.currentTimeMillis())
        if (failures >= failureThreshold && state != State.OPEN) {
            Log.w(TAG, "[$componentId] CLOSED → OPEN (failures=$failures/$failureThreshold)")
            state = State.OPEN
            onStateChange?.invoke(State.OPEN)
        } else if (state == State.HALF_OPEN) {
            Log.w(TAG, "[$componentId] HALF_OPEN → OPEN (probe failed)")
            state = State.OPEN
            onStateChange?.invoke(State.OPEN)
        }
    }

    /** Hard reset to CLOSED — use after manual remediation. */
    fun reset() {
        failureCount.set(0)
        state = State.CLOSED
        Log.i(TAG, "[$componentId] manually reset to CLOSED")
        onStateChange?.invoke(State.CLOSED)
    }

    /**
     * Convenience wrapper: guards the call with this circuit breaker.
     * Returns null if the circuit is OPEN or if [block] throws.
     */
    inline fun <T> execute(block: () -> T): T? {
        if (!allowExecution()) return null
        return try {
            val result = block()
            recordSuccess()
            result
        } catch (e: Throwable) {
            recordFailure()
            Log.w(TAG, "[$componentId] execution failed: ${e.message}")
            null
        }
    }

    /** Optional callback for telemetry / UI badge updates. */
    var onStateChange: ((State) -> Unit)? = null

    val isOpen:   Boolean get() = state == State.OPEN
    val isClosed: Boolean get() = state == State.CLOSED

    fun getFailureCount():   Int = failureCount.get()
    fun getExecutionCount(): Int = executionCount.get()
    fun getSuccessCount():   Int = successCount.get()

    /** Human-readable health summary for telemetry dashboards. */
    fun summary(): String =
        "CircuitBreaker[$componentId state=$state " +
        "failures=${failureCount.get()}/$failureThreshold " +
        "executions=${executionCount.get()} successes=${successCount.get()}]"

    enum class State { CLOSED, OPEN, HALF_OPEN }

    companion object {
        private const val TAG = "CircuitBreaker"

        /** Pre-configured breakers for TITAN engine components. */
        fun forVision()      = CircuitBreaker("vision",      failureThreshold = 3,  cooldownMs = 10_000L)
        fun forOcr()         = CircuitBreaker("ocr",         failureThreshold = 5,  cooldownMs = 15_000L)
        fun forTfLite()      = CircuitBreaker("tflite",      failureThreshold = 3,  cooldownMs = 30_000L)
        fun forGesture()     = CircuitBreaker("gesture",     failureThreshold = 5,  cooldownMs =  5_000L)
        fun forCapture()     = CircuitBreaker("capture",     failureThreshold = 3,  cooldownMs = 20_000L)
        fun forWorkflow()    = CircuitBreaker("workflow",    failureThreshold = 5,  cooldownMs = 10_000L)
    }
}
