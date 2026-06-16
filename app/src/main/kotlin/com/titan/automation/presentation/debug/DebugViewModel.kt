package com.titan.automation.presentation.debug

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.titan.automation.domain.model.DetectionResult
import com.titan.automation.domain.model.ScreenRegion
import com.titan.automation.domain.usecase.DetectTemplateUseCase
import com.titan.automation.engine.capture.FrameProvider
import com.titan.automation.telemetry.ExecutionTimeline
import com.titan.automation.telemetry.TelemetryManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * DebugViewModel — state for the frame inspector / coordinate picker / OCR debug screen.
 *
 * Provides:
 *   - Live frame capture (single snapshot from FrameProvider)
 *   - On-demand template detection with result overlay
 *   - OCR text extraction with bounding box preview
 *   - Coordinate tapping inspector
 *   - ExecutionTimeline stats per step
 */
@HiltViewModel
class DebugViewModel @Inject constructor(
    private val frameProvider      : FrameProvider,
    private val detectTemplateUseCase: DetectTemplateUseCase,
    private val executionTimeline  : ExecutionTimeline,
    private val telemetryManager   : TelemetryManager
) : ViewModel() {

    data class UiState(
        val frame           : Bitmap?                     = null,
        val detectionResult : DetectionResult?            = null,
        val ocrText         : String                      = "",
        val lastTapNx       : Float                       = 0f,
        val lastTapNy       : Float                       = 0f,
        val isCapturing     : Boolean                     = false,
        val isDetecting     : Boolean                     = false,
        val logs            : List<String>                = emptyList(),
        val error           : String?                     = null,
        val recentStepStats : List<ExecutionTimeline.StepStats> = emptyList()
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init { refreshLogs(); refreshStepStats() }

    // ── Commands ──────────────────────────────────────────────────────────────

    fun captureFrame() {
        viewModelScope.launch {
            _uiState.update { it.copy(isCapturing = true) }
            try {
                val capturedFrame = frameProvider.latestFrame.filterNotNull().first()
                _uiState.update { it.copy(frame = capturedFrame.bitmap, isCapturing = false) }
            } catch (e: Exception) {
                _uiState.update { it.copy(isCapturing = false, error = e.message) }
            }
        }
    }

    fun findText(query: String) {
        val frame = _uiState.value.frame ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isDetecting = true) }
            val result = detectTemplateUseCase.findText(frame, query)
            when (result) {
                is DetectTemplateUseCase.Result.Found -> {
                    _uiState.update { it.copy(
                        isDetecting     = false,
                        detectionResult = result.detection,
                        ocrText         = result.detection.label
                    )}
                }
                is DetectTemplateUseCase.Result.NotFound -> {
                    _uiState.update { it.copy(
                        isDetecting     = false,
                        detectionResult = null,
                        ocrText         = "Not found (conf=${result.confidence})"
                    )}
                }
                is DetectTemplateUseCase.Result.Error -> {
                    _uiState.update { it.copy(isDetecting = false, error = result.cause.message) }
                }
            }
        }
    }

    fun onTap(nx: Float, ny: Float) {
        _uiState.update { it.copy(lastTapNx = nx, lastTapNy = ny) }
        telemetryManager.log("DebugViewModel", "Tap: nx=${"%.3f".format(nx)} ny=${"%.3f".format(ny)}")
    }

    fun dismissError() = _uiState.update { it.copy(error = null) }

    fun refreshLogs() {
        viewModelScope.launch {
            val logs = telemetryManager.getRecentLogs(30)
                .map { "[${it.timestamp}] [${it.tag}] ${it.message}" }
            _uiState.update { it.copy(logs = logs) }
        }
    }

    fun refreshStepStats() {
        viewModelScope.launch {
            val stats = executionTimeline.allStepIds()
                .map { executionTimeline.statsForStep(it) }
                .sortedByDescending { it.invocations }
                .take(10)
            _uiState.update { it.copy(recentStepStats = stats) }
        }
    }
}
