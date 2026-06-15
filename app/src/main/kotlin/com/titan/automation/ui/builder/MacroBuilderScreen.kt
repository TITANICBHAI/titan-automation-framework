package com.titan.automation.ui.builder

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.titan.automation.domain.model.LoopMode
import com.titan.automation.domain.model.PlaybackConfig
import com.titan.automation.domain.model.ScheduleMode
import com.titan.automation.domain.model.SimpleAction
import com.titan.automation.domain.model.SimpleActionType
import com.titan.automation.domain.model.SimpleMacro
import java.util.UUID

// ── Colours ───────────────────────────────────────────────────────────────────

private val BG      = Color(0xFF0D1117)
private val SURFACE = Color(0xFF161B22)
private val BORDER  = Color(0xFF30363D)
private val CYAN    = Color(0xFF00E5FF)
private val GREEN   = Color(0xFF69F0AE)
private val RED     = Color(0xFFF44336)
private val AMBER   = Color(0xFFFFB300)
private val PURPLE  = Color(0xFFBA68C8)
private val TEAL    = Color(0xFF4DB6AC)
private val MUTED   = Color(0xFF8B949E)

// ── Entry composable ──────────────────────────────────────────────────────────

@Composable
fun MacroBuilderScreen(
    viewModel: MacroBuilderViewModel,
    modifier: Modifier = Modifier
) {
    val macros       by viewModel.macros.collectAsStateWithLifecycle()
    val editingMacro by viewModel.editingMacro.collectAsStateWithLifecycle()

    AnimatedContent(
        targetState = editingMacro,
        transitionSpec = {
            if (targetState != null)
                slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
            else
                slideInHorizontally { -it } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
        },
        label = "builder_nav"
    ) { editing ->
        if (editing == null) {
            MacroListScreen(macros = macros, viewModel = viewModel, modifier = modifier)
        } else {
            MacroEditorScreen(macro = editing, viewModel = viewModel, modifier = modifier)
        }
    }
}

// ── Macro list ────────────────────────────────────────────────────────────────

@Composable
private fun MacroListScreen(
    macros: List<SimpleMacro>,
    viewModel: MacroBuilderViewModel,
    modifier: Modifier = Modifier
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    val isPlaying        by viewModel.isPlaying.collectAsStateWithLifecycle()
    val currentName      by viewModel.currentMacroName.collectAsStateWithLifecycle()
    val scheduledJobs    by viewModel.scheduledJobs.collectAsStateWithLifecycle()

    Box(modifier = modifier.fillMaxSize().background(BG)) {
        if (macros.isEmpty()) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Default.TouchApp, null, tint = MUTED, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text("No macros yet", color = MUTED, fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))
                Text("Tap + to create your first macro", color = MUTED.copy(alpha = 0.6f), fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                contentPadding      = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier            = Modifier.fillMaxSize()
            ) {
                if (isPlaying && currentName != null) {
                    item { RunningBanner(name = currentName!!, onStop = viewModel::stop) }
                }
                itemsIndexed(macros, key = { _, m -> m.id }) { _, macro ->
                    MacroCard(
                        macro        = macro,
                        isPlaying    = isPlaying && currentName == macro.name,
                        isScheduled  = scheduledJobs.containsKey(macro.id),
                        scheduleInfo = scheduledJobs[macro.id]?.progressLabel,
                        onPlay       = { viewModel.play(macro) },
                        onStop       = viewModel::stop,
                        onEdit       = { viewModel.openMacro(macro) },
                        onDelete     = { viewModel.deleteMacro(macro.id) },
                        onCancelSchedule = { viewModel.cancelSchedule(macro.id) }
                    )
                }
            }
        }

        FloatingActionButton(
            onClick          = { showCreateDialog = true },
            modifier         = Modifier.align(Alignment.BottomEnd).padding(24.dp),
            containerColor   = CYAN,
            contentColor     = Color.Black
        ) { Icon(Icons.Default.Add, "New macro") }
    }

    if (showCreateDialog) {
        CreateMacroDialog(
            onConfirm = { name -> viewModel.createMacro(name); showCreateDialog = false },
            onDismiss = { showCreateDialog = false }
        )
    }
}

@Composable
private fun RunningBanner(name: String, onStop: () -> Unit) {
    Card(
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF0C1F10)),
        border   = BorderStroke(1.dp, GREEN),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(GREEN, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text("Playing: $name", color = GREEN, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            OutlinedButton(
                onClick = onStop,
                colors  = ButtonDefaults.outlinedButtonColors(contentColor = RED),
                border  = BorderStroke(1.dp, RED),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) { Text("Stop", fontSize = 13.sp) }
        }
    }
}

@Composable
private fun MacroCard(
    macro: SimpleMacro,
    isPlaying: Boolean,
    isScheduled: Boolean,
    scheduleInfo: String?,
    onPlay: () -> Unit,
    onStop: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCancelSchedule: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = SURFACE),
        border   = BorderStroke(1.dp, when {
            isPlaying   -> GREEN
            isScheduled -> AMBER
            else        -> BORDER
        })
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    macro.name,
                    fontWeight = FontWeight.SemiBold,
                    color      = when {
                        isPlaying   -> GREEN
                        isScheduled -> AMBER
                        else        -> Color.White
                    },
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (isScheduled) {
                    Badge(containerColor = AMBER.copy(alpha = 0.2f)) {
                        Text("⏰ Scheduled", color = AMBER, fontSize = 10.sp)
                    }
                    Spacer(Modifier.width(4.dp))
                }
                Badge(containerColor = Color(0xFF21262D)) {
                    Text("${macro.actions.size} steps", color = MUTED, fontSize = 10.sp)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(buildConfigSummary(macro.playbackConfig), color = MUTED, fontSize = 11.sp)
            if (scheduleInfo != null) {
                Spacer(Modifier.height(2.dp))
                Text(scheduleInfo, color = AMBER.copy(alpha = 0.8f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isPlaying) {
                    FilledTonalButton(
                        onClick = onStop,
                        colors  = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF1B0000)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.Stop, null, modifier = Modifier.size(16.dp), tint = RED)
                        Spacer(Modifier.width(4.dp))
                        Text("Stop", fontSize = 13.sp, color = RED)
                    }
                } else {
                    FilledTonalButton(
                        onClick  = onPlay,
                        enabled  = macro.actions.isNotEmpty(),
                        colors   = ButtonDefaults.filledTonalButtonColors(containerColor = Color(0xFF0C1F10)),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(16.dp), tint = GREEN)
                        Spacer(Modifier.width(4.dp))
                        Text("Play", fontSize = 13.sp, color = GREEN)
                    }
                }
                OutlinedButton(
                    onClick = onEdit,
                    colors  = ButtonDefaults.outlinedButtonColors(contentColor = CYAN),
                    border  = BorderStroke(1.dp, CYAN.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Edit, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Edit", fontSize = 13.sp)
                }
                if (isScheduled) {
                    IconButton(onClick = onCancelSchedule, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.AlarmOff, "Cancel schedule", tint = AMBER, modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showDeleteConfirm = true }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Delete, "Delete", tint = MUTED, modifier = Modifier.size(18.dp))
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest  = { showDeleteConfirm = false },
            containerColor    = SURFACE,
            title = { Text("Delete macro?", color = Color.White) },
            text  = { Text("\"${macro.name}\" will be permanently deleted.", color = MUTED) },
            confirmButton = {
                TextButton(onClick = { onDelete(); showDeleteConfirm = false }) {
                    Text("Delete", color = RED)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("Cancel", color = MUTED) }
            }
        )
    }
}

// ── Macro editor ──────────────────────────────────────────────────────────────

@Composable
private fun MacroEditorScreen(
    macro: SimpleMacro,
    viewModel: MacroBuilderViewModel,
    modifier: Modifier = Modifier
) {
    val isRecording    by viewModel.isRecording.collectAsStateWithLifecycle()
    val recordedCount  by viewModel.recordedCount.collectAsStateWithLifecycle()
    val isPlaying      by viewModel.isPlaying.collectAsStateWithLifecycle()
    val completedLoops by viewModel.completedLoops.collectAsStateWithLifecycle()
    val currentStep    by viewModel.currentStepIndex.collectAsStateWithLifecycle()
    val pickerResult   by viewModel.pickerResultForAction.collectAsStateWithLifecycle()

    var showStepEditor  by remember { mutableStateOf(false) }
    var editingStep     by remember { mutableStateOf<SimpleAction?>(null) }
    var showAddMenu     by remember { mutableStateOf(false) }
    var showConfigSheet by remember { mutableStateOf(false) }
    var showExportSheet by remember { mutableStateOf(false) }
    var exportJson      by remember { mutableStateOf("") }

    LaunchedEffect(pickerResult) {
        val pr = pickerResult ?: return@LaunchedEffect
        val (actionId, coords) = pr
        val (nx, ny) = coords
        val existing = editingStep
        if (existing != null && existing.id == actionId) {
            editingStep = existing.copy(x = nx, y = ny)
        } else {
            viewModel.updateAction(macro.actions.firstOrNull { it.id == actionId }?.copy(x = nx, y = ny) ?: return@LaunchedEffect)
        }
        viewModel.clearPickerResult()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        macro.name,
                        color      = CYAN,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = viewModel::closeEditor) {
                        Icon(Icons.Default.ArrowBack, "Back", tint = Color.White)
                    }
                },
                actions = {
                    if (isPlaying) {
                        IconButton(onClick = viewModel::stop) {
                            Icon(Icons.Default.Stop, "Stop", tint = RED)
                        }
                    } else {
                        IconButton(
                            onClick = { viewModel.play(macro) },
                            enabled = macro.actions.isNotEmpty()
                        ) {
                            Icon(Icons.Default.PlayArrow, "Play", tint = if (macro.actions.isEmpty()) MUTED else GREEN)
                        }
                    }
                    IconButton(onClick = { showConfigSheet = true }) {
                        Icon(Icons.Default.Tune, "Config", tint = MUTED)
                    }
                    IconButton(onClick = {
                        exportJson = viewModel.exportMacroJson(macro)
                        showExportSheet = true
                    }) {
                        Icon(Icons.Default.Share, "Export", tint = MUTED)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BG)
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End) {
                if (isRecording) {
                    ExtendedFloatingActionButton(
                        onClick        = viewModel::stopRecordingAndAdd,
                        containerColor = RED,
                        contentColor   = Color.White,
                        icon           = { Icon(Icons.Default.Stop, null) },
                        text           = { Text("Stop · $recordedCount captured") }
                    )
                } else {
                    SmallFloatingActionButton(
                        onClick        = viewModel::startRecording,
                        containerColor = Color(0xFF1B0000),
                        contentColor   = RED
                    ) { Icon(Icons.Default.FiberManualRecord, "Record") }
                    Spacer(Modifier.height(8.dp))
                    FloatingActionButton(
                        onClick        = { showAddMenu = true },
                        containerColor = CYAN,
                        contentColor   = Color.Black
                    ) { Icon(Icons.Default.Add, "Add step") }
                }
            }
        },
        containerColor = BG,
        modifier       = modifier
    ) { padding ->
        if (macro.actions.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Add, null, tint = MUTED, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("No steps yet", color = MUTED)
                    Spacer(Modifier.height(4.dp))
                    Text("Tap + to add steps, or use Record", color = MUTED.copy(alpha = 0.6f), fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                contentPadding      = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier            = Modifier.fillMaxSize().padding(padding)
            ) {
                if (isPlaying) {
                    item { PlayingBanner(completedLoops, macro.playbackConfig.loopMode) }
                }
                itemsIndexed(macro.actions, key = { _, a -> a.id }) { index, action ->
                    StepCard(
                        action      = action,
                        index       = index,
                        total       = macro.actions.size,
                        isActive    = isPlaying && index == currentStep,
                        onEdit      = { editingStep = action; showStepEditor = true },
                        onDelete    = { viewModel.removeAction(action.id) },
                        onMoveUp    = { if (index > 0) viewModel.moveAction(index, index - 1) },
                        onMoveDown  = { if (index < macro.actions.size - 1) viewModel.moveAction(index, index + 1) }
                    )
                }
            }
        }
    }

    if (showAddMenu) {
        AddStepMenu(
            onAddTap           = { viewModel.addAction(viewModel.buildNewTap());            showAddMenu = false },
            onAddLongPress     = { viewModel.addAction(viewModel.buildNewLongPress());      showAddMenu = false },
            onAddSwipe         = { viewModel.addAction(viewModel.buildNewSwipe());          showAddMenu = false },
            onAddWait          = { viewModel.addAction(viewModel.buildNewWait());           showAddMenu = false },
            onAddWaitForImage  = { viewModel.addAction(viewModel.buildNewWaitForImage());  showAddMenu = false },
            onAddWaitForText   = { viewModel.addAction(viewModel.buildNewWaitForOcrText()); showAddMenu = false },
            onDismiss          = { showAddMenu = false }
        )
    }

    if (showStepEditor) {
        val step = editingStep
        if (step != null) {
            StepEditorDialog(
                action       = step,
                onConfirm    = { updated -> viewModel.updateAction(updated); showStepEditor = false; editingStep = null },
                onDismiss    = { showStepEditor = false; editingStep = null },
                onPickCoords = { actionId -> viewModel.requestCoordinatePick(actionId) }
            )
        }
    }

    if (showConfigSheet) {
        PlaybackConfigDialog(
            config    = macro.playbackConfig,
            onConfirm = { updated -> viewModel.updatePlaybackConfig(updated); showConfigSheet = false },
            onDismiss = { showConfigSheet = false }
        )
    }

    if (showExportSheet) {
        ExportDialog(
            json      = exportJson,
            macroName = macro.name,
            onDismiss = { showExportSheet = false }
        )
    }
}

@Composable
private fun PlayingBanner(loops: Int, mode: LoopMode) {
    Card(
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF0C1F10)),
        border   = BorderStroke(1.dp, GREEN),
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(GREEN, CircleShape))
            Spacer(Modifier.width(8.dp))
            val loopText = when (mode) {
                LoopMode.ONCE     -> "Running once"
                LoopMode.COUNT    -> "Loop $loops"
                LoopMode.FOREVER  -> "Loop $loops (∞)"
                LoopMode.DURATION -> "Loop $loops (timed)"
            }
            Text(loopText, color = GREEN, fontSize = 12.sp)
        }
    }
}

// ── Step card ─────────────────────────────────────────────────────────────────

@Composable
private fun StepCard(
    action: SimpleAction,
    index: Int,
    total: Int,
    isActive: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    val typeColor = when (action.type) {
        SimpleActionType.TAP              -> CYAN
        SimpleActionType.LONG_PRESS       -> AMBER
        SimpleActionType.SWIPE            -> PURPLE
        SimpleActionType.WAIT             -> MUTED
        SimpleActionType.WAIT_FOR_IMAGE   -> TEAL
        SimpleActionType.WAIT_FOR_OCR_TEXT -> GREEN
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = if (isActive) Color(0xFF0C2020) else SURFACE),
        border   = BorderStroke(1.dp, if (isActive) TEAL else BORDER)
    ) {
        Row(
            modifier          = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier         = Modifier.size(28.dp).background(
                    if (isActive) TEAL.copy(alpha = 0.3f) else Color(0xFF21262D), CircleShape
                ),
                contentAlignment = Alignment.Center
            ) {
                Text("${index + 1}", fontSize = 11.sp, color = if (isActive) TEAL else MUTED, fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.width(10.dp))

            Box(
                modifier = Modifier.clip(RoundedCornerShape(4.dp))
                    .background(typeColor.copy(alpha = 0.15f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    "${action.type.emoji()} ${action.type.displayName()}",
                    color    = typeColor,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(Modifier.width(8.dp))

            Column(modifier = Modifier.weight(1f)) {
                if (action.label.isNotBlank()) {
                    Text(action.label, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val coordText = when (action.type) {
                    SimpleActionType.TAP, SimpleActionType.LONG_PRESS ->
                        "(${pct(action.x)}, ${pct(action.y)})  ${action.durationMs}ms"
                    SimpleActionType.SWIPE ->
                        "(${pct(action.x)},${pct(action.y)})→(${pct(action.endX)},${pct(action.endY)})  ${action.durationMs}ms"
                    SimpleActionType.WAIT ->
                        "Wait ${action.durationMs}ms"
                    SimpleActionType.WAIT_FOR_IMAGE ->
                        "template: '${action.templateId.ifBlank { "?" }}'  timeout ${action.conditionTimeoutMs / 1000}s"
                    SimpleActionType.WAIT_FOR_OCR_TEXT ->
                        "find: '${action.ocrPattern.ifBlank { "?" }}'  timeout ${action.conditionTimeoutMs / 1000}s"
                }
                Text(coordText, color = MUTED, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                if (action.delayAfterMs > 0 &&
                    action.type != SimpleActionType.WAIT_FOR_IMAGE &&
                    action.type != SimpleActionType.WAIT_FOR_OCR_TEXT) {
                    Text("+${action.delayAfterMs}ms delay", color = MUTED.copy(alpha = 0.6f), fontSize = 10.sp)
                }
            }

            Column {
                IconButton(onClick = onMoveUp, modifier = Modifier.size(28.dp), enabled = index > 0) {
                    Icon(Icons.Default.KeyboardArrowUp, null,
                        tint     = if (index > 0) MUTED else Color.Transparent,
                        modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onMoveDown, modifier = Modifier.size(28.dp), enabled = index < total - 1) {
                    Icon(Icons.Default.KeyboardArrowDown, null,
                        tint     = if (index < total - 1) MUTED else Color.Transparent,
                        modifier = Modifier.size(16.dp))
                }
            }

            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Edit, "Edit", tint = CYAN, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, "Delete", tint = MUTED, modifier = Modifier.size(16.dp))
            }
        }
    }
}

// ── Add step menu ─────────────────────────────────────────────────────────────

@Composable
private fun AddStepMenu(
    onAddTap: () -> Unit,
    onAddLongPress: () -> Unit,
    onAddSwipe: () -> Unit,
    onAddWait: () -> Unit,
    onAddWaitForImage: () -> Unit,
    onAddWaitForText: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = SURFACE,
        title = { Text("Add step", color = Color.White, fontWeight = FontWeight.Bold) },
        text  = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                data class StepOption(val label: String, val emoji: String, val color: Color, val onClick: () -> Unit)
                listOf(
                    StepOption("Tap",                "👆", CYAN,   onAddTap),
                    StepOption("Long Press",         "✋", AMBER,  onAddLongPress),
                    StepOption("Swipe",              "👉", PURPLE, onAddSwipe),
                    StepOption("Wait / Delay",       "⏱", MUTED,  onAddWait),
                    StepOption("Wait for Image",     "👁", TEAL,   onAddWaitForImage),
                    StepOption("Wait for Text (OCR)","🔤", GREEN,  onAddWaitForText),
                ).forEach { opt ->
                    OutlinedButton(
                        onClick  = opt.onClick,
                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = opt.color),
                        border   = BorderStroke(1.dp, opt.color.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("${opt.emoji} ${opt.label}", modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = MUTED) } }
    )
}

// ── Step editor dialog ────────────────────────────────────────────────────────

@Composable
private fun StepEditorDialog(
    action: SimpleAction,
    onConfirm: (SimpleAction) -> Unit,
    onDismiss: () -> Unit,
    onPickCoords: (String) -> Unit
) {
    var type              by remember { mutableStateOf(action.type) }
    var x                 by remember { mutableStateOf((action.x * 100).toInt().toString()) }
    var y                 by remember { mutableStateOf((action.y * 100).toInt().toString()) }
    var endX              by remember { mutableStateOf((action.endX * 100).toInt().toString()) }
    var endY              by remember { mutableStateOf((action.endY * 100).toInt().toString()) }
    var durationMs        by remember { mutableLongStateOf(action.durationMs) }
    var delayAfterMs      by remember { mutableLongStateOf(action.delayAfterMs) }
    var label             by remember { mutableStateOf(action.label) }
    // Conditional fields
    var templateId        by remember { mutableStateOf(action.templateId) }
    var minConfidence     by remember { mutableFloatStateOf(action.minConfidence) }
    var ocrPattern        by remember { mutableStateOf(action.ocrPattern) }
    var conditionTimeout  by remember { mutableLongStateOf(action.conditionTimeoutMs / 1000L) } // stored in seconds
    var tapWhenFound      by remember { mutableStateOf(action.tapWhenFound) }

    val isConditional = type == SimpleActionType.WAIT_FOR_IMAGE || type == SimpleActionType.WAIT_FOR_OCR_TEXT

    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Edit Step", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)

                // Type selector (scrollable row)
                Text("Type", color = MUTED, fontSize = 12.sp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    SimpleActionType.values().forEach { t ->
                        FilterChip(
                            selected = type == t,
                            onClick  = { type = t; label = t.displayName() },
                            label    = { Text("${t.emoji()} ${t.displayName()}", fontSize = 10.sp) },
                            colors   = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CYAN.copy(alpha = 0.2f),
                                selectedLabelColor     = CYAN
                            )
                        )
                    }
                }

                // Label
                OutlinedTextField(
                    value         = label,
                    onValueChange = { label = it },
                    label         = { Text("Label", color = MUTED) },
                    singleLine    = true,
                    colors        = tfColors(),
                    modifier      = Modifier.fillMaxWidth()
                )

                // ── Gesture coordinates (TAP / LONG_PRESS / SWIPE) ────────────

                if (!isConditional && type != SimpleActionType.WAIT) {
                    Text("Coordinates (0–100%)", color = MUTED, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = x, onValueChange = { x = it },
                            label = { Text("X %", color = MUTED) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true, colors = tfColors(), modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = y, onValueChange = { y = it },
                            label = { Text("Y %", color = MUTED) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true, colors = tfColors(), modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedButton(
                        onClick  = { onPickCoords(action.id) },
                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = CYAN),
                        border   = BorderStroke(1.dp, CYAN.copy(alpha = 0.5f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.MyLocation, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pick on screen", fontSize = 13.sp)
                    }

                    if (type == SimpleActionType.SWIPE) {
                        Text("End coordinates (0–100%)", color = MUTED, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = endX, onValueChange = { endX = it },
                                label = { Text("End X %", color = MUTED) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true, colors = tfColors(), modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = endY, onValueChange = { endY = it },
                                label = { Text("End Y %", color = MUTED) },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true, colors = tfColors(), modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // ── WAIT_FOR_IMAGE fields ─────────────────────────────────────

                if (type == SimpleActionType.WAIT_FOR_IMAGE) {
                    HorizontalDivider(color = BORDER)
                    Text("Image Match", color = TEAL, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value         = templateId,
                        onValueChange = { templateId = it },
                        label         = { Text("Template ID", color = MUTED) },
                        placeholder   = { Text("e.g. play_button", color = MUTED.copy(alpha = 0.5f)) },
                        singleLine    = true,
                        colors        = tfColors(),
                        modifier      = Modifier.fillMaxWidth()
                    )
                    Text("Min confidence: ${(minConfidence * 100).toInt()}%", color = MUTED, fontSize = 12.sp)
                    Slider(
                        value         = minConfidence,
                        onValueChange = { minConfidence = it },
                        valueRange    = 0.5f..0.99f,
                        steps         = 48,
                        colors        = SliderDefaults.colors(thumbColor = TEAL, activeTrackColor = TEAL)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Tap when found", color = Color.White, fontSize = 13.sp)
                            Text("Dispatch a tap at the match location", color = MUTED, fontSize = 11.sp)
                        }
                        Switch(
                            checked         = tapWhenFound,
                            onCheckedChange = { tapWhenFound = it },
                            colors          = SwitchDefaults.colors(checkedThumbColor = TEAL, checkedTrackColor = TEAL.copy(alpha = 0.4f))
                        )
                    }
                }

                // ── WAIT_FOR_OCR_TEXT fields ──────────────────────────────────

                if (type == SimpleActionType.WAIT_FOR_OCR_TEXT) {
                    HorizontalDivider(color = BORDER)
                    Text("OCR Text Match", color = GREEN, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    OutlinedTextField(
                        value         = ocrPattern,
                        onValueChange = { ocrPattern = it },
                        label         = { Text("Text to find (case-insensitive)", color = MUTED) },
                        placeholder   = { Text("e.g. Level Complete", color = MUTED.copy(alpha = 0.5f)) },
                        singleLine    = true,
                        colors        = tfColors(),
                        modifier      = Modifier.fillMaxWidth()
                    )
                }

                // ── Timeout (both conditional types) ──────────────────────────

                if (isConditional) {
                    Text("Timeout: ${conditionTimeout}s", color = MUTED, fontSize = 12.sp)
                    Slider(
                        value         = conditionTimeout.toFloat(),
                        onValueChange = { conditionTimeout = it.toLong() },
                        valueRange    = 1f..60f,
                        steps         = 58,
                        colors        = SliderDefaults.colors(thumbColor = AMBER, activeTrackColor = AMBER)
                    )
                }

                // ── Duration / delay (non-conditional, non-conditional) ───────

                if (!isConditional) {
                    val durLabel = when (type) {
                        SimpleActionType.WAIT       -> "Wait: ${durationMs}ms"
                        SimpleActionType.LONG_PRESS -> "Hold: ${durationMs}ms"
                        else                        -> "Tap duration: ${durationMs}ms"
                    }
                    Text(durLabel, color = MUTED, fontSize = 12.sp)
                    Slider(
                        value         = durationMs.toFloat(),
                        onValueChange = { durationMs = it.toLong() },
                        valueRange    = 50f..3000f,
                        steps         = 0,
                        colors        = SliderDefaults.colors(thumbColor = CYAN, activeTrackColor = CYAN)
                    )

                    if (type != SimpleActionType.WAIT) {
                        Text("Delay after: ${delayAfterMs}ms", color = MUTED, fontSize = 12.sp)
                        Slider(
                            value         = delayAfterMs.toFloat(),
                            onValueChange = { delayAfterMs = it.toLong() },
                            valueRange    = 0f..5000f,
                            steps         = 0,
                            colors        = SliderDefaults.colors(thumbColor = AMBER, activeTrackColor = AMBER)
                        )
                    }
                }

                // ── Buttons ───────────────────────────────────────────────────

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick  = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = MUTED),
                        border   = BorderStroke(1.dp, BORDER)
                    ) { Text("Cancel") }

                    Button(
                        onClick = {
                            val nx    = (x.toIntOrNull() ?: 50).coerceIn(0, 100) / 100f
                            val ny    = (y.toIntOrNull() ?: 50).coerceIn(0, 100) / 100f
                            val nEndX = (endX.toIntOrNull() ?: 50).coerceIn(0, 100) / 100f
                            val nEndY = (endY.toIntOrNull() ?: 50).coerceIn(0, 100) / 100f
                            onConfirm(action.copy(
                                type                = type,
                                x                   = nx,
                                y                   = ny,
                                endX                = nEndX,
                                endY                = nEndY,
                                durationMs          = durationMs,
                                delayAfterMs        = if (type == SimpleActionType.WAIT || isConditional) 0L else delayAfterMs,
                                label               = label.ifBlank { type.displayName() },
                                templateId          = templateId,
                                minConfidence       = minConfidence,
                                ocrPattern          = ocrPattern,
                                conditionTimeoutMs  = conditionTimeout * 1000L,
                                tapWhenFound        = tapWhenFound
                            ))
                        },
                        modifier = Modifier.weight(1f),
                        colors   = ButtonDefaults.buttonColors(containerColor = CYAN, contentColor = Color.Black)
                    ) { Text("Save", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

// ── Playback config dialog ────────────────────────────────────────────────────

@Composable
private fun PlaybackConfigDialog(
    config: PlaybackConfig,
    onConfirm: (PlaybackConfig) -> Unit,
    onDismiss: () -> Unit
) {
    var loopMode         by remember { mutableStateOf(config.loopMode) }
    var loopCount        by remember { mutableIntStateOf(config.loopCount) }
    var loopDurSec       by remember { mutableIntStateOf((config.loopDurationMs / 1000).toInt()) }
    var speed            by remember { mutableFloatStateOf(config.speedMultiplier) }
    var jitter           by remember { mutableStateOf(config.jitterEnabled) }
    var jitterRadius     by remember { mutableFloatStateOf(config.jitterRadiusPx) }
    var showDots         by remember { mutableStateOf(config.showTapDots) }
    var respectThermal   by remember { mutableStateOf(config.respectThermal) }
    var scheduleMode     by remember { mutableStateOf(config.scheduleMode) }
    var scheduleInterval by remember { mutableLongStateOf((config.scheduleIntervalMs / 1000L).coerceAtLeast(5L)) }
    var scheduleRepeats  by remember { mutableIntStateOf(config.scheduleRepeatCount.coerceAtLeast(1)) }

    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(16.dp)) {
            Column(
                modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Playback Settings", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)

                // ── Loop mode ─────────────────────────────────────────────────
                Text("Loop mode", color = MUTED, fontSize = 12.sp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    LoopMode.values().forEach { mode ->
                        FilterChip(
                            selected = loopMode == mode,
                            onClick  = { loopMode = mode },
                            label    = { Text(mode.displayName(), fontSize = 10.sp) },
                            colors   = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = CYAN.copy(alpha = 0.2f),
                                selectedLabelColor     = CYAN
                            )
                        )
                    }
                }

                when (loopMode) {
                    LoopMode.COUNT -> {
                        Text("Repeat $loopCount times", color = MUTED, fontSize = 12.sp)
                        Slider(
                            value         = loopCount.toFloat(),
                            onValueChange = { loopCount = it.toInt() },
                            valueRange    = 1f..100f, steps = 98,
                            colors        = SliderDefaults.colors(thumbColor = CYAN, activeTrackColor = CYAN)
                        )
                    }
                    LoopMode.DURATION -> {
                        Text("Loop for ${loopDurSec}s", color = MUTED, fontSize = 12.sp)
                        Slider(
                            value         = loopDurSec.toFloat(),
                            onValueChange = { loopDurSec = it.toInt() },
                            valueRange    = 5f..3600f, steps = 0,
                            colors        = SliderDefaults.colors(thumbColor = CYAN, activeTrackColor = CYAN)
                        )
                    }
                    else -> {}
                }

                HorizontalDivider(color = BORDER)

                // ── Speed ─────────────────────────────────────────────────────
                Text(
                    when {
                        speed < 0.5f -> "Speed: Slow (${speed}x)"
                        speed > 1.5f -> "Speed: Fast (${speed}x)"
                        else         -> "Speed: Normal (${speed}x)"
                    }, color = MUTED, fontSize = 12.sp
                )
                Slider(
                    value = speed, onValueChange = { speed = (it * 4).toInt() / 4f },
                    valueRange = 0.25f..4f, steps = 14,
                    colors = SliderDefaults.colors(thumbColor = AMBER, activeTrackColor = AMBER)
                )

                HorizontalDivider(color = BORDER)

                // ── Jitter ────────────────────────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Anti-detection jitter", color = Color.White, fontSize = 14.sp)
                        Text("Random ±${jitterRadius.toInt()}px per tap", color = MUTED, fontSize = 11.sp)
                    }
                    Switch(checked = jitter, onCheckedChange = { jitter = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = CYAN, checkedTrackColor = CYAN.copy(alpha = 0.4f)))
                }
                if (jitter) {
                    Slider(
                        value = jitterRadius, onValueChange = { jitterRadius = it },
                        valueRange = 1f..20f, steps = 18,
                        colors = SliderDefaults.colors(thumbColor = CYAN, activeTrackColor = CYAN)
                    )
                }

                HorizontalDivider(color = BORDER)

                // ── Tap dots ──────────────────────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Show tap dots", color = Color.White, fontSize = 14.sp)
                        Text("Visual circles at each tap location", color = MUTED, fontSize = 11.sp)
                    }
                    Switch(checked = showDots, onCheckedChange = { showDots = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = CYAN, checkedTrackColor = CYAN.copy(alpha = 0.4f)))
                }

                // ── Thermal respect ───────────────────────────────────────────
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Pause on thermal throttle", color = Color.White, fontSize = 14.sp)
                        Text("Halts macro if device overheats", color = MUTED, fontSize = 11.sp)
                    }
                    Switch(checked = respectThermal, onCheckedChange = { respectThermal = it },
                        colors = SwitchDefaults.colors(checkedThumbColor = AMBER, checkedTrackColor = AMBER.copy(alpha = 0.4f)))
                }

                HorizontalDivider(color = BORDER)

                // ── Schedule ──────────────────────────────────────────────────
                Text("Auto-schedule", color = MUTED, fontSize = 12.sp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.horizontalScroll(rememberScrollState())
                ) {
                    ScheduleMode.values().forEach { mode ->
                        FilterChip(
                            selected = scheduleMode == mode,
                            onClick  = { scheduleMode = mode },
                            label    = { Text(mode.displayName(), fontSize = 10.sp) },
                            colors   = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = AMBER.copy(alpha = 0.2f),
                                selectedLabelColor     = AMBER
                            )
                        )
                    }
                }

                when (scheduleMode) {
                    ScheduleMode.ONCE -> {
                        Text("Start after: ${scheduleInterval}s", color = MUTED, fontSize = 12.sp)
                        Slider(
                            value = scheduleInterval.toFloat(), onValueChange = { scheduleInterval = it.toLong() },
                            valueRange = 5f..3600f, steps = 0,
                            colors = SliderDefaults.colors(thumbColor = AMBER, activeTrackColor = AMBER)
                        )
                    }
                    ScheduleMode.INTERVAL -> {
                        Text("Run every: ${scheduleInterval}s", color = MUTED, fontSize = 12.sp)
                        Slider(
                            value = scheduleInterval.toFloat(), onValueChange = { scheduleInterval = it.toLong() },
                            valueRange = 5f..3600f, steps = 0,
                            colors = SliderDefaults.colors(thumbColor = AMBER, activeTrackColor = AMBER)
                        )
                    }
                    ScheduleMode.REPEAT -> {
                        Text("Run every: ${scheduleInterval}s", color = MUTED, fontSize = 12.sp)
                        Slider(
                            value = scheduleInterval.toFloat(), onValueChange = { scheduleInterval = it.toLong() },
                            valueRange = 5f..3600f, steps = 0,
                            colors = SliderDefaults.colors(thumbColor = AMBER, activeTrackColor = AMBER)
                        )
                        Text("Times: $scheduleRepeats", color = MUTED, fontSize = 12.sp)
                        Slider(
                            value = scheduleRepeats.toFloat(), onValueChange = { scheduleRepeats = it.toInt() },
                            valueRange = 1f..100f, steps = 98,
                            colors = SliderDefaults.colors(thumbColor = AMBER, activeTrackColor = AMBER)
                        )
                    }
                    ScheduleMode.MANUAL -> {}
                }

                HorizontalDivider(color = BORDER)

                // ── Save ──────────────────────────────────────────────────────
                Button(
                    onClick = {
                        onConfirm(config.copy(
                            loopMode             = loopMode,
                            loopCount            = loopCount,
                            loopDurationMs       = loopDurSec * 1000L,
                            speedMultiplier      = speed,
                            jitterEnabled        = jitter,
                            jitterRadiusPx       = jitterRadius,
                            showTapDots          = showDots,
                            respectThermal       = respectThermal,
                            scheduleMode         = scheduleMode,
                            scheduleIntervalMs   = scheduleInterval * 1000L,
                            scheduleRepeatCount  = scheduleRepeats
                        ))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.buttonColors(containerColor = CYAN, contentColor = Color.Black)
                ) { Text("Apply", fontWeight = FontWeight.Bold) }
            }
        }
    }
}

// ── Export dialog ─────────────────────────────────────────────────────────────

@Composable
private fun ExportDialog(json: String, macroName: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Card(colors = CardDefaults.cardColors(containerColor = SURFACE), shape = RoundedCornerShape(16.dp)) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Export: $macroName", color = Color.White, fontWeight = FontWeight.Bold)
                Text(
                    json.take(600) + if (json.length > 600) "\n… (${json.length} chars total)" else "",
                    color      = MUTED,
                    fontSize   = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier   = Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState())
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick  = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = MUTED),
                        border   = BorderStroke(1.dp, BORDER)
                    ) { Text("Close") }
                    Button(
                        onClick = {
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type    = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, json)
                                putExtra(android.content.Intent.EXTRA_SUBJECT, "$macroName.titan")
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, "Share macro"))
                        },
                        modifier = Modifier.weight(1f),
                        colors   = ButtonDefaults.buttonColors(containerColor = CYAN, contentColor = Color.Black)
                    ) {
                        Icon(Icons.Default.Share, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Share")
                    }
                }
            }
        }
    }
}

// ── Create dialog ─────────────────────────────────────────────────────────────

@Composable
private fun CreateMacroDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor   = SURFACE,
        title = { Text("New macro", color = Color.White, fontWeight = FontWeight.Bold) },
        text  = {
            OutlinedTextField(
                value         = name,
                onValueChange = { name = it },
                label         = { Text("Name", color = MUTED) },
                singleLine    = true,
                colors        = tfColors(),
                modifier      = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick  = { onConfirm(name) },
                enabled  = name.isNotBlank(),
                colors   = ButtonDefaults.buttonColors(containerColor = CYAN, contentColor = Color.Black)
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = MUTED) } }
    )
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun pct(n: Float) = "${(n * 100).toInt()}%"

private fun buildConfigSummary(cfg: PlaybackConfig): String {
    val loop = when (cfg.loopMode) {
        LoopMode.ONCE     -> "Once"
        LoopMode.COUNT    -> "×${cfg.loopCount}"
        LoopMode.FOREVER  -> "∞"
        LoopMode.DURATION -> "${cfg.loopDurationMs / 1000}s"
    }
    val speed    = if (cfg.speedMultiplier != 1f) " · ${cfg.speedMultiplier}x" else ""
    val jitter   = if (cfg.jitterEnabled) " · jitter" else ""
    val dots     = if (cfg.showTapDots)   " · dots"   else ""
    val schedule = when (cfg.scheduleMode) {
        ScheduleMode.MANUAL   -> ""
        ScheduleMode.ONCE     -> " · ⏰ once"
        ScheduleMode.INTERVAL -> " · ⏰ every ${cfg.scheduleIntervalMs / 1000}s"
        ScheduleMode.REPEAT   -> " · ⏰ ×${cfg.scheduleRepeatCount}"
    }
    return "$loop$speed$jitter$dots$schedule"
}

@Composable
private fun tfColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor   = CYAN,
    unfocusedBorderColor = BORDER,
    focusedTextColor     = Color.White,
    unfocusedTextColor   = Color.White,
    cursorColor          = CYAN
)

