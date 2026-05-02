package com.bugdigger.bytesight.ui.debugger.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.bugdigger.bytesight.debugger.CursorMode
import com.bugdigger.protocol.DebuggerEvent

/**
 * Bottom timeline scrubber. Each tick is one event in the recording log
 * (color-coded by kind: hit / step / thread). Click a tick or drag the
 * playhead to seek to that sequence id.
 *
 * Renders nothing when [events] is empty.
 */
@Composable
fun Timeline(
    events: List<DebuggerEvent>,
    cursorMode: CursorMode,
    onSeek: (sequenceId: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (events.isEmpty()) return

    val firstSeq = events.first().sequenceId
    val lastSeq = events.last().sequenceId
    val span = (lastSeq - firstSeq).coerceAtLeast(1L)

    val playhead: Long = when (cursorMode) {
        is CursorMode.Replay -> cursorMode.sequenceId
        is CursorMode.Live -> lastSeq
    }

    val tickColor = MaterialTheme.colorScheme.outline
    val hitColor = MaterialTheme.colorScheme.error
    val stepColor = MaterialTheme.colorScheme.primary
    val threadColor = MaterialTheme.colorScheme.secondary
    val playheadColor = MaterialTheme.colorScheme.tertiary
    val bgColor = MaterialTheme.colorScheme.surfaceVariant

    fun seqAtX(x: Float, width: Float): Long {
        val frac = (x / width).coerceIn(0f, 1f)
        return firstSeq + (frac * span).toLong()
    }

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                "Timeline (${events.size} events)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                "seq $firstSeq…$lastSeq · playhead $playhead",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .pointerInput(events.size, firstSeq, lastSeq) {
                    detectTapGestures { offset ->
                        val seq = seqAtX(offset.x, size.width.toFloat())
                        onSeek(seq)
                    }
                }
                .pointerInput(events.size, firstSeq, lastSeq) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            onSeek(seqAtX(offset.x, size.width.toFloat()))
                        },
                    ) { change, _ ->
                        change.consume()
                        onSeek(seqAtX(change.position.x, size.width.toFloat()))
                    }
                },
        ) {
            Canvas(modifier = Modifier.fillMaxWidth().height(36.dp)) {
                // Background track.
                drawRect(color = bgColor)

                val w = size.width
                val h = size.height

                // Subsample ticks if the recording is very dense — keep at most 500
                // visible to avoid Compose overdraw.
                val maxVisible = 500
                val stride = (events.size / maxVisible + 1).coerceAtLeast(1)
                var i = 0
                while (i < events.size) {
                    val evt = events[i]
                    val x = ((evt.sequenceId - firstSeq).toFloat() / span) * w
                    val color = when (evt.kindCase) {
                        DebuggerEvent.KindCase.HIT -> hitColor
                        DebuggerEvent.KindCase.STEP -> stepColor
                        DebuggerEvent.KindCase.THREAD -> threadColor
                        else -> tickColor
                    }
                    drawLine(
                        color = color,
                        start = Offset(x, h * 0.15f),
                        end = Offset(x, h * 0.85f),
                        strokeWidth = 1.5f,
                    )
                    i += stride
                }

                // Playhead.
                val playheadX = ((playhead - firstSeq).toFloat() / span) * w
                drawLine(
                    color = playheadColor,
                    start = Offset(playheadX, 0f),
                    end = Offset(playheadX, h),
                    strokeWidth = 2.5f,
                )
            }
        }
    }
}
