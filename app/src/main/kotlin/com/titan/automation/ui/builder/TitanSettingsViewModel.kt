package com.titan.automation.ui.builder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.titan.automation.data.store.WorkflowDataStore
import com.titan.automation.engine.governor.ThermalGovernor
import com.titan.automation.engine.governor.GovernorState
import com.titan.automation.engine.playback.MacroScheduler
import com.titan.automation.engine.playback.ScheduledJob
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * TitanSettingsViewModel — exposes all DataStore settings for the Settings tab.
 *
 * Each setting has a corresponding Flow (for reading) and a suspend setter
 * routed via [viewModelScope].
 */
@HiltViewModel
class TitanSettingsViewModel @Inject constructor(
    private val dataStore    : WorkflowDataStore,
    private val thermal      : ThermalGovernor,
    private val scheduler    : MacroScheduler
) : ViewModel() {

    // ── Engine settings ───────────────────────────────────────────────────────

    val rlEnabled: StateFlow<Boolean> = dataStore.rlEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val captureFps: StateFlow<Int> = dataStore.captureFps
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 10)

    // ── Macro defaults ────────────────────────────────────────────────────────

    val defaultShowDots: StateFlow<Boolean> = dataStore.defaultShowDots
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val defaultJitterEnabled: StateFlow<Boolean> = dataStore.defaultJitterEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val defaultJitterRadius: StateFlow<Float> = dataStore.defaultJitterRadius
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 3f)

    val defaultSpeed: StateFlow<Float> = dataStore.defaultSpeed
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1f)

    val respectThermal: StateFlow<Boolean> = dataStore.respectThermal
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val touchNoiseStdDev: StateFlow<Float> = dataStore.touchNoiseStdDev
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 3f)

    // ── Thermal state (live) ──────────────────────────────────────────────────

    val thermalState = thermal.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GovernorState())

    // ── Active schedules ──────────────────────────────────────────────────────

    val scheduledJobs: StateFlow<Map<String, ScheduledJob>> = scheduler.scheduled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // ── Setters ───────────────────────────────────────────────────────────────

    fun setRlEnabled(enabled: Boolean) = viewModelScope.launch {
        dataStore.setRlEnabled(enabled)
    }

    fun setCaptureFps(fps: Int) = viewModelScope.launch {
        dataStore.setCaptureFps(fps)
    }

    fun setDefaultShowDots(show: Boolean) = viewModelScope.launch {
        dataStore.setDefaultShowDots(show)
    }

    fun setDefaultJitterEnabled(enabled: Boolean) = viewModelScope.launch {
        dataStore.setDefaultJitterEnabled(enabled)
    }

    fun setDefaultJitterRadius(radius: Float) = viewModelScope.launch {
        dataStore.setDefaultJitterRadius(radius)
    }

    fun setDefaultSpeed(speed: Float) = viewModelScope.launch {
        dataStore.setDefaultSpeed(speed)
    }

    fun setRespectThermal(respect: Boolean) = viewModelScope.launch {
        dataStore.setRespectThermal(respect)
    }

    fun setTouchNoise(sigma: Float) = viewModelScope.launch {
        dataStore.setTouchNoiseStdDev(sigma)
    }

    fun cancelAllSchedules() {
        scheduler.cancelAll()
    }
}
