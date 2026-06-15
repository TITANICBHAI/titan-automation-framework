package com.titan.automation.ui.builder

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.titan.automation.domain.model.LoopMode
import com.titan.automation.domain.model.PlaybackConfig
import com.titan.automation.domain.model.SimpleAction
import com.titan.automation.domain.model.SimpleActionType
import com.titan.automation.domain.model.SimpleMacro
import com.titan.automation.domain.repository.SimpleMacroRepository
import com.titan.automation.engine.overlay.CoordinatePicker
import com.titan.automation.engine.playback.SimplePlaybackEngine
import com.titan.automation.engine.recorder.TouchRecorder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class MacroBuilderViewModel @Inject constructor(
    private val repository   : SimpleMacroRepository,
    private val touchRecorder: TouchRecorder,
    private val playbackEngine: SimplePlaybackEngine,
    private val coordinatePicker: CoordinatePicker
) : ViewModel() {

    // ── Macro list ────────────────────────────────────────────────────────────

    val macros: StateFlow<List<SimpleMacro>> = repository.allMacros()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Editor state ──────────────────────────────────────────────────────────

    private val _editingMacro = MutableStateFlow<SimpleMacro?>(null)
    val editingMacro: StateFlow<SimpleMacro?> = _editingMacro.asStateFlow()

    // ── Recording ─────────────────────────────────────────────────────────────

    val isRecording: StateFlow<Boolean> = touchRecorder.isRecording
    val recordedCount: StateFlow<Int>   = touchRecorder.recordedCount

    // ── Playback ──────────────────────────────────────────────────────────────

    val isPlaying: StateFlow<Boolean>      = playbackEngine.isPlaying
    val currentMacroName: StateFlow<String?> = playbackEngine.currentMacroName
    val completedLoops: StateFlow<Int>     = playbackEngine.completedLoops

    // ── Picker ────────────────────────────────────────────────────────────────

    val isPickerActive: StateFlow<Boolean>              = coordinatePicker.isActive
    private val _pendingPickTarget = MutableStateFlow<String?>(null) // action id awaiting pick
    val pendingPickTarget: StateFlow<String?> = _pendingPickTarget.asStateFlow()

    private val _pickerResultForAction = MutableStateFlow<Pair<String, Pair<Float,Float>>?>(null)
    val pickerResultForAction: StateFlow<Pair<String, Pair<Float,Float>>?> = _pickerResultForAction.asStateFlow()

    init {
        // Listen for picker results and route them back to the editing action
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
        saveMacro(current.copy(playbackConfig = config))
    }

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

    // ── Coordinate picker ─────────────────────────────────────────────────────

    fun requestCoordinatePick(forActionId: String) {
        _pendingPickTarget.value = forActionId
        coordinatePicker.requestPick("Drag to tap target")
    }

    fun clearPickerResult() {
        _pickerResultForAction.value = null
    }

    // ── Quick action builders ─────────────────────────────────────────────────

    fun buildNewTap()       = SimpleAction(UUID.randomUUID().toString(), SimpleActionType.TAP,        label = "Tap")
    fun buildNewLongPress() = SimpleAction(UUID.randomUUID().toString(), SimpleActionType.LONG_PRESS, durationMs = 600L, label = "Long Press")
    fun buildNewSwipe()     = SimpleAction(UUID.randomUUID().toString(), SimpleActionType.SWIPE,      label = "Swipe")
    fun buildNewWait()      = SimpleAction(UUID.randomUUID().toString(), SimpleActionType.WAIT,       durationMs = 1000L, delayAfterMs = 0L, label = "Wait 1s")

    fun loopModeOptions() = LoopMode.values().toList()
}
