package com.titan.automation.presentation.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.titan.automation.data.datastore.AppSettings
import com.titan.automation.data.datastore.toAppSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * SettingsViewModel — manages read/write of all [AppSettings] via Preferences DataStore.
 *
 * UI observes [settings] StateFlow; each setter writes immediately to DataStore.
 * No intermediate draft state — writes are atomic per-field.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val dataStore: DataStore<Preferences>
) : ViewModel() {

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    init {
        viewModelScope.launch {
            dataStore.data
                .map { it.toAppSettings() }
                .collect { loaded -> _settings.value = loaded }
        }
    }

    // ── Setters ───────────────────────────────────────────────────────────────

    fun setTargetFps(fps: Int) = write { prefs ->
        prefs[AppSettings.TARGET_FPS] = fps.coerceIn(3, 15)
    }

    fun setRlEnabled(enabled: Boolean) = write { prefs ->
        prefs[AppSettings.RL_ENABLED] = enabled
    }

    fun setChargingOnlyMode(enabled: Boolean) = write { prefs ->
        prefs[AppSettings.CHARGING_ONLY_MODE] = enabled
    }

    fun setBatteryPauseThreshold(pct: Int) = write { prefs ->
        prefs[AppSettings.BATTERY_PAUSE_THRESHOLD] = pct.coerceIn(5, 30)
    }

    fun setJitterEnabled(enabled: Boolean) = write { prefs ->
        prefs[AppSettings.JITTER_ENABLED] = enabled
    }

    fun setJitterRadiusPx(radius: Int) = write { prefs ->
        prefs[AppSettings.JITTER_RADIUS_PX] = radius.coerceIn(0, 20)
    }

    fun setTemplateThreshold(threshold: Float) = write { prefs ->
        prefs[AppSettings.DEFAULT_TEMPLATE_THRESHOLD] = threshold.coerceIn(0.5f, 0.99f)
    }

    fun setOcrConfidence(confidence: Float) = write { prefs ->
        prefs[AppSettings.DEFAULT_OCR_CONFIDENCE] = confidence.coerceIn(0.4f, 0.99f)
    }

    fun setMaxRetryCount(count: Int) = write { prefs ->
        prefs[AppSettings.MAX_RETRY_COUNT] = count.coerceIn(1, 10)
    }

    fun setRetryBackoffMs(ms: Long) = write { prefs ->
        prefs[AppSettings.RETRY_BACKOFF_MS] = ms.coerceIn(100L, 5000L)
    }

    fun setShowTapDots(show: Boolean) = write { prefs ->
        prefs[AppSettings.SHOW_TAP_DOTS] = show
    }

    fun setRespectThermal(respect: Boolean) = write { prefs ->
        prefs[AppSettings.RESPECT_THERMAL] = respect
    }

    fun setOverlayMinimized(minimized: Boolean) = write { prefs ->
        prefs[AppSettings.OVERLAY_MINIMIZED] = minimized
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            dataStore.edit { prefs ->
                val defaults = AppSettings()
                prefs[AppSettings.TARGET_FPS]                 = defaults.targetFps
                prefs[AppSettings.RL_ENABLED]                 = defaults.rlEnabled
                prefs[AppSettings.BATTERY_PAUSE_THRESHOLD]    = defaults.batteryPauseThreshold
                prefs[AppSettings.JITTER_ENABLED]             = defaults.jitterEnabled
                prefs[AppSettings.JITTER_RADIUS_PX]           = defaults.jitterRadiusPx
                prefs[AppSettings.DEFAULT_TEMPLATE_THRESHOLD] = defaults.defaultTemplateThreshold
                prefs[AppSettings.DEFAULT_OCR_CONFIDENCE]     = defaults.defaultOcrConfidence
                prefs[AppSettings.MAX_RETRY_COUNT]            = defaults.maxRetryCount
                prefs[AppSettings.RETRY_BACKOFF_MS]           = defaults.retryBackoffMs
                prefs[AppSettings.SHOW_TAP_DOTS]              = defaults.showTapDots
                prefs[AppSettings.RESPECT_THERMAL]            = defaults.respectThermal
                prefs[AppSettings.CHARGING_ONLY_MODE]         = defaults.chargingOnlyMode
            }
        }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun write(block: suspend (MutablePreferences) -> Unit) {
        viewModelScope.launch {
            dataStore.edit { prefs -> block(prefs) }
        }
    }
}

private typealias MutablePreferences = androidx.datastore.preferences.core.MutablePreferences
