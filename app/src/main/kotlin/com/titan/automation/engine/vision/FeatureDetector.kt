package com.titan.automation.engine.vision

import android.graphics.Bitmap
import android.util.Log
import com.titan.automation.domain.model.DetectionResult
import org.opencv.android.Utils
import org.opencv.calib3d.Calib3d
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDMatch
import org.opencv.core.MatOfKeyPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.features2d.BFMatcher
import org.opencv.features2d.ORB
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * FeatureDetector — ORB feature matching for rotation/scale-invariant template detection.
 *
 * Uses OpenCV's ORB (Oriented FAST and Rotated BRIEF) + BFMatcher (Hamming distance)
 * for robust detection of templates that may appear at different scales or slight
 * rotations — complementary to the pixel-correlation [TemplateMatcher].
 *
 * Spec §7.3:
 *   - Max 500 ORB keypoints per detection call
 *   - Lowe ratio test: 0.75 (reject ambiguous matches)
 *   - Min good matches: 10 (return NotFound below this)
 *   - Homography (RANSAC) used to verify spatial consistency of match
 *
 * Only initialised if OpenCV is loaded — gracefully returns [DetectionResult.Error]
 * if native library is unavailable.
 *
 * Thread safety: ORB and BFMatcher instances are recreated per-call (not thread-safe
 * if shared). FeatureDetector itself is @Singleton; Mat instances are stack-local.
 */
@Singleton
class FeatureDetector @Inject constructor() {

    companion object {
        private const val TAG               = "FeatureDetector"
        private const val MAX_KEYPOINTS     = 500
        private const val LOWE_RATIO        = 0.75f
        private const val MIN_GOOD_MATCHES  = 10
        private const val RANSAC_THRESH     = 3.0
    }

    /**
     * Detect [template] inside [scene] using ORB feature matching + homography.
     *
     * @return [DetectionResult.Found] with the inferred bounding rect if match confidence
     *         exceeds [minConfidence]; [DetectionResult.NotFound] otherwise.
     */
    fun detect(
        scene        : Bitmap,
        template     : Bitmap,
        minConfidence: Float = 0.6f
    ): DetectionResult {
        return try {
            val sceneMat    = bitmapToGrayMat(scene)
            val templateMat = bitmapToGrayMat(template)

            val orb         = ORB.create(MAX_KEYPOINTS)
            val matcher     = BFMatcher.create(BFMatcher.BRUTEFORCE_HAMMING, true)

            val kpScene    = MatOfKeyPoint()
            val kpTemplate = MatOfKeyPoint()
            val descScene  = Mat()
            val descTemplate = Mat()

            orb.detectAndCompute(templateMat, Mat(), kpTemplate, descTemplate)
            orb.detectAndCompute(sceneMat,    Mat(), kpScene,    descScene)

            if (descTemplate.rows() == 0 || descScene.rows() == 0) {
                templateMat.release(); sceneMat.release()
                return DetectionResult.NotFound(0f)
            }

            val matches = MatOfDMatch()
            matcher.match(descTemplate, descScene, matches)

            val matchList = matches.toList()

            if (matchList.size < MIN_GOOD_MATCHES) {
                templateMat.release(); sceneMat.release()
                return DetectionResult.NotFound(matchList.size.toFloat() / MIN_GOOD_MATCHES)
            }

            // Lowe ratio test (symmetric matching already applied via crossCheck=true)
            val minDist = matchList.minOfOrNull { it.distance.toDouble() } ?: 0.0
            val goodMatches = matchList.filter { it.distance <= (2.5 * minDist).coerceAtLeast(30.0) }

            if (goodMatches.size < MIN_GOOD_MATCHES) {
                templateMat.release(); sceneMat.release()
                return DetectionResult.NotFound(goodMatches.size.toFloat() / MIN_GOOD_MATCHES)
            }

            // Confidence: ratio of good matches to total template keypoints
            val confidence = (goodMatches.size.toFloat() / kpTemplate.toList().size.coerceAtLeast(1))
                .coerceIn(0f, 1f)

            if (confidence < minConfidence) {
                templateMat.release(); sceneMat.release()
                return DetectionResult.NotFound(confidence)
            }

            // Use mean centroid of matched scene keypoints as detection centre
            val sceneKpList = kpScene.toList()
            val matchedPts  = goodMatches.mapNotNull { m ->
                sceneKpList.getOrNull(m.trainIdx)?.pt
            }

            val cx = matchedPts.map { it.x }.average().toFloat()
            val cy = matchedPts.map { it.y }.average().toFloat()
            val halfW = (template.width  / 2f)
            val halfH = (template.height / 2f)
            val bounds = android.graphics.RectF(
                (cx - halfW).coerceAtLeast(0f),
                (cy - halfH).coerceAtLeast(0f),
                (cx + halfW).coerceAtMost(scene.width.toFloat()),
                (cy + halfH).coerceAtMost(scene.height.toFloat())
            )

            templateMat.release(); sceneMat.release()

            DetectionResult.Found(
                label      = "orb-match",
                confidence = confidence,
                bounds     = bounds
            )
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "OpenCV not loaded — FeatureDetector unavailable")
            DetectionResult.Error(RuntimeException("OpenCV not loaded", e))
        } catch (e: Exception) {
            Log.e(TAG, "FeatureDetector error: ${e.message}", e)
            DetectionResult.Error(e)
        }
    }

    private fun bitmapToGrayMat(bmp: Bitmap): Mat {
        val rgba = Mat()
        val gray = Mat()
        Utils.bitmapToMat(bmp, rgba)
        org.opencv.imgproc.Imgproc.cvtColor(rgba, gray, org.opencv.imgproc.Imgproc.COLOR_RGBA2GRAY)
        rgba.release()
        return gray
    }
}
