package com.bugdigger.bytesight.ui.debugger.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bugdigger.protocol.FrameSnapshot
import com.bugdigger.protocol.Variable

@Composable
fun VariablesPanel(
    frame: FrameSnapshot?,
    onAskAI: (String) -> Unit,
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
                "Variables",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            if (frame == null) {
                Text(
                    "Select a stack frame to inspect its arguments and `this` fields.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize()) {
                if (frame.argumentsList.isNotEmpty()) {
                    item {
                        SectionHeader("arguments (${frame.argumentsList.size})")
                    }
                    items(frame.argumentsList) { v -> VariableRow(v) }
                }
                if (frame.thisFieldsList.isNotEmpty()) {
                    item { SectionHeader("this fields (${frame.thisFieldsList.size})") }
                    items(frame.thisFieldsList) { v -> VariableRow(v) }
                }
                if (frame.argumentsList.isEmpty() && frame.thisFieldsList.isEmpty()) {
                    item {
                        Text(
                            text = "No variables captured at this frame. (v1 captures method args + instance fields only; full locals come in Phase 3.)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                item {
                    val prompt = buildAIPrompt(frame)
                    Text(
                        text = "Ask AI",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    )
                    androidx.compose.material3.TextButton(onClick = { onAskAI(prompt) }) {
                        Text("Explain this frame", color = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun VariableRow(v: Variable) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp, horizontal = 4.dp)) {
        Text(
            text = v.name,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = Color.White,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = "(${v.typeName.substringAfterLast('.')})",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(
            text = v.displayValue,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
            color = if (v.isNull) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
            modifier = Modifier.weight(1f),
        )
    }
}

private fun buildAIPrompt(frame: FrameSnapshot): String = buildString {
    appendLine("Explain what this paused stack frame is doing and what the values suggest.")
    appendLine()
    appendLine("Class: ${frame.className}")
    appendLine("Method: ${frame.methodName}${frame.signature}")
    if (frame.lineNumber > 0) appendLine("Line: ${frame.lineNumber}")
    if (frame.argumentsList.isNotEmpty()) {
        appendLine("Arguments:")
        frame.argumentsList.forEach { v ->
            appendLine("  ${v.name}: ${v.typeName} = ${if (v.isNull) "null" else v.displayValue}")
        }
    }
    if (frame.thisFieldsList.isNotEmpty()) {
        appendLine("`this` fields:")
        frame.thisFieldsList.forEach { v ->
            appendLine("  ${v.name}: ${v.typeName} = ${if (v.isNull) "null" else v.displayValue}")
        }
    }
}
