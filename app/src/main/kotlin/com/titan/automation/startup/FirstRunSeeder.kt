package com.titan.automation.startup

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.titan.automation.domain.repository.WorkflowRepository
import com.titan.automation.engine.workflow.WorkflowParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FirstRunSeeder — seeds bundled example workflows into Room on the very first
 * app launch so the user has something to run immediately after install.
 *
 * Idempotent: protected by a SharedPreferences flag [KEY_SEEDED]. Runs once,
 * never again. The user can delete seeded workflows from the UI without them
 * reappearing.
 *
 * Bundled workflows (assets/workflows/*.json) are parsed via [WorkflowParser]
 * and upserted via [WorkflowRepository]. Failures per-file are isolated —
 * one malformed JSON does not prevent the others from loading.
 *
 * Execution: background coroutine on [Dispatchers.IO] — never blocks
 * Application.onCreate().
 */
@Singleton
class FirstRunSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: WorkflowRepository,
    private val parser: WorkflowParser
) {
    companion object {
        private const val TAG = "FirstRunSeeder"
        private const val PREFS_NAME = "titan_meta"
        private const val KEY_SEEDED = "workflows_seeded_v1"
        private const val WORKFLOWS_ASSET_DIR = "workflows"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun seedIfFirstRun(scope: CoroutineScope) {
        if (prefs.getBoolean(KEY_SEEDED, false)) return

        scope.launch(Dispatchers.IO) {
            runCatching { seed() }
                .onSuccess { Log.i(TAG, "First-run workflow seed complete") }
                .onFailure { Log.e(TAG, "Seed failed: ${it.message}") }
        }
    }

    private suspend fun seed() {
        val files = context.assets.list(WORKFLOWS_ASSET_DIR) ?: return
        var seeded = 0

        for (filename in files) {
            if (!filename.endsWith(".json")) continue
            runCatching {
                val json = context.assets
                    .open("$WORKFLOWS_ASSET_DIR/$filename")
                    .bufferedReader()
                    .readText()
                val definition = parser.parse(json)
                repository.saveWorkflow(definition)
                seeded++
                Log.d(TAG, "Seeded workflow: ${definition.workflowId} ($filename)")
            }.onFailure {
                Log.w(TAG, "Failed to seed $filename: ${it.message}")
            }
        }

        if (seeded > 0) {
            prefs.edit().putBoolean(KEY_SEEDED, true).apply()
            Log.i(TAG, "Seeded $seeded workflow(s) into Room")
        }
    }
}
