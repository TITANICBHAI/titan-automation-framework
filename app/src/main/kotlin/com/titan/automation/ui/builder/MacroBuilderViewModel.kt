package com.titan.automation.ui.builder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.titan.automation.domain.model.LoopMode
import com.titan.automation.domain.model.PlaybackConfig
import com.titan.automation.domain.model.ScheduleMode
import com.titan.automation.domain.model.SimpleAction
import com.titan.automation.domain.model.SimpleActionType
import com.titan.automation.domain.model.SimpleMacro
import com.titan.automation.domain.repository.SimpleMacroRepository
import com.titan.automation.engine.overlay.CoordinatePicker
import com.titan.automation.engine.playback.MacroScheduler
import com.titan.automation.engine.playback.ScheduledJob
import com.titan.automation.engine.playback.SimplePlaybackEngine
import com.titan.automation.engine.recorder.TouchRecorder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class MacroBuilderViewModel @Inject constructor(
    private val repository      : SimpleMacroRepository,
    private val touchRecorder   : TouchRecorder,
    private val playbackEngine  : SimplePlaybackEngine,
    private val coordinatePicker: CoordinatePicker,
    private val macroScheduler  : MacroScheduler
) : ViewModel() {

    private val exportJson = Json { prettyPrint = true; encodeDefaults = true }

    // ── Macro list ────────────────────────────────────────────────────────────

    val macros: StateFlow<List<SimpleMacro>> = repository.allMacros()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Editor state ──────────────────────────────────────────────────────────

    private val _editingMacro = MutableStateFlow<SimpleMacro?>(null)
    val editingMacro: StateFlow<SimpleMacro?> = _editingMacro.asStateFlow()

    // ── Recording ─────────────────────────────────────────────────────────────

    val isRecording  : StateFlow<Boolean> = touchRecorder.isRecording
    val recordedCount: StateFlow<Int>     = touchRecorder.recordedCount

    // ── Playback ──────────────────────────────────────────────────────────────

    val isPlaying        : StateFlow<Boolean>  = playbackEngine.isPlaying
    val currentMacroName : StateFlow<String?>  = playbackEngine.currentMacroName
    val completedLoops   : StateFlow<Int>      = playbackEngine.completedLoops
    val currentStepIndex : StateFlow<Int>      = playbackEngine.currentStepIndex

    // ── Scheduler ─────────────────────────────────────────────────────────────

    val scheduledJobs: StateFlow<Map<String, ScheduledJob>> = macroScheduler.scheduled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    // ── Picker ────────────────────────────────────────────────────────────────

    val isPickerActive: StateFlow<Boolean>                           = coordinatePicker.isActive
    private val _pendingPickTarget = MutableStateFlow<String?>(null) // action id awaiting pick
    val pendingPickTarget: StateFlow<String?> = _pendingPickTarget.asStateFlow()

    private val _pickerResultForAction = MutableStateFlow<Pair<String, Pair<Float,Float>>?>(null)
    val pickerResultForAction: StateFlow<Pair<String, Pair<Float,Float>>?> = _pickerResultForAction.asStateFlow()

    init {
        viewModelScope.launch {
            coordinatePicker.pickerResult.collect { result ->
                val actionId = _pendingPickTarget.value ?: return@collect
                if (result is CoordinatePicker.PickerResult.Confirmed) {
                    _pickerResultForAction.value = actionId to (result.nx to result.ny)
                }
                _pendingPickTarget.value = null
            }
        }
    }

    // ── CRUD ──────────────────────────────────────────────────────────────────

    fun createMacro(name: String) {
        val macro = SimpleMacro(
            id   = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "Macro ${System.currentTimeMillis() % 10000}" }
        )
        viewModelScope.launch { repository.saveMacro(macro) }
    }

    fun openMacro(macro: SimpleMacro) {
        _editingMacro.value = macro
    }

    fun closeEditor() {
        if (isRecording.value) stopRecording()
        _editingMacro.value = null
    }

    fun saveMacro(macro: SimpleMacro) {
        val updated = macro.copy(updatedAt = System.currentTimeMillis())
        _editingMacro.value = updated
        viewModelScope.launch { repository.saveMacro(updated) }
    }

    fun deleteMacro(id: String) {
        macroScheduler.cancel(id)
        if (_editingMacro.value?.id == id) _editingMacro.value = null
        viewModelScope.launch { repository.deleteMacro(id) }
    }

    // ── Step editing ──────────────────────────────────────────────────────────

    fun addAction(action: SimpleAction) {
        val current = _editingMacro.value ?: return
        saveMacro(current.copy(actions = current.actions + action))
    }

    fun updateAction(action: SimpleAction) {
        val current = _editingMacro.value ?: return
        saveMacro(current.copy(actions = current.actions.map { if (it.id == action.id) action else it }))
    }

    fun removeAction(id: String) {
        val current = _editingMacro.value ?: return
        saveMacro(current.copy(actions = current.actions.filter { it.id != id }))
    }

    fun moveAction(fromIndex: Int, toIndex: Int) {
        val current = _editingMacro.value ?: return
        val list = current.actions.toMutableList()
        if (fromIndex !in list.indices || toIndex !in list.indices) return
        val item = list.removeAt(fromIndex)
        list.add(toIndex, item)
        saveMacro(current.copy(actions = list))
    }

    fun updatePlaybackConfig(config: PlaybackConfig) {
        val current = _editingMacro.value ?: return
        val updated = current.copy(playbackConfig = config)
        _editingMacro.value = updated
        viewModelScope.launch {
            repository.saveMacro(updated)
            applySchedule(updated)
        }
    }

    // ── Schedule ──────────────────────────────────────────────────────────────

    private fun applySchedule(macro: SimpleMacro) {
        val cfg = macro.playbackConfig
        when (cfg.scheduleMode) {
            ScheduleMode.MANUAL   -> macroScheduler.cancel(macro.id)
            ScheduleMode.ONCE     -> macroScheduler.scheduleOnce(macro,
                cfg.scheduleIntervalMs.coerceAtLeast(1_000L))
            ScheduleMode.INTERVAL -> macroScheduler.scheduleInterval(macro,
                cfg.scheduleIntervalMs.coerceAtLeast(1_000L))
            ScheduleMode.REPEAT   -> macroScheduler.scheduleRepeat(macro,
                cfg.scheduleRepeatCount.coerceAtLeast(1),
                cfg.scheduleIntervalMs.coerceAtLeast(1_000L))
        }
    }

    fun cancelSchedule(macroId: String) {
        macroScheduler.cancel(macroId)
    }

    fun isScheduled(macroId: String) = macroScheduler.isScheduled(macroId)

    // ── Recording ─────────────────────────────────────────────────────────────

    fun startRecording() {
        touchRecorder.startRecording()
    }

    fun stopRecordingAndAdd() {
        val recorded = touchRecorder.stopRecording()
        if (recorded.isEmpty()) return
        val current = _editingMacro.value ?: return
        saveMacro(current.copy(actions = current.actions + recorded))
    }

    fun stopRecording() {
        touchRecorder.stopRecording()
    }

    // ── Playback ──────────────────────────────────────────────────────────────

    fun play(macro: SimpleMacro) {
        playbackEngine.play(macro)
    }

    fun stop() {
        playbackEngine.stop()
    }

    // ── Export ────────────────────────────────────────────────────────────────

    fun exportMacroJson(macro: SimpleMacro): String =
        exportJson.encodeToString(macro)

    fun clearExportedJson() {
        _exportedJson.value = null
    }

    // ── Coordinate picker ─────────────────────────────────────────────────────

    fun requestCoordinatePick(forActionId: String) {
        _pendingPickTarget.value = forActionId
        coordinatePicker.requestPick("Drag to tap target")
    }

    fun clearPickerResult() {
        _pickerResultForAction.value = null
    }

    // ── Quick action builders ─────────────────────────────────────────────────

    fun buildNewTap() = SimpleAction(
        UUID.randomUUID().toString(), SimpleActionType.TAP,
        label = "Tap"
    )
    fun buildNewLongPress() = SimpleAction(
        UUID.randomUUID().toString(), SimpleActionType.LONG_PRESS,
        durationMs = 600L, label = "Long Press"
    )
    fun buildNewSwipe() = SimpleAction(
        UUID.randomUUID().toString(), SimpleActionType.SWIPE,
        label = "Swipe"
    )
    fun buildNewWait() = SimpleAction(
        UUID.randomUUID().toString(), SimpleActionType.WAIT,
        durationMs = 1000L, delayAfterMs = 0L, label = "Wait 1s"
    )
    fun buildNewWaitForImage() = SimpleAction(
        UUID.randomUUID().toString(), SimpleActionType.WAIT_FOR_IMAGE,
        templateId = "", conditionTimeoutMs = 15_000L, label = "Wait for Image"
    )
    fun buildNewWaitForOcrText() = SimpleAction(
        UUID.randomUUID().toString(), SimpleActionType.WAIT_FOR_OCR_TEXT,
        ocrPattern = "", conditionTimeoutMs = 15_000L, label = "Wait for Text"
    )

    fun loopModeOptions()     = LoopMode.values().toList()
    fun scheduleModeOptions() = ScheduleMode.values().toList()
}
