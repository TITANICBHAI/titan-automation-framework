package com.titan.automation.presentation.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * SettingsScreen — full AppSettings configuration UI.
 *
 * Sections: Capture, Detection, Anti-Detection, RL Engine, Battery & Thermal, Overlay.
 * All controls write immediately to DataStore via SettingsViewModel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onBack   : () -> Unit = {}
) {
    val settings by viewModel.settings.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.resetToDefaults() }) {
                        Text("Reset")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Capture settings
            SettingsSection("Capture") {
                SliderRow(
                    label    = "Target FPS",
                    value    = settings.targetFps.toFloat(),
                    range    = 3f..15f,
                    steps    = 11,
                    display  = "${settings.targetFps} fps",
                    onChange = { viewModel.setTargetFps(it.toInt()) }
                )
            }

            // Detection settings
            SettingsSection("Detection Thresholds") {
                SliderRow(
                    label    = "Template match threshold",
                    value    = settings.defaultTemplateThreshold,
                    range    = 0.5f..0.99f,
                    display  = "${"%.2f".format(settings.defaultTemplateThreshold)}",
                    onChange = { viewModel.setTemplateThreshold(it) }
                )
                SliderRow(
                    label    = "OCR confidence",
                    value    = settings.defaultOcrConfidence,
                    range    = 0.4f..0.99f,
                    display  = "${"%.2f".format(settings.defaultOcrConfidence)}",
                    onChange = { viewModel.setOcrConfidence(it) }
                )
            }

            // Anti-detection
            SettingsSection("Anti-Detection") {
                SwitchRow("Enable coordinate jitter", settings.jitterEnabled) {
                    viewModel.setJitterEnabled(it)
                }
                if (settings.jitterEnabled) {
                    SliderRow(
                        label    = "Jitter radius (px)",
                        value    = settings.jitterRadiusPx.toFloat(),
                        range    = 0f..20f,
                        steps    = 19,
                        display  = "${settings.jitterRadiusPx}px",
                        onChange = { viewModel.setJitterRadiusPx(it.toInt()) }
                    )
                }
                SwitchRow("Show tap dots", settings.showTapDots) {
                    viewModel.setShowTapDots(it)
                }
            }

            // RL engine
            SettingsSection("RL Engine") {
                SwitchRow("Enable RL optimization", settings.rlEnabled) {
                    viewModel.setRlEnabled(it)
                }
            }

            // Retry policy
            SettingsSection("Retry Policy") {
                SliderRow(
                    label    = "Max retries",
                    value    = settings.maxRetryCount.toFloat(),
                    range    = 1f..10f,
                    steps    = 8,
                    display  = "${settings.maxRetryCount}×",
                    onChange = { viewModel.setMaxRetryCount(it.toInt()) }
                )
            }

            // Battery & thermal
            SettingsSection("Battery & Thermal") {
                SwitchRow("Respect thermal limits", settings.respectThermal) {
                    viewModel.setRespectThermal(it)
                }
                SwitchRow("Charging-only mode", settings.chargingOnlyMode) {
                    viewModel.setChargingOnlyMode(it)
                }
                SliderRow(
                    label    = "Pause below battery level",
                    value    = settings.batteryPauseThreshold.toFloat(),
                    range    = 5f..30f,
                    steps    = 24,
                    display  = "${settings.batteryPauseThreshold}%",
                    onChange = { viewModel.setBatteryPauseThreshold(it.toInt()) }
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Reusable components ────────────────────────────────────────────────────────

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp,
        color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(6.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            content()
        }
    }
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(
        modifier          = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 13.sp)
        Switch(checked = checked, onCheckedChange = onChanged)
    }
}

@Composable
private fun SliderRow(
    label  : String,
    value  : Float,
    range  : ClosedFloatingPointRange<Float>,
    steps  : Int     = 0,
    display: String,
    onChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), fontSize = 13.sp)
            Spacer(Modifier.width(8.dp))
            Text(display, fontSize = 12.sp,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value        = value,
            onValueChange = onChange,
            valueRange   = range,
            steps        = steps,
            modifier     = Modifier.fillMaxWidth()
        )
    }
}
