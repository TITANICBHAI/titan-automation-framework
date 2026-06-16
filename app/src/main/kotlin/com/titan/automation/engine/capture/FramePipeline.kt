package com.titan.automation.engine.capture

import android.graphics.Bitmap
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FramePipeline — Channel-based frame routing layer between [ScreenCaptureService] (producer)
 * and [MacroEngine] / [VisionEngine] (consumers).
 *
 * Design:
 *   - Single producer: ScreenCaptureService publishes CapturedFrame via [publish].
 *   - Multiple consumers: each subscriber receives its own independent copy of the flow.
 *   - Backpressure policy: DROP_OLDEST — producers never block; stale frames are discarded.
 *   - FrameDifferenceFilter is applied before emission to skip redundant static frames.
 *
 * Pattern adapted from subway-surfers-bot frame processing loop, upgraded to structured
 * coroutines with Channel-backed backpressure.
 */
@Singleton
class FramePipeline @Inject constructor(
    private val differenceFilter: FrameDifferenceFilter,
    private val bitmapPool      : BitmapPool
) {
    /** Sentinel value emitted on pipeline shutdown. */
    companion object {
        val EOF = CapturedFrame(bitmap = null, timestampMs = -1L, rotation = 0, isEof = true)
    }

    private val _frames = MutableSharedFlow<CapturedFrame>(
        replay             = 1,
        extraBufferCapacity = 3,
        onBufferOverflow   = BufferOverflow.DROP_OLDEST
    )

    /** Hot flow of captured frames; subscribe to receive frames as they arrive. */
    val frames: Flow<CapturedFrame> = _frames.asSharedFlow()

    /**
     * Publish a new frame from ScreenCaptureService.
     * [FrameDifferenceFilter] may suppress near-identical frames.
     * Ownership of [bitmap] is transferred to this pipeline — call [releaseFrame] when done.
     */
    suspend fun publish(frame: CapturedFrame) {
        val bitmap = frame.bitmap ?: return
        if (frame.isEof || differenceFilter.shouldProcess(bitmap)) {
            _frames.emit(frame)
        } else {
            // Static frame — return bitmap to pool immediately
            bitmapPool.release(bitmap)
        }
    }

    /**
     * Emit the EOF sentinel to signal capture shutdown to all subscribers.
     */
    suspend fun close() {
        differenceFilter.reset()
        _frames.emit(EOF)
    }

    /**
     * Return a processed frame's bitmap back to the pool.
     * Consumers must call this after they have finished using the frame.
     */
    fun releaseFrame(frame: CapturedFrame) {
        frame.bitmap?.let { bitmapPool.release(it) }
    }

    /** Reset filter state (e.g. after orientation change). */
    fun resetFilter() = differenceFilter.reset()
}

/**
 * A single captured video frame.
 *
 * @param bitmap      Pooled ARGB_8888 bitmap — may be null for EOF sentinel.
 * @param timestampMs Wall-clock timestamp of acquisition.
 * @param rotation    Current display rotation (Surface.ROTATION_0 etc.).
 * @param isEof       True only for the shutdown sentinel — never contains image data.
 */
data class CapturedFrame(
    val bitmap     : Bitmap?,
    val timestampMs: Long,
    val rotation   : Int,
    val isEof      : Boolean = false
)
