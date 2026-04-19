package com.bugdigger.bytesight.ui.debugger.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bugdigger.bytesight.debugger.ThreadView
import com.bugdigger.protocol.ThreadState

@Composable
fun ThreadsPanel(
    threads: List<ThreadView>,
    currentThreadId: Long?,
    onSelect: (Long) -> Unit,
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
                "Threads (${threads.size})",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            if (threads.isEmpty()) {
                Text(
                    "No observed threads yet. Threads appear here once they hit a breakpoint or emit a state change.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(threads, key = { it.id }) { t ->
                        ThreadRow(
                            thread = t,
                            selected = t.id == currentThreadId,
                            onClick = { onSelect(t.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadRow(thread: ThreadView, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = thread.state.glyph(),
            modifier = Modifier.padding(end = 8.dp),
            color = Color.White,
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = thread.name.ifBlank { "thread-${thread.id}" },
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = Color.White,
            )
            Text(
                text = thread.state.label() + "  •  tid=${thread.id}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun ThreadState.glyph(): String = when (this) {
    ThreadState.THREAD_STATE_SUSPENDED -> "⏸"
    ThreadState.THREAD_STATE_RUNNING -> "▶"
    ThreadState.THREAD_STATE_WAITING -> "…"
    ThreadState.THREAD_STATE_BLOCKED -> "⛔"
    ThreadState.THREAD_STATE_TERMINATED -> "■"
    else -> "?"
}

private fun ThreadState.label(): String = when (this) {
    ThreadState.THREAD_STATE_SUSPENDED -> "SUSPENDED"
    ThreadState.THREAD_STATE_RUNNING -> "RUNNING"
    ThreadState.THREAD_STATE_WAITING -> "WAITING"
    ThreadState.THREAD_STATE_BLOCKED -> "BLOCKED"
    ThreadState.THREAD_STATE_TERMINATED -> "TERMINATED"
    else -> "UNKNOWN"
}
