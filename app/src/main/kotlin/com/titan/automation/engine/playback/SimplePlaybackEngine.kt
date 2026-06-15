package com.titan.automation.engine.playback

import com.titan.automation.domain.model.LoopMode
import com.titan.automation.domain.model.PlaybackConfig
import com.titan.automation.domain.model.SimpleAction
import com.titan.automation.domain.model.SimpleActionType
import com.titan.automation.domain.model.SimpleMacro
import com.titan.automation.engine.accessibility.MacroAccessibilityService
import com.titan.automation.engine.overlay.TapDotRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SimplePlaybackEngine — executes [SimpleMacro] action sequences.
 *
 * Features:
 *  - Loop modes: ONCE, COUNT (N times), FOREVER, DURATION (X ms)
 *  - Speed multiplier on delays (0.25x–4x)
 *  - Anti-detection jitter via [MacroAccessibilityService.touchNoiseStdDev]
 *  - Optional tap-dot visualization via [TapDotRenderer]
 *  - Clean stop/cancel at any point
 */
@Singleton
class SimplePlaybackEngine @Inject constructor(
    private val tapDotRenderer: TapDotRenderer
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playJob: Job? = null

    private val _isPlaying     = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentMacroName = MutableStateFlow<String?>(null)
    val currentMacroName: StateFlow<String?> = _currentMacroName.asStateFlow()

    private val _completedLoops = MutableStateFlow(0)
    val completedLoops: StateFlow<Int> = _completedLoops.asStateFlow()

    // ── Public API ────────────────────────────────────────────────────────────

    fun play(macro: SimpleMacro) {
        stop()
        if (macro.actions.isEmpty()) return
        _isPlaying.value     = true
        _currentMacroName.value = macro.name
        _completedLoops.value = 0

        val cfg = macro.playbackConfig
        applyJitter(cfg)

        playJob = scope.launch {
            val startTime = System.currentTimeMillis()
            var loops     = 0

            while (isActive) {
                // Execute one pass through all actions
                for (action in macro.actions) {
                    if (!isActive) break
                    executeAction(action, cfg)
                }
                loops++
                _completedLoops.value = loops

                // Loop control
                val shouldContinue = when (cfg.loopMode) {
                    LoopMode.ONCE     -> false
                    LoopMode.COUNT    -> loops < cfg.loopCount
                    LoopMode.FOREVER  -> true
                    LoopMode.DURATION -> (System.currentTimeMillis() - startTime) < cfg.loopDurationMs
                }
                if (!shouldContinue) break
            }

            _isPlaying.value        = false
            _currentMacroName.value = null
            resetJitter()
        }
    }

    fun stop() {
        playJob?.cancel()
        playJob = null
        _isPlaying.value        = false
        _currentMacroName.value = null
        resetJitter()
    }

    // ── Action execution ──────────────────────────────────────────────────────

    private suspend fun executeAction(action: SimpleAction, cfg: PlaybackConfig) {
        val svc = MacroAccessibilityService.get()

        when (action.type) {
            SimpleActionType.TAP -> {
                if (cfg.showTapDots) tapDotRenderer.show(action.x, action.y)
                svc?.dispatchClick(action.x, action.y)
            }
            SimpleActionType.LONG_PRESS -> {
                if (cfg.showTapDots) tapDotRenderer.show(action.x, action.y)
                svc?.dispatchLongPress(action.x, action.y, action.durationMs)
            }
            SimpleActionType.SWIPE -> {
                if (cfg.showTapDots) {
                    tapDotRenderer.show(action.x, action.y)
                    tapDotRenderer.showSwipeEnd(action.endX, action.endY)
                }
                svc?.dispatchSwipe(action.x, action.y, action.endX, action.endY, action.durationMs)
            }
            SimpleActionType.WAIT -> {
                // Pure delay — no gesture
            }
        }

        // Wait for the hold duration (for WAIT actions, durationMs is the entire wait)
        if (action.type == SimpleActionType.WAIT) {
            delay((action.durationMs / cfg.speedMultiplier).toLong().coerceAtLeast(10L))
        }

        // Apply post-action delay with speed multiplier
        val scaledDelay = (action.delayAfterMs / cfg.speedMultiplier).toLong().coerceAtLeast(10L)
        if (scaledDelay > 0) delay(scaledDelay)
    }

    // ── Jitter ────────────────────────────────────────────────────────────────

    private fun applyJitter(cfg: PlaybackConfig) {
        val svc = MacroAccessibilityService.get() ?: return
        svc.touchNoiseStdDev = if (cfg.jitterEnabled) cfg.jitterRadiusPx else 0f
    }

    private fun resetJitter() {
        MacroAccessibilityService.get()?.touchNoiseStdDev = 3f // restore default
    }
}
