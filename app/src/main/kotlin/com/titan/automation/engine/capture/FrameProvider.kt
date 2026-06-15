package com.titan.automation.engine.capture

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FrameProvider — shared [StateFlow] hub between [ScreenCaptureService] (producer)
 * and [MacroEngine] / [VisionEngine] (consumers).
 *
 * Why this exists:
 *   [ScreenCaptureService] is an Android Service. Hilt cannot provide a live Service
 *   instance to other Hilt components — Services are framework-constructed.
 *   Injecting a shared singleton that both sides access solves this cleanly without
 *   any static singletons or complicated IPC.
 *
 * Thread safety: [MutableStateFlow.value] setter is thread-safe.
 */
@Singleton
class FrameProvider @Inject constructor() {

    private val _latestFrame = MutableStateFlow<CapturedFrame?>(null)

    /** Current latest captured frame. Updated by [ScreenCaptureService]. */
    val latestFrame: StateFlow<CapturedFrame?> = _latestFrame.asStateFlow()

    /** Called by [ScreenCaptureService] on every new captured frame. */
    fun publish(frame: CapturedFrame) {
        _latestFrame.value = frame
    }

    /** Returns the most recent Bitmap without waiting for a new frame, or null. */
    fun latestBitmap(): Bitmap? = _latestFrame.value?.bitmap
}
