package com.titan.automation.telemetry

import android.content.Context
import android.os.Build
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CrashReporter — local crash diagnostics and structured crash log writer.
 *
 * Installs a [Thread.UncaughtExceptionHandler] on the current thread group.
 * On crash:
 *   1. Captures full stack trace + device info into a structured JSON report
 *   2. Writes to app's cache directory (shareable via FileProvider)
 *   3. Records a CrashEvent via [TelemetryManager]
 *   4. Delegates to the original handler (ensures Android's default crash behaviour)
 *
 * Reports are named: titan_crash_<timestamp>.json
 * Reports older than [retentionDays] days are auto-purged on startup.
 */
@Singleton
class CrashReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val telemetryManager: TelemetryManager
) {
    private val scope         = CoroutineScope(SupervisorJob())
    private val crashDir      = File(context.cacheDir, "titan_crashes").also { it.mkdirs() }
    private val dateFormat    = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
    private val retentionDays = 7

    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    // ── Initialisation ────────────────────────────────────────────────────────

    /**
     * Install the crash handler. Call once from TitanApplication.onCreate().
     */
    fun install() {
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleCrash(thread, throwable)
            originalHandler?.uncaughtException(thread, throwable)
        }

        purgeOldReports()
        Log.i(TAG, "CrashReporter installed (reports → ${crashDir.absolutePath})")
    }

    // ── Crash handling ────────────────────────────────────────────────────────

    /**
     * Manually record a caught-but-severe error (e.g. WatchdogGaveUp).
     */
    fun recordError(tag: String, throwable: Throwable, extra: Map<String, Any> = emptyMap()) {
        scope.launch {
            val report = buildReport(tag, throwable, extra)
            writeReport(report)
            telemetryManager.log("CrashReporter", "[$tag] ${throwable.message}")
        }
    }

    /** List all crash report files available for sharing. */
    fun crashReportFiles(): List<File> =
        crashDir.listFiles { f -> f.name.endsWith(".json") }?.toList() ?: emptyList()

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun handleCrash(thread: Thread, throwable: Throwable) {
        try {
            val report = buildReport("UncaughtException", throwable,
                mapOf("thread" to thread.name))
            writeReport(report)
            // Synchronous write — we're crashing, best-effort log
            try { telemetryManager.log("CrashReporter", "UncaughtException on ${thread.name}: ${throwable.message}") }
            catch (_: Exception) { }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write crash report", e)
        }
    }

    private fun buildReport(
        tag    : String,
        t      : Throwable,
        extra  : Map<String, Any>
    ): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))

        val lines = mutableListOf(
            "\"timestamp\": \"${dateFormat.format(Date())}\"",
            "\"tag\": \"${tag.escapeJson()}\"",
            "\"message\": \"${t.message?.escapeJson() ?: ""}\"",
            "\"stacktrace\": \"${sw.toString().escapeJson()}\"",
            "\"device\": \"${Build.MANUFACTURER} ${Build.MODEL}\"",
            "\"sdk\": ${Build.VERSION.SDK_INT}",
            "\"app_version\": \"${appVersion()}\""
        )
        extra.forEach { (k, v) -> lines.add("\"$k\": \"$v\"") }

        return "{\n  ${lines.joinToString(",\n  ")}\n}"
    }

    private fun writeReport(json: String) {
        val ts   = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(crashDir, "titan_crash_${ts}.json")
        file.writeText(json)
        Log.e(TAG, "Crash report written: ${file.name}")
    }

    private fun purgeOldReports() {
        val cutoff = System.currentTimeMillis() - retentionDays * 24 * 3600 * 1000L
        crashDir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    private fun appVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
    } catch (_: Exception) { "unknown" }

    private fun String.escapeJson() =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "")

    companion object { private const val TAG = "CrashReporter" }
}
