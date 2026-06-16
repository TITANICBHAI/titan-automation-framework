package com.titan.automation.presentation.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * WorkflowEditorScreen — full-screen JSON workflow editor.
 *
 * Features:
 *   - Mono-space text editor for raw JSON
 *   - Live syntax validation (error shown inline below editor)
 *   - Format/prettify button
 *   - Save button with loading state
 *   - Template loader for new workflows
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowEditorScreen(
    viewModel : WorkflowEditorViewModel = hiltViewModel(),
    onBack    : () -> Unit = {}
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.saveSuccess) {
        if (state.saveSuccess) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (state.workflowId != null) "Edit: ${state.workflowId}" else "New Workflow",
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.formatJson() }) {
                        Icon(Icons.Default.Code, contentDescription = null)
                        Text("Format")
                    }
                    IconButton(
                        onClick  = { viewModel.save() },
                        enabled  = !state.isSaving && state.isDirty
                    ) {
                        Icon(
                            if (state.saveSuccess) Icons.Default.Check else Icons.Default.Save,
                            contentDescription = "Save",
                            tint = if (state.saveSuccess) Color(0xFF00CC44)
                                   else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Validation error banner
            if (state.validationError != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x22FF4444))
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        text     = "⚠ ${state.validationError}",
                        color    = Color(0xFFFF8888),
                        fontSize = 11.sp
                    )
                }
            } else if (state.parsedWorkflow != null && state.isDirty) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x2200CC44))
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                ) {
                    Text("✓ Valid — ${state.parsedWorkflow?.states?.size ?: 0} steps",
                        color = Color(0xFF88FF88), fontSize = 11.sp)
                }
            }

            // JSON editor
            val vScroll = rememberScrollState()
            val hScroll = rememberScrollState()

            BasicTextField(
                value         = state.jsonText,
                onValueChange = { viewModel.onJsonChanged(it) },
                modifier      = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(vScroll)
                    .horizontalScroll(hScroll)
                    .padding(16.dp),
                textStyle     = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize   = 13.sp,
                    color      = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 20.sp
                )
            )

            Spacer(Modifier.height(8.dp))

            // Bottom action bar
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = { viewModel.loadTemplate() }) {
                    Text("Load Template")
                }
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                FilledTonalButton(
                    onClick  = { viewModel.save() },
                    enabled  = !state.isSaving && state.isDirty && state.validationError == null
                ) {
                    Text(if (state.isSaving) "Saving…" else "Save Workflow")
                }
            }
        }
    }
}
