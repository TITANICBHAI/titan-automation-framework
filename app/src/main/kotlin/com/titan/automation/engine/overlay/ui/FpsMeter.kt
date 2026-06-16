package com.titan.automation.engine.overlay.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * FpsMeter — real-time FPS display composable for the floating overlay.
 *
 * Color coding:
 *   >= 8 FPS  → green  (healthy capture rate)
 *   >= 5 FPS  → yellow (throttled)
 *   < 5 FPS   → red    (critically throttled — thermal or battery)
 */
@Composable
fun FpsMeter(fps: Int, modifier: Modifier = Modifier) {
    val color = when {
        fps >= 8 -> Color(0xFF00CC44)
        fps >= 5 -> Color(0xFFFFAA00)
        fps >  0 -> Color(0xFFFF4444)
        else     -> Color(0x66FFFFFF)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier          = modifier
    ) {
        Text(
            text       = fps.toString(),
            color      = color,
            fontSize   = 13.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.width(2.dp))
        Text(
            text     = "fps",
            color    = Color(0x66FFFFFF),
            fontSize = 9.sp
        )
    }
}
