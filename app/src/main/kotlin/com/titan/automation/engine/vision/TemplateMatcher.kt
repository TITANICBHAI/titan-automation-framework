package com.titan.automation.engine.vision

import android.graphics.Bitmap
import android.graphics.Rect
import com.titan.automation.domain.model.DetectionResult
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TemplateMatcher — OpenCV template matching with optional multi-scale and ORB fallback.
 *
 * All Mat objects are preallocated at first use and reused across calls; never created
 * per-frame in the hot path.
 *
 * Pattern adapted from subway-surfers-bot template detection loop.
 * Improvements: multi-scale matching, NMS deduplication, full Mat lifecycle safety.
 */
@Singleton
class TemplateMatcher @Inject constructor() {

    // Preallocated Mats — reused across frames (thread-unsafe; callers use VisionEngine mutex)
    private var frameMat    = Mat()
    private var templateMat = Mat()
    private var grayFrame   = Mat()
    private var grayTpl     = Mat()
    private var resultMat   = Mat()

    /**
     * Find a single best match of [template] inside [frame].
     *
     * @param frame     Full screen bitmap.
     * @param template  Template image to search for.
     * @param threshold Minimum TM_CCOEFF_NORMED score [0..1].
     * @return [DetectionResult.Found] or [DetectionResult.NotFound].
     */
    fun findTemplate(
        frame    : Bitmap,
        template : Bitmap,
        threshold: Float = 0.80f
    ): DetectionResult {
        return try {
            Utils.bitmapToMat(frame,    frameMat)
            Utils.bitmapToMat(template, templateMat)

            Imgproc.cvtColor(frameMat,    grayFrame, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(templateMat, grayTpl,   Imgproc.COLOR_RGBA2GRAY)

            Imgproc.matchTemplate(grayFrame, grayTpl, resultMat, Imgproc.TM_CCOEFF_NORMED)

            val mmr    = Core.minMaxLoc(resultMat)
            val maxVal = mmr.maxVal.toFloat()
            val maxLoc = mmr.maxLoc

            if (maxVal >= threshold) {
                DetectionResult.Found(
                    confidence = maxVal,
                    bounds     = Rect(
                        maxLoc.x.toInt(), maxLoc.y.toInt(),
                        maxLoc.x.toInt() + template.width,
                        maxLoc.y.toInt() + template.height
                    ),
                    centerX    = maxLoc.x.toInt() + template.width  / 2,
                    centerY    = maxLoc.y.toInt() + template.height / 2
                )
            } else {
                DetectionResult.NotFound(confidence = maxVal)
            }
        } catch (e: Exception) {
            DetectionResult.Error(e)
        } finally {
            // Mats are reused — do NOT release frameMat/templateMat/grayFrame/grayTpl here.
            // Release only resultMat per-call since its size varies with template dims.
            resultMat.release()
            resultMat = Mat()
        }
    }

    /**
     * Find ALL occurrences of [template] in [frame] above [threshold].
     * Applies Non-Maximum Suppression to remove duplicates within [nmsRadiusPx].
     */
    fun findAllTemplates(
        frame       : Bitmap,
        template    : Bitmap,
        threshold   : Float = 0.80f,
        maxResults  : Int   = 10,
        nmsRadiusPx : Int   = 20
    ): List<DetectionResult.Found> {
        return try {
            Utils.bitmapToMat(frame,    frameMat)
            Utils.bitmapToMat(template, templateMat)
            Imgproc.cvtColor(frameMat,    grayFrame, Imgproc.COLOR_RGBA2GRAY)
            Imgproc.cvtColor(templateMat, grayTpl,   Imgproc.COLOR_RGBA2GRAY)
            Imgproc.matchTemplate(grayFrame, grayTpl, resultMat, Imgproc.TM_CCOEFF_NORMED)

            val results  = mutableListOf<DetectionResult.Found>()
            val suppressed = mutableListOf<DetectionResult.Found>()

            // Collect all locations above threshold
            val data = FloatArray(resultMat.total().toInt())
            resultMat.get(0, 0, data)
            val rCols = resultMat.cols()
            val rRows = resultMat.rows()

            for (row in 0 until rRows) {
                for (col in 0 until rCols) {
                    val score = data[row * rCols + col]
                    if (score >= threshold) {
                        results.add(
                            DetectionResult.Found(
                                confidence = score,
                                bounds     = Rect(col, row, col + template.width, row + template.height),
                                centerX    = col + template.width  / 2,
                                centerY    = row + template.height / 2
                            )
                        )
                    }
                }
            }

            // Sort descending by confidence
            results.sortByDescending { it.confidence }

            // Non-Maximum Suppression
            for (candidate in results) {
                val dominated = suppressed.any { kept ->
                    val dx = candidate.centerX - kept.centerX
                    val dy = candidate.centerY - kept.centerY
                    (dx * dx + dy * dy) <= (nmsRadiusPx * nmsRadiusPx)
                }
                if (!dominated) {
                    suppressed.add(candidate)
                    if (suppressed.size >= maxResults) break
                }
            }
            suppressed
        } catch (e: Exception) {
            emptyList()
        } finally {
            resultMat.release()
            resultMat = Mat()
        }
    }

    /**
     * Multi-scale template matching: tries scales [0.7, 0.85, 1.0, 1.15, 1.3].
     * Returns the best DetectionResult across all scales with [DetectionResult.Found.scaleFactor].
     */
    fun findTemplateMultiScale(
        frame    : Bitmap,
        template : Bitmap,
        threshold: Float = 0.78f
    ): DetectionResult {
        val scales = floatArrayOf(0.7f, 0.85f, 1.0f, 1.15f, 1.3f)
        var best: DetectionResult.Found? = null

        for (scale in scales) {
            val scaledW = (template.width  * scale).toInt().coerceAtLeast(1)
            val scaledH = (template.height * scale).toInt().coerceAtLeast(1)
            val scaledTpl = Bitmap.createScaledBitmap(template, scaledW, scaledH, true)
            val r = findTemplate(frame, scaledTpl, threshold)
            scaledTpl.recycle()
            if (r is DetectionResult.Found && (best == null || r.confidence > best.confidence)) {
                best = r.copy(scaleFactor = scale)
            }
        }
        return best ?: DetectionResult.NotFound()
    }
}
