package com.bugdigger.bytesight.ui.debugger.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlBar(
    canResume: Boolean,
    suspendedCount: Int,
    breakpointCount: Int,
    onResumeAll: () -> Unit,
    onResumeCurrent: () -> Unit,
    onStop: () -> Unit,
    onStepOver: () -> Unit = {},
    onStepInto: () -> Unit = {},
    onStepOut: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onResumeCurrent,
            enabled = canResume,
            colors = ButtonDefaults.buttonColors(contentColor = Color.White),
        ) {
            Text("▶ Resume thread", color = Color.White)
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            onClick = onResumeAll,
            enabled = suspendedCount > 0,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
        ) {
            Text("▶▶ Resume all ($suspendedCount)", color = Color.White)
        }
        Spacer(Modifier.width(16.dp))
        // Step controls — enabled only when the current thread is suspended.
        // Step Over: next line in same method. Step Into: into a callee or
        // fall through to next line. Step Out: to current method's exit.
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("Step Over — next line in same method") } },
            state = rememberTooltipState(),
        ) {
            OutlinedButton(onClick = onStepOver, enabled = canResume,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                Text("⤳ Over", color = Color.White)
            }
        }
        Spacer(Modifier.width(4.dp))
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("Step Into — into a callee, or next line") } },
            state = rememberTooltipState(),
        ) {
            OutlinedButton(onClick = onStepInto, enabled = canResume,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                Text("↘ Into", color = Color.White)
            }
        }
        Spacer(Modifier.width(4.dp))
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("Step Out — to exit of current method") } },
            state = rememberTooltipState(),
        ) {
            OutlinedButton(onClick = onStepOut, enabled = canResume,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) {
                Text("↗ Out", color = Color.White)
            }
        }
        Spacer(Modifier.width(16.dp))
        OutlinedButton(
            onClick = onStop,
            enabled = breakpointCount > 0 || suspendedCount > 0,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text("■ Stop")
        }
        // Time-travel controls live in their own RecordingBar below; the stub
        // [⏺ Rec] button that used to live here has been replaced by it.
    }
}
