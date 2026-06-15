package com.titan.automation.engine.capture

import android.hardware.HardwareBuffer
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * NativeBridge — JNI entry point for the titan_jni.so native library.
 *
 * Provides two native operations:
 *
 *   1. [copyHardwareBufferToByteArray] — zero-copy AHardwareBuffer → ByteArray
 *      (API 31+). Used by [ScreenCaptureService] on high-end devices to avoid
 *      JVM Bitmap allocation in the hot capture path.
 *
 *   2. [matchTemplate] — native OpenCV template matching via cv::matchTemplate.
 *      Only available if the build was compiled with HAVE_OPENCV=1 and
 *      opencv.aar is present. Falls back gracefully to the Kotlin VisionEngine
 *      path if the native method returns null.
 *
 * Availability:
 *   - [nativeAvailable] is false until [loadLibrary] succeeds.
 *   - Callers must always check [nativeAvailable] before using native APIs.
 *   - On API < 31 [copyHardwareBufferToByteArray] always returns null.
 *
 * Architecture filter: armeabi-v7a + arm64-v8a only (gradle ABI filter).
 * The native library is not built for x86/x86_64.
 */
object NativeBridge {

    private const val TAG = "NativeBridge"

    @Volatile
    var nativeAvailable: Boolean = false
        private set

    @Volatile
    var opencvNativeAvailable: Boolean = false
        private set

    /**
     * Call once from [TitanApplication.onCreate] on a background thread.
     * Failing to call this (or a failed load) degrades gracefully to Kotlin paths.
     */
    fun loadLibrary() {
        try {
            System.loadLibrary("titan_jni")
            nativeAvailable = true
            Log.i(TAG, "titan_jni.so loaded successfully")

            // Probe OpenCV native method
            opencvNativeAvailable = probeOpenCVNative()
            Log.i(TAG, "OpenCV native: $opencvNativeAvailable")
        } catch (t: UnsatisfiedLinkError) {
            Log.w(TAG, "titan_jni.so not found — falling back to pure Kotlin paths: ${t.message}")
            nativeAvailable = false
        } catch (t: Throwable) {
            Log.e(TAG, "Native library load error: ${t.message}")
            nativeAvailable = false
        }
    }

    private fun probeOpenCVNative(): Boolean = try {
        // Try calling matchTemplate with empty arrays — if it doesn't throw LinkError, OpenCV is linked
        matchTemplate(ByteArray(4), 1, 1, ByteArray(4), 1, 1, 0.5f)
        true
    } catch (e: UnsatisfiedLinkError) {
        false
    } catch (_: Exception) {
        true   // threw a non-link error → method exists but input was invalid
    }

    // ── JNI declarations ──────────────────────────────────────────────────────

    /**
     * Copy raw RGBA_8888 pixels from a [HardwareBuffer] to a [ByteArray].
     *
     * Zero-copy on API 31+: uses AHardwareBuffer_lock → memcpy → AHardwareBuffer_unlock.
     * Returns null on any failure (API < 31, lock failure, OOM).
     *
     * @param buffer  The HardwareBuffer acquired from ImageReader or AccessibilityService
     * @param width   Buffer width in pixels
     * @param height  Buffer height in pixels
     * @return RGBA_8888 byte array of length width*height*4, or null
     */
    @RequiresApi(Build.VERSION_CODES.S)
    external fun copyHardwareBufferToByteArray(
        buffer: HardwareBuffer,
        width : Int,
        height: Int
    ): ByteArray?

    /**
     * Run OpenCV cv::matchTemplate (TM_CCOEFF_NORMED) natively.
     *
     * @param frameRgba    Full frame RGBA_8888 bytes
     * @param frameW/H     Frame dimensions
     * @param tmplRgba     Template RGBA_8888 bytes
     * @param tmplW/H      Template dimensions
     * @param minConfidence Minimum confidence threshold [0..1]
     * @return FloatArray [confidence, centerX_norm, centerY_norm, w_norm, h_norm]
     *         or null if no match above threshold
     */
    external fun matchTemplate(
        frameRgba     : ByteArray,
        frameW        : Int,
        frameH        : Int,
        tmplRgba      : ByteArray,
        tmplW         : Int,
        tmplH         : Int,
        minConfidence : Float
    ): FloatArray?
}
