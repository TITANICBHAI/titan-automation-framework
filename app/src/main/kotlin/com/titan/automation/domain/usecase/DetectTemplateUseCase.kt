package com.titan.automation.domain.usecase

import android.graphics.Bitmap
import com.titan.automation.domain.model.DetectionResult
import com.titan.automation.domain.model.ScreenRegion
import com.titan.automation.engine.capture.RoiCropper
import com.titan.automation.engine.vision.TemplateMatcher
import com.titan.automation.engine.vision.OcrProcessor
import javax.inject.Inject

/**
 * DetectTemplateUseCase — single-responsibility use case for on-demand detection.
 *
 * Provides a clean API for UI screens (debug inspector, workflow editor preview)
 * to run single detections without wiring into MacroEngine or VisionEngine directly.
 *
 * Also used by MacroEngine's executeStep() via this clean interface to keep engine
 * logic decoupled from detection implementation details.
 */
class DetectTemplateUseCase @Inject constructor(
    private val templateMatcher: TemplateMatcher,
    private val ocrProcessor   : OcrProcessor,
    private val roiCropper     : RoiCropper
) {
    sealed class Result {
        data class Found(
            val detection : DetectionResult.Found,
            val type      : DetectionType
        ) : Result()
        data class NotFound(val confidence: Float) : Result()
        data class Error(val cause: Throwable)     : Result()
    }

    enum class DetectionType { TEMPLATE, TEMPLATE_MULTI_SCALE, OCR, OCR_REGEX }

    /**
     * Find [template] image inside [frame], optionally restricted to [region].
     */
    suspend fun findTemplate(
        frame    : Bitmap,
        template : Bitmap,
        threshold: Float       = 0.80f,
        region   : ScreenRegion? = null,
        multiScale: Boolean    = false
    ): Result {
        return try {
            val source = if (region != null) roiCropper.crop(frame, region) else frame
            val raw = if (multiScale)
                templateMatcher.findTemplateMultiScale(source, template, threshold)
            else
                templateMatcher.findTemplate(source, template, threshold)

            if (source !== frame) source.recycle()

            when (raw) {
                is DetectionResult.Found    -> Result.Found(raw,
                    if (multiScale) DetectionType.TEMPLATE_MULTI_SCALE else DetectionType.TEMPLATE)
                is DetectionResult.NotFound -> Result.NotFound(raw.confidence)
                else                        -> Result.NotFound(0f)
            }
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Find text [query] in [frame] using ML Kit OCR with fuzzy matching.
     */
    suspend fun findText(
        frame         : Bitmap,
        query         : String,
        fuzzyThreshold: Float       = 0.85f,
        region        : ScreenRegion? = null
    ): Result {
        return try {
            when (val raw = ocrProcessor.findText(frame, query, fuzzyThreshold, region)) {
                is DetectionResult.Found    -> Result.Found(raw, DetectionType.OCR)
                is DetectionResult.NotFound -> Result.NotFound(raw.confidence)
                else                        -> Result.NotFound(0f)
            }
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    /**
     * Find all OCR matches in [frame] matching [pattern] regex.
     */
    suspend fun findTextRegex(
        frame  : Bitmap,
        pattern: String,
        region : ScreenRegion? = null
    ): List<DetectionResult.Found> {
        return try {
            ocrProcessor.findTextRegex(frame, pattern.toRegex(), region)
        } catch (e: Exception) {
            emptyList()
        }
    }
}
