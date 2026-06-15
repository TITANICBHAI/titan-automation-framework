package com.titan.automation.monitoring

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * CrashRecoveryManager — uncaught-exception handler + heartbeat stall detector.
 *
 * Features (ported from SmartAssistant CrashRecoveryManager.java, Kotlin rewrite):
 *
 *  1. Uncaught exception handler chained to the original default handler.
 *     Writes a crash log to [filesDir]/crash_logs/ before forwarding to the
 *     system. The system log includes cause chain and all thread stacks.
 *
 *  2. Heartbeat stall detector:
 *     Engine calls [heartbeat] every iteration. A background scheduler checks
 *     every [HEARTBEAT_CHECK_MS] that the heartbeat advanced within
 *     [STALL_THRESHOLD_MS]. If not, [RecoveryCallback.onStallDetected] fires
 *     so the engine can clear stuck buffers or self-restart.
 *
 *  3. Crash frequency window:
 *     Crash count is persisted to [SharedPreferences]. If the app crashes
 *     more than [MAX_RESTART_ATTEMPTS] times within [CRASH_WINDOW_MS], the
 *     callback escalates to [RecoveryCallback.onGivingUp] so the UI can show
 *     a "too many crashes" dialog instead of looping forever.
 *
 *  4. Crash log rotation:
 *     At most [MAX_LOG_FILES] crash files are kept. Oldest are pruned.
 *
 *  5. Non-fatal recording:
 *     Call [recordNonFatalException] from catch blocks to get a lightweight
 *     log without incrementing the restart counter.
 *
 * Thread safety: AtomicBoolean/AtomicInteger for concurrency-critical fields;
 *   SharedPreferences edits use apply() (background write, non-blocking).
 *
 * Extracted from: SmartAssistant CrashRecoveryManager.java
 * Changes: Kotlin idioms, @Inject constructor, crash log includes all threads,
 *   stall reset prevents repeated callbacks without a real stall.
 */
@Singleton
class CrashRecoveryManager @Inject constructor(
    context: Context
) {
    private val appContext   = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val crashLogDir  = File(appContext.filesDir, "crash_logs").apply { mkdirs() }
    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "Titan-CrashMonitor").apply { isDaemon = true }
    }

    private val running        = AtomicBoolean(false)
    private val sessionCrashes = AtomicInteger(0)

    @Volatile private var lastHeartbeat  = 0L
    @Volatile private var lastCrashInfo: String? = null
    private var originalHandler: Thread.UncaughtExceptionHandler? = null
    private var callback: RecoveryCallback? = null

    // ─────────────────────────────────────────────────────────────────────────
    // Callback interface
    // ─────────────────────────────────────────────────────────────────────────

    interface RecoveryCallback {
        /** A fatal crash was recorded — engine should restart itself. */
        fun onCrashDetected(crashInfo: String)
        /** Heartbeat went silent for [stalledMs] ms — engine processing loop stalled. */
        fun onStallDetected(stalledMs: Long)
        /** Crash count hit [MAX_RESTART_ATTEMPTS] — give up and surface error to user. */
        fun onGivingUp(crashCount: Int)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    fun install(cb: RecoveryCallback) {
        callback = cb
        resetSessionIfNeeded()
        installUncaughtExceptionHandler()
        startHeartbeatWatcher()
        running.set(true)
        Log.i(TAG, "installed (persisted crash count=${prefs.getInt(KEY_CRASH_COUNT, 0)})")
    }

    fun uninstall() {
        if (!running.compareAndSet(true, false)) return
        originalHandler?.let { Thread.setDefaultUncaughtExceptionHandler(it) }
        scheduler.shutdownNow()
        Log.i(TAG, "uninstalled")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Heartbeat
    // ─────────────────────────────────────────────────────────────────────────

    /** Call this from the engine's main processing loop on every iteration. */
    fun heartbeat() { lastHeartbeat = System.currentTimeMillis() }

    // ─────────────────────────────────────────────────────────────────────────
    // Queries
    // ─────────────────────────────────────────────────────────────────────────

    /** True if the app crashed recently and should attempt a service restart. */
    fun shouldRestartService(): Boolean {
        val total = prefs.getInt(KEY_CRASH_COUNT, 0)
        if (total >= MAX_RESTART_ATTEMPTS) {
            Log.w(TAG, "Too many crashes ($total) — giving up")
            return false
        }
        return total > 0
    }

    fun getTotalCrashCount():   Int    = prefs.getInt(KEY_CRASH_COUNT, 0)
    fun getSessionCrashCount(): Int    = sessionCrashes.get()
    fun getLastCrashInfo():     String? = lastCrashInfo

    /** Records a caught (non-fatal) exception for diagnostics without touching restart count. */
    fun recordNonFatalException(tag: String, t: Throwable) {
        val info = formatException(tag, t)
        writeCrashLog(info, fatal = false)
        Log.w(TAG, "Non-fatal recorded from $tag")
    }

    /** Call after a successful restart to reset the crash counter. */
    fun clearCrashCount() {
        prefs.edit().putInt(KEY_CRASH_COUNT, 0).apply()
        sessionCrashes.set(0)
        Log.i(TAG, "Crash count cleared")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal
    // ─────────────────────────────────────────────────────────────────────────

    private fun installUncaughtExceptionHandler() {
        originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            handleCrash(thread, throwable)
            originalHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun handleCrash(thread: Thread, t: Throwable) {
        try {
            val info  = formatException(thread.name, t)
            lastCrashInfo = info
            writeCrashLog(info, fatal = true)

            val total = incrementCrashCount()
            sessionCrashes.incrementAndGet()

            Log.e(TAG, "Fatal crash #$total on thread ${thread.name}: ${t.message}")

            if (total >= MAX_RESTART_ATTEMPTS) {
                callback?.onGivingUp(total)
            } else {
                callback?.onCrashDetected(info)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error in crash handler itself", e)
        }
    }

    private fun startHeartbeatWatcher() {
        lastHeartbeat = System.currentTimeMillis()
        scheduler.scheduleAtFixedRate({
            if (!running.get()) return@scheduleAtFixedRate
            val now     = System.currentTimeMillis()
            val elapsed = now - lastHeartbeat
            if (elapsed > STALL_THRESHOLD_MS) {
                Log.w(TAG, "Heartbeat stalled for ${elapsed}ms")
                callback?.onStallDetected(elapsed)
                // Reset to avoid repeated callbacks for the same stall
                lastHeartbeat = now
            }
        }, HEARTBEAT_CHECK_MS, HEARTBEAT_CHECK_MS, TimeUnit.MILLISECONDS)
    }

    private fun incrementCrashCount(): Int {
        val count = prefs.getInt(KEY_CRASH_COUNT, 0) + 1
        prefs.edit()
            .putInt(KEY_CRASH_COUNT, count)
            .putLong(KEY_LAST_CRASH_TIME, System.currentTimeMillis())
            .apply()
        return count
    }

    private fun resetSessionIfNeeded() {
        val lastCrash = prefs.getLong(KEY_LAST_CRASH_TIME, 0L)
        if (System.currentTimeMillis() - lastCrash > CRASH_WINDOW_MS) {
            prefs.edit().putInt(KEY_CRASH_COUNT, 0).apply()
            Log.d(TAG, "Crash count reset (window expired)")
        }
    }

    private fun writeCrashLog(info: String, fatal: Boolean) {
        try {
            val ts       = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val prefix   = if (fatal) "crash_" else "nonfatal_"
            val logFile  = File(crashLogDir, "$prefix$ts.txt")
            PrintWriter(FileWriter(logFile)).use { it.println(info) }
            pruneOldLogs(MAX_LOG_FILES)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write crash log", e)
        }
    }

    private fun pruneOldLogs(maxFiles: Int) {
        val files = crashLogDir.listFiles() ?: return
        if (files.size <= maxFiles) return
        files.sortBy { it.lastModified() }
        for (i in 0 until files.size - maxFiles) files[i].delete()
    }

    private fun formatException(source: String, t: Throwable): String =
        buildString(capacity = 4096) {
            append("Source : $source\n")
            append("Time   : ${Date()}\n")
            append("Thread : ${Thread.currentThread().name}\n")
            append("Error  : ${t.javaClass.name}: ${t.message}\n")
            append("Stack  :\n")
            t.stackTrace.forEach { append("  at $it\n") }
            t.cause?.let { cause ->
                append("Caused by: ${cause.javaClass.name}: ${cause.message}\n")
                cause.stackTrace.forEach { append("  at $it\n") }
            }
            append("\n=== All threads ===\n")
            Thread.getAllStackTraces().forEach { (th, frames) ->
                append("--- ${th.name} (${th.state}) ---\n")
                frames.forEach { append("  at $it\n") }
            }
        }

    companion object {
        private const val TAG                = "CrashRecoveryManager"
        private const val PREFS_NAME         = "titan_crash_recovery"
        private const val KEY_CRASH_COUNT    = "crash_count"
        private const val KEY_LAST_CRASH_TIME = "last_crash_time"
        private const val MAX_RESTART_ATTEMPTS = 5
        private const val CRASH_WINDOW_MS    = 10L * 60_000L   // 10 min rolling window
        private const val STALL_THRESHOLD_MS = 60_000L         // 60 s without heartbeat = stall
        private const val HEARTBEAT_CHECK_MS = 30_000L         // check every 30 s
        private const val MAX_LOG_FILES      = 20
    }
}
