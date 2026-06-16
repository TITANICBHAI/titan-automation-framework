package com.titan.automation.presentation.debug

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.titan.automation.domain.model.DetectionResult

/**
 * DebugScreen — frame preview, OCR overlay, coordinate inspector, step stats.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    viewModel: DebugViewModel = hiltViewModel(),
    onBack   : () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug Inspector") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
            // Frame capture controls
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledTonalButton(onClick = { viewModel.captureFrame() }) {
                    if (state.isCapturing) CircularProgressIndicator(Modifier.size(16.dp))
                    else {
                        Icon(Icons.Default.Camera, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Capture Frame")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Frame preview with detection overlay
            state.frame?.let { bmp ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(bmp.width.toFloat() / bmp.height.toFloat())
                    ) {
                        val found = state.detectionResult as? DetectionResult.Found
                        Image(
                            bitmap   = bmp.asImageBitmap(),
                            contentDescription = "Captured frame",
                            modifier = Modifier
                                .fillMaxSize()
                                .drawWithContent {
                                    drawContent()
                                    found?.let { r ->
                                        val scaleX = size.width  / bmp.width
                                        val scaleY = size.height / bmp.height
                                        drawRect(
                                            color   = Color(0xFF00FF88),
                                            topLeft = Offset(r.bounds.left * scaleX, r.bounds.top * scaleY),
                                            size    = Size(r.bounds.width() * scaleX, r.bounds.height() * scaleY),
                                            style   = Stroke(width = 2f)
                                        )
                                    }
                                    // Tap crosshair
                                    if (state.lastTapNx > 0f || state.lastTapNy > 0f) {
                                        val cx = state.lastTapNx * size.width
                                        val cy = state.lastTapNy * size.height
                                        drawCircle(Color.Red, radius = 8f, center = Offset(cx, cy))
                                    }
                                }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // OCR text search
            var ocrQuery by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value         = ocrQuery,
                    onValueChange = { ocrQuery = it },
                    label         = { Text("OCR Search") },
                    modifier      = Modifier.weight(1f),
                    singleLine    = true
                )
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = { viewModel.findText(ocrQuery) }) {
                    Icon(Icons.Default.Search, contentDescription = "Search")
                }
            }

            if (state.ocrText.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(state.ocrText, color = Color(0xFF80FF80), fontSize = 12.sp)
            }

            Spacer(Modifier.height(12.dp))

            // Step performance stats
            if (state.recentStepStats.isNotEmpty()) {
                Text("Step Performance", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                state.recentStepStats.take(5).forEach { stats ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text(stats.stepId, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                            Text(
                                "✓ ${stats.successes} ✗ ${stats.failures} | " +
                                "avg ${stats.avgLatencyMs}ms | retry ${(stats.retryRate * 100).toInt()}%",
                                fontSize = 10.sp,
                                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Recent log
            if (state.logs.isNotEmpty()) {
                Text("Recent Logs", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        state.logs.takeLast(10).forEach { log ->
                            Text(log, fontSize = 9.sp, fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
