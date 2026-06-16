package com.titan.automation.presentation.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.titan.automation.domain.model.WorkflowDefinition
import com.titan.automation.engine.governor.ThermalLevel

/**
 * DashboardScreen — main workflow management UI.
 *
 * Shows:
 *   - System status bar (thermal level, battery, FPS)
 *   - List of all saved workflows with start/stop controls
 *   - Recent execution log (last 10 entries)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel = hiltViewModel(),
    onNavigateToEditor: (String?) -> Unit = {},
    onNavigateToDebug : () -> Unit = {}
) {
    val state    by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbar.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TITAN Dashboard", fontWeight = FontWeight.Bold) },
                actions = {
                    SystemStatusBadge(
                        thermalLevel = state.thermalLevel,
                        batteryPct   = state.batteryPct,
                        isCharging   = state.isCharging
                    )
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            if (state.isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            // Workflow list
            Text("Workflows", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(state.workflows) { workflow ->
                    WorkflowCard(
                        workflow   = workflow,
                        isRunning  = workflow.id in state.runningWorkflows,
                        onStart    = { viewModel.startWorkflow(workflow.id) },
                        onStop     = { viewModel.stopWorkflow(workflow.id) },
                        onEdit     = { onNavigateToEditor(workflow.id) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
                item {
                    FilledTonalButton(
                        onClick  = { onNavigateToEditor(null) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("+ New Workflow")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Recent activity log
            if (state.recentLogs.isNotEmpty()) {
                Text("Recent Activity", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        state.recentLogs.takeLast(5).forEach { log ->
                            Text(log, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WorkflowCard(
    workflow : WorkflowDefinition,
    isRunning: Boolean,
    onStart  : () -> Unit,
    onStop   : () -> Unit,
    onEdit   : () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit)) {
        Row(
            modifier              = Modifier.padding(12.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(workflow.id, fontWeight = FontWeight.SemiBold)
                Text(
                    "${workflow.states.size} steps",
                    fontSize = 11.sp,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isRunning) {
                Box(
                    modifier = Modifier.size(8.dp).background(Color(0xFF00CC44), CircleShape)
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onStop) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Color.Red)
                }
            } else {
                IconButton(onClick = onStart) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Start", tint = Color(0xFF00CC44))
                }
            }
        }
    }
}

@Composable
private fun SystemStatusBadge(
    thermalLevel: ThermalLevel,
    batteryPct  : Int,
    isCharging  : Boolean
) {
    val thermalColor = when (thermalLevel) {
        ThermalLevel.NORMAL   -> Color(0xFF00CC44)
        ThermalLevel.LIGHT    -> Color(0xFF88CC00)
        ThermalLevel.MODERATE -> Color(0xFFFFAA00)
        ThermalLevel.SEVERE   -> Color(0xFFFF4400)
        ThermalLevel.CRITICAL -> Color(0xFFFF0000)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier          = Modifier.padding(end = 8.dp)
    ) {
        Box(Modifier.size(8.dp).background(thermalColor, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(
            text     = "$batteryPct%${if (isCharging) "⚡" else ""}",
            fontSize = 12.sp,
            color    = if (batteryPct < 20) Color.Yellow else MaterialTheme.colorScheme.onSurface
        )
    }
}
