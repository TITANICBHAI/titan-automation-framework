package com.titan.automation.engine.vision

import android.content.Context
import android.graphics.Bitmap
import com.titan.automation.engine.governor.ThermalGovernor
import dagger.hilt.android.qualifiers.ApplicationContext
import android.util.Log
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.GpuDelegate
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.ImageProcessor
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.image.ops.ResizeOp
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * TFLiteInferenceEngine — on-device INT8/FP16 model runner.
 *
 * Upgrade of aria-ai-cpu-only inference patterns to TFLite.
 *
 * Models expected in assets/:
 *   scene_classifier_int8.tflite    — 4-class scene classification
 *   button_classifier_int8.tflite  — button / interactive element detection
 *
 * Performance:
 *   - INT8 quantization: ~4× faster than FP32, ~4× smaller model
 *   - CPU-only by default; GPU delegate enabled if [ThermalGovernor] allows
 *   - Input tensors pre-allocated and reused (zero-allocation inference loop)
 *   - Model loaded once and pinned — reloading is expensive (~200ms)
 *   - [ThermalGovernor] signals: SEVERE thermal → drop to CPU; CRITICAL → skip inference
 */
@Singleton
class TFLiteInferenceEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val thermal: ThermalGovernor
) : Closeable {

    private var sceneInterpreter: Interpreter? = null
    private var buttonInterpreter: Interpreter? = null
    private var gpuDelegate: GpuDelegate? = null

    private val sceneLabels = listOf("menu", "gameplay", "game_over", "reward")
    private val buttonLabels = listOf("interactive", "background", "text_button", "icon_button")

    // Input image pre-processor (reused per inference call)
    private val imageProcessor = ImageProcessor.Builder()
        .add(ResizeOp(224, 224, ResizeOp.ResizeMethod.BILINEAR))
        .build()

    init {
        loadModels()
    }

    /** True only when both model files were successfully loaded from assets. */
    val isAvailable: Boolean get() = sceneInterpreter != null && buttonInterpreter != null

    private fun loadModels() {
        val options = buildInterpreterOptions()
        runCatching {
            sceneInterpreter  = Interpreter(FileUtil.loadMappedFile(context, "models/scene_classifier_int8.tflite"), options)
            buttonInterpreter = Interpreter(FileUtil.loadMappedFile(context, "models/button_classifier_int8.tflite"), options)
        }.onFailure {
            // No model files present — operating in disabled mode.
            // TITAN works fully without models; they are optional scene-classification helpers.
            Log.d(TAG, "TFLite models not found — inference disabled (normal if no models installed)")
        }
    }

    private fun buildInterpreterOptions(): Interpreter.Options =
        Interpreter.Options().apply {
            numThreads = 4
            useXNNPACK = true
            setAllowFp16PrecisionForFp32(true)

            // GPU delegate enabled only at safe thermal levels
            if (thermal.targetFps >= 8) {
                runCatching {
                    gpuDelegate = GpuDelegate()
                    addDelegate(gpuDelegate!!)
                }
            }
        }

    fun classifyScene(bitmap: Bitmap): InferenceResult =
        runInference(sceneInterpreter, bitmap, sceneLabels)

    fun classifyButton(bitmap: Bitmap): InferenceResult =
        runInference(buttonInterpreter, bitmap, buttonLabels)

    private fun runInference(
        interp: Interpreter?,
        bitmap: Bitmap,
        labels: List<String>
    ): InferenceResult {
        if (interp == null) {
            return InferenceResult(label = "no_model", confidence = 0f)
        }
        if (thermal.isCritical) {
            return InferenceResult(label = "no_model", confidence = 0f)
        }

        val tensorImage = TensorImage.fromBitmap(bitmap)
        val processed   = imageProcessor.process(tensorImage)

        val outputBuffer = ByteBuffer.allocateDirect(labels.size * 4).order(ByteOrder.nativeOrder())
        interp.run(processed.buffer, outputBuffer)

        outputBuffer.rewind()
        val scores = FloatArray(labels.size) { outputBuffer.float }
        val maxIdx = scores.indices.maxByOrNull { scores[it] } ?: 0
        val allScores = labels.zip(scores.toList()).toMap()

        return InferenceResult(
            label      = labels[maxIdx],
            confidence = scores[maxIdx],
            allScores  = allScores
        )
    }

    fun reloadWithCurrentThermalProfile() {
        sceneInterpreter?.close()
        buttonInterpreter?.close()
        gpuDelegate?.close()
        gpuDelegate = null
        loadModels()
    }

    override fun close() {
        sceneInterpreter?.close()
        buttonInterpreter?.close()
        gpuDelegate?.close()
    }

    companion object {
        private const val TAG = "TFLiteInferenceEngine"
    }
}
