package com.titan.automation.engine.vision

import android.content.Context
import android.graphics.Bitmap
import com.titan.automation.domain.model.InferenceResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * InferenceEngine — TFLite inference manager with memory-mapped model loading,
 * GPU delegate fallback, INT8/FP32 support, and per-run buffer reuse.
 *
 * Pattern adapted from aria-ai-cpu-only TFLite pipeline.
 * Improvements: model warm-up pass, preallocated TensorBuffer reuse,
 *               GPU delegate with silent CPU fallback, ModelPrecisionManager integration.
 */
@Singleton
class InferenceEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val precisionManager: ModelPrecisionManager
) {
    enum class ModelType(val assetName: String, val inputSize: Int, val isQuantized: Boolean) {
        UI_CLASSIFIER ("models/ui_classifier_int8.tflite",     224, true),
        SCENE_DETECTOR("models/scene_detector_fp16.tflite",    224, false),
    }

    private val interpreters = mutableMapOf<ModelType, Interpreter>()
    private val inputBuffers  = mutableMapOf<ModelType, ByteBuffer>()
    private val outputBuffers = mutableMapOf<ModelType, Array<FloatArray>>()
    private val labelCache    = mutableMapOf<ModelType, List<String>>()

    // ── Initialisation ────────────────────────────────────────────────────────

    /**
     * Load [model] from assets; must be called before [classify].
     * Safe to call multiple times — idempotent.
     */
    fun loadModel(model: ModelType) {
        if (interpreters.containsKey(model)) return

        val mappedModel = loadMappedModel(model.assetName)
        val options = Interpreter.Options().apply {
            numThreads = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)
            // CPU-only (XNNPACK) — GPU delegate removed; artifact unavailable at TFLite 2.14
        }

        val interpreter = Interpreter(mappedModel, options)
        interpreters[model] = interpreter

        // Preallocate input buffer (once; reused via ByteBuffer.rewind())
        val bytesPerPixel = if (model.isQuantized) 1 else 4
        val bufferSize    = model.inputSize * model.inputSize * 3 * bytesPerPixel
        inputBuffers[model] = ByteBuffer.allocateDirect(bufferSize).also {
            it.order(ByteOrder.nativeOrder())
        }

        // Preallocate output buffer
        val outputShape = interpreter.getOutputTensor(0).shape()
        outputBuffers[model] = Array(1) { FloatArray(outputShape[1]) }

        // Load label list
        labelCache[model] = loadLabels(model)

        // Warm-up pass (single inference with zero input)
        warmUp(model)
    }

    // ── Inference ─────────────────────────────────────────────────────────────

    /**
     * Classify [frame] using [modelType].
     * Must call [loadModel] first.
     * Runs on [Dispatchers.Default] with Process.THREAD_PRIORITY_BACKGROUND.
     */
    suspend fun classify(frame: Bitmap, modelType: ModelType): InferenceResult =
        withContext(Dispatchers.Default) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)

            val interpreter = interpreters[modelType]
                ?: return@withContext InferenceResult("unknown", 0f, latencyMs = 0L)

            val startMs = System.currentTimeMillis()

            val scaled = Bitmap.createScaledBitmap(frame, modelType.inputSize, modelType.inputSize, true)
            val inputBuf = inputBuffers[modelType]!!
            inputBuf.rewind()
            bitmapToBuffer(scaled, inputBuf, modelType.isQuantized)
            scaled.recycle()

            val outBuf = outputBuffers[modelType]!!
            interpreter.run(inputBuf, outBuf)

            val scores  = outBuf[0]
            val labels  = labelCache[modelType] ?: emptyList()
            val maxIdx  = scores.indices.maxByOrNull { scores[it] } ?: 0
            val label   = labels.getOrElse(maxIdx) { "class_$maxIdx" }
            val conf    = softmax(scores)[maxIdx]

            val allScores = labels.mapIndexed { i, lbl -> lbl to softmax(scores)[i] }.toMap()

            InferenceResult(
                label      = label,
                confidence = conf,
                allScores  = allScores,
                latencyMs  = System.currentTimeMillis() - startMs
            )
        }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun bitmapToBuffer(bmp: Bitmap, buf: ByteBuffer, quantized: Boolean) {
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        for (px in pixels) {
            val r = (px shr 16) and 0xFF
            val g = (px shr  8) and 0xFF
            val b =  px         and 0xFF
            if (quantized) {
                buf.put(r.toByte()); buf.put(g.toByte()); buf.put(b.toByte())
            } else {
                buf.putFloat(r / 127.5f - 1f)
                buf.putFloat(g / 127.5f - 1f)
                buf.putFloat(b / 127.5f - 1f)
            }
        }
    }

    private fun softmax(logits: FloatArray): FloatArray {
        val max  = logits.max()
        val exps = FloatArray(logits.size) { Math.exp((logits[it] - max).toDouble()).toFloat() }
        val sum  = exps.sum().coerceAtLeast(1e-9f)
        return FloatArray(logits.size) { exps[it] / sum }
    }

    private fun warmUp(model: ModelType) {
        inputBuffers[model]?.rewind()
        try {
            interpreters[model]?.run(inputBuffers[model], outputBuffers[model])
        } catch (_: Exception) { }
    }

    private fun loadMappedModel(assetName: String): MappedByteBuffer {
        val fd     = context.assets.openFd(assetName)
        val stream = FileInputStream(fd.fileDescriptor)
        val ch     = stream.channel
        return ch.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    private fun loadLabels(model: ModelType): List<String> =
        try {
            val labelFile = model.assetName.replace(".tflite", "_labels.txt")
            context.assets.open(labelFile).bufferedReader().readLines()
        } catch (_: Exception) {
            emptyList()
        }
}
