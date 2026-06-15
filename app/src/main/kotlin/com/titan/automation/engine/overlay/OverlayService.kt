package com.titan.automation.engine.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.titan.automation.domain.model.WorkflowSession
import com.titan.automation.engine.playback.SimplePlaybackEngine
import com.titan.automation.engine.recorder.TouchRecorder
import com.titan.automation.engine.workflow.MacroEngine
import com.titan.automation.events.TitanEvent
import com.titan.automation.events.TitanEventBus
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * OverlayService — floating runtime control panel rendered with Jetpack Compose.
 *
 * UI features:
 *   - Draggable compact panel (55dp pill, minimised by default)
 *   - Expanded view: real-time detection log, FPS, RL badge, thermal badge
 *   - Panic stop, play/pause, REC (touch recorder), PICK (coordinate picker), DOT toggle
 */
@AndroidEntryPoint
class OverlayService : Service() {

    @Inject lateinit var eventBus       : TitanEventBus
    @Inject lateinit var macroEngine    : MacroEngine
    @Inject lateinit var touchRecorder  : TouchRecorder
    @Inject lateinit var simplePlayback : SimplePlaybackEngine
    @Inject lateinit var coordPicker    : CoordinatePicker

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windowManager: WindowManager
    private var overlayView: ComposeView? = null

    // ── Overlay state ─────────────────────────────────────────────────────────

    private val fps          = mutableStateOf(0f)
    private val thermalLevel = mutableStateOf("NORMAL")
    private val rlEnabled    = mutableStateOf(true)
    private val logEntries   = mutableStateListOf<String>()
    private val isExpanded   = mutableStateOf(false)
    private val isRunning    = mutableStateOf(false)
    private val isRecording  = mutableStateOf(false)  // TouchRecorder state mirrored here
    private val showDots     = mutableStateOf(true)   // tap-dot visibility

    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createOverlayWindow()
        subscribeToEvents()
        mirrorRecordingState()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        overlayView?.let {
            (it.context as? LifecycleOwner)?.lifecycle?.let { lc ->
                if (lc is LifecycleRegistry) lc.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            }
            windowManager.removeViewImmediate(it)
        }
        overlayView = null
        super.onDestroy()
    }

    // ── Window ────────────────────────────────────────────────────────────────

    private fun createOverlayWindow() {
        val params = buildWindowParams()
        val view   = ComposeView(this).also { it.setContent { OverlayContent(params) } }

        val lifecycleOwner = OverlayLifecycleOwner()
        lifecycleOwner.performRestore(null)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleOwner.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        ViewTreeLifecycleOwner.set(view, lifecycleOwner)
        ViewTreeViewModelStoreOwner.set(view, lifecycleOwner)
        view.setViewTreeSavedStateRegistryOwner(lifecycleOwner)

        windowManager.addView(view, params)
        overlayView = view
    }

    private fun buildWindowParams(): WindowManager.LayoutParams {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.END; x = 16; y = 120 }
    }

    // ── Event subscription ────────────────────────────────────────────────────

    private fun subscribeToEvents() {
        scope.launch {
            eventBus.flow.collect { event ->
                when (event) {
                    is TitanEvent.CaptureFrame  -> fps.value = event.fps
                    is TitanEvent.ThermalStatus -> { thermalLevel.value = event.level; rlEnabled.value = event.rlEnabled }
                    is TitanEvent.EngineStarted -> isRunning.value = true
                    is TitanEvent.EngineStopped -> isRunning.value = false
                    is TitanEvent.VisionMatch   -> addLog("[VISION] ${event.templateId} conf=${event.confidence.fmt(2)}")
                    is TitanEvent.OcrResult     -> addLog("[OCR] ${event.text.take(60)}")
                    is TitanEvent.RLDecision    -> addLog("[RL] ${event.action} Q=${event.qValue.fmt(2)} ε=${event.epsilon.fmt(3)}")
                    is TitanEvent.GestureDispatched ->
                        addLog("[GESTURE] ${event.type} (${(event.x*100).toInt()}%,${(event.y*100).toInt()}%) ok=${event.success}")
                    is TitanEvent.Error         -> addLog("[ERR] ${event.source}: ${event.message}")
                    else -> Unit
                }
            }
        }
    }

    private fun mirrorRecordingState() {
        scope.launch {
            touchRecorder.isRecording.collect { recording ->
                isRecording.value = recording
                if (recording) addLog("[REC] Recording started")
                else           addLog("[REC] Recording stopped")
            }
        }
    }

    private fun addLog(entry: String) {
        if (logEntries.size >= 50) logEntries.removeFirst()
        logEntries.add(entry)
    }

    // ── Compose UI ────────────────────────────────────────────────────────────

    @Composable
    private fun OverlayContent(params: WindowManager.LayoutParams) {
        val expanded   by isExpanded
        val running    by isRunning
        val recording  by isRecording
        val dotsOn     by showDots
        val fpsVal     by fps
        val thermal    by thermalLevel
        val rl         by rlEnabled
        var offsetX    by remember { mutableStateOf(0f) }
        var offsetY    by remember { mutableStateOf(0f) }
        var isDragging by remember { mutableStateOf(false) }

        val thermalColor = when (thermal) {
            "CRITICAL" -> Color(0xFFF44336)
            "SEVERE"   -> Color(0xFFFF9800)
            "MODERATE" -> Color(0xFFFFEB3B)
            else       -> Color(0xFF4CAF50)
        }

        MaterialTheme(colorScheme = darkColorScheme()) {
            Box(
                modifier = Modifier
                    .wrapContentSize()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { isDragging = true },
                            onDragEnd   = { isDragging = false },
                            onDrag      = { _, drag ->
                                offsetX += drag.x; offsetY += drag.y
                                params.x = (params.x - offsetX.toInt()).coerceIn(-500, 500)
                                params.y = (params.y + offsetY.toInt()).coerceAtLeast(0)
                                windowManager.updateViewLayout(overlayView, params)
                                offsetX = 0f; offsetY = 0f
                            }
                        )
                    }
            ) {
                AnimatedContent(targetState = expanded) { exp ->
                    if (!exp) {
                        // ── Compact pill ──────────────────────────────────────
                        Surface(
                            shape    = MaterialTheme.shapes.extraLarge,
                            color    = if (recording) Color(0xCC2D0000) else Color(0xCC1A1A2E),
                            modifier = Modifier
                                .size(55.dp)
                                .clickable(onClick = { if (!isDragging) isExpanded.value = true })
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = when {
                                        recording -> Icons.Default.FiberManualRecord
                                        running   -> Icons.Default.PlayArrow
                                        else      -> Icons.Default.Stop
                                    },
                                    contentDescription = "Titan",
                                    tint = when {
                                        recording -> Color(0xFFF44336)
                                        running   -> Color(0xFF00E5FF)
                                        else      -> Color(0xFFBBBBBB)
                                    },
                                    modifier = Modifier.size(28.dp)
                                )
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(thermalColor, MaterialTheme.shapes.extraLarge)
                                        .align(Alignment.TopEnd)
                                )
                            }
                        }
                    } else {
                        // ── Expanded panel ────────────────────────────────────
                        Surface(
                            shape    = MaterialTheme.shapes.medium,
                            color    = Color(0xEE1A1A2E),
                            modifier = Modifier.width(280.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {

                                // Header
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        if (recording) "● REC" else "TITAN",
                                        fontWeight = FontWeight.Bold,
                                        color      = if (recording) Color(0xFFF44336) else Color(0xFF00E5FF),
                                        fontSize   = 14.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier   = Modifier.weight(1f)
                                    )
                                    Badge(containerColor = Color(0xFF263238)) {
                                        Text("${fpsVal.toInt()} fps", fontSize = 10.sp)
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    Badge(containerColor = thermalColor) {
                                        Text(thermal.take(3), fontSize = 10.sp, color = Color.Black)
                                    }
                                    Spacer(Modifier.width(4.dp))
                                    IconButton(
                                        onClick  = { isExpanded.value = false },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Icon(Icons.Default.KeyboardArrowRight, null, tint = Color.White, modifier = Modifier.size(16.dp))
                                    }
                                }

                                Spacer(Modifier.height(6.dp))
                                HorizontalDivider(color = Color(0xFF37474F))
                                Spacer(Modifier.height(6.dp))

                                // ── Controls row 1: engine controls ──────────
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    // Play / Pause workflow engine
                                    FilledTonalIconButton(
                                        onClick = {
                                            if (running) macroEngine.pauseAll() else macroEngine.resumeAll()
                                        },
                                        modifier = Modifier.size(36.dp),
                                        colors   = IconButtonDefaults.filledTonalIconButtonColors(
                                            containerColor = if (running) Color(0xFF1B5E20) else Color(0xFF263238)
                                        )
                                    ) {
                                        Icon(
                                            if (running) Icons.Default.Pause else Icons.Default.PlayArrow,
                                            null, tint = Color.White, modifier = Modifier.size(20.dp)
                                        )
                                    }

                                    // RL badge
                                    Badge(
                                        containerColor = if (rl) Color(0xFF1B5E20) else Color(0xFF37474F),
                                        modifier       = Modifier.align(Alignment.CenterVertically)
                                    ) {
                                        Text("RL", color = if (rl) Color(0xFF69F0AE) else Color.Gray)
                                    }

                                    Spacer(Modifier.weight(1f))

                                    // PANIC
                                    FilledIconButton(
                                        onClick = {
                                            macroEngine.pauseAll()
                                            simplePlayback.stop()
                                            isRunning.value = false
                                            addLog("[PANIC] Emergency stop triggered")
                                        },
                                        modifier = Modifier.size(36.dp),
                                        colors   = IconButtonDefaults.filledIconButtonColors(containerColor = Color(0xFFB71C1C))
                                    ) {
                                        Icon(Icons.Default.Stop, "PANIC", tint = Color.White, modifier = Modifier.size(20.dp))
                                    }
                                }

                                Spacer(Modifier.height(6.dp))

                                // ── Controls row 2: recorder + picker + dots ──
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment     = Alignment.CenterVertically
                                ) {
                                    // REC — toggle touch recorder
                                    FilledTonalIconButton(
                                        onClick = {
                                            if (recording) touchRecorder.stopRecording()
                                            else           touchRecorder.startRecording()
                                        },
                                        modifier = Modifier.size(36.dp),
                                        colors   = IconButtonDefaults.filledTonalIconButtonColors(
                                            containerColor = if (recording) Color(0xFF7F0000) else Color(0xFF263238)
                                        )
                                    ) {
                                        Icon(
                                            Icons.Default.FiberManualRecord,
                                            "Record",
                                            tint     = if (recording) Color(0xFFF44336) else Color(0xFF9E9E9E),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    // PICK — launch coordinate picker
                                    FilledTonalIconButton(
                                        onClick  = { coordPicker.requestPick() },
                                        modifier = Modifier.size(36.dp),
                                        colors   = IconButtonDefaults.filledTonalIconButtonColors(
                                            containerColor = Color(0xFF0D2137)
                                        )
                                    ) {
                                        Icon(Icons.Default.MyLocation, "Picker", tint = Color(0xFF00E5FF), modifier = Modifier.size(18.dp))
                                    }

                                    // DOT toggle — show/hide tap dots during playback
                                    FilledTonalIconButton(
                                        onClick  = {
                                            val next = !dotsOn
                                            showDots.value = next
                                            simplePlayback.runtimeShowDots = next
                                        },
                                        modifier = Modifier.size(36.dp),
                                        colors   = IconButtonDefaults.filledTonalIconButtonColors(
                                            containerColor = if (dotsOn) Color(0xFF1A2A1A) else Color(0xFF263238)
                                        )
                                    ) {
                                        Icon(
                                            Icons.Default.RemoveRedEye,
                                            "Dots",
                                            tint     = if (dotsOn) Color(0xFF69F0AE) else Color(0xFF9E9E9E),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }

                                    Spacer(Modifier.weight(1f))

                                    // Row labels
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            if (recording) "RECORDING" else "REC  PICK  DOTS",
                                            fontSize   = 8.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color      = if (recording) Color(0xFFF44336) else Color(0xFF546E7A)
                                        )
                                    }
                                }

                                Spacer(Modifier.height(6.dp))
                                HorizontalDivider(color = Color(0xFF37474F))
                                Spacer(Modifier.height(4.dp))

                                // ── Log stream ────────────────────────────────
                                val listState = rememberLazyListState()
                                LaunchedEffect(logEntries.size) {
                                    if (logEntries.isNotEmpty()) listState.animateScrollToItem(logEntries.size - 1)
                                }
                                LazyColumn(
                                    state    = listState,
                                    modifier = Modifier.height(130.dp).fillMaxWidth()
                                ) {
                                    items(logEntries) { entry ->
                                        Text(
                                            entry,
                                            fontSize   = 9.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color      = logColor(entry),
                                            lineHeight = 12.sp,
                                            modifier   = Modifier.padding(vertical = 1.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun logColor(entry: String): Color = when {
        entry.startsWith("[ERR]")     -> Color(0xFFF44336)
        entry.startsWith("[PANIC]")   -> Color(0xFFF44336)
        entry.startsWith("[REC]")     -> Color(0xFFF44336)
        entry.startsWith("[VISION]")  -> Color(0xFF00BCD4)
        entry.startsWith("[OCR]")     -> Color(0xFF81C784)
        entry.startsWith("[RL]")      -> Color(0xFFFFD54F)
        entry.startsWith("[GESTURE]") -> Color(0xFFBA68C8)
        else                          -> Color(0xFFB0BEC5)
    }

    private fun Float.fmt(decimals: Int) = "%.${decimals}f".format(this)

    companion object {
        fun startIntent(context: Context) = Intent(context, OverlayService::class.java)
    }
}

// ── Minimal LifecycleOwner/SavedStateRegistryOwner for ComposeView in a Service ──

private class OverlayLifecycleOwner :
    SavedStateRegistryOwner,
    ViewModelStoreOwner,
    LifecycleOwner {

    private val lifecycleRegistry    = LifecycleRegistry(this)
    private val savedStateController = androidx.savedstate.SavedStateRegistry.SavedStateRegistryController.create(this)
    private val vmStore              = ViewModelStore()

    override val lifecycle: Lifecycle           get() = lifecycleRegistry
    override val savedStateRegistry             get() = savedStateController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = vmStore

    fun performRestore(state: android.os.Bundle?) = savedStateController.performRestore(state)
    fun handleLifecycleEvent(event: Lifecycle.Event) = lifecycleRegistry.handleLifecycleEvent(event)
}
