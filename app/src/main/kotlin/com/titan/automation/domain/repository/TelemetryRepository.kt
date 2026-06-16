package com.titan.automation.domain.repository

import kotlinx.coroutines.flow.Flow

/**
 * TelemetryRepository — interface for structured telemetry event persistence.
 *
 * All writes are batched for performance (see [TelemetryManager] for batching impl).
 * Reads are exposed as Flow for live UI binding and as suspend fns for export.
 */
interface TelemetryRepository {

    /**
     * Inserts a single telemetry event.
     * Implementations must batch-write for performance — do not call directly in hot paths.
     */
    suspend fun insert(event: TelemetryEvent)

    /**
     * Inserts a batch of telemetry events atomically.
     * Preferred path from [TelemetryManager] batch collector.
     */
    suspend fun insertAll(events: List<TelemetryEvent>)

    /**
     * Returns all events for a given workflow, ordered by timestamp ascending.
     */
    suspend fun getByWorkflow(workflowId: String): List<TelemetryEvent>

    /**
     * Live stream of the most recent [limit] events across all workflows.
     * Re-emits on every new insertion.
     */
    fun recentEvents(limit: Int = 50): Flow<List<TelemetryEvent>>

    /**
     * Deletes all events older than [olderThanMs] epoch millis.
     * Called by maintenance job based on [TitanConstants.TELEMETRY_RETENTION_DAYS].
     */
    suspend fun pruneOlderThan(olderThanMs: Long)

    /**
     * Deletes all telemetry data — used by user-triggered "clear logs" action.
     */
    suspend fun clearAll()

    /**
     * Exports all events as a JSON string to the app cache directory.
     * Returns the absolute path of the exported file.
     */
    suspend fun exportToJson(): String
}

/**
 * Domain model for a single telemetry event.
 * Kept minimal — implementations map to their storage entity separately.
 */
data class TelemetryEvent(
    val id: Long = 0L,
    val workflowId: String,
    val stepId: String? = null,
    val eventType: TelemetryEventType,
    val payload: String = "{}",
    val timestamp: Long = System.currentTimeMillis()
)

enum class TelemetryEventType {
    STEP_STARTED,
    STEP_COMPLETED,
    STEP_FAILED,
    DETECTION_RESULT,
    GESTURE_DISPATCHED,
    GESTURE_FAILED,
    MACRO_STARTED,
    MACRO_COMPLETED,
    MACRO_PAUSED,
    MACRO_PANICKED,
    THERMAL_EVENT,
    BATTERY_EVENT,
    WATCHDOG_EVENT,
    CRASH_EVENT,
    RL_REWARD,
    OCR_RESULT,
    MEMORY_PRESSURE
}
