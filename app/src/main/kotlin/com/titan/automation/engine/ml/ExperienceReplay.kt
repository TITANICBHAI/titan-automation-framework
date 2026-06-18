package com.titan.automation.engine.ml

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.random.Random

/**
 * ExperienceReplay — fixed-capacity circular replay buffer for off-policy learning.
 *
 * Stores [Experience] tuples: (state, action, reward, nextState, done).
 * Provides random mini-batch sampling for DQN-style batch updates.
 *
 * Capacity: 1,000 experiences (configurable).
 * Mini-batch size: 32 for training updates.
 * Training trigger: every [trainingInterval] new experiences.
 * Training runs on Dispatchers.Default with CPU priority BACKGROUND (managed by caller).
 * NEVER trains during active gesture dispatch — caller must check [shouldTrain] first.
 *
 * Pattern adapted from aria-ai-cpu-only ExperienceReplay (replay buffer).
 */
@Singleton
class ExperienceReplay @Inject constructor() {

    var capacity         : Int = 1_000
    var miniBatchSize    : Int = 32
    var trainingInterval : Int = 50

    private val mutex   = Mutex()
    private val buffer  = ArrayDeque<Experience>(capacity + 1)
    private var addCount = 0

    // ── API ───────────────────────────────────────────────────────────────────

    /**
     * Add an experience to the buffer.
     * Evicts the oldest entry if buffer is full.
     * Returns `true` when [trainingInterval] has been reached (signals caller to train).
     */
    suspend fun add(experience: Experience): Boolean = mutex.withLock {
        if (buffer.size >= capacity) buffer.removeFirst()
        buffer.addLast(experience)
        addCount++
        addCount % trainingInterval == 0
    }

    /**
     * Sample a random mini-batch of up to [miniBatchSize] experiences.
     * Returns empty list if buffer doesn't have enough entries yet.
     */
    suspend fun sampleBatch(): List<Experience> = mutex.withLock {
        if (buffer.size < miniBatchSize) return@withLock emptyList()
        val indices = IntArray(miniBatchSize) { Random.nextInt(buffer.size) }
        indices.map { buffer[it] }
    }

    /** Current number of stored experiences. */
    suspend fun size(): Int = mutex.withLock { buffer.size }

    /** Whether buffer has enough entries for training. */
    suspend fun readyForTraining(): Boolean = mutex.withLock { buffer.size >= miniBatchSize }

    /** Clear all experiences (e.g. on workflow reset). */
    suspend fun clear() = mutex.withLock { buffer.clear(); addCount = 0 }
}

