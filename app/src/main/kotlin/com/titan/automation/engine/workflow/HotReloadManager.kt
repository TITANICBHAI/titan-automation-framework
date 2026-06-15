package com.titan.automation.engine.workflow

import android.content.Context
import android.util.Log
import com.titan.automation.domain.model.WorkflowDefinition
import com.titan.automation.domain.repository.WorkflowRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * HotReloadManager — file-system watcher that reloads workflow JSON files
 * from the device's external files directory without restarting the engine.
 *
 * Watch directory: [Context.getExternalFilesDir]/titan_workflows/
 *
 * How it works:
 *   1. Polls the watch directory every [POLL_MS] ms (no inotify on most Android OEMs)
 *   2. Compares file last-modified timestamps against a cached map
 *   3. On change: parses the JSON via [WorkflowParser], upserts to [WorkflowRepository]
 *   4. Emits a [HotReloadEvent] on [reloadEvents] SharedFlow
 *   5. [MacroEngine] subscribes — if the modified workflow is currently running,
 *      it checkpoints the current state and restarts from the new definition
 *
 * Supported operations:
 *   ADD    — new .json file detected → import and register
 *   UPDATE — existing .json modified → upsert and trigger live reload
 *   REMOVE — .json deleted → unregister workflow (does not delete DB entry)
 *
 * Thread safety: all file I/O runs on [Dispatchers.IO]; SharedFlow has replay=1
 * so late subscribers get the most recent event.
 */
@Singleton
class HotReloadManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val parser    : WorkflowParser,
    private val repository: WorkflowRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watchJob: Job? = null

    private val _reloadEvents = MutableSharedFlow<HotReloadEvent>(replay = 1, extraBufferCapacity = 32)
    val reloadEvents: SharedFlow<HotReloadEvent> = _reloadEvents.asSharedFlow()

    // Map: filePath → last modified timestamp
    private val fileTimestamps = mutableMapOf<String, Long>()

    val watchDir: File
        get() = File(context.getExternalFilesDir(null), WATCH_DIR_NAME).also { it.mkdirs() }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun startWatching() {
        if (watchJob?.isActive == true) return
        watchJob = scope.launch {
            Log.i(TAG, "Hot-reload watching: ${watchDir.absolutePath}")
            while (isActive) {
                scanDirectory()
                delay(POLL_MS)
            }
        }
    }

    fun stopWatching() {
        watchJob?.cancel()
        watchJob = null
    }

    // ── Scan logic ────────────────────────────────────────────────────────────

    private suspend fun scanDirectory() = withContext(Dispatchers.IO) {
        if (!watchDir.exists()) return@withContext

        val current = watchDir.listFiles { f -> f.extension == "json" }
            ?.associateBy { it.absolutePath } ?: emptyMap()

        // Detect additions and modifications
        for ((path, file) in current) {
            val lastSeen = fileTimestamps[path]
            val modified = file.lastModified()
            if (lastSeen == null) {
                // New file
                handleChange(file, HotReloadOperation.ADD)
                fileTimestamps[path] = modified
            } else if (modified > lastSeen) {
                // Modified file
                handleChange(file, HotReloadOperation.UPDATE)
                fileTimestamps[path] = modified
            }
        }

        // Detect removals
        val removed = fileTimestamps.keys - current.keys
        for (path in removed) {
            handleRemoval(File(path))
            fileTimestamps.remove(path)
        }
    }

    private suspend fun handleChange(file: File, operation: HotReloadOperation) {
        val json = runCatching { file.readText() }.getOrNull() ?: return
        val definition = runCatching { parser.parse(json) }.getOrElse { t ->
            Log.e(TAG, "Parse error in ${file.name}: ${t.message}")
            _reloadEvents.emit(HotReloadEvent(
                workflowId = file.nameWithoutExtension,
                operation  = HotReloadOperation.PARSE_ERROR,
                error      = t.message
            ))
            return
        }
        repository.saveWorkflow(definition)
        Log.i(TAG, "Hot-reload ${operation.name}: ${definition.workflowId}")
        _reloadEvents.emit(HotReloadEvent(
            workflowId = definition.workflowId,
            operation  = operation,
            definition = definition
        ))
    }

    private suspend fun handleRemoval(file: File) {
        val id = file.nameWithoutExtension
        Log.i(TAG, "Hot-reload REMOVE: $id")
        _reloadEvents.emit(HotReloadEvent(
            workflowId = id,
            operation  = HotReloadOperation.REMOVE
        ))
    }

    /**
     * Manually trigger a reload of a specific workflow ID from the watch directory.
     * Call this when the user taps "Reload" in the overlay UI.
     */
    suspend fun forceReload(workflowId: String) {
        val file = File(watchDir, "$workflowId.json")
        if (file.exists()) handleChange(file, HotReloadOperation.UPDATE)
        else Log.w(TAG, "forceReload: $workflowId.json not found in ${watchDir.absolutePath}")
    }

    companion object {
        private const val TAG           = "HotReloadManager"
        private const val WATCH_DIR_NAME = "titan_workflows"
        private const val POLL_MS        = 2_000L
    }
}

// ── Events ────────────────────────────────────────────────────────────────────

data class HotReloadEvent(
    val workflowId : String,
    val operation  : HotReloadOperation,
    val definition : WorkflowDefinition? = null,
    val error      : String?             = null
)

enum class HotReloadOperation { ADD, UPDATE, REMOVE, PARSE_ERROR }
