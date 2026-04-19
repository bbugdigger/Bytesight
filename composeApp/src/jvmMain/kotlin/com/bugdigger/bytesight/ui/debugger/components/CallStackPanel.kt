package com.bugdigger.bytesight.ui.debugger.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bugdigger.protocol.FrameSnapshot

@Composable
fun CallStackPanel(
    frames: List<FrameSnapshot>,
    selectedFrame: FrameSnapshot?,
    onSelectFrame: (Int) -> Unit,
    onOpenInInspector: (FrameSnapshot) -> Unit,
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
                "Call Stack (${frames.size})",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            if (frames.isEmpty()) {
                Text(
                    "No frame. Hit a breakpoint to populate this panel.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(frames) { index, frame ->
                        val selected = frame === selectedFrame
                        FrameRow(
                            depth = index,
                            frame = frame,
                            selected = selected,
                            onClick = { onSelectFrame(index) },
                            onOpenInInspector = { onOpenInInspector(frame) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FrameRow(
    depth: Int,
    frame: FrameSnapshot,
    selected: Boolean,
    onClick: () -> Unit,
    onOpenInInspector: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "#$depth",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = "${frame.className.substringAfterLast('.')}.${frame.methodName}" +
                    if (frame.lineNumber > 0) ":${frame.lineNumber}" else "",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = Color.White,
            )
            Text(
                text = frame.className,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onOpenInInspector) {
            Text("Inspect", color = Color.White)
        }
    }
}
