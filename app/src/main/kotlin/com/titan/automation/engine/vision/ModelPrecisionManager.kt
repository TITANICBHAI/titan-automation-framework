package com.titan.automation.engine.vision

import android.util.Log
import com.titan.automation.engine.governor.ThermalGovernor
import com.titan.automation.engine.governor.ThermalLevel
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ModelPrecisionManager — adjusts TFLite model precision in response to thermal pressure.
 *
 * Degradation ladder:
 *   NORMAL / LIGHT  → use FP16 model (highest accuracy)
 *   MODERATE        → switch to INT8 model (faster, lower battery)
 *   SEVERE          → disable inference entirely, emit Warning
 *   CRITICAL        → emergency stop (MacroEngine.panicStop called via ThermalGovernor)
 *
 * Listens to [ThermalGovernor.thermalLevel] StateFlow so it reacts instantly to thermal
 * status changes without polling.
 *
 * Pattern adapted from aria-ai-cpu-only ModelPrecisionManager (thermal throttle response).
 */
@Singleton
class ModelPrecisionManager @Inject constructor(
    private val thermalGovernor: ThermalGovernor
) {
    enum class Precision { FP16, INT8, DISABLED }

    private val currentThermalLevel: ThermalLevel
        get() = thermalGovernor.state.value.thermalLevel

    /** Current target precision for the inference pipeline. */
    val currentPrecision: Precision
        get() = when (currentThermalLevel) {
            ThermalLevel.NORMAL,
            ThermalLevel.LIGHT    -> Precision.FP16
            ThermalLevel.MODERATE -> Precision.INT8
            ThermalLevel.SEVERE,
            ThermalLevel.CRITICAL -> Precision.DISABLED
        }

    /**
     * Returns true if inference should proceed at all.
     * False when thermal status is SEVERE or CRITICAL.
     */
    val inferenceAllowed: Boolean
        get() = currentPrecision != Precision.DISABLED

    /**
     * Choose the correct [InferenceEngine.ModelType] for the current precision.
     * Falls back to INT8 (ui_classifier) when FP16 scene_detector is unavailable.
     */
    fun selectModel(): InferenceEngine.ModelType = when (currentPrecision) {
        Precision.FP16     -> InferenceEngine.ModelType.SCENE_DETECTOR
        Precision.INT8     -> InferenceEngine.ModelType.UI_CLASSIFIER
        Precision.DISABLED -> InferenceEngine.ModelType.UI_CLASSIFIER  // won't be used
    }

    companion object { private const val TAG = "ModelPrecisionManager" }
}
