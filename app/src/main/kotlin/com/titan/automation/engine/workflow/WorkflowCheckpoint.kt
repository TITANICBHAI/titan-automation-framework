package com.titan.automation.engine.workflow

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.checkpointStore: DataStore<Preferences>
        by preferencesDataStore(name = "titan_checkpoints")

/**
 * WorkflowCheckpoint — crash-safe workflow progress persistence.
 *
 * Writes the current (workflowId, stateId, stepIndex) after each successful
 * state transition using DataStore<Preferences> (atomic, corruption-resistant).
 *
 * On process restart:
 *   1. MacroEngine calls [restore] to retrieve the last checkpoint.
 *   2. If a checkpoint exists and matches the loaded workflow, execution resumes
 *      from that state rather than [WorkflowDefinition.initialState].
 *   3. After workflow completes (success or permanent failure), [clear] is called.
 *
 * Checkpoint data is intentionally minimal — full execution context is not
 * persisted; only enough to skip already-completed states.
 */
@Singleton
class WorkflowCheckpoint @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val store get() = context.checkpointStore

    /**
     * Persists the current workflow execution position.
     * Safe to call frequently — DataStore writes are debounced internally.
     */
    suspend fun save(
        workflowId: String,
        stateId: String,
        stepIndex: Int = 0,
        retryCount: Int = 0,
        timestamp: Long = System.currentTimeMillis()
    ) {
        runCatching {
            store.edit { prefs ->
                prefs[KEY_WORKFLOW_ID]  = workflowId
                prefs[KEY_STATE_ID]     = stateId
                prefs[KEY_STEP_INDEX]   = stepIndex
                prefs[KEY_RETRY_COUNT]  = retryCount
                prefs[KEY_SAVED_AT]     = timestamp
            }
        }.onFailure {
            Log.e(TAG, "Checkpoint save failed for $workflowId/$stateId", it)
        }
    }

    /**
     * Loads the last checkpoint. Returns null if none exists or if the stored
     * workflowId does not match [expectedWorkflowId].
     */
    suspend fun restore(expectedWorkflowId: String): CheckpointSnapshot? {
        return runCatching {
            store.data.map { prefs ->
                val workflowId = prefs[KEY_WORKFLOW_ID] ?: return@map null
                if (workflowId != expectedWorkflowId) return@map null
                CheckpointSnapshot(
                    workflowId = workflowId,
                    stateId    = prefs[KEY_STATE_ID]    ?: return@map null,
                    stepIndex  = prefs[KEY_STEP_INDEX]  ?: 0,
                    retryCount = prefs[KEY_RETRY_COUNT] ?: 0,
                    savedAt    = prefs[KEY_SAVED_AT]    ?: 0L
                )
            }.firstOrNull()
        }.onFailure {
            Log.e(TAG, "Checkpoint restore failed", it)
        }.getOrNull()
    }

    /**
     * Returns true if a checkpoint exists for [workflowId], false otherwise.
     * Lightweight check — does not deserialise the full snapshot.
     */
    suspend fun hasCheckpoint(workflowId: String): Boolean {
        return runCatching {
            store.data.map { prefs ->
                prefs[KEY_WORKFLOW_ID] == workflowId
            }.firstOrNull() ?: false
        }.getOrDefault(false)
    }

    /**
     * Clears the checkpoint for the given workflow.
     * Called on successful completion or permanent failure (max retries exceeded).
     */
    suspend fun clear(workflowId: String) {
        runCatching {
            store.data.map { prefs ->
                prefs[KEY_WORKFLOW_ID] == workflowId
            }.firstOrNull()?.let { matches ->
                if (matches) {
                    store.edit { prefs ->
                        prefs.remove(KEY_WORKFLOW_ID)
                        prefs.remove(KEY_STATE_ID)
                        prefs.remove(KEY_STEP_INDEX)
                        prefs.remove(KEY_RETRY_COUNT)
                        prefs.remove(KEY_SAVED_AT)
                    }
                }
            }
        }.onFailure {
            Log.e(TAG, "Checkpoint clear failed for $workflowId", it)
        }
    }

    /** Clears all checkpoints. Called on factory reset or manual user action. */
    suspend fun clearAll() {
        runCatching {
            store.edit { it.clear() }
        }.onFailure {
            Log.e(TAG, "Checkpoint clearAll failed", it)
        }
    }

    private companion object {
        private const val TAG = "WorkflowCheckpoint"

        val KEY_WORKFLOW_ID  = stringPreferencesKey("cp_workflow_id")
        val KEY_STATE_ID     = stringPreferencesKey("cp_state_id")
        val KEY_STEP_INDEX   = intPreferencesKey("cp_step_index")
        val KEY_RETRY_COUNT  = intPreferencesKey("cp_retry_count")
        val KEY_SAVED_AT     = longPreferencesKey("cp_saved_at")
    }
}

/**
 * Immutable snapshot of a persisted checkpoint.
 */
data class CheckpointSnapshot(
    val workflowId: String,
    val stateId: String,
    val stepIndex: Int,
    val retryCount: Int,
    val savedAt: Long
) {
    /** Returns the age of this checkpoint in milliseconds. */
    val ageMs: Long get() = System.currentTimeMillis() - savedAt

    /** Returns true if this checkpoint is older than [maxAgeMs]. */
    fun isStale(maxAgeMs: Long = 24 * 60 * 60 * 1_000L): Boolean = ageMs > maxAgeMs
}
