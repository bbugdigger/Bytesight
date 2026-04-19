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
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(
            onClick = onResumeCurrent,
            enabled = canResume,
        ) {
            Text("▶ Resume thread")
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            onClick = onResumeAll,
            enabled = suspendedCount > 0,
        ) {
            Text("▶▶ Resume all ($suspendedCount)")
        }
        Spacer(Modifier.width(8.dp))
        OutlinedButton(
            onClick = onStop,
            enabled = breakpointCount > 0 || suspendedCount > 0,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text("■ Stop")
        }
        Spacer(Modifier.width(24.dp))
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text("Time-travel recording — Phase 4") } },
            state = rememberTooltipState(),
        ) {
            OutlinedButton(
                onClick = {},
                enabled = false,
            ) {
                Text("⏺ Rec")
            }
        }
    }
}
