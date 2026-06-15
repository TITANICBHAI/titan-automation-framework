package com.titan.automation.domain.repository

import com.titan.automation.domain.model.WorkflowDefinition
import com.titan.automation.domain.model.WorkflowSession
import kotlinx.coroutines.flow.Flow

/**
 * WorkflowRepository — domain boundary for workflow persistence.
 *
 * Implementations live in :data. This interface is the only way the engine
 * layer touches storage — enforces clean architecture dependency direction.
 */
interface WorkflowRepository {

    /** Emit all stored workflow definitions as a cold Flow. */
    fun allWorkflows(): Flow<List<WorkflowDefinition>>

    /** Load a single workflow by ID. Returns null if not found. */
    suspend fun getWorkflow(workflowId: String): WorkflowDefinition?

    /** Insert or replace a workflow definition. */
    suspend fun saveWorkflow(workflow: WorkflowDefinition)

    /** Delete a workflow by ID. */
    suspend fun deleteWorkflow(workflowId: String)

    /** Persist a running session checkpoint for crash recovery. */
    suspend fun saveSession(session: WorkflowSession)

    /** Retrieve the last persisted session for recovery on restart. */
    suspend fun getLastSession(): WorkflowSession?

    /** Clear the session checkpoint after successful completion. */
    suspend fun clearSession()

    /** Parse a WorkflowDefinition from raw JSON text. */
    suspend fun importFromJson(json: String): WorkflowDefinition

    /** Serialize a WorkflowDefinition to JSON for export. */
    suspend fun exportToJson(workflow: WorkflowDefinition): String
}
