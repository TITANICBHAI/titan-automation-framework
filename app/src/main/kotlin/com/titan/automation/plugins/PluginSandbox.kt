package com.titan.automation.plugins

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * PluginSandbox — isolated execution context for a single loaded plugin.
 *
 * Provides:
 *   - Isolated [CoroutineScope] (cancelled on plugin unload — prevents coroutine leaks)
 *   - [PluginContext] implementation that proxies TITAN internals safely
 *   - Execution timeout enforcement ([maxExecutionMs])
 *   - Per-plugin file isolation ([getFilesDir] returns plugin-private directory)
 *
 * Pattern adapted from SmartAssistant plugin isolation model.
 * Improvements: coroutine scope isolation per plugin (missing in original),
 *               execution timeout, state isolation via per-plugin HashMap.
 */
class PluginSandbox(
    private val plugin        : TitanPlugin,
    private val sharedStateMap: HashMap<String, String>,
    private val pluginsRootDir: File,
    private val tapDelegate   : suspend (Float, Float) -> Boolean,
    private val swipeDelegate : suspend (Float, Float, Float, Float, Long) -> Boolean,
    private val logDelegate   : (String) -> Unit,
    val maxExecutionMs        : Long = 10_000L
) {
    val pluginId: String = plugin.id

    private val pluginScope = CoroutineScope(SupervisorJob())

    // Per-plugin isolated state
    private val pluginState = HashMap<String, String>()

    private val context = object : PluginContext {
        override suspend fun requestTap(nx: Float, ny: Float) = tapDelegate(nx, ny)
        override suspend fun requestSwipe(startNx: Float, startNy: Float, endNx: Float, endNy: Float, durationMs: Long) =
            swipeDelegate(startNx, startNy, endNx, endNy, durationMs)
        override fun readState(key: String)            = pluginState[key]
        override fun writeState(key: String, value: String) { pluginState[key] = value }
        override fun log(message: String)              = logDelegate("[$pluginId] $message")
        override fun getFilesDir(): File {
            val dir = File(pluginsRootDir, pluginId)
            if (!dir.exists()) dir.mkdirs()
            return dir
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    fun load() {
        plugin.onLoad(context)
        Log.d(TAG, "Plugin loaded: $pluginId v${plugin.version}")
    }

    fun unload() {
        try { plugin.onUnload() } catch (e: Exception) {
            Log.w(TAG, "Plugin unload threw: ${e.message}")
        }
        pluginScope.cancel("Plugin unloaded: $pluginId")
        pluginState.clear()
        Log.d(TAG, "Plugin unloaded: $pluginId")
    }

    // ── Execution ─────────────────────────────────────────────────────────────

    /**
     * Execute the plugin's action with a hard timeout.
     * Any exception is caught and wrapped in [PluginResult.Failure].
     */
    suspend fun execute(params: JsonObject): PluginResult =
        try {
            withTimeout(maxExecutionMs) {
                plugin.execute(params, context)
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            Log.e(TAG, "Plugin $pluginId timed out after ${maxExecutionMs}ms")
            PluginResult.Failure("Execution timed out after ${maxExecutionMs}ms", e)
        } catch (e: Exception) {
            Log.e(TAG, "Plugin $pluginId threw: ${e.message}", e)
            PluginResult.Failure(e.message ?: "Unknown error", e)
        }

    companion object { private const val TAG = "PluginSandbox" }
}
