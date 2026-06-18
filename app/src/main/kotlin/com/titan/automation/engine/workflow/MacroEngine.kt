package com.titan.automation.engine.workflow

import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.titan.automation.domain.model.*
import com.titan.automation.domain.repository.WorkflowRepository
import com.titan.automation.engine.accessibility.GesturePriority
import com.titan.automation.engine.accessibility.MacroAccessibilityService
import com.titan.automation.engine.capture.FrameProvider
import com.titan.automation.engine.capture.TemplateRepository
import com.titan.automation.engine.governor.ThermalGovernor
import com.titan.automation.engine.governor.ThermalLevel
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
    private val repository        : WorkflowRepository,
    private val visionEngine      : VisionEngine,
    private val rlEngine          : RLEngine,
    private val thermal           : ThermalGovernor,
    private val eventBus          : TitanEventBus,
    private val frameProvider     : FrameProvider,
    private val templateRepository: TemplateRepository,
    private val hotReloadManager  : HotReloadManager
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

        // Mutable reference so hot-reload can swap the definition between state transitions.
        // The swap is safe because both writer (reloadJob) and reader (engine loop) execute on
        // Dispatchers.Default; the coroutine context switch at each loop boundary provides the
        // happens-before guarantee required for visibility.
        var effectiveDefinition = definition

        // Sidecar coroutine: watch for file-system hot-reload events for this workflow.
        // Takes effect at the NEXT state boundary (not mid-state).
        val reloadJob = engineScope.launch {
            hotReloadManager.reloadEvents.collect { event ->
                if (event.workflowId == definition.workflowId &&
                    event.operation == HotReloadOperation.UPDATE &&
                    event.definition != null
                ) {
                    effectiveDefinition = event.definition
                    Log.i(TAG, "Hot-reload applied to running workflow: ${definition.workflowId}")
                }
            }
        }

        try {
            withTimeout(definition.globalTimeoutMs) {
                while (!session.completed && !session.failed) {
                    val wf = effectiveDefinition  // snapshot; safe to use for this iteration

                    // Thermal halt
                    if (thermal.isCritical) {
                        Log.w(TAG, "Thermal critical — halting workflow ${wf.workflowId}")
                        session = session.copy(failed = true)
                        break
                    }

                    // Pause support (overlay panic)
                    while (paused) delay(200)

                    val stateId = session.currentState
                    val state   = wf.states[stateId] ?: run {
                        if (stateId == "END") {
                            session = session.copy(completed = true)
                            return@withTimeout
                        }
                        Log.e(TAG, "Unknown state: $stateId in workflow ${wf.workflowId}")
                        session = session.copy(failed = true)
                        return@withTimeout
                    }

                    val stepStart = System.currentTimeMillis()

                    // Execute state with per-state timeout
                    val result = withTimeoutOrNull(state.timeoutMs) {
                        executeState(wf, state, session)
                    }

                    val durationMs = System.currentTimeMillis() - stepStart
                    eventBus.emit(TitanEvent.WorkflowStep(
                        stepId     = stateId,
                        state      = result?.nextState ?: "timeout",
                        retryCount = session.retryCount,
                        durationMs = durationMs
                    ))

                    session = when {
                        result == null -> handleTimeout(state, session)
                        result.success -> handleSuccess(state, session, result, wf)
                        else           -> handleFailure(state, session, wf)
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
            reloadJob.cancel()
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

    /**
     * Execute one workflow state:
     *   1. Capture frame
     *   2. Evaluate conditional branches (IF/ELSE) — first match short-circuits
     *   3. Vision match  (TEMPLATE or ORB based on [VisionMatchRule.matchMode])
     *   4. OCR validation
     *   5. Histogram validation (colour-based scene check)
     *   6. RL decision → or deterministic action intent
     *   7. Execute gesture
     *   8. Cooldown + RL reward
     */
    private suspend fun executeState(
        definition: WorkflowDefinition,
        state: WorkflowState,
        session: WorkflowSession
    ): StateResult? {

        // 1. Capture frame
        val frame = frameProvider.latestFrame.first { it != null }!!.bitmap
            ?: return StateResult(success = false)

        // 2. Conditional branches — evaluated first; first match wins (IF/ELSE)
        if (state.conditions.isNotEmpty()) {
            val branchResult = evaluateConditions(definition, state, session, frame)
            if (branchResult != null) return branchResult
        }

        // 3. Vision match — TEMPLATE (default) or ORB (rotation/scale-invariant)
        val visionResult = state.visionMatchRule?.let { rule ->
            val templateBitmap = loadTemplate(rule.templateId) ?: return StateResult(success = false)
            when (rule.matchMode) {
                MatchMode.TEMPLATE -> visionEngine.findTemplate(frame, templateBitmap, rule)
                MatchMode.ORB      -> visionEngine.matchOrb(frame, templateBitmap, rule)
            }
        }
        val visionSuccess = state.visionMatchRule == null || visionResult != null

        // 4. OCR validation
        val ocrResult = state.ocrScanRule?.let { rule -> visionEngine.runOcr(frame, rule) }
        val ocrSuccess = state.ocrScanRule == null || ocrResult?.matched == true

        // 5. Histogram validation — colour-based scene matching
        val histSuccess = state.histogramScanRule?.let { rule ->
            val refBitmap = loadTemplate(rule.referenceTemplateId)
                ?: return StateResult(success = false)
            val similarity = visionEngine.compareHistogram(frame, refBitmap, rule.region)
            if (similarity < rule.minSimilarity) {
                Log.d(TAG, "Histogram mismatch: ${"%.2f".format(similarity)} < ${rule.minSimilarity}")
            }
            similarity >= rule.minSimilarity
        } ?: true

        if (!visionSuccess || !ocrSuccess || !histSuccess) return StateResult(success = false)

        // 6. Determine action intent — RL overrides deterministic when enabled + thermal permits
        val actionIntent: String = if (state.rlEnabled && thermal.rlEnabled) {
            val available  = definition.actions.keys.toList()
            rlEngine.getBestAction(session.currentState, available).action
        } else {
            state.visionMatchRule?.actionIntent
                ?: state.ocrScanRule?.actionIntent
                ?: state.histogramScanRule?.actionIntent
                ?: return StateResult(success = false)
        }

        // 7. Execute action
        val actionDef = definition.actions[actionIntent] ?: return StateResult(success = false)
        val gestureOk = executeAction(actionDef, state.rlEnabled)

        // 8. Cooldown
        if (state.cooldownMs > 0) delay(state.cooldownMs)

        // 9. RL reward update
        if (state.rlEnabled && thermal.rlEnabled) {
            val reward = rlEngine.shapeReward(
                if (gestureOk) RewardOutcome.STATE_TRANSITION_SUCCESS else RewardOutcome.GESTURE_REJECTED,
                session.stepCount
            )
            rlEngine.learn(
                state     = session.currentState,
                action    = actionIntent,
                rawReward = reward,
                nextState = state.onSuccess
            )
        }

        return StateResult(
            success   = gestureOk,
            nextState = if (gestureOk) state.onSuccess else state.onFailure
        )
    }

    /**
     * Evaluate the [WorkflowState.conditions] list (IF/ELSE branching).
     *
     * Each [ConditionalBranch] specifies a condition, the action to take if it
     * matches, and the next state to transition to. The first matching branch
     * wins; subsequent branches are skipped.
     *
     * Supported condition types:
     *  - VISION_MATCH    — template matching against [branch.value] template ID
     *  - ORB_MATCH       — ORB keypoint match against [branch.value] template ID
     *  - HISTOGRAM_MATCH — HSV histogram similarity against [branch.value] ref ID
     *  - OCR_CONTAINS    — ML Kit OCR text regex match
     *  - STATE_FLAG      — current state name equals [branch.value]
     *  - BATTERY_BELOW   — battery % < [branch.value] (parsed as Int)
     *  - THERMAL_ABOVE   — thermal level ≥ [branch.value] (ThermalLevel name)
     */
    private suspend fun evaluateConditions(
        definition : WorkflowDefinition,
        state      : WorkflowState,
        session    : WorkflowSession,
        frame      : android.graphics.Bitmap
    ): StateResult? {
        for (branch in state.conditions) {
            val matches: Boolean = when (branch.conditionType) {

                ConditionType.VISION_MATCH -> {
                    val tmpl = loadTemplate(branch.value) ?: continue
                    val rule = VisionMatchRule(
                        templateId   = branch.value,
                        actionIntent = branch.actionIntent,
                        minConfidence = 0.80f
                    )
                    visionEngine.findTemplate(frame, tmpl, rule) != null
                }

                ConditionType.ORB_MATCH -> {
                    val tmpl = loadTemplate(branch.value) ?: continue
                    val rule = VisionMatchRule(
                        templateId    = branch.value,
                        actionIntent  = branch.actionIntent,
                        matchMode     = MatchMode.ORB,
                        minConfidence = 0.60f
                    )
                    visionEngine.matchOrb(frame, tmpl, rule) != null
                }

                ConditionType.HISTOGRAM_MATCH -> {
                    val refBitmap = loadTemplate(branch.value) ?: continue
                    visionEngine.compareHistogram(frame, refBitmap) >= 0.70f
                }

                ConditionType.OCR_CONTAINS -> {
                    val rule = OcrScanRule(
                        regexPattern = branch.value,
                        actionIntent = branch.actionIntent
                    )
                    visionEngine.runOcr(frame, rule)?.matched == true
                }

                ConditionType.STATE_FLAG -> {
                    session.currentState == branch.value
                }

                ConditionType.BATTERY_BELOW -> {
                    val threshold = branch.value.toIntOrNull() ?: 20
                    thermal.state.value.batteryPct < threshold
                }

                ConditionType.THERMAL_ABOVE -> {
                    val threshold = ThermalLevel.values()
                        .firstOrNull { it.name == branch.value }
                        ?: ThermalLevel.MODERATE
                    thermal.state.value.thermalLevel.ordinal >= threshold.ordinal
                }
            }

            if (matches) {
                val actionDef = definition.actions[branch.actionIntent]
                if (actionDef != null) executeAction(actionDef, state.rlEnabled)
                if (state.cooldownMs > 0) delay(state.cooldownMs)
                Log.d(TAG, "Condition ${branch.conditionType}[${branch.value}] matched → ${branch.nextState}")
                return StateResult(success = true, nextState = branch.nextState)
            }
        }
        return null  // no branch matched — fall through to primary vision/ocr/histogram checks
    }

    /**
     * Dispatch a single [ActionDefinition] gesture.
     *
     * MULTI_TOUCH  — two-pointer pinch/zoom gesture via [MacroAccessibilityService.dispatchMultiTouch]
     * TYPE_TEXT    — sets text on the currently focused input node via AccessibilityNodeInfo ACTION_SET_TEXT
     */
    private suspend fun executeAction(action: ActionDefinition, isRlManaged: Boolean): Boolean {
        // ── INVOKE_WORKFLOW — recursive sub-workflow execution ────────────────
        // Does not require the Accessibility service to be connected.
        if (action.interactionType == InteractionType.INVOKE_WORKFLOW) {
            val subId = action.subWorkflowId ?: run {
                Log.w(TAG, "INVOKE_WORKFLOW: 'sub_workflow_id' not set in action definition")
                return false
            }
            val subDefinition = repository.getWorkflow(subId) ?: run {
                Log.w(TAG, "INVOKE_WORKFLOW: sub-workflow '$subId' not found in repository")
                return false
            }
            val subSession = WorkflowSession(
                workflowId   = subId,
                currentState = subDefinition.initialState
            )
            Log.i(TAG, "INVOKE_WORKFLOW: entering sub-workflow '$subId'")
            updateSession(subSession)
            runWorkflow(subDefinition, subSession)
            Log.i(TAG, "INVOKE_WORKFLOW: returned from sub-workflow '$subId'")
            if (action.delayAfterMs > 0) delay(action.delayAfterMs)
            return true
        }

        // ── All other action types require the Accessibility service ──────────
        val svc = MacroAccessibilityService.get() ?: return false

        val ok = when (action.interactionType) {
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
            InteractionType.MULTI_TOUCH -> svc.dispatchMultiTouch(
                action.x, action.y,
                action.endX, action.endY,
                action.durationMs
            )
            InteractionType.TYPE_TEXT -> {
                // Inject text into the currently focused editable field via
                // AccessibilityNodeInfo.ACTION_SET_TEXT (API 21+).
                // Fallback: perform a tap first to ensure the field is focused.
                val text = action.textInput ?: return false
                val root = svc.rootInActiveWindow
                val focused = root?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    ?: run {
                        svc.dispatchClick(action.x, action.y, GesturePriority.NORMAL)
                        delay(300)
                        svc.rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                    }
                if (focused != null) {
                    val args = Bundle()
                    args.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
                    )
                    focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                } else {
                    Log.w(TAG, "TYPE_TEXT: no focused input node found at (${action.x}, ${action.y})")
                    false
                }
            }
            InteractionType.WAIT -> {
                delay(action.durationMs)
                true
            }
            InteractionType.INVOKE_WORKFLOW -> false  // handled above, never reached
        }

        if (action.delayAfterMs > 0) delay(action.delayAfterMs)
        return ok
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
