package com.titan.automation.engine.overlay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * DetectionLogFeed — scrollable log of the last N detection events.
 *
 * Backed by a [List<String>] emitted by [OverlayViewModel] (max 20 entries).
 * Auto-scrolls to the latest entry on each update.
 */
@Composable
fun DetectionLogFeed(
    entries : List<String>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new entries arrive
    LaunchedEffect(entries.size) {
        if (entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.size - 1)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(Color(0x22FFFFFF), RoundedCornerShape(6.dp))
            .padding(4.dp)
    ) {
        LazyColumn(state = listState) {
            items(entries) { entry ->
                val color = when {
                    entry.startsWith("✓") || entry.startsWith("▶") -> Color(0xFF88FF88)
                    entry.startsWith("✗") || entry.startsWith("⚠") -> Color(0xFFFF8888)
                    entry.startsWith("🛑")                         -> Color(0xFFFF4444)
                    else                                            -> Color(0xAAFFFFFF)
                }
                Text(
                    text       = entry,
                    color      = color,
                    fontSize   = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines   = 1
                )
            }
        }
    }
}
