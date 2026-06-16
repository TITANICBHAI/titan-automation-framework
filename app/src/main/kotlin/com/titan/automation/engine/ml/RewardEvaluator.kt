package com.titan.automation.engine.ml

import com.titan.automation.domain.model.DetectionResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * RewardEvaluator — computes RL reward signals from execution outcomes.
 *
 * Reward table (from spec §7.4):
 *   +1.0  DetectionResult.Found, retries < 2
 *   +0.5  DetectionResult.Found, retries 2-4
 *   +2.0  Full workflow loop completed
 *   -0.5  DetectionResult.NotFound (timeout)
 *   -1.0  Gesture dispatch failed
 *   -2.0  Step exceeded max retries → escalated failure
 *   +0.3  Execution latency improved vs rolling average
 *   -0.3  Execution latency degraded
 *
 * Shortest-Path Adjustment (SPA):
 *   R_adjusted = R_base − ψ × N_steps (ψ = STEP_PENALTY = 0.05)
 *
 * Pattern adapted from SmartAssistant reward shaping module.
 */
@Singleton
class RewardEvaluator @Inject constructor() {

    private val STEP_PENALTY = 0.05f   // SPA ψ parameter

    // Rolling latency average (EMA, α = 0.2)
    private var emaLatencyMs  : Long = 0L
    private var emaInitialised: Boolean = false

    // ── Reward computation ────────────────────────────────────────────────────

    /**
     * Compute reward for a detection result.
     *
     * @param result      Detection outcome from VisionEngine.
     * @param retries     Number of retries consumed.
     * @param maxRetries  Max allowed retries for this step.
     * @param latencyMs   Actual step execution latency.
     * @param stepIndex   Position in the workflow (for SPA).
     * @return Shaped reward scalar.
     */
    fun evaluateDetection(
        result    : DetectionResult,
        retries   : Int = 0,
        maxRetries: Int = 3,
        latencyMs : Long = 0L,
        stepIndex : Int = 0
    ): Float {
        val base = when {
            result is DetectionResult.Found && retries < 2 -> 1.0f
            result is DetectionResult.Found && retries < 5 -> 0.5f
            result is DetectionResult.Found               -> 0.2f
            result is DetectionResult.NotFound            -> -0.5f
            result is DetectionResult.Error               -> -1.0f
            else                                          -> 0f
        }

        val latencyBonus = evaluateLatency(latencyMs)
        val spa           = STEP_PENALTY * stepIndex

        return (base + latencyBonus - spa).coerceIn(-5f, 5f)
    }

    /**
     * Reward for a gesture dispatch outcome.
     */
    fun evaluateGesture(
        success   : Boolean,
        latencyMs : Long = 0L
    ): Float {
        val base = if (success) 0.2f else -1.0f
        return (base + evaluateLatency(latencyMs)).coerceIn(-5f, 5f)
    }

    /**
     * Reward for a step that exceeded max retries (escalated failure).
     */
    fun evaluateEscalatedFailure(): Float = -2.0f

    /**
     * Reward for a complete workflow loop cycle.
     */
    fun evaluateWorkflowComplete(): Float = 2.0f

    /**
     * Reward for thermal/battery forced pause.
     */
    fun evaluateThermalPause(): Float = -0.1f

    // ── Latency EMA ───────────────────────────────────────────────────────────

    private fun evaluateLatency(latencyMs: Long): Float {
        if (latencyMs <= 0L) return 0f
        return if (!emaInitialised) {
            emaLatencyMs  = latencyMs
            emaInitialised = true
            0f
        } else {
            val prev = emaLatencyMs
            emaLatencyMs = (emaLatencyMs * 8 + latencyMs * 2) / 10   // α = 0.2 EMA
            when {
                latencyMs < prev - 50 ->  0.3f   // Faster → positive
                latencyMs > prev + 50 -> -0.3f   // Slower → negative
                else                  ->  0f
            }
        }
    }

    fun resetLatencyBaseline() {
        emaLatencyMs   = 0L
        emaInitialised = false
    }
}
