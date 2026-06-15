package com.titan.automation.engine.playback

import com.titan.automation.domain.model.ScheduleMode
import com.titan.automation.domain.model.SimpleMacro
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MacroScheduler — orchestrates time-based auto-launch of [SimpleMacro]s.
 *
 * Supports two scheduling modes:
 *  - **One-shot**: run once after [delayMs], then clear
 *  - **Interval**: run repeatedly every [intervalMs], skipping if already playing
 *
 * Each macro gets its own coroutine Job so schedules are independently cancelable.
 * Safe to call from any thread; all mutations are coroutine-confined.
 */
@Singleton
class MacroScheduler @Inject constructor(
    private val playbackEngine: SimplePlaybackEngine
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobs  = ConcurrentHashMap<String, Job>()

    private val _scheduled = MutableStateFlow<Map<String, ScheduledJob>>(emptyMap())
    val scheduled: StateFlow<Map<String, ScheduledJob>> = _scheduled.asStateFlow()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Schedule [macro] to run once after [delayMs] milliseconds.
     */
    fun scheduleOnce(macro: SimpleMacro, delayMs: Long) {
        cancel(macro.id)
        val info = ScheduledJob(
            macroId   = macro.id,
            macroName = macro.name,
            mode      = ScheduleMode.ONCE,
            intervalMs = delayMs,
            fireCount = 0
        )
        val job = scope.launch {
            addScheduled(info)
            delay(delayMs)
            if (isActive) playbackEngine.play(macro)
            removeScheduled(macro.id)
        }
        jobs[macro.id] = job
    }

    /**
     * Schedule [macro] to run every [intervalMs] milliseconds, indefinitely,
     * skipping a cycle if the engine is still playing.
     */
    fun scheduleInterval(macro: SimpleMacro, intervalMs: Long) {
        cancel(macro.id)
        val info = ScheduledJob(
            macroId    = macro.id,
            macroName  = macro.name,
            mode       = ScheduleMode.INTERVAL,
            intervalMs = intervalMs,
            fireCount  = 0
        )
        val job = scope.launch {
            addScheduled(info)
            while (isActive) {
                if (!playbackEngine.isPlaying.value) {
                    playbackEngine.play(macro)
                    updateFireCount(macro.id)
                }
                delay(intervalMs)
            }
        }
        jobs[macro.id] = job
    }

    /**
     * Schedule [macro] to run [times] times with [intervalMs] between runs.
     */
    fun scheduleRepeat(macro: SimpleMacro, times: Int, intervalMs: Long) {
        cancel(macro.id)
        val info = ScheduledJob(
            macroId    = macro.id,
            macroName  = macro.name,
            mode       = ScheduleMode.REPEAT,
            intervalMs = intervalMs,
            totalRuns  = times,
            fireCount  = 0
        )
        val job = scope.launch {
            addScheduled(info)
            var fired = 0
            while (isActive && fired < times) {
                if (!playbackEngine.isPlaying.value) {
                    playbackEngine.play(macro)
                    fired++
                    updateFireCount(macro.id, fired)
                }
                delay(intervalMs)
            }
            removeScheduled(macro.id)
        }
        jobs[macro.id] = job
    }

    fun cancel(macroId: String) {
        jobs.remove(macroId)?.cancel()
        removeScheduled(macroId)
    }

    fun cancelAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        _scheduled.value = emptyMap()
    }

    fun isScheduled(macroId: String) = macroId in _scheduled.value

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun addScheduled(info: ScheduledJob) {
        _scheduled.value = _scheduled.value + (info.macroId to info)
    }

    private fun removeScheduled(macroId: String) {
        jobs.remove(macroId)
        _scheduled.value = _scheduled.value - macroId
    }

    private fun updateFireCount(macroId: String, count: Int = -1) {
        val current = _scheduled.value[macroId] ?: return
        val newCount = if (count >= 0) count else current.fireCount + 1
        _scheduled.value = _scheduled.value + (macroId to current.copy(fireCount = newCount))
    }
}

// ── Models ────────────────────────────────────────────────────────────────────

data class ScheduledJob(
    val macroId   : String,
    val macroName : String,
    val mode      : ScheduleMode,
    val intervalMs: Long,
    val totalRuns : Int = 0,
    val fireCount : Int = 0
) {
    val progressLabel: String get() = when (mode) {
        ScheduleMode.MANUAL   -> ""
        ScheduleMode.ONCE     -> "Pending (${intervalMs / 1000}s delay)"
        ScheduleMode.INTERVAL -> "Running — every ${intervalMs / 1000}s (fired $fireCount×)"
        ScheduleMode.REPEAT   -> "Running — $fireCount/$totalRuns"
    }
}
