package com.titan.automation.core

/**
 * Sealed result type for all engine operations.
 *
 * Replaces nullable returns and raw exceptions at module boundaries.
 * Models three states:
 *   [Success]  — operation succeeded, carries typed value
 *   [Failure]  — expected failure (vision miss, OCR timeout, etc.)
 *   [Error]    — unexpected exception — always carries the throwable
 *
 * Usage:
 * ```
 * when (val r = visionEngine.findTemplate(frame, bmp, rule)) {
 *     is TitanResult.Success -> dispatch(r.value.matchCenter)
 *     is TitanResult.Failure -> retryOrBranch(r.reason)
 *     is TitanResult.Error   -> log.error(r.cause)
 * }
 * ```
 */
sealed class TitanResult<out T> {

    data class Success<T>(val value: T) : TitanResult<T>()

    data class Failure(
        val reason: FailureReason,
        val detail: String = ""
    ) : TitanResult<Nothing>()

    data class Error(
        val cause: Throwable,
        val context: String = ""
    ) : TitanResult<Nothing>()

    // ── Convenience helpers ────────────────────────────────────────────────

    val isSuccess: Boolean get() = this is Success
    val isFailure: Boolean get() = this is Failure
    val isError:   Boolean get() = this is Error

    fun getOrNull(): T? = (this as? Success)?.value

    fun <R> map(transform: (T) -> R): TitanResult<R> = when (this) {
        is Success -> Success(transform(value))
        is Failure -> this
        is Error   -> this
    }

    inline fun onSuccess(block: (T) -> Unit): TitanResult<T> {
        if (this is Success) block(value)
        return this
    }

    inline fun onFailure(block: (FailureReason, String) -> Unit): TitanResult<T> {
        if (this is Failure) block(reason, detail)
        return this
    }

    inline fun onError(block: (Throwable) -> Unit): TitanResult<T> {
        if (this is Error) block(cause)
        return this
    }

    companion object {
        fun <T> of(block: () -> T): TitanResult<T> = try {
            Success(block())
        } catch (t: Throwable) {
            Error(t)
        }

        suspend fun <T> ofSuspend(block: suspend () -> T): TitanResult<T> = try {
            Success(block())
        } catch (t: Throwable) {
            Error(t)
        }
    }
}

enum class FailureReason {
    TEMPLATE_NOT_FOUND,
    OCR_NO_MATCH,
    OCR_BELOW_THRESHOLD,
    GESTURE_DISPATCH_FAILED,
    GESTURE_ACCESSIBILITY_NULL,
    WORKFLOW_TIMEOUT,
    WORKFLOW_MAX_RETRIES,
    CAPTURE_FRAME_NULL,
    CAPTURE_STALE_FRAME,
    TFLITE_MODEL_MISSING,
    RL_NO_VALID_ACTION,
    PLUGIN_LOAD_FAILED,
    PERMISSION_MISSING,
    THERMAL_HALT,
    BATTERY_CRITICAL
}
