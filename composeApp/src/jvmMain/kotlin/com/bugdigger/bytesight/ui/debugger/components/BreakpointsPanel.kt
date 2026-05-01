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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bugdigger.bytesight.debugger.DebuggerState
import com.bugdigger.protocol.MethodBreakpointMode

@Composable
fun BreakpointsPanel(
    breakpoints: List<DebuggerState.UiBreakpoint>,
    onToggleEnabled: (String) -> Unit,
    onRemove: (String) -> Unit,
    onUpdateCondition: (String, String) -> Unit = { _, _ -> },
    onUpdateSkipCount: (String, Int) -> Unit = { _, _ -> },
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
                color = Color.White,
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
                        BreakpointRow(bp, onToggleEnabled, onRemove, onUpdateCondition, onUpdateSkipCount)
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
    onUpdateCondition: (String, String) -> Unit,
    onUpdateSkipCount: (String, Int) -> Unit,
) {
    // Local edit buffers — committed to the agent on Enter / focus loss.
    var conditionDraft by remember(bp.id) { mutableStateOf(bp.condition) }
    var skipDraft by remember(bp.id) { mutableStateOf(if (bp.skipCount == 0) "" else bp.skipCount.toString()) }

    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                    color = Color.White,
                )
                val modeLabel = when (bp.mode) {
                    MethodBreakpointMode.METHOD_BP_ENTRY -> "entry"
                    MethodBreakpointMode.METHOD_BP_EXIT -> "exit"
                    MethodBreakpointMode.METHOD_BP_BOTH -> "entry+exit"
                    else -> bp.mode.name
                }
                Text(
                    text = "${bp.className}  •  $modeLabel  •  hits: ${bp.hitCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onRemove(bp.id) }) {
                Text("✕", style = MaterialTheme.typography.bodyLarge, color = Color.White)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 40.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = conditionDraft,
                onValueChange = { conditionDraft = it },
                modifier = Modifier.weight(1f),
                label = { Text("condition (e.g. arg0 > 5)", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.White),
                isError = bp.conditionError != null,
                supportingText = if (bp.conditionError != null) {
                    { Text(bp.conditionError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                } else null,
            )
            OutlinedTextField(
                value = skipDraft,
                onValueChange = { skipDraft = it.filter { c -> c.isDigit() } },
                modifier = Modifier.width(96.dp),
                label = { Text("skip", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = Color.White),
            )
            IconButton(
                onClick = {
                    if (conditionDraft != bp.condition) onUpdateCondition(bp.id, conditionDraft)
                    val newSkip = skipDraft.toIntOrNull() ?: 0
                    if (newSkip != bp.skipCount) onUpdateSkipCount(bp.id, newSkip)
                },
            ) {
                Text("✓", color = Color.White)
            }
        }
    }
}
