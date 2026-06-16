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
                        workflowId     = result.definition.id
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
              "version": "1.2",
              "id": "my-workflow-001",
              "name": "My Workflow",
              "description": "Describe what this workflow does",
              "initial_state": "find-element",
              "states": {
                "find-element": {
                  "vision_match_rule": {
                    "template_path": "templates/element.png",
                    "threshold": 0.82
                  },
                  "timeout_ms": 5000,
                  "transitions": [
                    {"condition": "VISION_MATCH", "target_state": "click-element"},
                    {"condition": "TIMEOUT",      "target_state": "find-element"}
                  ]
                },
                "click-element": {
                  "actions": [
                    {"type": "TAP", "x": 0.5, "y": 0.75}
                  ],
                  "transitions": [
                    {"condition": "ALWAYS", "target_state": "find-element"}
                  ]
                }
              }
            }
        """.trimIndent()
    }
}
