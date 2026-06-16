package com.titan.automation.domain.usecase

import com.titan.automation.domain.model.WorkflowDefinition
import com.titan.automation.domain.repository.WorkflowRepository
import com.titan.automation.engine.workflow.WorkflowParser
import javax.inject.Inject

/**
 * ParseWorkflowUseCase — parse, validate and optionally persist a JSON workflow.
 *
 * Combines [WorkflowParser] deserialization with [WorkflowRepository] persistence.
 * Returns typed results so callers never receive raw exceptions.
 */
class ParseWorkflowUseCase @Inject constructor(
    private val parser            : WorkflowParser,
    private val workflowRepository: WorkflowRepository
) {
    sealed class Result {
        data class Success(val definition: WorkflowDefinition) : Result()
        data class ParseError(val reason: String, val jsonPath: String = "")  : Result()
        data class ValidationError(val reason: String) : Result()
        data class Failure(val cause: Throwable) : Result()
    }

    /**
     * Parse JSON [text] into a [WorkflowDefinition].
     *
     * @param text   Raw JSON workflow string.
     * @param save   If true, persist the parsed workflow to [WorkflowRepository].
     */
    suspend operator fun invoke(text: String, save: Boolean = false): Result {
        return try {
            // Pre-validate syntax before full parse
            val validationError = parser.validate(text)
            if (validationError != null) {
                return Result.ValidationError(validationError)
            }

            val definition = parser.parse(text)

            if (save) {
                workflowRepository.saveWorkflow(definition)
            }

            Result.Success(definition)
        } catch (e: kotlinx.serialization.SerializationException) {
            Result.ParseError(e.message ?: "JSON deserialization failed")
        } catch (e: Exception) {
            Result.Failure(e)
        }
    }

    /**
     * Serialize an existing [WorkflowDefinition] back to JSON.
     */
    fun serialize(definition: WorkflowDefinition, pretty: Boolean = false): String =
        if (pretty) parser.prettyPrint(definition) else parser.serialize(definition)
}
