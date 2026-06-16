package com.titan.automation.plugins

import kotlinx.serialization.json.JsonObject

/**
 * PluginContract — public interface every TITAN plugin must implement.
 *
 * Plugins are loaded from .jar files via [PluginManager] using PathClassLoader.
 * Each plugin receives an isolated [PluginContext] that proxies all framework
 * operations — plugins cannot call GestureDispatcher directly.
 *
 * Lifecycle:
 *   [onLoad]    → called once after the plugin class is instantiated
 *   [execute]   → called for each InvokePlugin macro step targeting this plugin
 *   [onUnload]  → called before the plugin is removed; clean up resources here
 */
interface TitanPlugin {
    /** Unique stable identifier, e.g. "com.example.myplugin". */
    val id: String

    /** Plugin version integer; increment on every published change. */
    val version: Int

    /** Android permission strings this plugin needs (checked before install). */
    val requiredPermissions: List<String>

    /**
     * Execute the plugin's action.
     *
     * @param params  Arbitrary JSON parameters from the workflow step definition.
     * @param context Sandboxed context — the only way to interact with TITAN internals.
     * @return [PluginResult] indicating success/failure and optional output data.
     */
    suspend fun execute(params: JsonObject, context: PluginContext): PluginResult

    /**
     * Called immediately after the plugin is loaded. Initialise resources here.
     */
    fun onLoad(context: PluginContext)

    /**
     * Called before unloading. Release all resources, cancel internal coroutines.
     */
    fun onUnload()
}

/**
 * Sandboxed context passed to every plugin.
 *
 * Plugins may ONLY interact with TITAN via this interface.
 * Direct access to GestureDispatcher, MacroEngine, or any other internal
 * component is blocked by the PluginSandbox's ClassLoader isolation.
 */
interface PluginContext {
    /** Request a tap gesture at normalised screen coordinates. */
    suspend fun requestTap(nx: Float, ny: Float): Boolean

    /** Request a swipe gesture. */
    suspend fun requestSwipe(startNx: Float, startNy: Float, endNx: Float, endNy: Float, durationMs: Long): Boolean

    /** Read a value from the workflow's shared state map. */
    fun readState(key: String): String?

    /** Write a value to the workflow's shared state map. */
    fun writeState(key: String, value: String)

    /** Log a message to TelemetryManager (visible in debug overlay). */
    fun log(message: String)

    /** Get the plugin's private files directory (isolated from other plugins). */
    fun getFilesDir(): java.io.File
}

/**
 * Result of a plugin [TitanPlugin.execute] call.
 */
sealed class PluginResult {
    data class Success(val output: JsonObject? = null) : PluginResult()
    data class Failure(val reason: String, val cause: Throwable? = null) : PluginResult()
    data class Retry  (val afterMs: Long = 500L)       : PluginResult()
}

/**
 * Metadata descriptor for a loaded plugin (displayed in settings UI).
 */
data class PluginInfo(
    val id          : String,
    val version     : Int,
    val displayName : String,
    val description : String,
    val permissions : List<String>,
    val jarPath     : String
)
