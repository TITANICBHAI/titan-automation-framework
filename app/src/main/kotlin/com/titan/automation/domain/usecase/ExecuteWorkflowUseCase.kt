package com.titan.automation.domain.usecase

import com.titan.automation.core.TitanResult
import com.titan.automation.domain.model.WorkflowDefinition
import com.titan.automation.domain.repository.WorkflowRepository
import com.titan.automation.engine.workflow.MacroEngine
import kotlinx.coroutines.Job
import javax.inject.Inject

/**
 * ExecuteWorkflowUseCase — Clean Architecture use case for launching a workflow.
 *
 * Orchestrates: lookup → validate → load into MacroEngine → start execution.
 * Returns a [Job] that callers can cancel for graceful interruption.
 *
 * All error paths are wrapped in [TitanResult.Failure] — no raw exceptions propagate.
 */
class ExecuteWorkflowUseCase @Inject constructor(
    private val workflowRepository: WorkflowRepository,
    private val macroEngine        : MacroEngine
) {
    sealed class Result {
        data class Started(val workflowId: String, val job: Job) : Result()
        data class AlreadyRunning(val workflowId: String)        : Result()
        data class WorkflowNotFound(val workflowId: String)      : Result()
        data class ValidationFailed(val reason: String)          : Result()
        data class Failure(val cause: Throwable)                 : Result()
    }

    /**
     * Start executing the workflow identified by [workflowId].
     *
     * Checks that the workflow is not already running to prevent duplicate execution.
     * Loads the workflow definition into [MacroEngine] before starting.
     */
    suspend operator fun invoke(workflowId: String): Result {
        return try {
            // Check not already running
            if (macroEngine.runningWorkflows.value.containsKey(workflowId)) {
                return Result.AlreadyRunning(workflowId)
            }

            // Fetch from repository
            val definition = workflowRepository.getWorkflow(workflowId)
                ?: return@invoke Result.WorkflowNotFound(workflowId)

            // Validate basic schema
            val validationError = validateWorkflow(definition)
            if (validationError != null) return@invoke Result.ValidationFailed(validationError)

            // Load then start
            macroEngine.loadWorkflow(definition)
            val job = macroEngine.startWorkflow(workflowId)

            Result.Started(workflowId, job)
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    /**
     * Stop a running workflow immediately.
     */
    fun stop(workflowId: String) = macroEngine.stopWorkflow(workflowId)

    private fun validateWorkflow(def: WorkflowDefinition): String? {
        if (def.workflowId.isBlank()) return "Workflow ID is empty"
        if (def.states.isEmpty())     return "Workflow has no steps"
        if (def.initialState.isBlank()) return "No initial state defined"
        val stateIds = def.states.keys
        for ((id, state) in def.states) {
            if (state.onSuccess != "END" && state.onSuccess !in stateIds)
                return "Step '$id' on_success references unknown state '${state.onSuccess}'"
            if (state.onFailure != "END" && state.onFailure !in stateIds)
                return "Step '$id' on_failure references unknown state '${state.onFailure}'"
        }
        return null
    }
}
