package com.titan.automation.engine.ml

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * QTable — thread-safe LRU Q-table for on-device Q-learning.
 *
 * Implementation: HashMap<StateHash, FloatArray> where FloatArray.size = action count.
 * StateHash:      SHA-256 of the state vector, truncated to 16 bytes (hex string).
 * Max entries:    10,000 — evicts LRU entry when full.
 * Persistence:    serialization/deserialization via [toSnapshot] / [fromSnapshot];
 *                 callers (RLEngine) decide when to persist to Room DB.
 *
 * Optimistic init: new entries start at [OPTIMISTIC_Q] (+0.1) to encourage exploration.
 *
 * Pattern adapted from subway-surfers-bot DQNAgent Q-table management.
 */
@Singleton
class QTable @Inject constructor() {

    companion object {
        const val OPTIMISTIC_Q  = 0.1f
        const val MAX_ENTRIES   = 10_000
        private  const val TAG  = "QTable"
    }

    private val mutex = Mutex()

    // Access-ordered LinkedHashMap: eldest entry evicted when MAX_ENTRIES exceeded.
    private val table = object : LinkedHashMap<String, FloatArray>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, FloatArray>): Boolean {
            val evict = size > MAX_ENTRIES
            if (evict) Log.v(TAG, "LRU eviction: table full")
            return evict
        }
    }

    // Per-state-action visit counts for adaptive learning rate
    private val visitCounts = HashMap<String, IntArray>(256)

    private var actionCount: Int = 5  // Matches RLAction sealed class variants

    // ── API ───────────────────────────────────────────────────────────────────

    /**
     * Initialise table for [numActions] actions. Must be called before first use.
     */
    fun setActionCount(numActions: Int) { actionCount = numActions }

    /**
     * Get Q-values for [stateVector]. Creates entry with optimistic init if absent.
     * Returns a snapshot copy — modifications require [update].
     */
    suspend fun getValues(stateVector: FloatArray): FloatArray = mutex.withLock {
        val hash = hashState(stateVector)
        table.getOrPut(hash) { FloatArray(actionCount) { OPTIMISTIC_Q } }.copyOf()
    }

    /**
     * Get best action index for [stateVector] (epsilon-greedy done by caller).
     * Only considers [validActions] (action masking — skips out-of-bounds actions).
     */
    suspend fun getBestAction(
        stateVector  : FloatArray,
        validActions : IntArray = IntArray(actionCount) { it }
    ): Int = mutex.withLock {
        val hash    = hashState(stateVector)
        val qValues = table[hash] ?: return@withLock validActions.first()
        validActions.maxByOrNull { idx -> if (idx < qValues.size) qValues[idx] else Float.NEGATIVE_INFINITY }
            ?: validActions.first()
    }

    /**
     * TD update: Q(s,a) += α(s,a) × δ
     * Learning rate α is adaptive: α₀ / (1 + n(s,a) × 0.1)
     */
    suspend fun update(
        stateVector: FloatArray,
        actionIdx  : Int,
        tdError    : Float,
        alpha0     : Float = 0.1f
    ) = mutex.withLock {
        val hash   = hashState(stateVector)
        val qRow   = table.getOrPut(hash) { FloatArray(actionCount) { OPTIMISTIC_Q } }
        val counts = visitCounts.getOrPut(hash) { IntArray(actionCount) }

        val n      = counts[actionIdx].coerceAtLeast(0)
        val alpha  = alpha0 / (1f + n * 0.1f)
        qRow[actionIdx] += alpha * tdError
        counts[actionIdx]++
    }

    /**
     * Get visit count for a (state, action) pair.
     */
    suspend fun visitCount(stateVector: FloatArray, actionIdx: Int): Int = mutex.withLock {
        visitCounts[hashState(stateVector)]?.getOrNull(actionIdx) ?: 0
    }

    /** Total number of unique states in the table. */
    suspend fun size(): Int = mutex.withLock { table.size }

    // ── Snapshot for Room persistence ─────────────────────────────────────────

    data class Snapshot(val entries: List<Entry>) {
        data class Entry(val hash: String, val qValues: FloatArray, val visits: IntArray)
    }

    suspend fun toSnapshot(): Snapshot = mutex.withLock {
        Snapshot(table.map { (k, v) ->
            Snapshot.Entry(k, v.copyOf(), visitCounts[k]?.copyOf() ?: IntArray(actionCount))
        })
    }

    suspend fun fromSnapshot(snapshot: Snapshot) = mutex.withLock {
        table.clear()
        visitCounts.clear()
        snapshot.entries.forEach { e ->
            table[e.hash] = e.qValues.copyOf()
            visitCounts[e.hash] = e.visits.copyOf()
        }
    }

    // ── Hashing ───────────────────────────────────────────────────────────────

    private val md: MessageDigest = MessageDigest.getInstance("SHA-256")

    private fun hashState(stateVector: FloatArray): String {
        val bytes = ByteArray(stateVector.size * 4)
        val buf   = java.nio.ByteBuffer.wrap(bytes)
        stateVector.forEach { buf.putFloat(it) }
        val digest = md.digest(bytes)
        // Truncate to 16 bytes (32 hex chars) — balance precision vs memory
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }
}
