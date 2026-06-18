package com.titan.automation.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.titan.automation.domain.model.WorkflowDefinition
import com.titan.automation.domain.repository.WorkflowRepository
import com.titan.automation.engine.capture.ScreenCaptureService
import com.titan.automation.engine.overlay.OverlayService
import com.titan.automation.ui.builder.MacroBuilderScreen
import com.titan.automation.ui.builder.MacroBuilderViewModel
import com.titan.automation.ui.builder.TitanSettingsViewModel
import com.titan.automation.engine.workflow.MacroEngine
import com.titan.automation.engine.workflow.WorkflowParser
import com.titan.automation.events.TitanEvent
import com.titan.automation.events.TitanEventBus
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── Activity ──────────────────────────────────────────────────────────────────

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel               by viewModels()
    private val permViewModel: PermissionViewModel     by viewModels()
    private val builderViewModel: MacroBuilderViewModel by viewModels()
    private val settingsViewModel: TitanSettingsViewModel by viewModels()

    // MediaProjection permission (API 29 screen capture)
    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            startService(
                ScreenCaptureService.startIntent(this, result.resultCode, result.data!!)
            )
            permViewModel.markProjectionGranted()
        }
    }

    // POST_NOTIFICATIONS runtime permission (API 33+)
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permViewModel.refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                RootScreen(
                    viewModel        = viewModel,
                    permViewModel    = permViewModel,
                    builderViewModel  = builderViewModel,
                    settingsViewModel = settingsViewModel,
                    onRequestOverlay       = ::requestOverlayPermission,
                    onRequestAccessibility = ::openAccessibilitySettings,
                    onRequestProjection    = ::requestMediaProjection,
                    onRequestNotification  = ::requestNotificationPermission
                )
            }
        }
        if (Settings.canDrawOverlays(this)) {
            startService(OverlayService.startIntent(this))
        }
    }

    override fun onResume() {
        super.onResume()
        permViewModel.refresh()
        // Restart overlay if permissions came back granted while the settings activity was open
        if (Settings.canDrawOverlays(this)) {
            startService(OverlayService.startIntent(this))
        }
    }

    private fun requestOverlayPermission() {
        startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        )
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun requestMediaProjection() {
        val mgr = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        projectionLauncher.launch(mgr.createScreenCaptureIntent())
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

// ── ViewModel ─────────────────────────────────────────────────────────────────

@HiltViewModel
class MainViewModel @Inject constructor(
    private val macroEngine : MacroEngine,
    private val repository  : WorkflowRepository,
    private val parser      : WorkflowParser,
    private val eventBus    : TitanEventBus
) : ViewModel() {

    private val _workflows    = MutableStateFlow<List<WorkflowDefinition>>(emptyList())
    val workflows: StateFlow<List<WorkflowDefinition>> = _workflows.asStateFlow()

    private val _recentEvents = MutableStateFlow<List<String>>(emptyList())
    val recentEvents: StateFlow<List<String>> = _recentEvents.asStateFlow()

    private val _runningIds   = MutableStateFlow<Set<String>>(emptySet())
    val runningIds: StateFlow<Set<String>> = _runningIds.asStateFlow()

    init {
        repository.allWorkflows()
            .onEach { _workflows.value = it }
            .launchIn(viewModelScope)

        macroEngine.runningWorkflows
            .onEach { _runningIds.value = it.keys }
            .launchIn(viewModelScope)

        eventBus.flow
            .onEach { event ->
                val label = when (event) {
                    is TitanEvent.EngineStarted     -> "▶ Started: ${event.workflowId}"
                    is TitanEvent.EngineStopped     -> "■ Engine stopped"
                    is TitanEvent.VisionMatch       -> "👁 Match: ${event.templateId} (${event.confidence.fmt(2)})"
                    is TitanEvent.OcrResult         -> "📝 OCR: ${event.text.take(40)}"
                    is TitanEvent.RLDecision        -> "🤖 RL: ${event.action} Q=${event.qValue.fmt(2)}"
                    is TitanEvent.GestureDispatched -> "👆 ${event.type} ok=${event.success}"
                    is TitanEvent.ThermalStatus     -> "🌡 Thermal: ${event.level} fps=${event.targetFps}"
                    is TitanEvent.Error             -> "⚠ ${event.source}: ${event.message.take(60)}"
                    else -> null
                }
                label?.let {
                    val current = _recentEvents.value.takeLast(49)
                    _recentEvents.value = current + it
                }
            }
            .launchIn(viewModelScope)
    }

    fun startWorkflow(workflowId: String) = macroEngine.startWorkflow(workflowId)
    fun stopWorkflow(workflowId: String)  = macroEngine.stopWorkflow(workflowId)
    fun pauseAll()  = macroEngine.pauseAll()
    fun resumeAll() = macroEngine.resumeAll()

    fun importWorkflow(json: String) = viewModelScope.launch {
        runCatching {
            val def = parser.parse(json)
            repository.saveWorkflow(def)
        }
    }

    private fun Float.fmt(d: Int) = "%.${d}f".format(this)
}

// ── Root: shows Onboarding until all permissions are ready, then main app ─────

@Composable
private fun RootScreen(
    viewModel: MainViewModel,
    permViewModel: PermissionViewModel,
    builderViewModel: MacroBuilderViewModel,
    settingsViewModel: TitanSettingsViewModel,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onRequestProjection: () -> Unit,
    onRequestNotification: () -> Unit
) {
    val permissions by permViewModel.permissions.collectAsStateWithLifecycle()

    AnimatedContent(
        targetState = permissions.allGranted,
        transitionSpec = {
            fadeIn() + slideInVertically { it / 4 } togetherWith
            fadeOut() + slideOutVertically { -it / 4 }
        },
        label = "root"
    ) { allGranted ->
        if (allGranted) {
            TitanApp(
                viewModel              = viewModel,
                builderViewModel       = builderViewModel,
                settingsViewModel      = settingsViewModel,
                permissions            = permissions,
                onRequestOverlay       = onRequestOverlay,
                onRequestAccessibility = onRequestAccessibility,
                onRequestProjection    = onRequestProjection
            )
        } else {
            OnboardingScreen(
                permissions            = permissions,
                onRequestOverlay       = onRequestOverlay,
                onRequestAccessibility = onRequestAccessibility,
                onRequestProjection    = onRequestProjection,
                onRequestNotification  = onRequestNotification
            )
        }
    }
}

// ── Onboarding screen ─────────────────────────────────────────────────────────

@Composable
private fun OnboardingScreen(
    permissions: PermissionState,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onRequestProjection: () -> Unit,
    onRequestNotification: () -> Unit
) {
    Scaffold(containerColor = Color(0xFF0D1117)) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Spacer(Modifier.height(48.dp))

            // Header
            Icon(
                Icons.Default.AutoFixHigh,
                contentDescription = null,
                tint = Color(0xFF00E5FF),
                modifier = Modifier.size(56.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "TITAN AUTOMATION",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                color = Color(0xFF00E5FF)
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Grant the following permissions to unlock full automation.\nAll processing stays on-device — nothing leaves your phone.",
                fontSize = 13.sp,
                color = Color(0xFF8B949E),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(32.dp))

            val steps = buildOnboardingSteps(
                permissions            = permissions,
                onRequestOverlay       = onRequestOverlay,
                onRequestAccessibility = onRequestAccessibility,
                onRequestProjection    = onRequestProjection,
                onRequestNotification  = onRequestNotification
            )

            steps.forEachIndexed { idx, step ->
                PermissionStepCard(stepNumber = idx + 1, step = step)
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(16.dp))

            val allDone = permissions.allGranted
            if (allDone) {
                Text(
                    "✓ All permissions granted — you're ready!",
                    color = Color(0xFF69F0AE),
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            } else {
                Text(
                    "${permissions.missingCount} permission(s) remaining",
                    color = Color(0xFF546E7A),
                    fontSize = 13.sp
                )
            }
            Spacer(Modifier.height(48.dp))
        }
    }
}

private data class OnboardingStep(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val granted: Boolean,
    val buttonText: String,
    val onRequest: () -> Unit,
    val required: Boolean = true
)

@Composable
private fun buildOnboardingSteps(
    permissions: PermissionState,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onRequestProjection: () -> Unit,
    onRequestNotification: () -> Unit
): List<OnboardingStep> = buildList {
    add(OnboardingStep(
        title       = "Accessibility Service",
        description = "Required — enables TITAN to dispatch taps, swipes, and multi-touch gestures on your behalf. " +
                      "Open Settings → TITAN Automation → toggle ON.",
        icon        = Icons.Default.Accessibility,
        granted     = permissions.accessibilityGranted,
        buttonText  = "Open Accessibility Settings",
        onRequest   = onRequestAccessibility,
        required    = true
    ))
    add(OnboardingStep(
        title       = "Display Over Other Apps",
        description = "Required — allows the TITAN floating control panel (start/stop/panic button) " +
                      "to appear over any game or app while automation is running.",
        icon        = Icons.Default.Layers,
        granted     = permissions.overlayGranted,
        buttonText  = "Grant Overlay Permission",
        onRequest   = onRequestOverlay,
        required    = true
    ))
    add(OnboardingStep(
        title       = "Screen Capture (Android 10 only)",
        description = "Required on Android 10 — used for screen reading. " +
                      "On Android 11+, TITAN uses the Accessibility screenshot API instead " +
                      "(no extra permission needed).",
        icon        = Icons.Default.Screenshot,
        granted     = permissions.projectionGranted || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
        buttonText  = "Grant Screen Capture",
        onRequest   = onRequestProjection,
        required    = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
    ))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(OnboardingStep(
            title       = "Notifications",
            description = "Optional — shows a persistent status notification so you can monitor " +
                          "the engine and stop automation from the notification shade.",
            icon        = Icons.Default.Notifications,
            granted     = permissions.notificationsGranted,
            buttonText  = "Allow Notifications",
            onRequest   = onRequestNotification,
            required    = false
        ))
    }
}

@Composable
private fun PermissionStepCard(stepNumber: Int, step: OnboardingStep) {
    val borderColor = when {
        step.granted  -> Color(0xFF1B6B2F)
        step.required -> Color(0xFF37474F)
        else          -> Color(0xFF2C2C2C)
    }
    val bgColor = when {
        step.granted  -> Color(0xFF0C1F10)
        step.required -> Color(0xFF161B22)
        else          -> Color(0xFF111518)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(containerColor = bgColor),
        border   = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Step number / check icon
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(
                        if (step.granted) Color(0xFF1B6B2F) else Color(0xFF21262D),
                        shape = androidx.compose.foundation.shape.CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (step.granted) {
                    Icon(Icons.Default.Check, null, tint = Color(0xFF69F0AE), modifier = Modifier.size(18.dp))
                } else {
                    Text("$stepNumber", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8B949E))
                }
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(step.icon, null, tint = if (step.granted) Color(0xFF69F0AE) else Color(0xFF00E5FF),
                        modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        step.title,
                        fontWeight = FontWeight.SemiBold,
                        color      = if (step.granted) Color(0xFF69F0AE) else Color(0xFFE6EDF3),
                        fontSize   = 14.sp
                    )
                    if (!step.required) {
                        Spacer(Modifier.width(6.dp))
                        Badge(containerColor = Color(0xFF21262D)) {
                            Text("optional", fontSize = 9.sp, color = Color(0xFF8B949E))
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    step.description,
                    fontSize   = 12.sp,
                    color      = Color(0xFF8B949E),
                    lineHeight = 18.sp
                )
                if (!step.granted) {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        onClick  = step.onRequest,
                        modifier = Modifier.fillMaxWidth(),
                        colors   = ButtonDefaults.outlinedButtonColors(
                            contentColor = Color(0xFF00E5FF)
                        ),
                        border = BorderStroke(1.dp, Color(0xFF00E5FF).copy(alpha = 0.5f))
                    ) {
                        Icon(Icons.Default.OpenInNew, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(step.buttonText, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

// ── Main app ──────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TitanApp(
    viewModel: MainViewModel,
    builderViewModel: MacroBuilderViewModel,
    settingsViewModel: TitanSettingsViewModel,
    permissions: PermissionState,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    onRequestProjection: () -> Unit
) {
    val workflows  by viewModel.workflows.collectAsStateWithLifecycle()
    val events     by viewModel.recentEvents.collectAsStateWithLifecycle()
    val runningIds by viewModel.runningIds.collectAsStateWithLifecycle()

    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "TITAN AUTOMATION",
                        fontFamily  = FontFamily.Monospace,
                        fontWeight  = FontWeight.Bold,
                        color       = Color(0xFF00E5FF),
                        fontSize    = 16.sp
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0D1117)),
                actions = {
                    // Live permission status dots in top bar
                    PermissionDot(granted = permissions.accessibilityGranted, tooltip = "Accessibility")
                    PermissionDot(granted = permissions.overlayGranted, tooltip = "Overlay")
                    Spacer(Modifier.width(8.dp))
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF0D1117)) {
                listOf(
                    "Workflows" to Icons.Default.PlayArrow,
                    "Builder"   to Icons.Default.TouchApp,
                    "Live Log"  to Icons.Default.Terminal,
                    "Settings"  to Icons.Default.Settings
                ).forEachIndexed { i, (label, icon) ->
                    NavigationBarItem(
                        selected = selectedTab == i,
                        onClick  = { selectedTab = i },
                        icon     = { Icon(icon, label) },
                        label    = { Text(label, fontSize = 11.sp) }
                    )
                }
            }
        },
        containerColor = Color(0xFF0D1117)
    ) { padding ->
        when (selectedTab) {
            0 -> WorkflowsTab(
                    workflows   = workflows,
                    runningIds  = runningIds,
                    permissions = permissions,
                    onStart     = viewModel::startWorkflow,
                    onStop      = viewModel::stopWorkflow,
                    onRequestAccessibility = onRequestAccessibility,
                    modifier    = Modifier.padding(padding)
                )
            1 -> MacroBuilderScreen(
                    viewModel = builderViewModel,
                    modifier  = Modifier.padding(padding)
                )
            2 -> LiveLogTab(events = events, modifier = Modifier.padding(padding))
            3 -> SettingsTab(
                    permissions            = permissions,
                    settingsViewModel      = settingsViewModel,
                    onRequestProjection    = onRequestProjection,
                    onRequestOverlay       = onRequestOverlay,
                    onRequestAccessibility = onRequestAccessibility,
                    modifier               = Modifier.padding(padding)
                )
        }
    }
}

@Composable
private fun PermissionDot(granted: Boolean, tooltip: String) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(
                if (granted) Color(0xFF69F0AE) else Color(0xFFFF5252),
                shape = androidx.compose.foundation.shape.CircleShape
            )
    )
    Spacer(Modifier.width(4.dp))
}

// ── Workflows Tab ─────────────────────────────────────────────────────────────

@Composable
private fun WorkflowsTab(
    workflows  : List<WorkflowDefinition>,
    runningIds : Set<String>,
    permissions: PermissionState,
    onStart    : (String) -> Unit,
    onStop     : (String) -> Unit,
    onRequestAccessibility: () -> Unit,
    modifier   : Modifier = Modifier
) {
    LazyColumn(
        modifier            = modifier.fillMaxSize(),
        contentPadding      = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Prerequisite warning banner
        if (!permissions.accessibilityGranted) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1200)),
                    border = BorderStroke(1.dp, Color(0xFFFFC107))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, null, tint = Color(0xFFFFC107),
                            modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Accessibility Service required",
                                fontWeight = FontWeight.SemiBold,
                                color      = Color(0xFFFFC107),
                                fontSize   = 13.sp
                            )
                            Text(
                                "Workflows will not execute gestures until enabled.",
                                color    = Color(0xFF8B949E),
                                fontSize = 12.sp
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onRequestAccessibility) {
                            Text("Enable", color = Color(0xFFFFC107), fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        if (workflows.isEmpty()) {
            item {
                Box(
                    Modifier.fillMaxWidth().padding(top = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.FolderOff, null,
                            tint     = Color(0xFF37474F),
                            modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(12.dp))
                        Text("No workflows loaded", color = Color(0xFF546E7A))
                        Text(
                            "Drop JSON files into {externalFiles}/titan_workflows/\nor copy them to assets/workflows/ and reinstall.",
                            color    = Color(0xFF37474F),
                            fontSize = 12.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }

        items(workflows, key = { it.workflowId }) { wf ->
            val running = wf.workflowId in runningIds
            WorkflowCard(
                workflow        = wf,
                running         = running,
                canRun          = permissions.accessibilityGranted,
                onStart         = { onStart(wf.workflowId) },
                onStop          = { onStop(wf.workflowId) }
            )
        }
    }
}

@Composable
private fun WorkflowCard(
    workflow: WorkflowDefinition,
    running: Boolean,
    canRun: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (running) Color(0xFF0D2137) else Color(0xFF161B22)
        ),
        border = when {
            running -> BorderStroke(1.dp, Color(0xFF00E5FF))
            else    -> null
        }
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    workflow.workflowId,
                    fontWeight = FontWeight.SemiBold,
                    color      = Color(0xFFE6EDF3),
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Badge(containerColor = Color(0xFF21262D)) {
                        Text("${workflow.states.size} states", fontSize = 10.sp)
                    }
                    if (workflow.rlGlobalEnabled) {
                        Badge(containerColor = Color(0xFF1B3A1F)) {
                            Text("RL", color = Color(0xFF69F0AE), fontSize = 10.sp)
                        }
                    }
                    Badge(containerColor = Color(0xFF21262D)) {
                        Text("v${workflow.version}", fontSize = 10.sp)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            FilledIconButton(
                onClick = if (running) onStop else onStart,
                enabled = running || canRun,
                colors  = IconButtonDefaults.filledIconButtonColors(
                    containerColor         = if (running) Color(0xFFB71C1C) else Color(0xFF1B5E20),
                    disabledContainerColor = Color(0xFF1C2128)
                )
            ) {
                Icon(
                    if (running) Icons.Default.Stop else Icons.Default.PlayArrow,
                    contentDescription = if (running) "Stop" else "Start",
                    tint = if (!running && !canRun) Color(0xFF37474F) else Color.White
                )
            }
        }
    }
}

// ── Live Log Tab ──────────────────────────────────────────────────────────────

@Composable
private fun LiveLogTab(events: List<String>, modifier: Modifier = Modifier) {
    if (events.isEmpty()) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.Terminal, null,
                    tint     = Color(0xFF37474F),
                    modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(8.dp))
                Text("No events yet", color = Color(0xFF546E7A), fontSize = 13.sp)
                Text("Start a workflow to see live logs", color = Color(0xFF37474F), fontSize = 12.sp)
            }
        }
    } else {
        LazyColumn(
            modifier      = modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            reverseLayout = true
        ) {
            items(events.reversed()) { entry ->
                Text(
                    entry,
                    fontSize   = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color      = logEntryColor(entry),
                    modifier   = Modifier.padding(vertical = 2.dp)
                )
            }
        }
    }
}

private fun logEntryColor(entry: String): Color = when {
    entry.startsWith("⚠")  -> Color(0xFFFF8A65)
    entry.startsWith("▶")  -> Color(0xFF69F0AE)
    entry.startsWith("■")  -> Color(0xFFFF5252)
    entry.startsWith("👁") -> Color(0xFF00E5FF)
    entry.startsWith("🤖") -> Color(0xFFCE93D8)
    entry.startsWith("🌡") -> Color(0xFFFFCC02)
    else                   -> Color(0xFFB0BEC5)
}

// ── Settings Tab ──────────────────────────────────────────────────────────────

@Composable
private fun SettingsTab(
    permissions: PermissionState,
    settingsViewModel: TitanSettingsViewModel,
    onRequestProjection: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit,
    modifier: Modifier = Modifier
) {
    val rlEnabled      by settingsViewModel.rlEnabled.collectAsStateWithLifecycle()
    val captureFps     by settingsViewModel.captureFps.collectAsStateWithLifecycle()
    val showDots       by settingsViewModel.defaultShowDots.collectAsStateWithLifecycle()
    val jitter         by settingsViewModel.defaultJitterEnabled.collectAsStateWithLifecycle()
    val jitterRadius   by settingsViewModel.defaultJitterRadius.collectAsStateWithLifecycle()
    val speed          by settingsViewModel.defaultSpeed.collectAsStateWithLifecycle()
    val respectThermal by settingsViewModel.respectThermal.collectAsStateWithLifecycle()
    val touchNoise     by settingsViewModel.touchNoiseStdDev.collectAsStateWithLifecycle()
    val thermal        by settingsViewModel.thermalState.collectAsStateWithLifecycle()
    val scheduled      by settingsViewModel.scheduledJobs.collectAsStateWithLifecycle()

    val SURFACE  = Color(0xFF161B22)
    val BORDER   = Color(0xFF30363D)
    val CYAN     = Color(0xFF00E5FF)
    val GREEN    = Color(0xFF69F0AE)
    val AMBER    = Color(0xFFFFB300)
    val RED      = Color(0xFFF44336)
    val MUTED    = Color(0xFF8B949E)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Permissions ───────────────────────────────────────────────────────
        Text("Permissions", fontWeight = FontWeight.Bold, color = CYAN, fontSize = 13.sp)
        Card(
            colors = CardDefaults.cardColors(containerColor = SURFACE),
            border = BorderStroke(1.dp, BORDER)
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                SettingsPermissionRow("Accessibility Service",
                    permissions.accessibilityGranted, onRequestAccessibility, accentColor = CYAN)
                HorizontalDivider(color = BORDER, modifier = Modifier.padding(horizontal = 16.dp))
                SettingsPermissionRow("Display Over Other Apps",
                    permissions.overlayGranted, onRequestOverlay, accentColor = CYAN)
                HorizontalDivider(color = BORDER, modifier = Modifier.padding(horizontal = 16.dp))
                SettingsPermissionRow("Screen Capture",
                    permissions.projectionGranted || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                    onRequestProjection, accentColor = CYAN)
            }
        }

        // ── Capture + Vision ──────────────────────────────────────────────────
        Text("Capture & Vision", fontWeight = FontWeight.Bold, color = CYAN, fontSize = 13.sp)
        Card(
            colors = CardDefaults.cardColors(containerColor = SURFACE),
            border = BorderStroke(1.dp, BORDER)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SettingsSliderRow(
                    label    = "Frame rate: $captureFps fps",
                    value    = captureFps.toFloat(),
                    range    = 1f..30f,
                    steps    = 28,
                    color    = CYAN,
                    onChange = { settingsViewModel.setCaptureFps(it.toInt()) }
                )
                HorizontalDivider(color = BORDER)
                SettingsToggleRow(
                    label    = "Reinforcement learning (Q-learning)",
                    sub      = "AI adapts gesture decisions at runtime",
                    checked  = rlEnabled,
                    color    = CYAN,
                    onChange = settingsViewModel::setRlEnabled
                )
            }
        }

        // ── Macro defaults ────────────────────────────────────────────────────
        Text("Macro defaults", fontWeight = FontWeight.Bold, color = CYAN, fontSize = 13.sp)
        Card(
            colors = CardDefaults.cardColors(containerColor = SURFACE),
            border = BorderStroke(1.dp, BORDER)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SettingsToggleRow(
                    label    = "Show tap dots",
                    sub      = "Visual circles overlaid at each tap point",
                    checked  = showDots,
                    color    = CYAN,
                    onChange = settingsViewModel::setDefaultShowDots
                )
                HorizontalDivider(color = BORDER)
                SettingsToggleRow(
                    label    = "Anti-detection jitter",
                    sub      = "Random offset added to every tap",
                    checked  = jitter,
                    color    = CYAN,
                    onChange = settingsViewModel::setDefaultJitterEnabled
                )
                if (jitter) {
                    SettingsSliderRow(
                        label    = "Jitter radius: ${jitterRadius.toInt()}px",
                        value    = jitterRadius,
                        range    = 1f..15f,
                        steps    = 13,
                        color    = CYAN,
                        onChange = settingsViewModel::setDefaultJitterRadius
                    )
                }
                HorizontalDivider(color = BORDER)
                SettingsSliderRow(
                    label    = "Default speed: ${speed}x",
                    value    = speed,
                    range    = 0.25f..4f,
                    steps    = 14,
                    color    = AMBER,
                    onChange = settingsViewModel::setDefaultSpeed
                )
                HorizontalDivider(color = BORDER)
                SettingsToggleRow(
                    label    = "Pause on thermal throttle",
                    sub      = "Halts macros when device overheats",
                    checked  = respectThermal,
                    color    = AMBER,
                    onChange = settingsViewModel::setRespectThermal
                )
                HorizontalDivider(color = BORDER)
                SettingsSliderRow(
                    label    = "Touch-noise σ: ${touchNoise.toInt()}px",
                    value    = touchNoise,
                    range    = 0f..10f,
                    steps    = 9,
                    color    = MUTED,
                    onChange = settingsViewModel::setTouchNoise
                )
            }
        }

        // ── Thermal monitor ───────────────────────────────────────────────────
        Text("Thermal monitor", fontWeight = FontWeight.Bold, color = CYAN, fontSize = 13.sp)
        Card(
            colors = CardDefaults.cardColors(containerColor = SURFACE),
            border = BorderStroke(1.dp, when (thermal.thermalLevel.ordinal) {
                in 3..4 -> RED.copy(alpha = 0.6f)
                2       -> AMBER.copy(alpha = 0.6f)
                else    -> BORDER
            })
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Level: ${thermal.thermalLevel.name}",
                        color      = when (thermal.thermalLevel.ordinal) {
                            in 3..4 -> RED
                            2       -> AMBER
                            else    -> GREEN
                        },
                        fontWeight = FontWeight.SemiBold, fontSize = 14.sp
                    )
                    Text("Target: ${thermal.targetFps} fps  |  RL: ${if (thermal.rlEnabled) "on" else "off"}",
                        color = MUTED, fontSize = 11.sp)
                }
                Icon(
                    if (thermal.thermalLevel.ordinal >= 3) Icons.Default.Warning else Icons.Default.CheckCircle,
                    null,
                    tint     = if (thermal.thermalLevel.ordinal >= 3) RED else MUTED,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        // ── Scheduled jobs ────────────────────────────────────────────────────
        if (scheduled.isNotEmpty()) {
            Text("Active schedules (${scheduled.size})", fontWeight = FontWeight.Bold, color = AMBER, fontSize = 13.sp)
            Card(
                colors = CardDefaults.cardColors(containerColor = SURFACE),
                border = BorderStroke(1.dp, AMBER.copy(alpha = 0.4f))
            ) {
                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                    scheduled.values.forEachIndexed { i, job ->
                        Row(
                            modifier          = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(job.macroName, color = Color.White, fontSize = 13.sp)
                                Text(job.progressLabel, color = AMBER.copy(alpha = 0.8f), fontSize = 10.sp)
                            }
                            IconButton(
                                onClick  = { settingsViewModel.cancelAllSchedules() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Close, "Cancel", tint = MUTED, modifier = Modifier.size(16.dp))
                            }
                        }
                        if (i < scheduled.size - 1) HorizontalDivider(color = BORDER, modifier = Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }

        // ── About ─────────────────────────────────────────────────────────────
        Text("About", fontWeight = FontWeight.Bold, color = CYAN, fontSize = 13.sp)
        Card(
            colors = CardDefaults.cardColors(containerColor = SURFACE),
            border = BorderStroke(1.dp, BORDER)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("TITAN Automation Framework", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("OpenCV · ML Kit OCR · TFLite · Q-Learning RL",
                    color = MUTED, fontSize = 12.sp)
                Text("All processing is 100% on-device.\nNo network access. No data leaves your phone.",
                    color = MUTED.copy(alpha = 0.7f), fontSize = 12.sp, lineHeight = 18.sp)
            }
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SettingsToggleRow(
    label: String,
    sub: String,
    checked: Boolean,
    color: Color,
    onChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Color(0xFFE6EDF3), fontSize = 14.sp)
            Text(sub, color = Color(0xFF8B949E), fontSize = 11.sp)
        }
        Switch(
            checked         = checked,
            onCheckedChange = onChange,
            colors          = SwitchDefaults.colors(
                checkedThumbColor  = color,
                checkedTrackColor  = color.copy(alpha = 0.4f)
            )
        )
    }
}

@Composable
private fun SettingsSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    color: Color,
    onChange: (Float) -> Unit
) {
    Column {
        Text(label, color = Color(0xFF8B949E), fontSize = 12.sp)
        Slider(
            value         = value,
            onValueChange = onChange,
            valueRange    = range,
            steps         = steps,
            colors        = SliderDefaults.colors(thumbColor = color, activeTrackColor = color)
        )
    }
}

@Composable
private fun SettingsPermissionRow(label: String, granted: Boolean, onClick: () -> Unit, accentColor: Color = Color(0xFF00E5FF)) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !granted, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color(0xFFE6EDF3), fontSize = 13.sp, modifier = Modifier.weight(1f))
        if (granted) {
            Icon(Icons.Default.CheckCircle, null, tint = Color(0xFF69F0AE), modifier = Modifier.size(20.dp))
        } else {
            OutlinedButton(
                onClick = onClick,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                border = BorderStroke(1.dp, accentColor.copy(alpha = 0.5f))
            ) {
                Text("Grant", color = accentColor, fontSize = 12.sp)
            }
        }
    }
}
