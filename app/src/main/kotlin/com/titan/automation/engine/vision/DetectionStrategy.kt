package com.titan.automation.engine.vision

import android.graphics.Bitmap
import com.titan.automation.domain.model.DetectionResult
import com.titan.automation.domain.model.ScreenRegion
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DetectionStrategy — pluggable strategy interface for all detection types.
 *
 * Implementors encapsulate a single detection algorithm.
 * [DetectionStrategyRegistry] maps strategy names (from workflow JSON) to implementations.
 *
 * Pattern adapted from subway-surfers-bot detection trigger abstraction.
 * Improvements: coroutine-native suspend API, result sealed class, registry-based dispatch.
 */
interface DetectionStrategy {
    val name: String

    /**
     * Execute detection on [frame] with optional [region] crop.
     * Never throws — all errors are wrapped in [DetectionResult.Error].
     */
    suspend fun detect(
        frame    : Bitmap,
        params   : DetectionParams,
        region   : ScreenRegion? = null
    ): DetectionResult
}

/**
 * Unified parameter bag for all detection strategies.
 * Fields unused by a particular strategy are simply ignored.
 */
data class DetectionParams(
    val templateBitmap : Bitmap?  = null,
    val templatePath   : String?  = null,
    val threshold      : Float    = 0.80f,
    val ocrQuery       : String?  = null,
    val ocrFuzzy       : Float    = 0.85f,
    val ocrPattern     : String?  = null,
    val modelType      : InferenceEngine.ModelType? = null,
    val multiScale     : Boolean  = false,
    val maxResults     : Int      = 1
)

// ── Concrete strategies ────────────────────────────────────────────────────────

class TemplateMatchStrategy @Inject constructor(
    private val templateMatcher: TemplateMatcher
) : DetectionStrategy {
    override val name = "template_match"

    override suspend fun detect(frame: Bitmap, params: DetectionParams, region: ScreenRegion?): DetectionResult {
        val tpl = params.templateBitmap ?: return DetectionResult.NotFound(reason = "no template bitmap provided")
        return if (params.multiScale)
            templateMatcher.findTemplateMultiScale(frame, tpl, params.threshold)
        else
            templateMatcher.findTemplate(frame, tpl, params.threshold)
    }
}

class TemplateMatchAllStrategy @Inject constructor(
    private val templateMatcher: TemplateMatcher
) : DetectionStrategy {
    override val name = "template_match_all"

    override suspend fun detect(frame: Bitmap, params: DetectionParams, region: ScreenRegion?): DetectionResult {
        val tpl = params.templateBitmap ?: return DetectionResult.NotFound(reason = "no template bitmap provided")
        val results = templateMatcher.findAllTemplates(frame, tpl, params.threshold, params.maxResults)
        return results.firstOrNull() ?: DetectionResult.NotFound()
    }
}

class OcrFindStrategy @Inject constructor(
    private val ocrProcessor: OcrProcessor
) : DetectionStrategy {
    override val name = "ocr_find"

    override suspend fun detect(frame: Bitmap, params: DetectionParams, region: ScreenRegion?): DetectionResult {
        val query = params.ocrQuery ?: return DetectionResult.NotFound(reason = "no OCR query provided")
        return ocrProcessor.findText(frame, query, params.ocrFuzzy, region)
    }
}

class OcrRegexStrategy @Inject constructor(
    private val ocrProcessor: OcrProcessor
) : DetectionStrategy {
    override val name = "ocr_regex"

    override suspend fun detect(frame: Bitmap, params: DetectionParams, region: ScreenRegion?): DetectionResult {
        val pattern = params.ocrPattern?.toRegexOrNull()
            ?: return DetectionResult.NotFound(reason = "invalid regex pattern")
        val results = ocrProcessor.findTextRegex(frame, pattern, region)
        return results.firstOrNull() ?: DetectionResult.NotFound()
    }

    private fun String.toRegexOrNull(): Regex? = try { this.toRegex() } catch (_: Exception) { null }
}

class InferenceStrategy @Inject constructor(
    private val inferenceEngine    : InferenceEngine,
    private val precisionManager   : ModelPrecisionManager
) : DetectionStrategy {
    override val name = "inference"

    override suspend fun detect(frame: Bitmap, params: DetectionParams, region: ScreenRegion?): DetectionResult {
        if (!precisionManager.inferenceAllowed) return DetectionResult.Skipped("inference disabled (thermal)")
        val model  = params.modelType ?: precisionManager.selectModel()
        inferenceEngine.loadModel(model)
        val result = inferenceEngine.classify(frame, model)
        return if (result.confidence >= params.threshold) {
            DetectionResult.Found(
                confidence = result.confidence,
                bounds     = android.graphics.Rect(),
                centerX    = frame.width / 2,
                centerY    = frame.height / 2,
                label      = result.label,
                latencyMs  = result.latencyMs
            )
        } else {
            DetectionResult.NotFound(confidence = result.confidence)
        }
    }
}

/**
 * Registry that maps strategy names to implementations.
 * Inject all strategies via Hilt and register them here.
 */
@Singleton
class DetectionStrategyRegistry @Inject constructor(
    templateMatchStrategy   : TemplateMatchStrategy,
    templateMatchAllStrategy: TemplateMatchAllStrategy,
    ocrFindStrategy         : OcrFindStrategy,
    ocrRegexStrategy        : OcrRegexStrategy,
    inferenceStrategy       : InferenceStrategy
) {
    private val strategies: Map<String, DetectionStrategy> = listOf(
        templateMatchStrategy,
        templateMatchAllStrategy,
        ocrFindStrategy,
        ocrRegexStrategy,
        inferenceStrategy
    ).associateBy { it.name }

    fun get(name: String): DetectionStrategy? = strategies[name]
    fun all(): Collection<DetectionStrategy> = strategies.values
}
