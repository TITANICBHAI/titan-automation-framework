package com.titan.automation.engine.workflow

import com.titan.automation.core.TitanConstants
import kotlinx.coroutines.delay

/**
 * RetryPolicy — configurable exponential-backoff retry controller.
 *
 * Used by [MacroEngine] to govern per-state and per-gesture retry loops.
 *
 * Backoff formula:
 *   delay(n) = min(base × 2^(n−1), maxBackoff) + jitter(0..jitterMs)
 *
 * where n = attempt number (1-indexed).
 *
 * Thread-safe: all state is immutable; a new [RetryPolicy] should be created
 * per execution context rather than shared across coroutines.
 */
data class RetryPolicy(
    val maxAttempts: Int       = TitanConstants.DEFAULT_MAX_RETRIES,
    val baseBackoffMs: Long    = TitanConstants.RETRY_BACKOFF_BASE_MS,
    val maxBackoffMs: Long     = TitanConstants.RETRY_BACKOFF_MAX_MS,
    val jitterMs: Long         = 200L,
    val resetOnSuccess: Boolean = true
) {

    init {
        require(maxAttempts >= 0)  { "maxAttempts must be ≥ 0" }
        require(baseBackoffMs > 0) { "baseBackoffMs must be > 0" }
        require(maxBackoffMs >= baseBackoffMs) { "maxBackoffMs must be ≥ baseBackoffMs" }
    }

    /** Returns true if [attempt] (1-indexed) is within the retry budget. */
    fun shouldRetry(attempt: Int): Boolean = attempt <= maxAttempts

    /**
     * Computes the backoff delay for the given [attempt] (1-indexed).
     * Clamps to [maxBackoffMs] and adds uniform random jitter.
     */
    fun backoffMs(attempt: Int): Long {
        val exponential = baseBackoffMs * (1L shl minOf(attempt - 1, 30))
        val clamped = minOf(exponential, maxBackoffMs)
        val jitter = if (jitterMs > 0) (Math.random() * jitterMs).toLong() else 0L
        return clamped + jitter
    }

    /**
     * Suspends for the computed backoff period for [attempt].
     * Cancellation-safe: uses structured [delay].
     */
    suspend fun awaitBackoff(attempt: Int) {
        val ms = backoffMs(attempt)
        if (ms > 0) delay(ms)
    }

    companion object {

        /** Immediate retry — no delay, single extra attempt. */
        val IMMEDIATE = RetryPolicy(
            maxAttempts  = 1,
            baseBackoffMs = 0L,
            maxBackoffMs  = 0L,
            jitterMs      = 0L
        )

        /** Gesture dispatch retry — fast, up to 3 attempts. */
        val GESTURE = RetryPolicy(
            maxAttempts  = TitanConstants.GESTURE_MAX_RETRIES,
            baseBackoffMs = 100L,
            maxBackoffMs  = 500L,
            jitterMs      = 50L
        )

        /** Workflow state retry — standard exponential backoff. */
        val WORKFLOW_STATE = RetryPolicy(
            maxAttempts  = TitanConstants.DEFAULT_MAX_RETRIES,
            baseBackoffMs = TitanConstants.RETRY_BACKOFF_BASE_MS,
            maxBackoffMs  = TitanConstants.RETRY_BACKOFF_MAX_MS,
            jitterMs      = 200L
        )

        /** No retries — fail fast. */
        val NONE = RetryPolicy(maxAttempts = 0, baseBackoffMs = 1L, maxBackoffMs = 1L, jitterMs = 0L)
    }
}

/**
 * RetryState — mutable per-execution retry tracker.
 * One instance is created per workflow state execution and discarded when the state exits.
 */
class RetryState(val policy: RetryPolicy) {

    private var attempt = 0
    private var firstFailureAt: Long? = null

    /** Returns true if another attempt is allowed. */
    val canRetry: Boolean
        get() = policy.shouldRetry(attempt + 1)

    /** Current attempt count (0 before any attempt has been recorded). */
    val attemptCount: Int get() = attempt

    /** Records a failure. Must be called before [awaitBackoff]. */
    fun recordFailure() {
        if (attempt == 0) firstFailureAt = System.currentTimeMillis()
        attempt++
    }

    /** Records success — resets attempt counter if [RetryPolicy.resetOnSuccess]. */
    fun recordSuccess() {
        if (policy.resetOnSuccess) attempt = 0
    }

    /** Suspends for the backoff period corresponding to the current attempt count. */
    suspend fun awaitBackoff() = policy.awaitBackoff(attempt)

    /** Returns elapsed ms since first failure, or 0 if no failures yet. */
    fun failureDurationMs(): Long =
        firstFailureAt?.let { System.currentTimeMillis() - it } ?: 0L
}
