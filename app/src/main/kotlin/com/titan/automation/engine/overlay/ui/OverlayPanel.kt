package com.titan.automation.engine.overlay.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.titan.automation.engine.governor.ThermalLevel
import com.titan.automation.engine.overlay.OverlayViewModel

/**
 * OverlayPanel — Compose root composable for the TITAN floating overlay.
 *
 * Two modes:
 *   Minimized: 48×48dp translucent "T" badge — tap to expand.
 *   Expanded:  Draggable panel with FPS counter, detection log, controls, PANIC button.
 *
 * Drag is handled in [OverlayService] by modifying WindowManager.LayoutParams directly.
 * This composable only reports drag delta via [onDrag]; it does NOT use translationX/Y.
 *
 * Touch pass-through: the root Box uses pointerInput only on interactive elements,
 * so touches on empty areas fall through to the game below.
 */
@Composable
fun OverlayPanel(
    viewModel : OverlayViewModel,
    onDrag    : (dx: Float, dy: Float) -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    if (state.isMinimized) {
        MinimizedBadge(onClick = { viewModel.toggleMinimized() })
    } else {
        ExpandedPanel(
            state      = state,
            onMinimize = { viewModel.toggleMinimized() },
            onToggle   = { viewModel.toggleRunning() },
            onPanic    = { viewModel.panicStop() },
            onDrag     = onDrag
        )
    }
}

@Composable
private fun MinimizedBadge(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .background(Color(0xCC1A1A2E), CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { /* handled by parent */ }
                ) { _, _ -> }
            },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.FilledTonalButton(
            onClick  = onClick,
            modifier = Modifier.size(48.dp),
            shape    = CircleShape
        ) {
            Text("T", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
    }
}

@Composable
private fun ExpandedPanel(
    state     : OverlayViewModel.OverlayUiState,
    onMinimize: () -> Unit,
    onToggle  : () -> Unit,
    onPanic   : () -> Unit,
    onDrag    : (Float, Float) -> Unit
) {
    Column(
        modifier = Modifier
            .width(220.dp)
            .background(Color(0xEE0D0D1A), RoundedCornerShape(12.dp))
            .padding(8.dp)
    ) {
        // Draggable header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectDragGestures { _, dragAmount ->
                        onDrag(dragAmount.x, dragAmount.y)
                    }
                },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            Text(
                text       = "TITAN",
                color      = Color(0xFF7B68EE),
                fontWeight = FontWeight.Bold,
                fontSize   = 14.sp
            )
            ThermalIndicator(state.thermalLevel)
            androidx.compose.material3.IconButton(onClick = onMinimize, modifier = Modifier.size(24.dp)) {
                Text("—", color = Color.White, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(4.dp))

        // FPS + step row
        Row(verticalAlignment = Alignment.CenterVertically) {
            FpsMeter(fps = state.currentFps)
            Spacer(Modifier.width(8.dp))
            Text(
                text     = state.currentStep,
                color    = Color(0xAAFFFFFF),
                fontSize = 10.sp,
                maxLines = 1
            )
        }

        Spacer(Modifier.height(4.dp))

        // Last detection
        Text(
            text     = state.lastDetection,
            color    = if (state.lastConfidence > 0.8f) Color(0xFF80FF80) else Color(0xAAFFFFFF),
            fontSize = 10.sp,
            maxLines = 2
        )

        Spacer(Modifier.height(4.dp))

        // Detection log feed
        DetectionLogFeed(entries = state.logEntries)

        Spacer(Modifier.height(6.dp))

        // Control row: Start/Pause + battery
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment     = Alignment.CenterVertically
        ) {
            androidx.compose.material3.FilledTonalButton(
                onClick  = onToggle,
                modifier = Modifier.weight(1f).height(32.dp)
            ) {
                Text(
                    if (state.isRunning) "⏸ Pause" else "▶ Start",
                    fontSize = 11.sp
                )
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text     = "${state.batteryPct}%${if (state.isCharging) "⚡" else ""}",
                color    = if (state.batteryPct < 20) Color.Yellow else Color(0x88FFFFFF),
                fontSize = 10.sp
            )
        }

        Spacer(Modifier.height(4.dp))

        // PANIC STOP
        PanicButton(onPanic = onPanic)
    }
}

@Composable
private fun ThermalIndicator(level: ThermalLevel) {
    val color = when (level) {
        ThermalLevel.NORMAL   -> Color(0xFF00CC44)
        ThermalLevel.LIGHT    -> Color(0xFF88CC00)
        ThermalLevel.MODERATE -> Color(0xFFFFAA00)
        ThermalLevel.SEVERE   -> Color(0xFFFF4400)
        ThermalLevel.CRITICAL -> Color(0xFFFF0000)
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color, CircleShape)
    )
}
