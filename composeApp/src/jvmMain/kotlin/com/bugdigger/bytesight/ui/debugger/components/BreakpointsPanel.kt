package com.bugdigger.bytesight.ui.debugger.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bugdigger.bytesight.debugger.DebuggerState
import com.bugdigger.protocol.MethodBreakpointMode

@Composable
fun BreakpointsPanel(
    breakpoints: List<DebuggerState.UiBreakpoint>,
    onToggleEnabled: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(Modifier.fillMaxSize().padding(12.dp)) {
            Text(
                "Breakpoints (${breakpoints.size})",
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.width(8.dp))
            if (breakpoints.isEmpty()) {
                Text(
                    "No breakpoints. Use Inspector's gutter to add one, or right-click a method in Class Browser.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(breakpoints, key = { it.id }) { bp ->
                        BreakpointRow(bp, onToggleEnabled, onRemove)
                    }
                }
            }
        }
    }
}

@Composable
private fun BreakpointRow(
    bp: DebuggerState.UiBreakpoint,
    onToggleEnabled: (String) -> Unit,
    onRemove: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = bp.enabled,
            onCheckedChange = { onToggleEnabled(bp.id) },
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = "${bp.className.substringAfterLast('.')}#${bp.methodName}" +
                    if (bp.displayLine > 0) ":${bp.displayLine}" else "",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            val modeLabel = when (bp.mode) {
                MethodBreakpointMode.METHOD_BP_ENTRY -> "entry"
                MethodBreakpointMode.METHOD_BP_EXIT -> "exit"
                MethodBreakpointMode.METHOD_BP_BOTH -> "entry+exit"
                else -> bp.mode.name
            }
            Text(
                text = "${bp.className}  •  $modeLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onRemove(bp.id) }) {
            Text("✕", style = MaterialTheme.typography.bodyLarge)
        }
    }
}
