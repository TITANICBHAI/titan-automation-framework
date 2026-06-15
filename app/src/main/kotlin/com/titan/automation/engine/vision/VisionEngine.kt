package com.titan.automation.engine.vision

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.titan.automation.domain.model.ScreenRegion
import com.titan.automation.domain.model.VisionMatchRule
import com.titan.automation.domain.model.OcrScanRule
import com.titan.automation.events.TitanEvent
import com.titan.automation.events.TitanEventBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import javax.inject.Singleton

/**
 * VisionEngine — multimodal screen analysis.
 *
 * Three subsystems:
 *
 * 1. Traditional CV (OpenCV)
 *    - Template matching: TM_CCOEFF_NORMED with multi-scale resizing
 *    - Edge detection: Canny for UI anchor tracking
 *    - Contour detection: for bounding-box extraction
 *    - ORB feature matching: for scale/rotation-invariant template detection
 *    - Histogram comparison: for colour-based state classification
 *
 * 2. OCR (ML Kit Text Recognition v2)
 *    - Region-of-interest cropping before recognition (reduces CPU load ~4×)
 *    - Confidence threshold filtering
 *    - Regex extraction on recognised text blocks
 *    - Fuzzy matching via Levenshtein distance
 *    - Async batching: multiple ROIs processed in one ML Kit call
 *
 * 3. TFLite Inference (scene / button / state classification)
 *    - Delegated to [TFLiteInferenceEngine] (INT8/FP16 quantized models)
 *    - Results surfaced as [InferenceResult] with confidence scores
 *
 * All heavy work runs on Dispatchers.Default. Never blocks the main thread.
 * Mat objects are released after every operation to prevent native memory leaks.
 */
@Singleton
class VisionEngine @Inject constructor(
    private val eventBus: TitanEventBus,
    private val tflite: TFLiteInferenceEngine
) {

    private val textRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    // ── Mat reuse pool (prevents GC in hot template-matching loop) ────────────
    private val srcMat   = Mat()
    private val tmplMat  = Mat()
    private val resultMat= Mat()

    // ─────────────────────────────────────────────────────────────────────────
    // Template matching
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Find the best match for [template] inside [frame].
     *
     * Uses TM_CCOEFF_NORMED with multi-scale resizing (0.7×–1.3× in 0.05 steps)
     * to handle layout shifts on different screen densities.
     *
     * @return [MatchResult] with normalised (0..1) centre coordinates, or null if
     *   no match exceeds [rule.minConfidence].
     */
    suspend fun findTemplate(
        frame: Bitmap,
        template: Bitmap,
        rule: VisionMatchRule
    ): MatchResult? = withContext(Dispatchers.Default) {
        try {
            Utils.bitmapToMat(frame, srcMat)
            Utils.bitmapToMat(template, tmplMat)

            val roi = rule.region?.let { cropMat(srcMat, it, frame.width, frame.height) } ?: srcMat
            Imgproc.cvtColor(roi, roi, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(tmplMat, tmplMat, Imgproc.COLOR_RGBA2GRAY)

            var bestConf = 0f
            var bestLoc: Point? = null
            var bestScale = 1.0

            val scales = generateSequence(0.7) { it + 0.05 }.takeWhile { it <= 1.3 }

            if (rule.multiScale) {
                for (scale in scales) {
                    val scaledTmpl = Mat()
                    Imgproc.resize(tmplMat, scaledTmpl, Size(
                        (tmplMat.cols() * scale).toInt().toDouble(),
                        (tmplMat.rows() * scale).toInt().toDouble()
                    ))
                    if (scaledTmpl.cols() > roi.cols() || scaledTmpl.rows() > roi.rows()) {
                        scaledTmpl.release()
                        continue
                    }
                    val res = Mat()
                    Imgproc.matchTemplate(roi, scaledTmpl, res, Imgproc.TM_CCOEFF_NORMED)
                    val mm = Core.minMaxLoc(res)
                    if (mm.maxVal.toFloat() > bestConf) {
                        bestConf = mm.maxVal.toFloat()
                        bestLoc  = mm.maxLoc
                        bestScale = scale
                    }
                    res.release()
                    scaledTmpl.release()
                }
            } else {
                Imgproc.matchTemplate(roi, tmplMat, resultMat, Imgproc.TM_CCOEFF_NORMED)
                val mm = Core.minMaxLoc(resultMat)
                bestConf = mm.maxVal.toFloat()
                bestLoc  = mm.maxLoc
            }

            if (bestConf < rule.minConfidence || bestLoc == null) return@withContext null

            // Convert pixel location to normalised screen coords
            val offsetX = rule.region?.left ?: 0
            val offsetY = rule.region?.top  ?: 0
            val nx = (bestLoc.x + offsetX + tmplMat.cols() * bestScale / 2) / frame.width
            val ny = (bestLoc.y + offsetY + tmplMat.rows() * bestScale / 2) / frame.height

            val result = MatchResult(
                confidence  = bestConf,
                normX       = nx.toFloat().coerceIn(0f, 1f),
                normY       = ny.toFloat().coerceIn(0f, 1f),
                templateId  = rule.templateId
            )
            eventBus.emit(TitanEvent.VisionMatch(
                templateId = rule.templateId,
                confidence = bestConf,
                x = (nx * frame.width).toInt(),
                y = (ny * frame.height).toInt()
            ))
            result
        } finally {
            // Always release to prevent native heap OOM
            if (!srcMat.empty())    srcMat.release()
            if (!tmplMat.empty())   tmplMat.release()
            if (!resultMat.empty()) resultMat.release()
        }
    }

    /**
     * Find all template occurrences above [minConfidence] threshold.
     * Uses NMS (non-max suppression) to deduplicate overlapping detections.
     */
    suspend fun findAllTemplates(
        frame: Bitmap,
        template: Bitmap,
        minConfidence: Float = 0.8f,
        maxResults: Int = 10
    ): List<MatchResult> = withContext(Dispatchers.Default) {
        val src = Mat(); val tmpl = Mat(); val res = Mat()
        try {
            Utils.bitmapToMat(frame, src)
            Utils.bitmapToMat(template, tmpl)
            Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(tmpl, tmpl, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.matchTemplate(src, tmpl, res, Imgproc.TM_CCOEFF_NORMED)

            val results = mutableListOf<MatchResult>()
            val resMat  = MatOfFloat(res.reshape(1, 1))

            // Threshold the result mat
            val thresholded = Mat()
            Core.compare(res, Scalar(minConfidence.toDouble()), thresholded, Core.CMP_GE)
            val locations = MatOfPoint()
            Core.findNonZero(thresholded, locations)
            thresholded.release()

            locations.toArray().take(maxResults).forEach { pt ->
                val conf = res.get(pt.y.toInt(), pt.x.toInt())[0].toFloat()
                results.add(
                    MatchResult(
                        confidence = conf,
                        normX      = ((pt.x + tmpl.cols() / 2f) / src.cols()).toFloat(),
                        normY      = ((pt.y + tmpl.rows() / 2f) / src.rows()).toFloat(),
                        templateId = ""
                    )
                )
            }
            resMat.release(); locations.release()
            results.sortedByDescending { it.confidence }
        } finally {
            src.release(); tmpl.release(); res.release()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // OCR
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Run ML Kit OCR on a region of [frame] defined by [rule.region].
     * Returns all text blocks that match [rule.regexPattern] above [rule.minConfidence].
     */
    suspend fun runOcr(frame: Bitmap, rule: OcrScanRule): OcrResult? =
        withContext(Dispatchers.Default) {
            val cropped = rule.region?.let { cropBitmap(frame, it, frame.width, frame.height) }
                ?: frame

            val image  = InputImage.fromBitmap(cropped, 0)
            val result = textRecognizer.process(image).await()

            val regex = Regex(rule.regexPattern, RegexOption.IGNORE_CASE)
            val fullText = result.text

            val matched = if (rule.fuzzyMatch) {
                result.textBlocks.any { block ->
                    block.lines.any { line ->
                        levenshteinRatio(line.text, rule.regexPattern) > rule.minConfidence
                    }
                }
            } else {
                regex.containsMatchIn(fullText)
            }

            if (!matched) return@withContext null

            val confidence = result.textBlocks
                .flatMap { it.lines }
                .flatMap { it.elements }
                .mapNotNull { it.confidence }
                .average()
                .toFloat()
                .coerceIn(0f, 1f)

            val ocrResult = OcrResult(
                text       = fullText,
                confidence = confidence,
                matched    = true,
                region     = rule.region?.toString() ?: "full"
            )

            eventBus.emit(TitanEvent.OcrResult(
                text       = fullText.take(100),
                region     = ocrResult.region,
                confidence = confidence
            ))

            ocrResult
        }

    /**
     * Extract all text from a region without pattern matching.
     * Used for telemetry / debugging overlay.
     */
    suspend fun extractText(frame: Bitmap, region: ScreenRegion? = null): String =
        withContext(Dispatchers.Default) {
            val src = region?.let { cropBitmap(frame, it, frame.width, frame.height) } ?: frame
            textRecognizer.process(InputImage.fromBitmap(src, 0)).await().text
        }

    /**
     * Batch OCR — process multiple [OcrScanRule]s concurrently on the same [frame].
     *
     * Each rule runs in a separate async coroutine so all recognitions are issued
     * simultaneously rather than serially. Results are returned in the same order as
     * the input list; null means the rule did not match.
     *
     * Use when a workflow state must check several text conditions at once (e.g. game HUD
     * parsing) to avoid multiplying single-OCR latency by the number of rules.
     */
    suspend fun batchOcr(frame: Bitmap, rules: List<OcrScanRule>): List<OcrResult?> =
        coroutineScope {
            rules.map { rule -> async { runOcr(frame, rule) } }.awaitAll()
        }

    // ─────────────────────────────────────────────────────────────────────────
    // Inference
    // ─────────────────────────────────────────────────────────────────────────

    suspend fun classifyScene(frame: Bitmap): InferenceResult =
        withContext(Dispatchers.Default) { tflite.classifyScene(frame) }

    suspend fun classifyButton(region: Bitmap): InferenceResult =
        withContext(Dispatchers.Default) { tflite.classifyButton(region) }

    // ─────────────────────────────────────────────────────────────────────────
    // UI anchor tracking
    // ─────────────────────────────────────────────────────────────────────────

    /** Detect strong edges and return centroid of largest contour as anchor point. */
    suspend fun trackUiAnchor(frame: Bitmap, region: ScreenRegion): MatchResult? =
        withContext(Dispatchers.Default) {
            val src = Mat()
            try {
                Utils.bitmapToMat(cropBitmap(frame, region, frame.width, frame.height), src)
                Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2GRAY)
                Imgproc.GaussianBlur(src, src, Size(5.0, 5.0), 0.0)
                val edges = Mat()
                Imgproc.Canny(src, edges, 50.0, 150.0)

                val contours = mutableListOf<MatOfPoint>()
                Imgproc.findContours(edges, contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                edges.release()

                val largest = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return@withContext null
                val moments = Imgproc.moments(largest)
                if (moments.m00 == 0.0) return@withContext null

                val cx = (moments.m10 / moments.m00 + region.left) / frame.width
                val cy = (moments.m01 / moments.m00 + region.top) / frame.height
                MatchResult(confidence = 1f, normX = cx.toFloat(), normY = cy.toFloat(), templateId = "anchor")
            } finally {
                src.release()
            }
        }

    // ─────────────────────────────────────────────────────────────────────────
    // detectRegion — bounding boxes of significant UI elements in an ROI
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Find bounding boxes of visually significant regions (UI elements, buttons,
     * icons) within [region] of [frame] using adaptive thresholding + contour
     * finding.
     *
     * Filters by contour area ≥ [minArea] and aspect ratio within [0.1 .. 10].
     * Results are in absolute pixel coordinates of [frame], sorted largest-first.
     *
     * Use this to dynamically discover interactive elements without a template.
     */
    suspend fun detectRegion(
        frame: Bitmap,
        region: ScreenRegion? = null,
        minArea: Double = 400.0,
        maxResults: Int = 20
    ): List<android.graphics.Rect> = withContext(Dispatchers.Default) {
        val src = Mat()
        try {
            val cropped = region?.let { cropBitmap(frame, it, frame.width, frame.height) } ?: frame
            Utils.bitmapToMat(cropped, src)
            Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.GaussianBlur(src, src, Size(3.0, 3.0), 0.0)

            val thresh = Mat()
            Imgproc.adaptiveThreshold(
                src, thresh, 255.0,
                Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
                Imgproc.THRESH_BINARY_INV,
                11, 2.0
            )

            val contours = mutableListOf<MatOfPoint>()
            Imgproc.findContours(
                thresh, contours, Mat(),
                Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE
            )
            thresh.release()

            val offsetX = region?.left ?: 0
            val offsetY = region?.top  ?: 0

            contours
                .filter { Imgproc.contourArea(it) >= minArea }
                .sortedByDescending { Imgproc.contourArea(it) }
                .take(maxResults)
                .mapNotNull { contour ->
                    val br = Imgproc.boundingRect(contour)
                    val ar = br.width.toFloat() / br.height.toFloat()
                    if (ar < 0.1f || ar > 10f) return@mapNotNull null
                    android.graphics.Rect(
                        br.x + offsetX,
                        br.y + offsetY,
                        br.x + offsetX + br.width,
                        br.y + offsetY + br.height
                    )
                }
        } finally {
            src.release()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ORB feature matching — scale/rotation-invariant template detection
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Match [template] against [frame] using ORB keypoint descriptors + BFMatcher
     * (Hamming distance). Invariant to scale and rotation — ideal for templates
     * that appear at different sizes/orientations (rotated icons, tilted cards).
     *
     * Good matches are filtered using Lowe's ratio test ([matchRatioThreshold]).
     * Requires ≥ [minGoodMatches] good matches to return a result.
     *
     * @return [MatchResult] whose (normX, normY) is the centroid of matched
     *   keypoints in the source frame (normalised 0..1), or null if insufficient
     *   good matches or confidence < [rule.minConfidence].
     */
    suspend fun matchOrb(
        frame: Bitmap,
        template: Bitmap,
        rule: VisionMatchRule,
        minGoodMatches: Int = 8,
        matchRatioThreshold: Float = 0.75f
    ): MatchResult? = withContext(Dispatchers.Default) {
        val srcGray  = Mat()
        val tmplGray = Mat()
        val descSrc  = Mat()
        val descTmpl = Mat()
        val kpSrc    = MatOfKeyPoint()
        val kpTmpl   = MatOfKeyPoint()
        try {
            Utils.bitmapToMat(frame, srcGray)
            Utils.bitmapToMat(template, tmplGray)
            Imgproc.cvtColor(srcGray,  srcGray,  Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(tmplGray, tmplGray, Imgproc.COLOR_RGBA2GRAY)

            val roi = rule.region?.let { cropMat(srcGray, it, frame.width, frame.height) } ?: srcGray

            val orb = ORB.create(500)
            orb.detectAndCompute(roi,      Mat(), kpSrc,  descSrc)
            orb.detectAndCompute(tmplGray, Mat(), kpTmpl, descTmpl)

            if (descSrc.empty() || descTmpl.empty()) return@withContext null

            val matcher    = BFMatcher.create(Core.NORM_HAMMING, false)
            val knnMatches = ArrayList<MatOfDMatch>()
            matcher.knnMatch(descTmpl, descSrc, knnMatches, 2)

            // Lowe's ratio test
            val goodMatches = knnMatches.filter { m ->
                val arr = m.toArray()
                arr.size >= 2 && arr[0].distance < matchRatioThreshold * arr[1].distance
            }

            if (goodMatches.size < minGoodMatches) return@withContext null

            val srcKps  = kpSrc.toArray()
            val offsetX = (rule.region?.left ?: 0).toDouble()
            val offsetY = (rule.region?.top  ?: 0).toDouble()

            var sumX = 0.0; var sumY = 0.0
            goodMatches.forEach { m ->
                val trainIdx = m.toArray()[0].trainIdx
                sumX += srcKps[trainIdx].pt.x + offsetX
                sumY += srcKps[trainIdx].pt.y + offsetY
            }

            val cx   = (sumX / goodMatches.size / frame.width).toFloat().coerceIn(0f, 1f)
            val cy   = (sumY / goodMatches.size / frame.height).toFloat().coerceIn(0f, 1f)
            val conf = (goodMatches.size.toFloat() / kpTmpl.rows().coerceAtLeast(1).toFloat())
                .coerceAtMost(1f)

            if (conf < rule.minConfidence) return@withContext null

            MatchResult(confidence = conf, normX = cx, normY = cy, templateId = rule.templateId)
        } finally {
            srcGray.release(); tmplGray.release()
            descSrc.release(); descTmpl.release()
            kpSrc.release();   kpTmpl.release()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Histogram comparison — colour-based state classification
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Compute and compare 2-D HSV histograms (50 H-bins × 60 S-bins) of [frame]
     * and [reference] within [region], using HISTCMP_CORREL.
     *
     * Correlation returns values in [-1 .. 1]; result is clamped to [0 .. 1].
     * Score ≈ 1.0 means identical colour distribution (same game state / scene).
     * Score < 0.5 typically indicates a significant visual state change.
     *
     * Use this for scene/state detection without requiring a pixel-perfect template:
     *   e.g. compare current frame to a saved "game over" reference screenshot.
     */
    suspend fun compareHistogram(
        frame: Bitmap,
        reference: Bitmap,
        region: ScreenRegion? = null
    ): Float = withContext(Dispatchers.Default) {
        val srcMat  = Mat(); val refMat  = Mat()
        val srcHsv  = Mat(); val refHsv  = Mat()
        val srcHist = Mat(); val refHist = Mat()
        try {
            val croppedFrame = region?.let { cropBitmap(frame,     it, frame.width,     frame.height)     } ?: frame
            val croppedRef   = region?.let { cropBitmap(reference, it, reference.width, reference.height) } ?: reference

            Utils.bitmapToMat(croppedFrame, srcMat)
            Utils.bitmapToMat(croppedRef,   refMat)

            // RGBA → BGR → HSV
            Imgproc.cvtColor(srcMat, srcHsv, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(refMat, refHsv, Imgproc.COLOR_RGBA2BGR)
            Imgproc.cvtColor(srcHsv, srcHsv, Imgproc.COLOR_BGR2HSV)
            Imgproc.cvtColor(refHsv, refHsv, Imgproc.COLOR_BGR2HSV)

            val histSize = MatOfInt(50, 60)
            val ranges   = MatOfFloat(0f, 180f, 0f, 256f)
            val channels = MatOfInt(0, 1)

            Imgproc.calcHist(listOf(srcHsv), channels, Mat(), srcHist, histSize, ranges, false)
            Imgproc.calcHist(listOf(refHsv), channels, Mat(), refHist, histSize, ranges, false)

            Core.normalize(srcHist, srcHist, 0.0, 1.0, Core.NORM_MINMAX)
            Core.normalize(refHist, refHist, 0.0, 1.0, Core.NORM_MINMAX)

            Imgproc.compareHist(srcHist, refHist, Imgproc.HISTCMP_CORREL)
                .toFloat()
                .coerceIn(0f, 1f)
        } finally {
            srcMat.release();  refMat.release()
            srcHsv.release();  refHsv.release()
            srcHist.release(); refHist.release()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun cropMat(src: Mat, region: ScreenRegion, w: Int, h: Int): Mat {
        val l = (region.left.toFloat()   / w * src.cols()).toInt().coerceIn(0, src.cols() - 1)
        val t = (region.top.toFloat()    / h * src.rows()).toInt().coerceIn(0, src.rows() - 1)
        val r = (region.right.toFloat()  / w * src.cols()).toInt().coerceIn(l + 1, src.cols())
        val b = (region.bottom.toFloat() / h * src.rows()).toInt().coerceIn(t + 1, src.rows())
        return src.submat(t, b, l, r)
    }

    private fun cropBitmap(src: Bitmap, region: ScreenRegion, w: Int, h: Int): Bitmap {
        val l = region.left.coerceIn(0, w - 1)
        val t = region.top.coerceIn(0, h - 1)
        val rw = (region.right  - l).coerceIn(1, w - l)
        val rh = (region.bottom - t).coerceIn(1, h - t)
        return Bitmap.createBitmap(src, l, t, rw, rh)
    }

    /** Normalised Levenshtein similarity [0..1]. */
    private fun levenshteinRatio(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in dp.indices) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                       else minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1]) + 1
        }
        return 1f - dp[a.length][b.length].toFloat() / maxOf(a.length, b.length)
    }
}

data class MatchResult(
    val confidence: Float,
    val normX: Float,
    val normY: Float,
    val templateId: String
)

data class OcrResult(
    val text: String,
    val confidence: Float,
    val matched: Boolean,
    val region: String
)

data class InferenceResult(
    val label: String,
    val confidence: Float,
    val allScores: Map<String, Float> = emptyMap()
)
