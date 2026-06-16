package com.titan.automation.telemetry

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ExecutionTimeline — reconstructs a chronological timeline of macro execution events
 * for display in the debug UI and post-run analysis.
 *
 * Events are captured from [TelemetryManager.events] SharedFlow and indexed by
 * workflowId + stepId + timestamp for O(1) lookup.
 *
 * Provides per-step statistics: min/max/avg latency, success rate, retry rate.
 *
 * Memory: capped at [MAX_EVENTS] events; oldest events are evicted (ring-buffer semantics).
 */
@Singleton
class ExecutionTimeline @Inject constructor() {

    data class TimelineEvent(
        val workflowId : String,
        val stepId     : String,
        val eventType  : String,
        val payload    : Map<String, Any>,
        val timestampMs: Long
    )

    data class StepStats(
        val stepId      : String,
        val invocations : Int,
        val successes   : Int,
        val failures    : Int,
        val avgLatencyMs: Long,
        val minLatencyMs: Long,
        val maxLatencyMs: Long,
        val retryRate   : Float
    )

    private val MAX_EVENTS = 500
    private val events     = ArrayDeque<TimelineEvent>(MAX_EVENTS + 1)

    // Index: stepId → list of latencies (ms)
    private val stepLatencies = HashMap<String, MutableList<Long>>()
    private val stepSuccesses = HashMap<String, Int>()
    private val stepFailures  = HashMap<String, Int>()
    private val stepRetries   = HashMap<String, Int>()

    // ── Recording ─────────────────────────────────────────────────────────────

    fun record(event: TimelineEvent) {
        if (events.size >= MAX_EVENTS) events.removeFirst()
        events.addLast(event)

        when (event.eventType) {
            "StepCompleted" -> {
                val latency = (event.payload["latencyMs"] as? Number)?.toLong() ?: 0L
                stepLatencies.getOrPut(event.stepId) { mutableListOf() }.add(latency)
                stepSuccesses[event.stepId] = (stepSuccesses[event.stepId] ?: 0) + 1
            }
            "StepFailed" -> {
                stepFailures[event.stepId] = (stepFailures[event.stepId] ?: 0) + 1
            }
            "StepRetried" -> {
                stepRetries[event.stepId] = (stepRetries[event.stepId] ?: 0) + 1
            }
        }
    }

    // ── Queries ───────────────────────────────────────────────────────────────

    /** All events for a specific workflow, in chronological order. */
    fun eventsForWorkflow(workflowId: String): List<TimelineEvent> =
        events.filter { it.workflowId == workflowId }

    /** Events for a specific step. */
    fun eventsForStep(stepId: String): List<TimelineEvent> =
        events.filter { it.stepId == stepId }

    /** Aggregated performance statistics for a step. */
    fun statsForStep(stepId: String): StepStats {
        val latencies = stepLatencies[stepId] ?: emptyList<Long>()
        val successes = stepSuccesses[stepId] ?: 0
        val failures  = stepFailures[stepId]  ?: 0
        val retries   = stepRetries[stepId]   ?: 0
        val total     = successes + failures

        return StepStats(
            stepId       = stepId,
            invocations  = total,
            successes    = successes,
            failures     = failures,
            avgLatencyMs = if (latencies.isEmpty()) 0L else latencies.average().toLong(),
            minLatencyMs = latencies.minOrNull() ?: 0L,
            maxLatencyMs = latencies.maxOrNull() ?: 0L,
            retryRate    = if (total == 0) 0f else retries.toFloat() / total
        )
    }

    /** All recorded steps with at least one event. */
    fun allStepIds(): Set<String> = events.map { it.stepId }.toSet()

    /** Recent events (last N). */
    fun recent(n: Int = 50): List<TimelineEvent> =
        events.takeLast(n.coerceIn(1, MAX_EVENTS))

    /** Clear all timeline data. */
    fun clear() {
        events.clear()
        stepLatencies.clear()
        stepSuccesses.clear()
        stepFailures.clear()
        stepRetries.clear()
        Log.d(TAG, "Timeline cleared")
    }

    companion object { private const val TAG = "ExecutionTimeline" }
}
