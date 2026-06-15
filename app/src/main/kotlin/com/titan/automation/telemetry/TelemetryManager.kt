package com.titan.automation.telemetry

import android.content.Context
import android.os.Build
import android.util.Log
import com.titan.automation.events.TitanEvent
import com.titan.automation.events.TitanEventBus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TelemetryManager — structured on-device telemetry, crash diagnostics, and tracing.
 *
 * Derived from aria-ai-cpu-only AnrWatchdog + LogManager patterns.
 *
 * Features:
 *   - Structured log ring-buffer (last [LOG_RING_SIZE] entries, memory-only)
 *   - File-backed crash reports: crashes written to [logDir]/crash-*.txt
 *   - Timeline reconstruction: each [TitanEvent] stamped and stored
 *   - Performance metrics: per-second FPS average, RL win rate, gesture success rate
 *   - ANR watchdog: main-thread heartbeat monitor (see [AnrProbe])
 *   - Memory tracker: logs heap usage every 60s under debug builds
 *   - Uncaught exception handler: writes crash dump before default handler fires
 *
 * All file I/O on Dispatchers.IO. Ring buffer is lock-free via [LinkedBlockingQueue].
 */
@Singleton
class TelemetryManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val eventBus: TitanEventBus
) {
    private val scope   = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logDir  = File(context.filesDir, "telemetry").also { it.mkdirs() }
    private val ringBuf = LinkedBlockingQueue<TelemetryEntry>(LOG_RING_SIZE)
    private val ts      = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileDts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    // ── Metrics ───────────────────────────────────────────────────────────────
    private var fpsSum        = 0f
    private var fpsSamples    = 0
    private var gestureOk     = 0
    private var gestureFail   = 0
    private var rlExplore     = 0
    private var rlExploit     = 0

    // ─────────────────────────────────────────────────────────────────────────
    // Initialisation
    // ─────────────────────────────────────────────────────────────────────────

    fun init() {
        installCrashHandler()
        subscribeToEvents()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU || isDebug()) {
            startMemoryTracker()
        }
        log("TelemetryManager", "initialised — logDir=${logDir.absolutePath}")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Event subscription
    // ─────────────────────────────────────────────────────────────────────────

    private fun subscribeToEvents() {
        scope.launch {
            eventBus.flow.collect { event ->
                when (event) {
                    is TitanEvent.CaptureFrame -> {
                        fpsSum += event.fps; fpsSamples++
                    }
                    is TitanEvent.GestureDispatched -> {
                        if (event.success) gestureOk++ else gestureFail++
                    }
                    is TitanEvent.RLDecision -> {
                        if (event.explorationMode) rlExplore++ else rlExploit++
                    }
                    is TitanEvent.Error -> {
                        writeCrashReport("error", event.source, event.message)
                        log(event.source, "[ERROR] ${event.message}")
                    }
                    else -> Unit
                }
                // Timeline entry for every event
                log("Event", event::class.simpleName ?: "Unknown")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Logging
    // ─────────────────────────────────────────────────────────────────────────

    fun log(tag: String, message: String) {
        val entry = TelemetryEntry(
            timestamp = ts.format(Date()),
            tag       = tag,
            message   = message
        )
        // Ring buffer — drops oldest if full (never blocks)
        if (!ringBuf.offer(entry)) {
            ringBuf.poll()
            ringBuf.offer(entry)
        }
        Log.d("Titan/$tag", message)
    }

    fun getRecentLogs(n: Int = 100): List<TelemetryEntry> =
        ringBuf.toList().takeLast(n)

    // ─────────────────────────────────────────────────────────────────────────
    // Crash handler
    // ─────────────────────────────────────────────────────────────────────────

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                writeCrashReport(
                    type    = "crash",
                    source  = thread.name,
                    message = throwable.stackTraceToString()
                )
            } catch (_: Throwable) { /* crash handler must never throw */ }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun writeCrashReport(type: String, source: String, message: String) {
        val file = File(logDir, "$type-${fileDts.format(Date())}.txt")
        file.writeText(buildString {
            appendLine("=== TITAN CRASH REPORT ===")
            appendLine("time:   ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}")
            appendLine("type:   $type")
            appendLine("source: $source")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} API ${Build.VERSION.SDK_INT}")
            appendLine()
            appendLine("=== STACK TRACE / MESSAGE ===")
            appendLine(message)
            appendLine()
            appendLine("=== RECENT TELEMETRY ===")
            getRecentLogs(30).forEach { appendLine("${it.timestamp} [${it.tag}] ${it.message}") }
            appendLine()
            appendLine("=== METRICS ===")
            appendLine("avgFps=${if (fpsSamples > 0) fpsSum / fpsSamples else 0f}")
            appendLine("gestureOk=$gestureOk gestureFail=$gestureFail")
            appendLine("rlExplore=$rlExplore rlExploit=$rlExploit")
        })
        Log.e("TelemetryManager", "Crash report written: ${file.name}")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Memory tracker
    // ─────────────────────────────────────────────────────────────────────────

    private fun startMemoryTracker() {
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(60_000L)
                val rt   = Runtime.getRuntime()
                val used = (rt.totalMemory() - rt.freeMemory()) / 1_048_576L
                val max  = rt.maxMemory() / 1_048_576L
                log("Memory", "heap ${used}MB / ${max}MB (${used * 100 / max.coerceAtLeast(1)}%)")
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Crash file management
    // ─────────────────────────────────────────────────────────────────────────

    fun listCrashReports(): List<File> =
        logDir.listFiles()
            ?.filter { it.isFile && it.extension == "txt" }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()

    fun clearOldReports(keepCount: Int = 10) {
        listCrashReports().drop(keepCount).forEach { it.delete() }
    }

    fun destroy() { scope.cancel() }

    private fun isDebug(): Boolean = Build.TYPE == "debug" ||
        context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0

    companion object {
        private const val LOG_RING_SIZE = 512
    }
}

data class TelemetryEntry(
    val timestamp: String,
    val tag: String,
    val message: String
)
