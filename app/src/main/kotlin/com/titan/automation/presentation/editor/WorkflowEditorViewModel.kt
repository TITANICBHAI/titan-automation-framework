package com.titan.automation.presentation.editor

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.titan.automation.domain.model.WorkflowDefinition
import com.titan.automation.domain.repository.WorkflowRepository
import com.titan.automation.domain.usecase.ParseWorkflowUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * WorkflowEditorViewModel — state management for the JSON workflow editor screen.
 *
 * Supports:
 *   - Loading an existing workflow (by ID from nav args)
 *   - Live JSON editing with syntax validation
 *   - Save (persist to Room via WorkflowRepository)
 *   - Export to file
 */
@HiltViewModel
class WorkflowEditorViewModel @Inject constructor(
    private val workflowRepository: WorkflowRepository,
    private val parseWorkflowUseCase: ParseWorkflowUseCase,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    data class UiState(
        val workflowId     : String?           = null,
        val jsonText       : String            = TEMPLATE_JSON,
        val parsedWorkflow : WorkflowDefinition? = null,
        val validationError: String?           = null,
        val isSaving       : Boolean           = false,
        val saveSuccess    : Boolean           = false,
        val isDirty        : Boolean           = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        savedStateHandle.get<String>("workflowId")?.let { id ->
            if (id.isNotEmpty()) loadWorkflow(id)
        }
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    fun onJsonChanged(text: String) {
        _uiState.update { it.copy(jsonText = text, isDirty = true, saveSuccess = false) }
        validateJson(text)
    }

    fun save() {
        viewModelScope.launch {
            val text = _uiState.value.jsonText
            _uiState.update { it.copy(isSaving = true, validationError = null) }
            when (val result = parseWorkflowUseCase(text, save = true)) {
                is ParseWorkflowUseCase.Result.Success -> {
                    _uiState.update { it.copy(
                        isSaving       = false,
                        saveSuccess    = true,
                        isDirty        = false,
                        parsedWorkflow = result.definition,
                        workflowId     = result.definition.workflowId
                    )}
                }
                is ParseWorkflowUseCase.Result.ParseError -> {
                    _uiState.update { it.copy(isSaving = false, validationError = "Parse error: ${result.reason}") }
                }
                is ParseWorkflowUseCase.Result.ValidationError -> {
                    _uiState.update { it.copy(isSaving = false, validationError = result.reason) }
                }
                is ParseWorkflowUseCase.Result.Failure -> {
                    _uiState.update { it.copy(isSaving = false, validationError = result.cause.message) }
                }
            }
        }
    }

    fun formatJson() {
        viewModelScope.launch {
            val text = _uiState.value.jsonText
            when (val result = parseWorkflowUseCase(text, save = false)) {
                is ParseWorkflowUseCase.Result.Success -> {
                    val pretty = parseWorkflowUseCase.serialize(result.definition, pretty = true)
                    _uiState.update { it.copy(jsonText = pretty) }
                }
                else -> { /* leave as-is if unparseable */ }
            }
        }
    }

    fun loadTemplate() {
        _uiState.update { it.copy(jsonText = TEMPLATE_JSON, isDirty = true) }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun loadWorkflow(id: String) {
        viewModelScope.launch {
            val def = workflowRepository.getWorkflow(id) ?: return@launch
            val json = parseWorkflowUseCase.serialize(def, pretty = true)
            _uiState.update { it.copy(
                workflowId     = id,
                jsonText       = json,
                parsedWorkflow = def,
                isDirty        = false
            )}
        }
    }

    private fun validateJson(text: String) {
        viewModelScope.launch {
            when (val result = parseWorkflowUseCase(text, save = false)) {
                is ParseWorkflowUseCase.Result.Success -> {
                    _uiState.update { it.copy(validationError = null, parsedWorkflow = result.definition) }
                }
                is ParseWorkflowUseCase.Result.ParseError -> {
                    _uiState.update { it.copy(validationError = result.reason, parsedWorkflow = null) }
                }
                is ParseWorkflowUseCase.Result.ValidationError -> {
                    _uiState.update { it.copy(validationError = result.reason, parsedWorkflow = null) }
                }
                else -> { }
            }
        }
    }

    companion object {
        private val TEMPLATE_JSON = """
            {
              "workflow_id": "my-workflow-001",
              "version": 1,
              "initial_state": "FIND_ELEMENT",
              "global_timeout_ms": 120000,
              "rl_global_enabled": false,
              "states": {
                "FIND_ELEMENT": {
                  "rl_enabled": false,
                  "max_retries": 5,
                  "cooldown_ms": 800,
                  "timeout_ms": 10000,
                  "on_success": "TAP_ELEMENT",
                  "on_failure": "FIND_ELEMENT",
                  "vision_match_rule": {
                    "template_id": "element",
                    "min_confidence": 0.82,
                    "multi_scale": false,
                    "action_intent": "WAIT_FOR_MATCH"
                  }
                },
                "TAP_ELEMENT": {
                  "rl_enabled": false,
                  "max_retries": 2,
                  "cooldown_ms": 500,
                  "timeout_ms": 5000,
                  "on_success": "FIND_ELEMENT",
                  "on_failure": "FIND_ELEMENT"
                }
              },
              "actions": {
                "WAIT_FOR_MATCH": {
                  "interaction_type": "TAP",
                  "x": 0.5,
                  "y": 0.5,
                  "delay_after_ms": 300
                }
              }
            }
        """.trimIndent()
    }
}
