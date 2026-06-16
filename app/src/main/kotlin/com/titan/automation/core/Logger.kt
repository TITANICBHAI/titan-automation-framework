package com.titan.automation.core

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

enum class LogLevel(val tag: String) {
    VERBOSE("V"),
    DEBUG("D"),
    INFO("I"),
    WARN("W"),
    ERROR("E")
}

data class LogEntry(
    val level: LogLevel,
    val tag: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val threadName: String = Thread.currentThread().name
)

/**
 * TitanLogger — structured ring-buffer logger with optional file sink.
 *
 * Ring buffer: last [RING_CAPACITY] entries held in memory (lock-free deque).
 * File sink:   writes to [logDir]/titan-<date>.log on Dispatchers.IO.
 * Zero-allocation hot path: reuses pre-allocated StringBuilder for file writes.
 *
 * Pattern adapted from aria-ai-cpu-only LogManager.
 */
@Singleton
class TitanLogger @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ring  = LinkedBlockingDeque<LogEntry>(RING_CAPACITY)
    private val seq   = AtomicLong(0L)
    private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val tsFmt   = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private var logDir: File? = null
    private val lineBuf = StringBuilder(256)

    fun init(filesDir: File) {
        logDir = File(filesDir, "logs").also { it.mkdirs() }
    }

    fun v(tag: String, msg: String) = log(LogLevel.VERBOSE, tag, msg)
    fun d(tag: String, msg: String) = log(LogLevel.DEBUG,   tag, msg)
    fun i(tag: String, msg: String) = log(LogLevel.INFO,    tag, msg)
    fun w(tag: String, msg: String) = log(LogLevel.WARN,    tag, msg)
    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        val full = if (throwable != null) "$msg\n${throwable.stackTraceToString()}" else msg
        log(LogLevel.ERROR, tag, full)
    }

    fun snapshot(): List<LogEntry> = ring.toList()

    fun flush() {
        val entries = snapshot()
        if (entries.isEmpty() || logDir == null) return
        scope.launch {
            val dir  = logDir ?: return@launch
            val file = File(dir, "titan-${dateFmt.format(Date())}.log")
            runCatching {
                file.bufferedWriter().use { writer ->
                    entries.forEach { e ->
                        lineBuf.setLength(0)
                        lineBuf.append(tsFmt.format(Date(e.timestamp)))
                            .append(' ').append(e.level.tag)
                            .append('/').append(e.tag)
                            .append('[').append(e.threadName).append(']')
                            .append(": ").append(e.message).append('\n')
                        writer.write(lineBuf.toString())
                    }
                }
            }.onFailure { Log.e(SYSTEM_TAG, "TitanLogger flush failed", it) }
        }
    }

    private fun log(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(level, tag, message)
        if (ring.size >= RING_CAPACITY) ring.pollFirst()
        ring.offerLast(entry)

        when (level) {
            LogLevel.VERBOSE -> Log.v(tag, message)
            LogLevel.DEBUG   -> Log.d(tag, message)
            LogLevel.INFO    -> Log.i(tag, message)
            LogLevel.WARN    -> Log.w(tag, message)
            LogLevel.ERROR   -> Log.e(tag, message)
        }

        if (level == LogLevel.ERROR || seq.incrementAndGet() % FLUSH_INTERVAL == 0L) {
            flush()
        }
    }

    companion object {
        private const val RING_CAPACITY  = 500
        private const val FLUSH_INTERVAL = 100L
        private const val SYSTEM_TAG     = "TitanLogger"
    }
}
