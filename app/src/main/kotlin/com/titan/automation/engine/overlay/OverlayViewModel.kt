package com.titan.automation.engine.overlay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.titan.automation.domain.model.DetectionResult
import com.titan.automation.engine.governor.ThermalGovernor
import com.titan.automation.engine.governor.ThermalLevel
import com.titan.automation.engine.workflow.MacroEngine
import com.titan.automation.telemetry.TelemetryManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * OverlayViewModel — StateFlow-driven state manager for the floating overlay UI.
 *
 * Observes [MacroEngine.executionState], [ThermalGovernor.thermalLevel], and the
 * [TelemetryManager.events] SharedFlow to derive all overlay display data.
 *
 * All state changes happen on the ViewModel's coroutine scope so the Compose UI
 * simply collects flows — no side-effect logic in composables.
 */
@HiltViewModel
class OverlayViewModel @Inject constructor(
    private val macroEngine    : MacroEngine,
    private val thermalGovernor: ThermalGovernor,
    private val telemetryManager: TelemetryManager
) : ViewModel() {

    // ── UI state ──────────────────────────────────────────────────────────────

    data class OverlayUiState(
        val isRunning       : Boolean       = false,
        val isMinimized     : Boolean       = true,
        val currentStep     : String        = "Idle",
        val currentFps      : Int           = 0,
        val lastDetection   : String        = "—",
        val lastConfidence  : Float         = 0f,
        val thermalLevel    : ThermalLevel  = ThermalLevel.NORMAL,
        val batteryPct      : Int           = 100,
        val isCharging      : Boolean       = false,
        val activeWorkflow  : String        = "",
        val logEntries      : List<String>  = emptyList()
    )

    private val _uiState = MutableStateFlow(OverlayUiState())
    val uiState: StateFlow<OverlayUiState> = _uiState.asStateFlow()

    private val _overlayX = MutableStateFlow(16)
    private val _overlayY = MutableStateFlow(200)
    val overlayX: StateFlow<Int> = _overlayX.asStateFlow()
    val overlayY: StateFlow<Int> = _overlayY.asStateFlow()

    private val logBuffer = ArrayDeque<String>(21)

    init {
        observeExecutionState()
        observeThermalState()
        observeTelemetry()
    }

    // ── Commands ──────────────────────────────────────────────────────────────

    fun toggleRunning() {
        if (_uiState.value.isRunning) {
            macroEngine.pauseAll()
            _uiState.update { it.copy(isRunning = false, currentStep = "Paused") }
        } else {
            macroEngine.resumeAll()
            _uiState.update { it.copy(isRunning = true, currentStep = "Running") }
        }
    }

    fun panicStop() {
        macroEngine.pauseAll()
        _uiState.update { it.copy(isRunning = false, currentStep = "Stopped") }
        addLog("🛑 PANIC STOP")
        viewModelScope.launch {
            telemetryManager.log("OverlayViewModel", "Panic stop triggered by user")
        }
    }

    fun toggleMinimized() {
        _uiState.update { it.copy(isMinimized = !it.isMinimized) }
    }

    fun updatePosition(x: Int, y: Int) {
        _overlayX.value = x
        _overlayY.value = y
    }

    fun updateFps(fps: Int) {
        _uiState.update { it.copy(currentFps = fps) }
    }

    fun reportDetection(result: DetectionResult, stepName: String = "") {
        val label = when (result) {
            is DetectionResult.Found    -> "✓ ${result.label.ifEmpty { "match" }} (${(result.confidence * 100).toInt()}%)"
            is DetectionResult.NotFound -> "✗ not found (${(result.confidence * 100).toInt()}%)"
            is DetectionResult.Skipped  -> "⏭ skipped: ${result.reason}"
            is DetectionResult.Error    -> "⚠ error: ${result.cause.message}"
        }
        _uiState.update { it.copy(
            lastDetection  = if (stepName.isNotEmpty()) "$stepName: $label" else label,
            lastConfidence = result.confidence
        )}
        addLog(if (stepName.isNotEmpty()) "$stepName → $label" else label)
    }

    // ── Private observers ─────────────────────────────────────────────────────

    private fun observeExecutionState() {
        viewModelScope.launch {
            macroEngine.runningWorkflows.collect { workflows ->
                val running = workflows.isNotEmpty() && workflows.values.any { !it.completed }
                val stepLabel = workflows.values.firstOrNull()?.currentState ?: "Idle"
                _uiState.update { it.copy(
                    isRunning   = running,
                    currentStep = stepLabel,
                    activeWorkflow = workflows.keys.firstOrNull() ?: ""
                )}
            }
        }
    }

    private fun observeThermalState() {
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

    private fun observeTelemetry() {
        // TelemetryManager uses ring buffer + log(); recent entries polled via getRecentLogs
        viewModelScope.launch {
            kotlinx.coroutines.delay(2000)
            while (true) {
                val recent = telemetryManager.getRecentLogs(5)
                recent.lastOrNull()?.let { entry ->
                    addLog("[${entry.timestamp}] ${entry.message}")
                }
                kotlinx.coroutines.delay(500)
            }
        }
    }

    private fun addLog(entry: String) {
        if (logBuffer.size >= 20) logBuffer.removeFirst()
        logBuffer.addLast(entry)
        _uiState.update { it.copy(logEntries = logBuffer.toList()) }
    }
}
