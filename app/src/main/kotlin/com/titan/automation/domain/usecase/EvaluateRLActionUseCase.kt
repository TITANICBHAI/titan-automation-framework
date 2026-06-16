package com.titan.automation.domain.usecase

import com.titan.automation.domain.model.DetectionResult
import com.titan.automation.engine.ml.QTable
import com.titan.automation.engine.ml.RewardEvaluator
import com.titan.automation.engine.ml.StateEncoder
import com.titan.automation.engine.ml.ExperienceReplay
import com.titan.automation.engine.ml.Experience
import com.titan.automation.engine.ml.DQNLite
import javax.inject.Inject

/**
 * EvaluateRLActionUseCase — clean interface for RL decision evaluation.
 *
 * Abstracts over [QTable], [DQNLite], [StateEncoder], [RewardEvaluator], and
 * [ExperienceReplay] so MacroEngine interacts with a single facade.
 *
 * Decision strategy:
 *   1. Encode current state via StateEncoder
 *   2. ε-greedy: random action with probability epsilon, else best from QTable
 *   3. If QTable has no entry (novel state): fall back to DQNLite.forward()
 *   4. Apply action masking (only valid action indices allowed)
 */
class EvaluateRLActionUseCase @Inject constructor(
    private val stateEncoder   : StateEncoder,
    private val qTable         : QTable,
    private val dqnLite        : DQNLite,
    private val rewardEvaluator: RewardEvaluator,
    private val experienceReplay: ExperienceReplay
) {
    data class ActionDecision(
        val actionIdx   : Int,
        val isExploring : Boolean,
        val stateVector : FloatArray,
        val qValues     : FloatArray
    )

    /**
     * Select the best action for the current state using ε-greedy policy.
     *
     * @param detections     Latest detection results.
     * @param stepIndex      Current step index.
     * @param totalSteps     Total workflow steps.
     * @param retryCount     Current retry count.
     * @param maxRetries     Max retries for this step.
     * @param msSinceLast    Ms since last successful step.
     * @param epsilon        Exploration rate [0..1].
     * @param validActions   Indices of currently valid actions (action masking).
     */
    suspend fun selectAction(
        detections  : List<DetectionResult> = emptyList(),
        stepIndex   : Int    = 0,
        totalSteps  : Int    = 1,
        retryCount  : Int    = 0,
        maxRetries  : Int    = 3,
        msSinceLast : Long   = 0L,
        epsilon     : Float  = 0.3f,
        validActions: IntArray = IntArray(5) { it }
    ): ActionDecision {
        val stateVec = stateEncoder.encode(
            detections         = detections,
            stepIndex          = stepIndex,
            totalSteps         = totalSteps,
            retryCount         = retryCount,
            maxRetries         = maxRetries,
            msSinceLastSuccess = msSinceLast
        )

        val isExploring = kotlin.random.Random.nextFloat() < epsilon
        val actionIdx   : Int
        val qValues     : FloatArray

        if (isExploring) {
            actionIdx = validActions[kotlin.random.Random.nextInt(validActions.size)]
            qValues   = FloatArray(5)
        } else {
            // Try QTable first (exact state match)
            qValues   = qTable.getValues(stateVec)
            val hasEntry = qValues.any { it != QTable.OPTIMISTIC_Q }
            actionIdx = if (hasEntry) {
                qTable.getBestAction(stateVec, validActions)
            } else {
                // Novel state → use DQNLite generalisation
                val dqnQ = dqnLite.forward(stateVec)
                validActions.maxByOrNull { if (it < dqnQ.size) dqnQ[it] else Float.NEGATIVE_INFINITY }
                    ?: validActions.first()
            }
        }

        return ActionDecision(actionIdx, isExploring, stateVec, qValues)
    }

    /**
     * Record the transition outcome and potentially trigger a training batch.
     */
    suspend fun observe(
        decision   : ActionDecision,
        reward     : Float,
        nextDetections: List<DetectionResult> = emptyList(),
        done       : Boolean = false
    ) {
        val nextStateVec = stateEncoder.encode(detections = nextDetections)

        val exp = Experience(
            state     = decision.stateVector,
            actionIdx = decision.actionIdx,
            reward    = reward,
            nextState = nextStateVec,
            done      = done
        )

        val shouldTrain = experienceReplay.add(exp)

        // TD update on QTable
        val nextQValues = qTable.getValues(nextStateVec)
        val target = if (done) reward else reward + 0.9f * nextQValues.max()
        val current = qTable.getValues(decision.stateVector)
        val tdError = target - (current.getOrElse(decision.actionIdx) { 0f })
        qTable.update(decision.stateVector, decision.actionIdx, tdError)

        // Trigger batch training on DQNLite if enough experiences
        if (shouldTrain && experienceReplay.readyForTraining()) {
            val batch = experienceReplay.sampleBatch()
            if (batch.isNotEmpty()) {
                dqnLite.trainOnBatch(batch)
            }
        }
    }

    /**
     * Evaluate reward for a detection outcome.
     */
    fun evaluateReward(
        result   : DetectionResult,
        retries  : Int  = 0,
        latencyMs: Long = 0L,
        stepIndex: Int  = 0
    ): Float = rewardEvaluator.evaluateDetection(result, retries, latencyMs = latencyMs, stepIndex = stepIndex)
}
