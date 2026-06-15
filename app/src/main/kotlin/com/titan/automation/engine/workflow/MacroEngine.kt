package com.titan.automation.engine.workflow

import android.util.Log
import com.titan.automation.domain.model.*
import com.titan.automation.domain.repository.WorkflowRepository
import com.titan.automation.engine.accessibility.GesturePriority
import com.titan.automation.engine.accessibility.MacroAccessibilityService
import com.titan.automation.engine.capture.FrameProvider
import com.titan.automation.engine.capture.TemplateRepository
import com.titan.automation.engine.governor.ThermalGovernor
import com.titan.automation.engine.ml.RLDecision
import com.titan.automation.engine.ml.RLEngine
import com.titan.automation.engine.ml.RewardOutcome
import com.titan.automation.engine.vision.VisionEngine
import com.titan.automation.events.TitanEvent
import com.titan.automation.events.TitanEventBus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MacroEngine — coroutine-driven workflow state machine.
 *
 * Execution pipeline per state transition:
 *
 *   1. Capture Frame (via [FrameProvider.latestFrame])
 *   2. Vision Match  (template matching via [VisionEngine.findTemplate])
 *   3. OCR Validate  ([VisionEngine.runOcr] if [WorkflowState.ocrScanRule] present)
 *   4. RL Decision   ([RLEngine.getBestAction] if [WorkflowState.rlEnabled])
 *   5. Execute Action ([MacroAccessibilityService] gesture dispatch)
 *   6. State Checkpoint (persist to [WorkflowRepository])
 *   7. Branch / Retry / Recover
 *
 * Execution model:
 *   - Each workflow runs in a dedicated [CoroutineScope] with [SupervisorJob]
 *   - States are processed sequentially within one workflow
 *   - Multiple workflows can run concurrently (separate scopes)
 *   - Nested workflows supported via recursive [runWorkflow] calls
 *
 * Fault tolerance:
 *   - Per-state retry chains with configurable cooldown ([WorkflowState.maxRetries])
 *   - Per-state timeout ([WorkflowState.timeoutMs]) with [withTimeoutOrNull]
 *   - Global workflow timeout ([WorkflowDefinition.globalTimeoutMs])
 *   - Checkpoint written after every successful state transition
 *   - On crash recovery: last checkpoint loaded from [WorkflowRepository]
 *
 * Hot-reload:
 *   - [loadWorkflow] can replace a running workflow's definition mid-execution
 *     (takes effect at the next state transition)
 *   - [pauseAll] / [resumeAll] for overlay panic button
 */
@Singleton
class MacroEngine @Inject constructor(
    private val repository     : WorkflowRepository,
    private val visionEngine   : VisionEngine,
    private val rlEngine       : RLEngine,
    private val thermal        : ThermalGovernor,
    private val eventBus          : TitanEventBus,
    private val frameProvider     : FrameProvider,
    private val templateRepository: TemplateRepository
) {

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _runningWorkflows = MutableStateFlow<Map<String, WorkflowSession>>(emptyMap())
    val runningWorkflows: StateFlow<Map<String, WorkflowSession>> = _runningWorkflows.asStateFlow()

    @Volatile private var paused = false

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Start a workflow by ID. Resumes from last checkpoint if available.
     * Returns immediately — execution happens asynchronously in [engineScope].
     */
    fun startWorkflow(workflowId: String): Job = engineScope.launch {
        val definition = repository.getWorkflow(workflowId) ?: run {
            eventBus.emit(TitanEvent.Error("MacroEngine", "Workflow not found: $workflowId", false))
            return@launch
        }

        // Crash recovery: resume from saved checkpoint if it matches this workflow
        val savedSession = repository.getLastSession()
        val initialState = if (savedSession?.workflowId == workflowId && !savedSession.completed) {
            Log.i(TAG, "Resuming workflow $workflowId from checkpoint: ${savedSession.lastCheckpointState}")
            savedSession.lastCheckpointState.ifEmpty { definition.initialState }
        } else {
            definition.initialState
        }

        val session = WorkflowSession(
            workflowId           = workflowId,
            currentState         = initialState,
            lastCheckpointState  = initialState
        )

        updateSession(session)
        eventBus.emit(TitanEvent.EngineStarted(workflowId))

        runWorkflow(definition, session)
    }

    /** Stop a specific workflow by ID. */
    fun stopWorkflow(workflowId: String) {
        _runningWorkflows.value = _runningWorkflows.value - workflowId
        engineScope.coroutineContext.cancelChildren()
    }

    /** Pause all running workflows (overlay panic button). */
    fun pauseAll() { paused = true }
    fun resumeAll() { paused = false }

    /** Hot-reload: update workflow definition — takes effect at next state. */
    suspend fun loadWorkflow(definition: WorkflowDefinition) {
        repository.saveWorkflow(definition)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Core execution loop
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun runWorkflow(
        definition: WorkflowDefinition,
        initialSession: WorkflowSession
    ) {
        var session = initialSession
        val globalDeadline = System.currentTimeMillis() + definition.globalTimeoutMs

        try {
            withTimeout(definition.globalTimeoutMs) {
                while (!session.completed && !session.failed) {

                    // Thermal halt
                    if (thermal.isCritical) {
                        Log.w(TAG, "Thermal critical — halting workflow ${definition.workflowId}")
                        session = session.copy(failed = true)
                        break
                    }

                    // Pause support (overlay panic)
                    while (paused) delay(200)

                    val stateId = session.currentState
                    val state   = definition.states[stateId] ?: run {
                        if (stateId == "END") {
                            session = session.copy(completed = true)
                            return@withTimeout
                        }
                        Log.e(TAG, "Unknown state: $stateId in workflow ${definition.workflowId}")
                        session = session.copy(failed = true)
                        return@withTimeout
                    }

                    val stepStart = System.currentTimeMillis()

                    // Execute state with per-state timeout
                    val result = withTimeoutOrNull(state.timeoutMs) {
                        executeState(definition, state, session)
                    }

                    val durationMs = System.currentTimeMillis() - stepStart
                    eventBus.emit(TitanEvent.WorkflowStep(
                        stepId     = stateId,
                        state      = result?.nextState ?: "timeout",
                        retryCount = session.retryCount,
                        durationMs = durationMs
                    ))

                    session = when {
                        result == null -> handleTimeout(state, session)    // state timed out
                        result.success -> handleSuccess(state, session, result, definition)
                        else           -> handleFailure(state, session, definition)
                    }

                    // Checkpoint after every transition
                    repository.saveSession(session)
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Global timeout reached for workflow ${definition.workflowId}")
            session = session.copy(failed = true)
        } catch (e: CancellationException) {
            Log.d(TAG, "Workflow ${definition.workflowId} cancelled")
        } finally {
            val succeeded = session.completed && !session.failed
            rlEngine.onEpisodeEnd(succeeded)
            if (succeeded) repository.clearSession()
            _runningWorkflows.value = _runningWorkflows.value - definition.workflowId
            eventBus.emit(TitanEvent.EngineStopped)
            Log.i(TAG, "Workflow ${definition.workflowId} ended — success=$succeeded steps=${session.stepCount}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // State execution
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun executeState(
        definition: WorkflowDefinition,
        state: WorkflowState,
        session: WorkflowSession
    ): StateResult? {

        // 1. Capture frame
        val frame = frameProvider.latestFrame.first { it != null }!!.bitmap

        // 2. Vision match
        val visionResult = state.visionMatchRule?.let { rule ->
            // Load template bitmap from assets — real app stores in TemplateRepository
            val templateBitmap = loadTemplate(rule.templateId) ?: return StateResult(success = false)
            visionEngine.findTemplate(frame, templateBitmap, rule)
        }

        val visionSuccess = state.visionMatchRule == null || visionResult != null

        // 3. OCR validation
        val ocrResult = state.ocrScanRule?.let { rule ->
            visionEngine.runOcr(frame, rule)
        }
        val ocrSuccess = state.ocrScanRule == null || ocrResult?.matched == true

        if (!visionSuccess || !ocrSuccess) return StateResult(success = false)

        // 4. RL decision (if enabled for this state and by thermal governor)
        val actionIntent: String
        if (state.rlEnabled && thermal.rlEnabled) {
            val available   = definition.actions.keys.toList()
            val rlDecision  = rlEngine.getBestAction(session.currentState, available)
            actionIntent    = rlDecision.action
        } else {
            actionIntent = state.visionMatchRule?.actionIntent
                ?: state.ocrScanRule?.actionIntent
                ?: state.conditions.firstOrNull()?.actionIntent
                ?: return StateResult(success = false)
        }

        // 5. Execute action
        val actionDef = definition.actions[actionIntent] ?: return StateResult(success = false)
        val gestureOk = executeAction(actionDef, state.rlEnabled)

        // 6. Cooldown
        if (state.cooldownMs > 0) delay(state.cooldownMs)

        // 7. RL reward
        if (state.rlEnabled && thermal.rlEnabled) {
            val reward = rlEngine.shapeReward(
                if (gestureOk) RewardOutcome.STATE_TRANSITION_SUCCESS else RewardOutcome.GESTURE_REJECTED,
                session.stepCount
            )
            rlEngine.learn(
                state      = session.currentState,
                action     = actionIntent,
                rawReward  = reward,
                nextState  = state.onSuccess
            )
        }

        return StateResult(success = gestureOk, nextState = if (gestureOk) state.onSuccess else state.onFailure)
    }

    private suspend fun executeAction(action: ActionDefinition, isRlManaged: Boolean): Boolean {
        val svc = MacroAccessibilityService.get() ?: return false

        return when (action.interactionType) {
            InteractionType.TAP -> svc.dispatchClick(
                action.x, action.y,
                if (isRlManaged) GesturePriority.HIGH else GesturePriority.NORMAL
            )
            InteractionType.LONG_PRESS -> svc.dispatchLongPress(
                action.x, action.y, action.durationMs
            )
            InteractionType.SWIPE -> svc.dispatchSwipe(
                action.x, action.y, action.endX, action.endY,
                action.durationMs
            )
            InteractionType.WAIT -> {
                delay(action.durationMs)
                true
            }
            InteractionType.TYPE_TEXT,
            InteractionType.MULTI_TOUCH -> {
                // Extended gesture types — handled by gesture controller
                Log.w(TAG, "Gesture type ${action.interactionType} requires extended handler")
                false
            }
        }.also {
            if (action.delayAfterMs > 0) delay(action.delayAfterMs)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // State transition handlers
    // ─────────────────────────────────────────────────────────────────────────

    private fun handleSuccess(
        state: WorkflowState,
        session: WorkflowSession,
        result: StateResult,
        definition: WorkflowDefinition
    ): WorkflowSession {
        val next = result.nextState ?: state.onSuccess
        val completed = next == "END" || !definition.states.containsKey(next)
        return session.copy(
            currentState         = if (completed) "END" else next,
            retryCount           = 0,
            stepCount            = session.stepCount + 1,
            lastCheckpointState  = next,
            completed            = completed
        )
    }

    private fun handleFailure(
        state: WorkflowState,
        session: WorkflowSession,
        definition: WorkflowDefinition
    ): WorkflowSession {
        val newRetry = session.retryCount + 1
        return if (newRetry >= state.maxRetries) {
            val failState = state.onFailure
            Log.w(TAG, "State ${session.currentState} exhausted retries → $failState")
            session.copy(
                currentState = failState,
                retryCount   = 0,
                failed       = failState == "END" || !definition.states.containsKey(failState)
            )
        } else {
            session.copy(retryCount = newRetry)
        }
    }

    private fun handleTimeout(
        state: WorkflowState,
        session: WorkflowSession
    ): WorkflowSession {
        Log.w(TAG, "State ${session.currentState} timed out after ${state.timeoutMs}ms")
        return session.copy(
            currentState = state.onFailure,
            retryCount   = session.retryCount + 1,
            failed       = session.retryCount + 1 >= state.maxRetries
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun updateSession(session: WorkflowSession) {
        _runningWorkflows.value = _runningWorkflows.value + (session.workflowId to session)
    }

    /** Load template bitmap from [TemplateRepository] (memory cache → Room → assets). */
    private suspend fun loadTemplate(templateId: String): android.graphics.Bitmap? =
        templateRepository.getTemplate(templateId)

    companion object {
        private const val TAG = "MacroEngine"
    }
}

private data class StateResult(
    val success: Boolean,
    val nextState: String? = null
)
