package com.titan.automation.domain.model

/**
 * RLState — compressed state vector fed into the Q-table / DQN-lite.
 *
 * The state is a fixed-length float vector derived from [StateEncoder].
 * Using a value class over a FloatArray avoids an extra heap allocation per step.
 *
 * Encoding layout (see StateEncoder for full details):
 *   [0..63]   Screen region histogram (8 cells × 8 colour channels)
 *   [64..79]  Active OCR text fingerprint (16 float bag-of-chars)
 *   [80..83]  Template match confidence per active rule (up to 4)
 *   [84]      Normalised battery level (0.0–1.0)
 *   [85]      Thermal level index / 4.0 (0.0 = NORMAL, 1.0 = CRITICAL)
 *   [86]      Normalised retry count (retries / maxRetries)
 *   [87]      Time-in-state normalised (elapsed / timeoutMs)
 *   total = STATE_VECTOR_DIM
 */
data class RLState(
    val vector: FloatArray,
    val stateId: String,
    val workflowId: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    init {
        require(vector.size == STATE_VECTOR_DIM) {
            "RLState vector must be $STATE_VECTOR_DIM floats, got ${vector.size}"
        }
    }

    /** Content-based equality on the float vector (avoids referential FloatArray pitfall). */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RLState) return false
        return stateId == other.stateId &&
               workflowId == other.workflowId &&
               vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int {
        var result = vector.contentHashCode()
        result = 31 * result + stateId.hashCode()
        result = 31 * result + workflowId.hashCode()
        return result
    }

    /** Returns a hex-encoded SHA-256 hash of the vector for use as a Q-table key. */
    fun hash(): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = ByteArray(vector.size * 4)
        val buf = java.nio.ByteBuffer.wrap(bytes)
        vector.forEach { buf.putFloat(it) }
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val STATE_VECTOR_DIM = 88

        /** Returns a zeroed state vector for use as a neutral initial state. */
        fun zero(stateId: String, workflowId: String): RLState =
            RLState(FloatArray(STATE_VECTOR_DIM), stateId, workflowId)
    }
}

/**
 * RLAction — typed action the RL agent can select.
 *
 * Actions map to gesture primitives dispatched through [MacroAccessibilityService].
 * The action space is bounded to [MAX_ACTIONS] to keep the Q-table tractable.
 */
sealed class RLAction(open val actionIndex: Int) {

    data class Tap(
        val xRatio: Float,
        val yRatio: Float,
        override val actionIndex: Int
    ) : RLAction(actionIndex)

    data class Swipe(
        val startXRatio: Float,
        val startYRatio: Float,
        val endXRatio: Float,
        val endYRatio: Float,
        val durationMs: Long,
        override val actionIndex: Int
    ) : RLAction(actionIndex)

    data class Wait(
        val durationMs: Long,
        override val actionIndex: Int
    ) : RLAction(actionIndex)

    data class Custom(
        val id: String,
        override val actionIndex: Int
    ) : RLAction(actionIndex)

    companion object {
        const val MAX_ACTIONS = 32
    }
}

/**
 * RLTransition — a (state, action, reward, nextState) tuple for experience replay.
 */
data class RLTransition(
    val state: RLState,
    val action: RLAction,
    val reward: Float,
    val nextState: RLState,
    val done: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
