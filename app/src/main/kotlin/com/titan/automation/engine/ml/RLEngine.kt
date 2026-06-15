package com.titan.automation.engine.ml

import android.util.Log
import com.titan.automation.events.TitanEvent
import com.titan.automation.events.TitanEventBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.random.Random

/**
 * RLEngine — on-device Q(λ)-learning automation optimizer.
 *
 * Algorithm: Q(λ) with replacing eligibility traces, experience replay,
 * ε-greedy exploration, and a frozen target network for stable TD targets.
 *
 *   Online update (Q(λ) replacing traces):
 *     δ  = clip(r + γ·maxₐ'Q_target(s',a') − Q(s,a), -GRAD_CLIP, GRAD_CLIP)
 *     e(s,a) = 1                         ← replacing: reset current
 *     ∀ (s',a') ∈ traces:
 *       Q(s',a') += α(s',a') · δ · e(s',a')
 *       e(s',a') *= γ · λ               ← decay; remove if |e| < TRACE_MIN
 *
 *   Adaptive learning rate (per state-action visit count n):
 *     α(s,a) = α₀ / (1 + n(s,a) × 0.1)
 *
 *   Target network:
 *     Frozen copy of Q-table synced every TARGET_SYNC_FREQ updates.
 *     maxₐ'Q_target is computed from this frozen copy to prevent divergence.
 *
 *   LRU Q-table:
 *     Backed by access-ordered LinkedHashMap; evicts oldest state-action
 *     pairs when the table exceeds MAX_TABLE_SIZE entries. Prevents OOM
 *     on novel screens generating unbounded keys.
 *
 *   Shortest-Path Reward Adjustment (SPA):
 *     R_adjusted = R_base − ψ × N_steps (ψ = step penalty, default 0.05)
 *
 *   Optimistic Q initialisation:
 *     New state-action pairs start at +OPTIMISTIC_Q (0.1) to encourage
 *     exploration of unseen state regions.
 *
 *   Action masking:
 *     Only actions within valid interactive screen regions are evaluated.
 *
 *   Battery awareness:
 *     RL disabled entirely when [enabled] = false (set by ThermalGovernor).
 *
 * Thread safety:
 *     All Q-table mutations serialised through [qTableMutex].
 *     [getBestAction] reads operate on a snapshot copy.
 *
 * Extracted from: subway-surfers-bot DQNAgent.java + SmartAssistant QLearningAgent.java
 * Key improvements over those sources:
 *   - Eligibility traces (QLearningAgent) ported to Kotlin coroutines
 *   - Gradient clipping + NaN/Inf guard (DQNAgent) applied to tabular updates
 *   - Target network sync (DQNAgent) adapted for HashMap-based Q-table
 *   - Non-recursive replay (QLearningAgent bug #fixed) — traces not polluted
 *   - LRU eviction + adaptive α from QLearningAgent
 */
@Singleton
class RLEngine @Inject constructor(
    private val eventBus: TitanEventBus
) {

    // ── Hyperparameters ───────────────────────────────────────────────────────
    @Volatile var learningRate     = 0.1f
    @Volatile var discountFactor   = 0.95f
    @Volatile var lambda           = 0.9f      // Q(λ) trace decay
    @Volatile var epsilonStart     = 1.0f
    @Volatile var epsilonMin       = 0.05f
    @Volatile var stepPenalty      = 0.05f     // SPA ψ

    @Volatile var enabled          = true

    // Runtime state
    private var episodeCount       = 0
    private var totalSteps         = 0
    private var currentEpsilon     = epsilonStart

    // ── LRU Q-table ───────────────────────────────────────────────────────────
    // access-ordered LinkedHashMap → LRU eviction above MAX_TABLE_SIZE entries
    private val qTable = object : java.util.LinkedHashMap<String, Float>(
        MAX_TABLE_SIZE + 1, 0.75f, /* accessOrder= */ true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>?): Boolean =
            size > MAX_TABLE_SIZE
    }

    // ── Target Q-table (frozen copy for stable TD targets) ────────────────────
    private val targetQTable = HashMap<String, Float>(512)
    private var updatesSinceTargetSync = 0

    // ── Per-state-action visit counts (for adaptive α) ────────────────────────
    // Key: same qKey format as qTable
    private val visitCounts = HashMap<String, Int>(512)

    // ── Eligibility traces for Q(λ) ───────────────────────────────────────────
    private val eligTraces = HashMap<String, Float>(128)

    // ── Experience replay buffer ──────────────────────────────────────────────
    private val replayBuffer = ArrayDeque<Experience>(REPLAY_BUFFER_SIZE + 1)

    // ── Episode metrics ───────────────────────────────────────────────────────
    private var episodeSteps  = 0
    private var episodeReward = 0f

    private val qTableMutex = Mutex()

    // ─────────────────────────────────────────────────────────────────────────
    // Core API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Learn from a single transition: (state, action, reward, nextState).
     *
     * Step order:
     *   1. SPA reward adjustment
     *   2. Store in replay buffer
     *   3. Q(λ) online update (eligibility traces)
     *   4. Mini-batch replay every 8 steps (NO trace pollution — separate path)
     *   5. Target network sync every TARGET_SYNC_FREQ updates
     *   6. Telemetry
     */
    suspend fun learn(
        state: String,
        action: String,
        rawReward: Float,
        nextState: String,
        done: Boolean = false,
        actionMask: Set<String> = emptySet()
    ) = withContext(Dispatchers.Default) {
        if (!enabled) return@withContext

        val adjustedReward = rawReward - stepPenalty * episodeSteps
        episodeReward += adjustedReward
        episodeSteps++
        totalSteps++

        val encodedState = encodeState(state)
        val encodedNext  = encodeState(nextState)
        val key          = qKey(encodedState, action)

        qTableMutex.withLock {
            val exp = Experience(
                state      = encodedState,
                action     = action,
                reward     = adjustedReward,
                nextState  = encodedNext,
                done       = done,
                actionMask = actionMask
            )
            replayBuffer.addLast(exp)
            if (replayBuffer.size > REPLAY_BUFFER_SIZE) replayBuffer.removeFirst()

            // ── Q(λ) online update ─────────────────────────────────────────
            val currentQ = qGet(key)
            val maxNext  = if (done) 0f else maxTargetQ(encodedNext, actionMask)
            val tdError  = adjustedReward + discountFactor * maxNext - currentQ

            // Gradient clip (from DQNAgent: prevents explosion in early training)
            val clippedDelta = tdError.coerceIn(-GRAD_CLIP, GRAD_CLIP)
            val wasClipped   = clippedDelta != tdError

            // Replacing trace: current (s,a) → 1, all others in current state → 0
            // (replacing = zero out same-state traces before setting current to 1)
            val prefix = "${encodedState}__"
            eligTraces.keys
                .filter { it.startsWith(prefix) }
                .forEach { eligTraces[it] = 0f }
            eligTraces[key] = 1f

            // Update all active traces
            val toRemove = mutableListOf<String>()
            for ((traceKey, traceVal) in eligTraces) {
                if (traceVal == 0f) { toRemove.add(traceKey); continue }

                val traceAction = traceKey.substringAfter("__")
                val alpha = adaptiveAlpha(traceKey)
                val oldQ  = qGet(traceKey)
                val newQ  = oldQ + alpha * clippedDelta * traceVal

                // NaN/Inf guard (from DQNAgent critical pattern)
                if (newQ.isFinite()) {
                    qTable[traceKey] = newQ
                    visitCounts[traceKey] = (visitCounts[traceKey] ?: 0) + 1
                } else {
                    Log.w(TAG, "Non-finite Q rejected for key=$traceKey newQ=$newQ")
                }

                val decayed = traceVal * discountFactor * lambda
                if (abs(decayed) < TRACE_MIN || traceAction == "done") {
                    toRemove.add(traceKey)
                } else {
                    eligTraces[traceKey] = decayed
                }
            }
            for (k in toRemove) eligTraces.remove(k)
            if (done) eligTraces.clear()

            updatesSinceTargetSync++
            if (updatesSinceTargetSync >= TARGET_SYNC_FREQ) {
                syncTargetNetwork()
                updatesSinceTargetSync = 0
                Log.d(TAG, "Target network synced — ${qTable.size} states")
                eventBus.emit(TitanEvent.RLDecision(
                    action = "__target_sync__",
                    qValue = 0f, explorationMode = false, epsilon = currentEpsilon
                ))
            }

            // ── Mini-batch replay every 8 steps (non-recursive, no trace pollution)
            if (totalSteps % 8 == 0 && replayBuffer.size >= BATCH_SIZE) {
                replayBatchNoTraces()
            }

            if (wasClipped) {
                Log.v(TAG, "Gradient clipped: raw=${"%.3f".format(tdError)} clipped=$clippedDelta")
            }
        }
    }

    /**
     * Select best action using ε-greedy policy.
     * ε decays linearly: 1.0 → 0.05 over DECAY_STEPS total steps.
     */
    suspend fun getBestAction(
        state: String,
        availableActions: List<String>,
        actionMask: Set<String> = emptySet()
    ): RLDecision = withContext(Dispatchers.Default) {
        if (!enabled || availableActions.isEmpty()) {
            return@withContext RLDecision(
                action          = availableActions.firstOrNull() ?: "wait",
                qValue          = 0f,
                explorationMode = true,
                epsilon         = currentEpsilon
            )
        }

        decayEpsilon()

        val valid = if (actionMask.isNotEmpty())
            availableActions.filter { it in actionMask }
        else availableActions

        val inExploration = Random.nextFloat() < currentEpsilon

        val action: String
        val qValue: Float

        if (inExploration || valid.isEmpty()) {
            action = (if (valid.isNotEmpty()) valid else availableActions).random()
            qValue = OPTIMISTIC_Q
        } else {
            val encoded  = encodeState(state)
            val snapshot = qTableMutex.withLock { HashMap(qTable) }
            val best     = valid.maxByOrNull { snapshot.getOrDefault(qKey(encoded, it), OPTIMISTIC_Q) }
                ?: valid.first()
            action = best
            qValue = snapshot.getOrDefault(qKey(encoded, action), OPTIMISTIC_Q)
        }

        eventBus.emit(
            TitanEvent.RLDecision(
                action          = action,
                qValue          = qValue,
                explorationMode = inExploration,
                epsilon         = currentEpsilon
            )
        )

        RLDecision(
            action          = action,
            qValue          = qValue,
            explorationMode = inExploration,
            epsilon         = currentEpsilon
        )
    }

    /**
     * Episode end: clear eligibility traces, log metrics.
     */
    suspend fun onEpisodeEnd(success: Boolean) = withContext(Dispatchers.Default) {
        episodeCount++
        val finalReward = if (success) episodeReward + 10f else episodeReward - 5f

        Log.d(TAG, "Episode $episodeCount ended: success=$success " +
            "steps=$episodeSteps reward=${"%.2f".format(finalReward)} " +
            "ε=${"%.3f".format(currentEpsilon)} Q-states=${qTable.size}")

        qTableMutex.withLock { eligTraces.clear() }

        episodeSteps  = 0
        episodeReward = 0f
    }

    /** Shaped reward values — centralises reward logic so callers stay clean. */
    fun shapeReward(outcome: RewardOutcome, stepsToComplete: Int = 0): Float = when (outcome) {
        RewardOutcome.STATE_TRANSITION_SUCCESS -> 5f
        RewardOutcome.GOAL_ACHIEVED            -> 20f
        RewardOutcome.COLLISION_OR_FAILURE     -> -10f
        RewardOutcome.GESTURE_REJECTED         -> -2f
        RewardOutcome.TIMEOUT                  -> -5f
        RewardOutcome.RETRY_INCURRED           -> -1f
        RewardOutcome.FASTER_COMPLETION        -> 3f + (10f / (stepsToComplete.coerceAtLeast(1).toFloat()))
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Persistence
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun exportQTable(): Map<String, Float> =
        qTableMutex.withLock { HashMap(qTable) }

    suspend fun importQTable(table: Map<String, Float>) {
        qTableMutex.withLock {
            qTable.clear()
            qTable.putAll(table)
            syncTargetNetwork()
        }
    }

    suspend fun reset() {
        qTableMutex.withLock {
            qTable.clear()
            targetQTable.clear()
            visitCounts.clear()
            eligTraces.clear()
            replayBuffer.clear()
        }
        episodeCount  = 0
        totalSteps    = 0
        currentEpsilon = epsilonStart
        episodeSteps  = 0
        episodeReward = 0f
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internals — all called under qTableMutex unless noted
    // ─────────────────────────────────────────────────────────────────────────

    /** Get Q-value with optimistic initialisation for unseen pairs. */
    private fun qGet(key: String): Float =
        qTable.getOrDefault(key, OPTIMISTIC_Q)

    /** Max Q-value from the frozen target table for next state. */
    private fun maxTargetQ(nextState: String, actionMask: Set<String>): Float {
        var maxQ = OPTIMISTIC_Q
        for ((key, v) in targetQTable) {
            if (!key.startsWith(nextState)) continue
            val action = key.substringAfter("__")
            if (actionMask.isNotEmpty() && action !in actionMask) continue
            if (v > maxQ) maxQ = v
        }
        return maxQ
    }

    /** Adaptive α: larger updates for rarely-visited pairs. */
    private fun adaptiveAlpha(key: String): Float {
        val n = visitCounts.getOrDefault(key, 0)
        return learningRate / (1f + n * 0.1f)
    }

    /** Copy qTable → targetQTable atomically (called under qTableMutex). */
    private fun syncTargetNetwork() {
        targetQTable.clear()
        for ((k, v) in qTable) {
            if (v.isFinite()) targetQTable[k] = v
        }
    }

    /**
     * Mini-batch replay — standard Q-learning update WITHOUT touching
     * eligibility traces (prevents trace pollution, bug from QLearningAgent fixed).
     */
    private fun replayBatchNoTraces() {
        val snapshot = replayBuffer.toList()
        val n = BATCH_SIZE.coerceAtMost(snapshot.size)
        repeat(n) {
            val exp    = snapshot[Random.nextInt(snapshot.size)]
            val key    = qKey(exp.state, exp.action)
            val oldQ   = qGet(key)
            val maxN   = if (exp.done) 0f else maxTargetQ(exp.nextState, exp.actionMask)
            val delta  = (exp.reward + discountFactor * maxN - oldQ).coerceIn(-GRAD_CLIP, GRAD_CLIP)
            val alpha  = adaptiveAlpha(key)
            val newQ   = oldQ + alpha * delta
            if (newQ.isFinite()) {
                qTable[key] = newQ
                visitCounts[key] = (visitCounts[key] ?: 0) + 1
            }
        }
    }

    /** Compact state key — caps vocabulary to 4 alphanumeric tokens. */
    private fun encodeState(raw: String): String =
        raw.lowercase().split(Regex("[^a-z0-9]+")).take(4).joinToString(":")

    private fun qKey(state: String, action: String) = "${state}__${action}"

    /** Linear epsilon decay: 1.0 → ε_min over DECAY_STEPS total steps. */
    private fun decayEpsilon() {
        currentEpsilon = epsilonStart +
            (epsilonMin - epsilonStart) * (totalSteps.toFloat() / DECAY_STEPS).coerceIn(0f, 1f)
    }

    companion object {
        private const val TAG               = "RLEngine"
        private const val REPLAY_BUFFER_SIZE = 2048
        private const val BATCH_SIZE         = 32
        private const val MAX_TABLE_SIZE     = 50_000   // LRU eviction threshold
        private const val TARGET_SYNC_FREQ   = 100      // updates between target syncs
        private const val DECAY_STEPS        = 5_000    // steps for full ε decay
        private const val GRAD_CLIP          = 1.0f     // TD-error clip magnitude
        private const val TRACE_MIN          = 0.01f    // prune traces below this
        private const val OPTIMISTIC_Q       = 0.1f     // initial Q-value for new pairs
        private const val LAMBDA             = 0.9f     // eligibility trace decay (λ)
    }
}

// ── Data models ───────────────────────────────────────────────────────────────

data class Experience(
    val state: String,
    val action: String,
    val reward: Float,
    val nextState: String,
    val done: Boolean = false,
    val actionMask: Set<String> = emptySet()
)

data class RLDecision(
    val action: String,
    val qValue: Float,
    val explorationMode: Boolean,
    val epsilon: Float
)

enum class RewardOutcome {
    STATE_TRANSITION_SUCCESS,
    GOAL_ACHIEVED,
    COLLISION_OR_FAILURE,
    GESTURE_REJECTED,
    TIMEOUT,
    RETRY_INCURRED,
    FASTER_COMPLETION
}
