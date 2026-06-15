package com.titan.automation.events

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TitanEventBus — unidirectional engine event backbone.
 *
 * All engine components emit here; UI and plugins subscribe.
 * Thread-safe: [tryEmit] is non-suspending and callable from any thread.
 *
 * Buffer: 256 events with DROP_OLDEST to ensure UI freshness over completeness.
 *
 * Event catalogue:
 *   engine_started       / engine_stopped
 *   capture_frame        fps: Float, droppedFrames: Int
 *   vision_match         templateId: String, confidence: Float, x: Int, y: Int
 *   ocr_result           text: String, region: String
 *   rl_decision          action: String, qValue: Float, explorationMode: Boolean
 *   workflow_step        stepId: String, state: String, retryCount: Int
 *   gesture_dispatched   type: String, x: Float, y: Float, success: Boolean
 *   thermal_status       level: String, fps: Int, rlEnabled: Boolean
 *   watchdog_ping        timestamp: Long, healthy: Boolean
 *   error                source: String, message: String
 */
@Singleton
class TitanEventBus @Inject constructor() {

    private val _flow = MutableSharedFlow<TitanEvent>(
        replay             = 0,
        extraBufferCapacity= 256,
        onBufferOverflow   = BufferOverflow.DROP_OLDEST
    )

    val flow: SharedFlow<TitanEvent> = _flow.asSharedFlow()

    fun emit(event: TitanEvent): Boolean = _flow.tryEmit(event)
}

sealed class TitanEvent {
    data class EngineStarted(val workflowId: String)                                : TitanEvent()
    data object EngineStopped                                                        : TitanEvent()
    data class CaptureFrame(val fps: Float, val droppedFrames: Int)                 : TitanEvent()
    data class VisionMatch(
        val templateId: String, val confidence: Float,
        val x: Int, val y: Int
    )                                                                                : TitanEvent()
    data class OcrResult(val text: String, val region: String, val confidence: Float): TitanEvent()
    data class RLDecision(
        val action: String, val qValue: Float,
        val explorationMode: Boolean, val epsilon: Float
    )                                                                                : TitanEvent()
    data class WorkflowStep(
        val stepId: String, val state: String,
        val retryCount: Int, val durationMs: Long
    )                                                                                : TitanEvent()
    data class GestureDispatched(
        val type: String, val x: Float, val y: Float, val success: Boolean
    )                                                                                : TitanEvent()
    data class ThermalStatus(
        val level: String, val targetFps: Int, val rlEnabled: Boolean
    )                                                                                : TitanEvent()
    data class WatchdogPing(val timestamp: Long, val healthy: Boolean)              : TitanEvent()
    data class Error(val source: String, val message: String, val fatal: Boolean)   : TitanEvent()
}
