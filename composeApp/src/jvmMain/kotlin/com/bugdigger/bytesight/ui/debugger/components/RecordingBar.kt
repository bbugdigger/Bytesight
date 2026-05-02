package com.bugdigger.bytesight.ui.debugger.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bugdigger.bytesight.debugger.CursorMode
import com.bugdigger.bytesight.debugger.RecordingState

/**
 * Time-travel controls — record/stop, save/load, prev/next hit, replay-mode
 * status, resume-live.
 *
 * Sits between [ControlBar] and the panels in [DebuggerScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingBar(
    recordingState: RecordingState,
    cursorMode: CursorMode,
    eventCount: Int,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onClearRecording: () -> Unit,
    onPrevHit: () -> Unit,
    onNextHit: () -> Unit,
    onResumeLive: () -> Unit,
    onSave: () -> Unit,
    onLoad: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isRecording = recordingState == RecordingState.RECORDING
    val hasEvents = eventCount > 0
    val isReplaying = cursorMode is CursorMode.Replay

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Status badge — LIVE / REC ● / REPLAY @ seq=N
        StatusBadge(recordingState, cursorMode)
        Spacer(Modifier.width(12.dp))

        // Record toggle
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = {
                PlainTooltip {
                    Text(if (isRecording) "Stop capturing events" else "Start capturing events to a scrubbable timeline")
                }
            },
            state = rememberTooltipState(),
        ) {
            if (isRecording) {
                Button(
                    onClick = onStopRecording,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = Color.White,
                    ),
                ) { Text("■ Stop Rec") }
            } else {
                OutlinedButton(
                    onClick = onStartRecording,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                ) { Text("⏺ Rec") }
            }
        }

        Spacer(Modifier.width(8.dp))

        // Prev / Next hit
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("Jump to previous breakpoint hit on the focused thread") } },
            state = rememberTooltipState(),
        ) {
            OutlinedButton(
                onClick = onPrevHit,
                enabled = hasEvents,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) { Text("⏮ Prev hit") }
        }
        Spacer(Modifier.width(4.dp))
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("Jump to next breakpoint hit on the focused thread") } },
            state = rememberTooltipState(),
        ) {
            OutlinedButton(
                onClick = onNextHit,
                enabled = hasEvents,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) { Text("⏭ Next hit") }
        }

        if (isReplaying) {
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onResumeLive,
                colors = ButtonDefaults.buttonColors(contentColor = Color.White),
            ) { Text("↩ Resume Live") }
        }

        Spacer(Modifier.width(16.dp))

        // Save / Load
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("Save the current recording to a .btsrec file") } },
            state = rememberTooltipState(),
        ) {
            OutlinedButton(
                onClick = onSave,
                enabled = hasEvents,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) { Text("💾 Save") }
        }
        Spacer(Modifier.width(4.dp))
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("Load a previously saved .btsrec recording") } },
            state = rememberTooltipState(),
        ) {
            OutlinedButton(
                onClick = onLoad,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) { Text("📂 Load") }
        }

        if (hasEvents && !isRecording) {
            Spacer(Modifier.width(16.dp))
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Discard the current recording") } },
                state = rememberTooltipState(),
            ) {
                OutlinedButton(
                    onClick = onClearRecording,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Clear") }
            }
        }
    }
}

@Composable
private fun StatusBadge(state: RecordingState, mode: CursorMode) {
    val (label, color) = when {
        mode is CursorMode.Replay -> "REPLAY @ seq=${mode.sequenceId}" to MaterialTheme.colorScheme.tertiary
        state == RecordingState.RECORDING -> "REC ●" to MaterialTheme.colorScheme.error
        state == RecordingState.REPLAY -> "STOPPED" to MaterialTheme.colorScheme.secondary
        else -> "LIVE" to MaterialTheme.colorScheme.primary
    }
    Text(
        text = label,
        color = color,
        style = MaterialTheme.typography.labelLarge,
    )
}
