package com.titan.automation.engine.playback

import android.content.Context
import android.graphics.Rect
import com.titan.automation.data.db.MacroDatabase
import com.titan.automation.domain.model.LoopMode
import com.titan.automation.domain.model.PlaybackConfig
import com.titan.automation.domain.model.SimpleAction
import com.titan.automation.domain.model.SimpleActionType
import com.titan.automation.domain.model.SimpleMacro
import com.titan.automation.engine.accessibility.MacroAccessibilityService
import com.titan.automation.engine.capture.FrameProvider
import com.titan.automation.engine.governor.ThermalGovernor
import com.titan.automation.engine.overlay.TapDotRenderer
import com.titan.automation.engine.vision.VisionEngine
import com.titan.automation.events.TitanEvent
import com.titan.automation.events.TitanEventBus
import com.titan.automation.domain.model.VisionMatchRule
import com.titan.automation.domain.model.ScreenRegion
import dagger.hilt.android.qualifiers.ApplicationContext
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
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * SimplePlaybackEngine — executes [SimpleMacro] action sequences.
 *
 * Integration:
 *  - [TitanEventBus]    — emits GestureDispatched / Error / EngineStarted / EngineStopped
 *  - [ThermalGovernor]  — halts playback when thermal is CRITICAL; scales delays on throttle
 *  - [MacroDatabase]    — increments runCount + updates lastRunAt after each completion
 *  - [VisionEngine]     — powers WAIT_FOR_IMAGE and WAIT_FOR_OCR_TEXT conditional steps
 *  - [FrameProvider]    — supplies live screen frames for conditional checks
 *
 * Loop modes: ONCE, COUNT (N times), FOREVER, DURATION (X ms)
 * Speed multiplier: 0.25x – 4x applied to every delay
 * Anti-detection jitter via [MacroAccessibilityService.touchNoiseStdDev]
 * [runtimeShowDots]: when non-null, overrides per-macro PlaybackConfig.showTapDots at runtime
 *   (used by OverlayService's DOT toggle button)
 */
@Singleton
class SimplePlaybackEngine @Inject constructor(
    private val tapDotRenderer : TapDotRenderer,
    private val eventBus       : TitanEventBus,
    private val thermal        : ThermalGovernor,
    private val db             : MacroDatabase,
    private val visionEngine   : VisionEngine,
    private val frameProvider  : FrameProvider,
    @ApplicationContext private val context: Context
) {
    private val scope   = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var playJob : Job? = null

    private val _isPlaying        = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentMacroName = MutableStateFlow<String?>(null)
    val currentMacroName: StateFlow<String?> = _currentMacroName.asStateFlow()

    private val _completedLoops   = MutableStateFlow(0)
    val completedLoops: StateFlow<Int> = _completedLoops.asStateFlow()

    private val _currentStepIndex = MutableStateFlow(0)
    val currentStepIndex: StateFlow<Int> = _currentStepIndex.asStateFlow()

    /**
     * Runtime override for tap-dot visibility.
     * - null  → honour per-macro [PlaybackConfig.showTapDots]
     * - true  → always show dots regardless of config
     * - false → always hide dots regardless of config
     *
     * Set by [com.titan.automation.engine.overlay.OverlayService] DOT toggle button.
     */
    @Volatile var runtimeShowDots: Boolean? = null

    // ── Public API ────────────────────────────────────────────────────────────

    fun play(macro: SimpleMacro) {
        stop()
        if (macro.actions.isEmpty()) return

        _isPlaying.value      = true
        _currentMacroName.value = macro.name
        _completedLoops.value = 0
        _currentStepIndex.value = 0

        val cfg = macro.playbackConfig
        applyJitter(cfg)

        eventBus.emit(TitanEvent.EngineStarted(workflowId = "macro:${macro.id}"))

        playJob = scope.launch {
            val startTime = System.currentTimeMillis()
            var loops     = 0
            var completed = false

            try {
                while (isActive) {
                    // ── Thermal gate ──────────────────────────────────────────
                    if (cfg.respectThermal && thermal.isCritical) {
                        eventBus.emit(TitanEvent.ThermalStatus(
                            level = "CRITICAL",
                            targetFps = thermal.targetFps,
                            rlEnabled = false
                        ))
                        // Wait for thermal to cool down (check every 2s)
                        while (isActive && thermal.isCritical) delay(2_000L)
                    }

                    // ── Execute all steps in this loop ────────────────────────
                    macro.actions.forEachIndexed { idx, action ->
                        if (!isActive) return@forEachIndexed
                        _currentStepIndex.value = idx

                        // Throttle extra delay when thermal is degraded
                        val thermalSlowdown = computeThermalSlowdown(cfg)

                        executeAction(action, cfg, thermalSlowdown)
                    }

                    loops++
                    _completedLoops.value = loops

                    val shouldContinue = when (cfg.loopMode) {
                        LoopMode.ONCE     -> false
                        LoopMode.COUNT    -> loops < cfg.loopCount
                        LoopMode.FOREVER  -> true
                        LoopMode.DURATION -> (System.currentTimeMillis() - startTime) < cfg.loopDurationMs
                    }
                    if (!shouldContinue) {
                        completed = true
                        break
                    }
                }
            } catch (e: Exception) {
                eventBus.emit(TitanEvent.Error(
                    source  = "SimplePlaybackEngine",
                    message = e.message ?: "Unknown error during macro playback",
                    fatal   = false
                ))
            } finally {
                if (completed) {
                    // Update run stats in DB on natural completion
                    db.simpleMacroDao().incrementRunCount(macro.id, System.currentTimeMillis())
                }
                _isPlaying.value        = false
                _currentMacroName.value = null
                _currentStepIndex.value = 0
                resetJitter()
                eventBus.emit(TitanEvent.EngineStopped)
            }
        }
    }

    fun stop() {
        playJob?.cancel()
        playJob = null
        _isPlaying.value        = false
        _currentMacroName.value = null
        _currentStepIndex.value = 0
        resetJitter()
    }

    // ── Action execution ──────────────────────────────────────────────────────

    private suspend fun executeAction(
        action: SimpleAction,
        cfg: PlaybackConfig,
        thermalSlowdown: Long
    ) {
        val svc      = MacroAccessibilityService.get()
        val showDots = runtimeShowDots ?: cfg.showTapDots

        when (action.type) {

            SimpleActionType.TAP -> {
                if (showDots) tapDotRenderer.show(action.x, action.y)
                val ok = svc?.dispatchClick(action.x, action.y) ?: false
                eventBus.emit(TitanEvent.GestureDispatched(type = "TAP", x = action.x, y = action.y, success = ok))
            }

            SimpleActionType.LONG_PRESS -> {
                if (showDots) tapDotRenderer.show(action.x, action.y)
                val ok = svc?.dispatchLongPress(action.x, action.y, action.durationMs) ?: false
                eventBus.emit(TitanEvent.GestureDispatched(type = "LONG_PRESS", x = action.x, y = action.y, success = ok))
            }

            SimpleActionType.SWIPE -> {
                if (showDots) {
                    tapDotRenderer.show(action.x, action.y)
                    tapDotRenderer.showSwipeEnd(action.endX, action.endY)
                }
                val ok = svc?.dispatchSwipe(action.x, action.y, action.endX, action.endY, action.durationMs) ?: false
                eventBus.emit(TitanEvent.GestureDispatched(type = "SWIPE", x = action.x, y = action.y, success = ok))
            }

            SimpleActionType.WAIT -> {
                val waitMs = (action.durationMs / cfg.speedMultiplier).toLong().coerceAtLeast(10L)
                delay(waitMs)
                eventBus.emit(TitanEvent.GestureDispatched(type = "WAIT", x = 0f, y = 0f, success = true))
            }

            SimpleActionType.WAIT_FOR_IMAGE -> {
                val found = waitForImage(action, cfg)
                if (found != null && action.tapWhenFound) {
                    if (showDots) tapDotRenderer.show(found.first, found.second)
                    val ok = svc?.dispatchClick(found.first, found.second) ?: false
                    eventBus.emit(TitanEvent.GestureDispatched(type = "IMG_TAP", x = found.first, y = found.second, success = ok))
                } else if (found != null) {
                    eventBus.emit(TitanEvent.GestureDispatched(type = "IMG_FOUND", x = found.first, y = found.second, success = true))
                } else {
                    eventBus.emit(TitanEvent.Error(
                        source  = "WAIT_FOR_IMAGE",
                        message = "Timeout waiting for template '${action.templateId}' after ${action.conditionTimeoutMs}ms",
                        fatal   = false
                    ))
                }
            }

            SimpleActionType.WAIT_FOR_OCR_TEXT -> {
                val found = waitForOcrText(action, cfg)
                if (found) {
                    eventBus.emit(TitanEvent.GestureDispatched(type = "OCR_FOUND", x = 0f, y = 0f, success = true))
                } else {
                    eventBus.emit(TitanEvent.Error(
                        source  = "WAIT_FOR_OCR_TEXT",
                        message = "Timeout waiting for text '${action.ocrPattern}' after ${action.conditionTimeoutMs}ms",
                        fatal   = false
                    ))
                }
            }
        }

        // Post-action delay (not applied to conditional waiting steps)
        if (action.type != SimpleActionType.WAIT_FOR_IMAGE &&
            action.type != SimpleActionType.WAIT_FOR_OCR_TEXT &&
            action.type != SimpleActionType.WAIT
        ) {
            val scaledDelay = (action.delayAfterMs / cfg.speedMultiplier).toLong()
                .coerceAtLeast(10L) + thermalSlowdown
            if (scaledDelay > 0) delay(scaledDelay)
        }
    }

    // ── Conditional: WAIT_FOR_IMAGE ───────────────────────────────────────────

    /**
     * Polls [FrameProvider] for a fresh bitmap, then calls [VisionEngine.findTemplate]
     * every 500ms until the template is found or [SimpleAction.conditionTimeoutMs] elapses.
     *
     * @return Normalized (x, y) of the match centre if found; null on timeout.
     */
    private suspend fun waitForImage(
        action: SimpleAction,
        cfg: PlaybackConfig
    ): Pair<Float, Float>? = withTimeoutOrNull(action.conditionTimeoutMs) {
        val rule = VisionMatchRule(
            templateId    = action.templateId,
            minConfidence = action.minConfidence,
            actionIntent  = "wait_for"
        )
        while (isActive) {
            val frame = frameProvider.latestBitmap()
            if (frame != null) {
                val match = visionEngine.findTemplate(rule, frame)
                if (match != null && match.confidence >= action.minConfidence) {
                    return@withTimeoutOrNull Pair(match.cx, match.cy)
                }
            }
            delay(500L)
        }
        null
    }

    // ── Conditional: WAIT_FOR_OCR_TEXT ────────────────────────────────────────

    /**
     * Polls [FrameProvider] and calls [VisionEngine.extractText] every 700ms
     * until the extracted text contains [SimpleAction.ocrPattern] (case-insensitive)
     * or the timeout elapses.
     *
     * @return true if text was found within the timeout, false otherwise.
     */
    private suspend fun waitForOcrText(
        action: SimpleAction,
        cfg: PlaybackConfig
    ): Boolean {
        val dm  = context.resources.displayMetrics
        val region: ScreenRegion? =
            if (action.ocrRegionW >= 1f && action.ocrRegionH >= 1f) null
            else ScreenRegion(
                left   = (action.ocrRegionX * dm.widthPixels).toInt(),
                top    = (action.ocrRegionY * dm.heightPixels).toInt(),
                right  = ((action.ocrRegionX + action.ocrRegionW) * dm.widthPixels).toInt(),
                bottom = ((action.ocrRegionY + action.ocrRegionH) * dm.heightPixels).toInt()
            )

        val found = withTimeoutOrNull(action.conditionTimeoutMs) {
            while (isActive) {
                val frame = frameProvider.latestBitmap()
                if (frame != null) {
                    val text = visionEngine.extractText(frame, region)
                    if (text.contains(action.ocrPattern, ignoreCase = true)) {
                        return@withTimeoutOrNull true
                    }
                }
                delay(700L)
            }
            false
        }
        return found == true
    }

    // ── Thermal scaling ───────────────────────────────────────────────────────

    /**
     * When the governor is throttling (targetFps < nominal 10), inject extra delay
     * between steps so we don't hammer the CPU while the device is hot.
     */
    private fun computeThermalSlowdown(cfg: PlaybackConfig): Long {
        if (!cfg.respectThermal) return 0L
        val targetFps = thermal.targetFps
        return when {
            targetFps >= 10 -> 0L
            targetFps >= 5  -> 50L
            targetFps >= 2  -> 150L
            else            -> 300L
        }
    }

    // ── Jitter ────────────────────────────────────────────────────────────────

    private fun applyJitter(cfg: PlaybackConfig) {
        val svc = MacroAccessibilityService.get() ?: return
        svc.touchNoiseStdDev = if (cfg.jitterEnabled) cfg.jitterRadiusPx else 0f
    }

    private fun resetJitter() {
        MacroAccessibilityService.get()?.touchNoiseStdDev = 3f
    }

    // ── Utilities ─────────────────────────────────────────────────────────────

    private fun pct(n: Float) = "${(n * 100).toInt()}%"
}
