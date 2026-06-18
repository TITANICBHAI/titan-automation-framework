package com.titan.automation.engine.ml

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * DQNLite — lightweight 2-layer neural Q-network for on-device DQN-style learning.
 *
 * Architecture:
 *   Input layer:  STATE_DIM (64) neurons
 *   Hidden layer: 32 neurons, ReLU activation
 *   Output layer: ACTION_DIM neurons, linear (Q-values)
 *
 * This is a small approximation network to generalise Q-values across similar states,
 * complementing the exact [QTable] which handles seen states precisely.
 *
 * Weights are initialised with He initialisation (ReLU-safe).
 * Forward pass only at inference time; batch gradient update via [trainOnBatch].
 *
 * Training:
 *   Huber loss (δ=1.0) for robust TD-error minimisation.
 *   SGD with momentum = 0.9, learning rate = 0.001.
 *   Runs on Dispatchers.Default at THREAD_PRIORITY_BACKGROUND (managed by caller).
 *   NEVER called during active gesture dispatch.
 *
 * Pattern adapted from aria-ai-cpu-only lightweight DQN (CPU-only neural engine).
 */
@Singleton
class DQNLite @Inject constructor() {

    private val inputDim  = StateEncoder.STATE_DIM  // 64
    private val hiddenDim = 32
    private var actionDim = 5

    // Weights: [hidden × input], biases: [hidden]
    private var w1 = Array(hiddenDim) { FloatArray(inputDim) }
    private var b1 = FloatArray(hiddenDim)

    // Weights: [action × hidden], biases: [action]
    private var w2 = Array(actionDim) { FloatArray(hiddenDim) }
    private var b2 = FloatArray(actionDim)

    // SGD momentum buffers
    private var mW1 = Array(hiddenDim) { FloatArray(inputDim) }
    private var mW2 = Array(actionDim) { FloatArray(hiddenDim) }
    private var mB1 = FloatArray(hiddenDim)
    private var mB2 = FloatArray(actionDim)

    // Target network (frozen copy, synced every TARGET_SYNC_FREQ updates)
    private var tw1 = Array(hiddenDim) { FloatArray(inputDim) }
    private var tb1 = FloatArray(hiddenDim)
    private var tw2 = Array(actionDim) { FloatArray(hiddenDim) }
    private var tb2 = FloatArray(actionDim)

    private var updateCount    = 0
    private val TARGET_SYNC    = 100
    private val LEARNING_RATE  = 0.001f
    private val MOMENTUM       = 0.9f
    private val DISCOUNT        = 0.9f
    private val HUBER_DELTA    = 1.0f

    init { resetWeights() }

    // ── Initialisation ────────────────────────────────────────────────────────

    fun setActionDim(dim: Int) {
        actionDim = dim
        w2 = Array(dim) { FloatArray(hiddenDim) }
        b2 = FloatArray(dim)
        mW2 = Array(dim) { FloatArray(hiddenDim) }
        mB2 = FloatArray(dim)
        tw2 = Array(dim) { FloatArray(hiddenDim) }
        tb2 = FloatArray(dim)
        resetWeights()
    }

    private fun resetWeights() {
        // He initialisation: σ = sqrt(2 / fan_in)
        val heScale1 = sqrt(2.0 / inputDim).toFloat()
        val heScale2 = sqrt(2.0 / hiddenDim).toFloat()
        val rng = java.util.Random()

        for (i in 0 until hiddenDim) {
            for (j in 0 until inputDim) w1[i][j] = rng.nextGaussian().toFloat() * heScale1
            b1[i] = 0f
        }
        for (i in 0 until actionDim) {
            for (j in 0 until hiddenDim) w2[i][j] = rng.nextGaussian().toFloat() * heScale2
            b2[i] = 0f
        }
        syncTargetNetwork()
    }

    // ── Forward pass ─────────────────────────────────────────────────────────

    /**
     * Forward pass: state → Q-values for all actions.
     * Uses current (online) network weights.
     */
    fun forward(state: FloatArray): FloatArray {
        val hidden = FloatArray(hiddenDim) { i ->
            relu(dot(w1[i], state) + b1[i])
        }
        return FloatArray(actionDim) { i -> dot(w2[i], hidden) + b2[i] }
    }

    /**
     * Forward pass using frozen target network (for stable TD targets).
     */
    fun forwardTarget(state: FloatArray): FloatArray {
        val hidden = FloatArray(hiddenDim) { i ->
            relu(dot(tw1[i], state) + tb1[i])
        }
        return FloatArray(actionDim) { i -> dot(tw2[i], hidden) + tb2[i] }
    }

    // ── Training ─────────────────────────────────────────────────────────────

    /**
     * Perform one SGD update on a mini-batch of [experiences].
     * Returns mean Huber loss for telemetry.
     */
    fun trainOnBatch(experiences: List<DQNExperience>): Float {
        if (experiences.isEmpty()) return 0f

        var totalLoss = 0f

        for (exp in experiences) {
            // Compute TD target using frozen target network
            val targetQ = if (exp.done) {
                exp.reward
            } else {
                val nextQTarget = forwardTarget(exp.nextState)
                exp.reward + DISCOUNT * nextQTarget.max()
            }

            // Current Q-value
            val currentQ = forward(exp.state)
            val predicted = currentQ[exp.actionIdx]
            val tdError   = targetQ - predicted

            // Huber loss gradient
            val grad = if (kotlin.math.abs(tdError) <= HUBER_DELTA) -tdError
                       else -HUBER_DELTA * if (tdError > 0) 1f else -1f

            totalLoss += huberLoss(tdError)

            // Backprop through output layer
            val hidden = FloatArray(hiddenDim) { i -> relu(dot(w1[i], exp.state) + b1[i]) }

            val dW2 = FloatArray(hiddenDim) { j -> grad * hidden[j] }
            val dB2 = grad

            // Backprop through hidden layer
            val dHidden = FloatArray(hiddenDim) { j ->
                w2[exp.actionIdx][j] * grad
            }
            val dReLU = FloatArray(hiddenDim) { j -> if (hidden[j] > 0f) dHidden[j] else 0f }

            // SGD + momentum updates
            for (j in 0 until hiddenDim) {
                mW2[exp.actionIdx][j] = MOMENTUM * mW2[exp.actionIdx][j] + LEARNING_RATE * dW2[j]
                w2[exp.actionIdx][j] -= mW2[exp.actionIdx][j]
            }
            mB2[exp.actionIdx] = MOMENTUM * mB2[exp.actionIdx] + LEARNING_RATE * dB2
            b2[exp.actionIdx]  -= mB2[exp.actionIdx]

            for (i in 0 until hiddenDim) {
                for (j in 0 until inputDim) {
                    mW1[i][j] = MOMENTUM * mW1[i][j] + LEARNING_RATE * dReLU[i] * exp.state[j]
                    w1[i][j]  -= mW1[i][j]
                }
                mB1[i] = MOMENTUM * mB1[i] + LEARNING_RATE * dReLU[i]
                b1[i]  -= mB1[i]
            }
        }

        updateCount++
        if (updateCount % TARGET_SYNC == 0) syncTargetNetwork()

        return totalLoss / experiences.size
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun syncTargetNetwork() {
        tw1 = Array(hiddenDim) { i -> w1[i].copyOf() }
        tb1 = b1.copyOf()
        tw2 = Array(actionDim) { i -> w2[i].copyOf() }
        tb2 = b2.copyOf()
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    private fun relu(x: Float) = if (x > 0f) x else 0f
    private fun huberLoss(e: Float) = if (kotlin.math.abs(e) <= HUBER_DELTA) 0.5f * e * e
                                      else HUBER_DELTA * (kotlin.math.abs(e) - 0.5f * HUBER_DELTA)
}

data class DQNExperience(
    val state     : FloatArray,
    val actionIdx : Int,
    val reward    : Float,
    val nextState : FloatArray,
    val done      : Boolean = false
)
