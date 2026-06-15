package com.titan.automation.debug

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ExecutionTracer — structured, timestamped trace log for the automation engine.
 *
 * Every engine component emits span events (begin/end) which are correlated
 * by [spanId]. The tracer reconstructs a timeline showing:
 *
 *   [SPAN 001] MacroEngine.executeState   STATE_A     12ms
 *     [SPAN 002] VisionEngine.findTemplate  play_btn  8ms   confidence=0.92
 *     [SPAN 003] RLEngine.getBestAction     TAP_START 1ms   Q=0.74 ε=0.12
 *     [SPAN 004] Accessibility.dispatchTap  (0.5,0.8) 3ms
 *
 * Usage:
 *   val spanId = tracer.begin("MacroEngine", "executeState", "STATE_A")
 *   // ... do work ...
 *   tracer.end(spanId, metadata = mapOf("confidence" to "0.92"))
 *
 * Thread safe: all state mutations go through [ConcurrentLinkedDeque] and
 * [AtomicLong] for span ID generation.
 */
@Singleton
class ExecutionTracer @Inject constructor() {

    private val _spans  = MutableStateFlow<List<TraceSpan>>(emptyList())
    val spans: StateFlow<List<TraceSpan>> = _spans.asStateFlow()

    private val deque   = ConcurrentLinkedDeque<TraceSpan>()
    private val counter = AtomicLong(0L)
    private val activeSpans = java.util.concurrent.ConcurrentHashMap<Long, TraceSpan>()

    @Volatile var enabled: Boolean = false

    // ── Span API ──────────────────────────────────────────────────────────────

    /**
     * Begin a span. Returns a [spanId] that must be passed to [end].
     * No-op (returns -1) if tracing is disabled.
     */
    fun begin(component: String, operation: String, detail: String = ""): Long {
        if (!enabled) return -1L
        val id   = counter.incrementAndGet()
        val span = TraceSpan(
            spanId    = id,
            component = component,
            operation = operation,
            detail    = detail,
            startMs   = System.currentTimeMillis()
        )
        activeSpans[id] = span
        return id
    }

    /**
     * Complete a span opened by [begin].
     * @param spanId The ID returned from [begin].
     * @param metadata Optional key-value annotations attached to the span.
     * @param success Whether the operation succeeded.
     */
    fun end(spanId: Long, metadata: Map<String, String> = emptyMap(), success: Boolean = true) {
        if (spanId < 0L || !enabled) return
        val span = activeSpans.remove(spanId) ?: return
        val finished = span.copy(
            endMs    = System.currentTimeMillis(),
            metadata = metadata,
            success  = success
        )
        deque.addLast(finished)
        if (deque.size > MAX_SPANS) deque.pollFirst()
        _spans.value = deque.toList()

        if (BuildConfig_DEBUG) {
            val dur = (finished.endMs ?: finished.startMs) - finished.startMs
            Log.v(TAG, "[${finished.component}] ${finished.operation}(${finished.detail}) ${dur}ms ok=$success $metadata")
        }
    }

    /** Inline helper: run [block] inside a span, automatically ending it. */
    inline fun <T> trace(component: String, operation: String, detail: String = "", block: () -> T): T {
        val id = begin(component, operation, detail)
        return try {
            val result = block()
            end(id, success = true)
            result
        } catch (t: Throwable) {
            end(id, metadata = mapOf("exception" to t.javaClass.simpleName), success = false)
            throw t
        }
    }

    fun clearAll() {
        activeSpans.clear()
        deque.clear()
        _spans.value = emptyList()
        counter.set(0)
    }

    /** Export all completed spans as a single text report. */
    fun exportReport(): String = buildString {
        appendLine("=== TITAN Execution Trace ===")
        for (span in deque.toList()) {
            val dur = span.durationMs?.let { "${it}ms" } ?: "?"
            val ok  = if (span.success) "OK" else "FAIL"
            val meta = if (span.metadata.isEmpty()) "" else " ${span.metadata}"
            appendLine("[${span.spanId.toString().padStart(4, '0')}] ${span.component}.${span.operation}" +
                       "(${span.detail}) $dur $ok$meta")
        }
    }

    companion object {
        private const val TAG       = "ExecutionTracer"
        private const val MAX_SPANS = 2048
        // Avoid importing BuildConfig which may not exist at compile time
        private val BuildConfig_DEBUG = try {
            Class.forName("com.titan.automation.BuildConfig")
                .getField("DEBUG").getBoolean(null)
        } catch (_: Exception) { false }
    }
}

data class TraceSpan(
    val spanId    : Long,
    val component : String,
    val operation : String,
    val detail    : String,
    val startMs   : Long,
    val endMs     : Long?                 = null,
    val metadata  : Map<String, String>   = emptyMap(),
    val success   : Boolean               = true
) {
    val durationMs: Long? get() = endMs?.let { it - startMs }
}
