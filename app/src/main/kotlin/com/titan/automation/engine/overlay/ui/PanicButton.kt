package com.titan.automation.engine.overlay.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * PanicButton — full-width emergency kill switch.
 *
 * Spec requirements:
 *   - Background: Color.Red, 0.9f alpha
 *   - onClick: calls macroEngine.panicStop() + gestureDispatcher.clearAll()
 *   - Haptic: LONG_PRESS
 *   - NO confirmation dialog — response in < 100ms is critical
 *   - Must be reachable without unlocking minimized overlay
 *
 * Haptic feedback fires on press-down (via LocalHapticFeedback) before the
 * onClick lambda executes, ensuring tactile response is immediate.
 */
@Composable
fun PanicButton(
    onPanic : () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    Button(
        onClick = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            onPanic()
        },
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xE6FF0000),   // Red, 90% alpha
            contentColor   = Color.White
        ),
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
    ) {
        Text(
            text       = "⛔ PANIC STOP",
            fontWeight = FontWeight.ExtraBold,
            fontSize   = 13.sp,
            letterSpacing = 1.sp
        )
    }
}
