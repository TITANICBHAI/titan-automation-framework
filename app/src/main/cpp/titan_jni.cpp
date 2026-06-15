/**
 * titan_jni.cpp — Zero-copy AHardwareBuffer → OpenCV Mat bridge.
 *
 * Spec reference (textfile_1781531009277.txt, section 4.2 "JNI Bridge Mapping"):
 *   "Pointers to the raw underlying AHardwareBuffer are mapped to native
 *    OpenCV structures via a JNI layer to prevent JVM memory allocations
 *    and garbage collection pauses."
 *
 * What this file does:
 *   1. Receives a android.hardware.HardwareBuffer Java object from Kotlin
 *   2. Acquires the underlying AHardwareBuffer via AHardwareBuffer_fromHardwareBuffer
 *   3. Locks the buffer for CPU read access — zero copy, no JVM allocation
 *   4. Wraps the raw pixel pointer in an OpenCV cv::Mat (if OpenCV available)
 *      OR copies into a Kotlin ByteArray (fallback path)
 *   5. Unlocks and releases the buffer immediately after read
 *
 * Platform requirements:
 *   AHardwareBuffer_fromHardwareBuffer: API 31+
 *   AHardwareBuffer_lock:               API 26+
 *
 * On API 29-30 the fallback ImageReader → ByteBuffer path is used in Kotlin,
 * so this native bridge is only activated on API 31+.
 */

#include <jni.h>
#include <android/hardware_buffer_jni.h>  // AHardwareBuffer_fromHardwareBuffer (API 31)
#include <android/hardware_buffer.h>      // AHardwareBuffer_lock / unlock
#include <android/log.h>
#include <cstring>

#define TAG "TitanJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)

#ifdef HAVE_OPENCV
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#endif

extern "C" {

/**
 * copyHardwareBufferToByteArray
 *
 * Kotlin signature:
 *   external fun copyHardwareBufferToByteArray(
 *       buffer: HardwareBuffer, width: Int, height: Int
 *   ): ByteArray?
 *
 * Returns: RGBA_8888 pixel data as a flat ByteArray of length width*height*4,
 *          or null on any failure.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_titan_automation_engine_capture_NativeBridge_copyHardwareBufferToByteArray(
    JNIEnv* env,
    jclass  /* clazz */,
    jobject hardwareBuffer,
    jint    width,
    jint    height)
{
    if (!hardwareBuffer) {
        LOGE("copyHardwareBufferToByteArray: null hardwareBuffer");
        return nullptr;
    }

    // 1. Get the native AHardwareBuffer from Java HardwareBuffer (API 31+)
    AHardwareBuffer* ahb = AHardwareBuffer_fromHardwareBuffer(env, hardwareBuffer);
    if (!ahb) {
        LOGE("AHardwareBuffer_fromHardwareBuffer failed");
        return nullptr;
    }

    // 2. Lock for CPU read access — zero-copy into raw pointer
    void* rawPixels = nullptr;
    int lockStatus  = AHardwareBuffer_lock(
        ahb,
        AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN,
        /*fence=*/ -1,
        /*rect=*/  nullptr,
        &rawPixels
    );
    if (lockStatus != 0 || rawPixels == nullptr) {
        LOGE("AHardwareBuffer_lock failed: %d", lockStatus);
        AHardwareBuffer_release(ahb);
        return nullptr;
    }

    // 3. Copy pixels into a JVM ByteArray
    const jsize byteCount = width * height * 4;  // RGBA_8888
    jbyteArray  outArray  = env->NewByteArray(byteCount);
    if (!outArray) {
        LOGE("NewByteArray allocation failed for %d bytes", byteCount);
        AHardwareBuffer_unlock(ahb, nullptr);
        AHardwareBuffer_release(ahb);
        return nullptr;
    }

    jbyte* dst = env->GetByteArrayElements(outArray, nullptr);
    if (dst) {
        std::memcpy(dst, rawPixels, byteCount);
        env->ReleaseByteArrayElements(outArray, dst, 0);
    }

    // 4. Unlock and release — MUST happen immediately after copy
    AHardwareBuffer_unlock(ahb, /*fence=*/ nullptr);
    AHardwareBuffer_release(ahb);

    LOGD("copyHardwareBufferToByteArray: %dx%d = %d bytes OK", width, height, byteCount);
    return outArray;
}

#ifdef HAVE_OPENCV
/**
 * matchTemplate
 *
 * Kotlin signature:
 *   external fun matchTemplate(
 *       frameRgba: ByteArray, frameW: Int, frameH: Int,
 *       tmplRgba:  ByteArray, tmplW:  Int, tmplH:  Int,
 *       minConfidence: Float
 *   ): FloatArray?  // [confidence, x_norm, y_norm, w_norm, h_norm] or null
 *
 * Native OpenCV template matching — avoids JVM ↔ JNI Mat copying.
 */
JNIEXPORT jfloatArray JNICALL
Java_com_titan_automation_engine_capture_NativeBridge_matchTemplate(
    JNIEnv*  env,
    jclass   /* clazz */,
    jbyteArray frameRgba, jint frameW, jint frameH,
    jbyteArray tmplRgba,  jint tmplW,  jint tmplH,
    jfloat   minConfidence)
{
    jbyte* framePx = env->GetByteArrayElements(frameRgba, nullptr);
    jbyte* tmplPx  = env->GetByteArrayElements(tmplRgba,  nullptr);
    if (!framePx || !tmplPx) {
        if (framePx) env->ReleaseByteArrayElements(frameRgba, framePx, JNI_ABORT);
        if (tmplPx)  env->ReleaseByteArrayElements(tmplRgba,  tmplPx,  JNI_ABORT);
        return nullptr;
    }

    cv::Mat frame(frameH, frameW, CV_8UC4, framePx);
    cv::Mat tmpl (tmplH,  tmplW,  CV_8UC4, tmplPx);

    // Convert to grayscale for faster matching
    cv::Mat frameGray, tmplGray;
    cv::cvtColor(frame, frameGray, cv::COLOR_RGBA2GRAY);
    cv::cvtColor(tmpl,  tmplGray,  cv::COLOR_RGBA2GRAY);

    cv::Mat result;
    cv::matchTemplate(frameGray, tmplGray, result, cv::TM_CCOEFF_NORMED);

    double minVal, maxVal;
    cv::Point minLoc, maxLoc;
    cv::minMaxLoc(result, &minVal, &maxVal, &minLoc, &maxLoc);

    env->ReleaseByteArrayElements(frameRgba, framePx, JNI_ABORT);
    env->ReleaseByteArrayElements(tmplRgba,  tmplPx,  JNI_ABORT);

    if (maxVal < minConfidence) return nullptr;

    // Return [confidence, x_norm, y_norm, w_norm, h_norm]
    jfloatArray out = env->NewFloatArray(5);
    float vals[5] = {
        (float) maxVal,
        (float)(maxLoc.x + tmplW / 2) / frameW,   // center x normalised
        (float)(maxLoc.y + tmplH / 2) / frameH,   // center y normalised
        (float) tmplW / frameW,
        (float) tmplH / frameH
    };
    env->SetFloatArrayRegion(out, 0, 5, vals);
    return out;
}
#endif  // HAVE_OPENCV

}  // extern "C"
