package com.titan.automation.data.repository

import com.titan.automation.data.db.MacroDatabase
import com.titan.automation.data.db.SessionEntity
import com.titan.automation.data.db.WorkflowEntity
import com.titan.automation.data.store.WorkflowDataStore
import com.titan.automation.domain.model.WorkflowDefinition
import com.titan.automation.domain.model.WorkflowSession
import com.titan.automation.domain.repository.WorkflowRepository
import com.titan.automation.engine.workflow.WorkflowParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkflowRepositoryImpl @Inject constructor(
    private val db: MacroDatabase,
    private val dataStore: WorkflowDataStore,
    private val parser: WorkflowParser
) : WorkflowRepository {

    override fun allWorkflows(): Flow<List<WorkflowDefinition>> =
        db.workflowDao().allWorkflows().map { entities ->
            entities.mapNotNull { entity ->
                runCatching { parser.parse(entity.jsonPayload) }.getOrNull()
            }
        }

    override suspend fun getWorkflow(workflowId: String): WorkflowDefinition? {
        val entity = db.workflowDao().getById(workflowId) ?: return null
        return runCatching { parser.parse(entity.jsonPayload) }.getOrNull()
    }

    override suspend fun saveWorkflow(workflow: WorkflowDefinition) {
        db.workflowDao().upsert(
            WorkflowEntity(
                workflowId  = workflow.workflowId,
                version     = workflow.version,
                jsonPayload = parser.serialize(workflow),
                updatedAt   = System.currentTimeMillis()
            )
        )
    }

    override suspend fun deleteWorkflow(workflowId: String) {
        db.workflowDao().deleteById(workflowId)
    }

    override suspend fun saveSession(session: WorkflowSession) {
        db.sessionDao().clearFinished()
        db.sessionDao().upsert(
            SessionEntity(
                workflowId           = session.workflowId,
                currentState         = session.currentState,
                lastCheckpointState  = session.lastCheckpointState,
                stepCount            = session.stepCount,
                retryCount           = session.retryCount,
                startedAt            = session.startedAtMs,
                completed            = session.completed,
                failed               = session.failed
            )
        )
        // Also persist to DataStore for cross-process watchdog recovery
        dataStore.saveCheckpoint(
            """{"workflowId":"${session.workflowId}","state":"${session.lastCheckpointState}"}"""
        )
    }

    override suspend fun getLastSession(): WorkflowSession? {
        val entity = db.sessionDao().getActiveSession() ?: return null
        return WorkflowSession(
            workflowId          = entity.workflowId,
            currentState        = entity.currentState,
            lastCheckpointState = entity.lastCheckpointState,
            stepCount           = entity.stepCount,
            retryCount          = entity.retryCount,
            startedAtMs         = entity.startedAt,
            completed           = entity.completed,
            failed              = entity.failed
        )
    }

    override suspend fun clearSession() {
        db.sessionDao().clearAll()
        dataStore.clearCheckpoint()
    }

    override suspend fun importFromJson(json: String): WorkflowDefinition =
        parser.parse(json)

    override suspend fun exportToJson(workflow: WorkflowDefinition): String =
        parser.prettyPrint(workflow)
}
