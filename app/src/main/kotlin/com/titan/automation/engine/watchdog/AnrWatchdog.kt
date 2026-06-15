package com.titan.automation.engine.watchdog

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * AnrWatchdog — in-process main-thread liveness monitor.
 *
 * What it catches:
 *   ANRs (Application Not Responding): the main thread is blocked for longer
 *   than [thresholdMs]. This fires BEFORE Android's own ~5 s ANR dialog, giving
 *   us a stack snapshot of the main thread WHILE it is still stuck — which is
 *   exactly what's needed for debugging. The system ANR trace at /data/anr/ is
 *   unreadable on production devices.
 *
 * How it works (from aria-ai-cpu-only AnrWatchdog.kt):
 *   - A daemon thread posts a no-op Runnable to the main looper every [thresholdMs].
 *   - If the counter doesn't advance within [thresholdMs], the main thread is stuck.
 *   - We capture the main thread's full stack trace AND all other thread stacks,
 *     write an anr-*.txt file, and call [onAnrDetected] if set.
 *   - We do NOT kill the process — we let Android decide that. Our job is to
 *     have the stack before the process dies.
 *   - After capture we wait for the main thread to recover before re-arming,
 *     preventing disk-spam (one report per incident, not one per poll interval).
 *
 * Note: This is an in-process monitor. It cannot detect crashes of the main
 *   process from outside. For cross-process watchdog + service restart, use
 *   [WatchdogService] (runs in :watchdog process).
 *
 * Extracted from: aria-ai-cpu-only AnrWatchdog.kt
 * Changes: @param logDir from Context.filesDir/anr, Kotlin, TITAN logger, callback.
 */
class AnrWatchdog(
    logDir: File,
    private val thresholdMs: Long = 5_000L,
    private val pollMs:      Long = 1_000L,
) {
    private val anrDir      = File(logDir, "anr").apply { mkdirs() }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tick        = AtomicLong(0L)
    private val ts          = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    @Volatile private var running = false
    private var thread: Thread?   = null

    /**
     * Optional callback invoked on the watchdog daemon thread when an ANR is
     * captured. Use to emit a [TitanEvent] or trigger the [WatchdogService].
     */
    var onAnrDetected: ((File) -> Unit)? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread(::loop, "Titan-AnrWatchdog").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "started — threshold=${thresholdMs}ms poll=${pollMs}ms")
    }

    fun stop() {
        running = false
        thread?.interrupt()
        Log.i(TAG, "stopped")
    }

    /** List all captured ANR reports, newest first. */
    fun listAnrs(): List<File> =
        (anrDir.listFiles() ?: emptyArray()).sortedByDescending { it.lastModified() }

    /** Delete all ANR reports — call after uploading or on first-run. */
    fun clearAnrs() {
        anrDir.listFiles()?.forEach { it.delete() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal
    // ─────────────────────────────────────────────────────────────────────────

    private fun loop() {
        while (running) {
            val before = tick.get()

            // Post a lightweight ping to the main looper. When it runs it
            // advances the counter, proving the main thread is alive.
            mainHandler.post { tick.incrementAndGet() }

            try {
                Thread.sleep(thresholdMs)
            } catch (_: InterruptedException) {
                return
            }

            if (!running) return

            if (tick.get() == before) {
                // Main thread did not process our ping within thresholdMs → ANR
                val report = captureAllStacks()
                Log.e(TAG, "ANR detected! Report: ${report.name}")
                onAnrDetected?.invoke(report)

                // Wait for recovery before re-arming (prevents report flooding)
                while (running && tick.get() == before) {
                    try {
                        Thread.sleep(pollMs)
                    } catch (_: InterruptedException) {
                        return
                    }
                }
                if (running) Log.w(TAG, "Main thread recovered after ANR")
            }
        }
    }

    private fun captureAllStacks(): File {
        val main    = Looper.getMainLooper().thread
        val all     = Thread.getAllStackTraces()
        val content = buildString(capacity = 8192) {
            append("===== TITAN ANR snapshot =====\n")
            append("when:    ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())}\n")
            append("blocked: >= ${thresholdMs}ms\n")
            append("thread:  ${main.name} (state=${main.state})\n\n")
            append("===== main thread stack =====\n")
            (all[main] ?: main.stackTrace).forEach { append("  at $it\n") }
            append("\n===== all threads =====\n")
            all.forEach { (th, frames) ->
                if (th == main) return@forEach
                append("--- ${th.name} (state=${th.state}) ---\n")
                frames.forEach { append("  at $it\n") }
            }
        }
        val file = File(anrDir, "anr-${ts.format(Date())}.txt")
        try {
            file.writeText(content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write ANR report", e)
        }
        return file
    }

    companion object {
        private const val TAG = "AnrWatchdog"
    }
}
