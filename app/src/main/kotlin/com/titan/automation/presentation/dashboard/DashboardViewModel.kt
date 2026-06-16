package com.titan.automation.presentation.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.titan.automation.domain.model.WorkflowDefinition
import com.titan.automation.domain.repository.WorkflowRepository
import com.titan.automation.domain.usecase.ExecuteWorkflowUseCase
import com.titan.automation.engine.governor.ThermalGovernor
import com.titan.automation.engine.governor.ThermalLevel
import com.titan.automation.engine.workflow.MacroEngine
import com.titan.automation.performance.BatteryGuard
import com.titan.automation.telemetry.TelemetryManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * DashboardViewModel — state for the main workflow management screen.
 *
 * Aggregates data from:
 *   - [WorkflowRepository] for the list of saved workflows
 *   - [MacroEngine.runningWorkflows] for live execution status
 *   - [ThermalGovernor] + [BatteryGuard] for system status badges
 *   - [TelemetryManager] for recent activity log entries
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val workflowRepository   : WorkflowRepository,
    private val macroEngine          : MacroEngine,
    private val executeWorkflowUseCase: ExecuteWorkflowUseCase,
    private val thermalGovernor      : ThermalGovernor,
    private val batteryGuard         : BatteryGuard,
    private val telemetryManager     : TelemetryManager
) : ViewModel() {

    data class UiState(
        val workflows        : List<WorkflowDefinition> = emptyList(),
        val runningWorkflows : Set<String>              = emptySet(),
        val thermalLevel     : ThermalLevel             = ThermalLevel.NORMAL,
        val batteryPct       : Int                      = 100,
        val isCharging       : Boolean                  = false,
        val recentLogs       : List<String>             = emptyList(),
        val isLoading        : Boolean                  = true,
        val error            : String?                  = null,
        val lastAction       : String                   = ""
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadWorkflows()
        observeRunningWorkflows()
        observeThermal()
        observeBattery()
        pollLogs()
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    fun startWorkflow(workflowId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(lastAction = "Starting $workflowId…") }
            when (val r = executeWorkflowUseCase(workflowId)) {
                is ExecuteWorkflowUseCase.Result.Started       ->
                    _uiState.update { it.copy(lastAction = "Started: $workflowId") }
                is ExecuteWorkflowUseCase.Result.AlreadyRunning ->
                    _uiState.update { it.copy(lastAction = "Already running: $workflowId") }
                is ExecuteWorkflowUseCase.Result.WorkflowNotFound ->
                    _uiState.update { it.copy(error = "Not found: $workflowId") }
                is ExecuteWorkflowUseCase.Result.ValidationFailed ->
                    _uiState.update { it.copy(error = "Validation failed: ${r.reason}") }
                is ExecuteWorkflowUseCase.Result.Failure        ->
                    _uiState.update { it.copy(error = r.cause.message) }
            }
        }
    }

    fun stopWorkflow(workflowId: String) {
        executeWorkflowUseCase.stop(workflowId)
        _uiState.update { it.copy(lastAction = "Stopped: $workflowId") }
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    fun refresh() = loadWorkflows()

    // ── Private observers ─────────────────────────────────────────────────────

    private fun loadWorkflows() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                workflowRepository.allWorkflows().collect { workflows ->
                    _uiState.update { it.copy(workflows = workflows, isLoading = false) }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, error = e.message) }
            }
        }
    }

    private fun observeRunningWorkflows() {
        viewModelScope.launch {
            macroEngine.runningWorkflows.collect { running ->
                val ids = running.keys.filter { id ->
                    running[id]?.completed == false
                }.toSet()
                _uiState.update { it.copy(runningWorkflows = ids) }
            }
        }
    }

    private fun observeThermal() {
        viewModelScope.launch {
            thermalGovernor.state.collect { gs ->
                _uiState.update { it.copy(
                    thermalLevel = gs.thermalLevel,
                    batteryPct   = gs.batteryPct,
                    isCharging   = gs.isCharging
                )}
            }
        }
    }

    private fun observeBattery() {
        viewModelScope.launch {
            batteryGuard.batteryState.collect { bs ->
                _uiState.update { it.copy(batteryPct = bs.level, isCharging = bs.isCharging) }
            }
        }
    }

    private fun pollLogs() {
        viewModelScope.launch {
            while (true) {
                val logs = telemetryManager.getRecentLogs(10)
                    .map { "[${it.tag}] ${it.message}" }
                _uiState.update { it.copy(recentLogs = logs) }
                kotlinx.coroutines.delay(2_000)
            }
        }
    }
}
