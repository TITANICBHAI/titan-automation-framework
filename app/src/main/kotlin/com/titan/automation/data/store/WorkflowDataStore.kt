package com.titan.automation.data.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.enginePrefs: DataStore<Preferences> by preferencesDataStore(name = "titan_engine")

/**
 * WorkflowDataStore — typed preference persistence via DataStore.
 *
 * Stores:
 *   - Engine global settings (RL enabled, thermal thresholds, capture FPS)
 *   - Last active workflow ID for session recovery
 *   - Plugin permissions granted by user
 *   - Encrypted Q-table snapshot key (key only; actual table goes to Room)
 *
 * All reads are non-blocking [Flow]s. Writes are suspending and coroutine-safe.
 * IOException on read returns safe default (DataStore contract).
 */
@Singleton
class WorkflowDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val store = context.enginePrefs

    // ── Keys ──────────────────────────────────────────────────────────────────

    private object Keys {
        val RL_ENABLED              = booleanPreferencesKey("rl_enabled")
        val CAPTURE_FPS             = intPreferencesKey("capture_fps")
        val LAST_WORKFLOW_ID        = stringPreferencesKey("last_workflow_id")
        val LAST_CHECKPOINT_JSON    = stringPreferencesKey("last_checkpoint_json")
        val PLUGIN_PERMISSIONS      = stringPreferencesKey("plugin_permissions")
        val TOUCH_NOISE_STDDEV      = floatPreferencesKey("touch_noise_stddev")
        val EPSILON                 = floatPreferencesKey("rl_epsilon")
        val LEARNING_RATE           = floatPreferencesKey("rl_learning_rate")
        val OVERLAY_ENABLED         = booleanPreferencesKey("overlay_enabled")
        val THERMAL_POLL_MS         = longPreferencesKey("thermal_poll_ms")
        // ── Macro builder global defaults ─────────────────────────────────────
        val DEFAULT_SHOW_DOTS       = booleanPreferencesKey("default_show_dots")
        val DEFAULT_JITTER_ENABLED  = booleanPreferencesKey("default_jitter_enabled")
        val DEFAULT_JITTER_RADIUS   = floatPreferencesKey("default_jitter_radius")
        val DEFAULT_SPEED           = floatPreferencesKey("default_speed")
        val RESPECT_THERMAL         = booleanPreferencesKey("respect_thermal")
    }

    // ── Reads ─────────────────────────────────────────────────────────────────

    val rlEnabled: Flow<Boolean> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.RL_ENABLED] ?: true }

    val captureFps: Flow<Int> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.CAPTURE_FPS] ?: 10 }

    val lastWorkflowId: Flow<String?> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.LAST_WORKFLOW_ID] }

    val lastCheckpointJson: Flow<String?> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.LAST_CHECKPOINT_JSON] }

    val touchNoiseStdDev: Flow<Float> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.TOUCH_NOISE_STDDEV] ?: 3f }

    val rlEpsilon: Flow<Float> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.EPSILON] ?: 1f }

    val touchNoiseStdDev: Flow<Float> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.TOUCH_NOISE_STDDEV] ?: 3f }

    val overlayEnabled: Flow<Boolean> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.OVERLAY_ENABLED] ?: true }

    val defaultShowDots: Flow<Boolean> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.DEFAULT_SHOW_DOTS] ?: true }

    val defaultJitterEnabled: Flow<Boolean> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.DEFAULT_JITTER_ENABLED] ?: true }

    val defaultJitterRadius: Flow<Float> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.DEFAULT_JITTER_RADIUS] ?: 3f }

    val defaultSpeed: Flow<Float> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.DEFAULT_SPEED] ?: 1f }

    val respectThermal: Flow<Boolean> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map { it[Keys.RESPECT_THERMAL] ?: true }

    // ── Writes ────────────────────────────────────────────────────────────────

    suspend fun setRlEnabled(enabled: Boolean) {
        store.edit { it[Keys.RL_ENABLED] = enabled }
    }

    suspend fun setCaptureFps(fps: Int) {
        store.edit { it[Keys.CAPTURE_FPS] = fps.coerceIn(1, 30) }
    }

    suspend fun setLastWorkflowId(id: String) {
        store.edit { it[Keys.LAST_WORKFLOW_ID] = id }
    }

    suspend fun saveCheckpoint(sessionJson: String) {
        store.edit { it[Keys.LAST_CHECKPOINT_JSON] = sessionJson }
    }

    suspend fun clearCheckpoint() {
        store.edit { it.remove(Keys.LAST_CHECKPOINT_JSON) }
    }

    suspend fun setTouchNoiseStdDev(sigma: Float) {
        store.edit { it[Keys.TOUCH_NOISE_STDDEV] = sigma.coerceIn(0f, 15f) }
    }

    suspend fun saveRlEpsilon(epsilon: Float) {
        store.edit { it[Keys.EPSILON] = epsilon.coerceIn(0f, 1f) }
    }

    suspend fun setOverlayEnabled(enabled: Boolean) {
        store.edit { it[Keys.OVERLAY_ENABLED] = enabled }
    }

    suspend fun setLearningRate(rate: Float) {
        store.edit { it[Keys.LEARNING_RATE] = rate.coerceIn(0.001f, 1f) }
    }

    suspend fun setTouchNoiseStdDev(sigma: Float) {
        store.edit { it[Keys.TOUCH_NOISE_STDDEV] = sigma.coerceIn(0f, 10f) }
    }

    suspend fun setDefaultShowDots(show: Boolean) {
        store.edit { it[Keys.DEFAULT_SHOW_DOTS] = show }
    }

    suspend fun setDefaultJitterEnabled(enabled: Boolean) {
        store.edit { it[Keys.DEFAULT_JITTER_ENABLED] = enabled }
    }

    suspend fun setDefaultJitterRadius(radius: Float) {
        store.edit { it[Keys.DEFAULT_JITTER_RADIUS] = radius.coerceIn(0f, 15f) }
    }

    suspend fun setDefaultSpeed(speed: Float) {
        store.edit { it[Keys.DEFAULT_SPEED] = speed.coerceIn(0.25f, 4f) }
    }

    suspend fun setRespectThermal(respect: Boolean) {
        store.edit { it[Keys.RESPECT_THERMAL] = respect }
    }

    suspend fun clearAll() {
        store.edit { it.clear() }
    }
}
