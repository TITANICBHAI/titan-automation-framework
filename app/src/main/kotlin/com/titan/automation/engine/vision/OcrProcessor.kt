package com.titan.automation.engine.vision

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.titan.automation.domain.model.DetectionResult
import com.titan.automation.domain.model.OcrBlock
import com.titan.automation.domain.model.OcrResult
import com.titan.automation.domain.model.ScreenRegion
import com.titan.automation.engine.capture.RoiCropper
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OcrProcessor — ML Kit Text Recognition v2 wrapper.
 *
 * All operations are suspend functions backed by [suspendCancellableCoroutine];
 * they run on whatever dispatcher the caller provides and never block a thread.
 *
 * Pattern adapted from SmartAssistant OCR module.
 * Improvements: region crop before OCR, Levenshtein fuzzy match, regex support,
 *               auto-language detection (TextRecognizerOptions.DEFAULT_OPTIONS).
 */
@Singleton
class OcrProcessor @Inject constructor(
    private val roiCropper: RoiCropper
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    // ── Core recognition ──────────────────────────────────────────────────────

    /**
     * Run full OCR on [frame], optionally restricted to [region].
     * Crops before recognition to minimise ML Kit input area.
     */
    suspend fun recognizeText(
        frame : Bitmap,
        region: ScreenRegion? = null
    ): OcrResult {
        val source = if (region != null) roiCropper.crop(frame, region) else frame
        val image  = InputImage.fromBitmap(source, 0)

        return suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val blocks = visionText.textBlocks.flatMap { block ->
                        block.lines.map { line ->
                            OcrBlock(
                                text       = line.text,
                                confidence = line.confidence ?: 0.8f,
                                bounds     = line.boundingBox ?: Rect()
                            )
                        }
                    }
                    cont.resume(
                        OcrResult(
                            text       = visionText.text,
                            confidence = blocks.map { it.confidence }.average()
                                .toFloat().takeIf { it.isFinite() } ?: 0f,
                            matched    = true,
                            blocks     = blocks
                        )
                    )
                }
                .addOnFailureListener { e -> cont.resumeWithException(e) }

            cont.invokeOnCancellation { /* ML Kit tasks are fire-and-forget; nothing to cancel */ }
        }.also {
            if (region != null && source !== frame) source.recycle()
        }
    }

    // ── High-level matchers ───────────────────────────────────────────────────

    /**
     * Search for [query] text in [frame] using Levenshtein similarity.
     * @param fuzzyThreshold similarity score [0..1]; 1.0 = exact match.
     */
    suspend fun findText(
        frame          : Bitmap,
        query          : String,
        fuzzyThreshold : Float      = 0.85f,
        region         : ScreenRegion? = null
    ): DetectionResult {
        return try {
            val result = recognizeText(frame, region)
            var best: OcrBlock? = null
            var bestScore = 0f

            for (block in result.blocks) {
                val score = levenshteinSimilarity(query.lowercase(), block.text.lowercase())
                if (score >= fuzzyThreshold && score > bestScore) {
                    bestScore = score
                    best = block
                }
            }

            if (best != null) {
                DetectionResult.Found(
                    confidence = bestScore,
                    bounds     = best.bounds,
                    centerX    = best.bounds.centerX(),
                    centerY    = best.bounds.centerY(),
                    label      = best.text
                )
            } else {
                DetectionResult.NotFound(confidence = bestScore)
            }
        } catch (e: Exception) {
            DetectionResult.Error(e)
        }
    }

    /**
     * Find all text matches in [frame] that match [pattern] regex.
     */
    suspend fun findTextRegex(
        frame  : Bitmap,
        pattern: Regex,
        region : ScreenRegion? = null
    ): List<DetectionResult.Found> {
        return try {
            val result = recognizeText(frame, region)
            val matches = mutableListOf<DetectionResult.Found>()

            for (block in result.blocks) {
                if (pattern.containsMatchIn(block.text)) {
                    matches.add(
                        DetectionResult.Found(
                            confidence = block.confidence,
                            bounds     = block.bounds,
                            centerX    = block.bounds.centerX(),
                            centerY    = block.bounds.centerY(),
                            label      = block.text
                        )
                    )
                }
            }
            matches
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── Levenshtein similarity ────────────────────────────────────────────────

    private fun levenshteinSimilarity(a: String, b: String): Float {
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1f
        return 1f - levenshteinDistance(a, b).toFloat() / maxLen
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
        }
        return dp[a.length][b.length]
    }
}
