package com.titan.automation.domain.model

import android.graphics.Rect

/**
 * DetectionResult — typed output DTO for all VisionEngine detection operations.
 *
 * Pattern adapted from subway-surfers-bot object detection triggers.
 * Sealed hierarchy ensures callers handle both outcomes at the call site.
 */
sealed class DetectionResult {

    /** A detection that matched above the configured threshold. */
    data class Found(
        val confidence  : Float,
        val bounds      : Rect,
        val centerX     : Int,
        val centerY     : Int,
        val scaleFactor : Float = 1.0f,   // Used by multi-scale matching
        val label       : String = "",     // Populated by InferenceEngine
        val latencyMs   : Long  = 0L
    ) : DetectionResult() {
        /** Normalised [0..1] centre coordinates (relative to frame dimensions). */
        fun normX(frameWidth : Int): Float = centerX.toFloat() / frameWidth.coerceAtLeast(1)
        fun normY(frameHeight: Int): Float = centerY.toFloat() / frameHeight.coerceAtLeast(1)
    }

    /** No match found above threshold. Carries best observed confidence for diagnostics. */
    data class NotFound(
        val confidence : Float = 0f,
        val reason     : String = ""
    ) : DetectionResult()

    /** Detection was skipped (e.g. frame too similar to previous, or thermal throttle). */
    data class Skipped(val reason: String) : DetectionResult()

    /** Detection failed due to an internal error (mat allocation, model crash, etc.). */
    data class Error(val cause: Throwable) : DetectionResult()

    // ── Helpers ───────────────────────────────────────────────────────────────

    val isFound: Boolean get() = this is Found
    val confidence: Float get() = when (this) {
        is Found    -> confidence
        is NotFound -> confidence
        else        -> 0f
    }
}

/**
 * OCR-specific result carrying the recognized text alongside detection metadata.
 */
data class OcrResult(
    val text       : String,
    val confidence : Float,
    val matched    : Boolean,
    val region     : String = "full",
    val blocks     : List<OcrBlock> = emptyList()
)

data class OcrBlock(
    val text       : String,
    val confidence : Float,
    val bounds     : Rect
)

/**
 * TFLite inference output DTO.
 */
data class InferenceResult(
    val label      : String,
    val confidence : Float,
    val allScores  : Map<String, Float> = emptyMap(),
    val latencyMs  : Long = 0L
)
