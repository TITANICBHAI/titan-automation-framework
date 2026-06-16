package com.titan.automation.engine.ml

import com.titan.automation.domain.model.DetectionResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * StateEncoder — compresses screen/execution state into a fixed-length Float vector
 * suitable for Q-table hashing and DQN input.
 *
 * Output vector layout (64 dimensions):
 *   Slot  0-15: Binary feature flags (template_found, ocr_match, latency_ok, etc.)
 *   Slot 16-31: Normalised confidence values from last detections
 *   Slot 32-47: Timing features (elapsed since last success, retry count, cooldown)
 *   Slot 48-63: Workflow position (step index, branch depth, loop count)
 *
 * Output is L2-normalised before return to ensure consistent Q-table hash distances.
 *
 * Pattern adapted from aria-ai-cpu-only StateEncoder (state vector construction).
 */
@Singleton
class StateEncoder @Inject constructor() {

    companion object {
        const val STATE_DIM = 64
    }

    /**
     * Encode current execution context into a [STATE_DIM]-dimensional float vector.
     *
     * @param detections       Latest detection results (up to 4).
     * @param stepIndex        Current step index in the workflow.
     * @param totalSteps       Total number of steps in the workflow.
     * @param retryCount       Number of retries consumed on the current step.
     * @param maxRetries       Max retries allowed for the current step.
     * @param msSinceLastSuccess Milliseconds since the last successful step.
     * @param branchDepth      Current branch nesting depth.
     * @param loopIteration    Current loop iteration counter.
     * @param thermalLevel     0=NORMAL, 1=LIGHT, 2=MODERATE, 3=SEVERE, 4=CRITICAL
     * @param batteryPct       Battery level [0..100].
     */
    fun encode(
        detections           : List<DetectionResult> = emptyList(),
        stepIndex            : Int   = 0,
        totalSteps           : Int   = 1,
        retryCount           : Int   = 0,
        maxRetries           : Int   = 3,
        msSinceLastSuccess   : Long  = 0L,
        branchDepth          : Int   = 0,
        loopIteration        : Int   = 0,
        thermalLevel         : Int   = 0,
        batteryPct           : Int   = 100
    ): FloatArray {
        val v = FloatArray(STATE_DIM)

        // ── Slots 0-15: Binary feature flags ─────────────────────────────────
        v[0]  = if (detections.any { it is DetectionResult.Found }) 1f else 0f
        v[1]  = if (detections.any { it is DetectionResult.NotFound }) 1f else 0f
        v[2]  = if (detections.any { it is DetectionResult.Error }) 1f else 0f
        v[3]  = if (retryCount > 0) 1f else 0f
        v[4]  = if (retryCount >= maxRetries - 1) 1f else 0f
        v[5]  = if (msSinceLastSuccess > 5_000L) 1f else 0f
        v[6]  = if (msSinceLastSuccess > 15_000L) 1f else 0f
        v[7]  = if (thermalLevel >= 2) 1f else 0f
        v[8]  = if (thermalLevel >= 3) 1f else 0f
        v[9]  = if (batteryPct < 20) 1f else 0f
        v[10] = if (batteryPct < 10) 1f else 0f
        v[11] = if (branchDepth > 0) 1f else 0f
        v[12] = if (loopIteration > 0) 1f else 0f
        v[13] = if (stepIndex == 0) 1f else 0f
        v[14] = if (totalSteps > 0 && stepIndex == totalSteps - 1) 1f else 0f
        v[15] = if (detections.filterIsInstance<DetectionResult.Found>().size > 1) 1f else 0f

        // ── Slots 16-31: Confidence values ────────────────────────────────────
        val foundList = detections.filterIsInstance<DetectionResult.Found>()
        for (i in 0 until minOf(4, foundList.size)) {
            v[16 + i * 2]     = foundList[i].confidence.coerceIn(0f, 1f)
            v[16 + i * 2 + 1] = if (foundList[i].confidence > 0.9f) 1f else 0f
        }
        val notFoundList = detections.filterIsInstance<DetectionResult.NotFound>()
        for (i in 0 until minOf(4, notFoundList.size)) {
            v[24 + i] = notFoundList[i].confidence.coerceIn(0f, 1f)
        }
        // Overall confidence statistics
        val allConfidences = detections.map { it.confidence }
        v[28] = if (allConfidences.isNotEmpty()) allConfidences.max() else 0f
        v[29] = if (allConfidences.isNotEmpty()) allConfidences.average().toFloat() else 0f
        v[30] = if (allConfidences.size > 1) allConfidences.min() else 0f
        v[31] = allConfidences.size.toFloat() / 8f

        // ── Slots 32-47: Timing features ──────────────────────────────────────
        v[32] = (msSinceLastSuccess / 30_000f).coerceIn(0f, 1f)   // Norm over 30s
        v[33] = (retryCount.toFloat() / maxRetries.coerceAtLeast(1)).coerceIn(0f, 1f)
        v[34] = thermalLevel.toFloat() / 4f
        v[35] = (100 - batteryPct).toFloat() / 100f
        v[36] = if (msSinceLastSuccess in 1_000..5_000) 1f else 0f
        v[37] = if (msSinceLastSuccess in 5_000..15_000) 0.5f else 0f
        // Slots 38-47: reserved for future timing windows
        for (i in 38..47) v[i] = 0f

        // ── Slots 48-63: Workflow position ────────────────────────────────────
        v[48] = (stepIndex.toFloat() / totalSteps.coerceAtLeast(1)).coerceIn(0f, 1f)
        v[49] = (branchDepth.toFloat() / 8f).coerceIn(0f, 1f)
        v[50] = (loopIteration.toFloat() / 100f).coerceIn(0f, 1f)
        v[51] = if (totalSteps > 0) totalSteps.toFloat() / 50f else 0f
        // Slots 52-63: reserved
        for (i in 52..63) v[i] = 0f

        return l2Normalize(v)
    }

    // ── L2 normalisation ──────────────────────────────────────────────────────

    private fun l2Normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.map { it * it }.sum().toDouble()).toFloat()
        if (norm < 1e-8f) return v
        return FloatArray(v.size) { v[it] / norm }
    }
}
