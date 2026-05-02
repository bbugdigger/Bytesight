package com.bugdigger.bytesight.ui.debugger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.bugdigger.bytesight.ui.debugger.components.BreakpointsPanel
import com.bugdigger.bytesight.ui.debugger.components.CallStackPanel
import com.bugdigger.bytesight.ui.debugger.components.ControlBar
import com.bugdigger.bytesight.ui.debugger.components.RecordingBar
import com.bugdigger.bytesight.ui.debugger.components.ThreadsPanel
import com.bugdigger.bytesight.ui.debugger.components.Timeline
import com.bugdigger.bytesight.ui.debugger.components.VariablesPanel
import com.bugdigger.protocol.ThreadState
import java.awt.FileDialog
import java.awt.Frame
import java.nio.file.Path
import java.nio.file.Paths

@Composable
fun DebuggerScreen(
    viewModel: DebuggerViewModel,
    connectionKey: String,
    onNavigateToInspector: (className: String, methodName: String?, methodSignature: String?) -> Unit,
    onAskAI: (prompt: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(connectionKey) {
        viewModel.setConnectionKey(connectionKey)
    }

    val breakpoints by viewModel.breakpoints.collectAsState()
    val threads by viewModel.threads.collectAsState()
    val currentThreadId by viewModel.currentThreadId.collectAsState()
    val callStack by viewModel.callStack.collectAsState()
    val currentFrame by viewModel.currentFrame.collectAsState()
    val lastHit by viewModel.lastHit.collectAsState()
    val error by viewModel.error.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val recordingState by viewModel.recordingState.collectAsState()
    val recordedEvents by viewModel.recordedEvents.collectAsState()
    val cursorMode by viewModel.cursorMode.collectAsState()

    val suspendedCount = threads.count { it.state == ThreadState.THREAD_STATE_SUSPENDED }
    val currentIsSuspended = threads.firstOrNull { it.id == currentThreadId }?.state ==
        ThreadState.THREAD_STATE_SUSPENDED

    CompositionLocalProvider(LocalContentColor provides Color.White) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
    ) {
        ControlBar(
            canResume = currentIsSuspended,
            suspendedCount = suspendedCount,
            breakpointCount = breakpoints.size,
            onResumeAll = viewModel::resumeAll,
            onResumeCurrent = viewModel::resumeCurrentThread,
            onStop = viewModel::stopDebugging,
            onStepOver = viewModel::stepOver,
            onStepInto = viewModel::stepInto,
            onStepOut = viewModel::stepOut,
        )

        RecordingBar(
            recordingState = recordingState,
            cursorMode = cursorMode,
            eventCount = recordedEvents.size,
            onStartRecording = viewModel::startRecording,
            onStopRecording = viewModel::stopRecording,
            onClearRecording = viewModel::clearRecording,
            onPrevHit = { viewModel.prevHit() },
            onNextHit = { viewModel.nextHit() },
            onResumeLive = viewModel::resumeLive,
            onSave = {
                pickSaveFile()?.let(viewModel::saveRecording)
            },
            onLoad = {
                pickLoadFile()?.let(viewModel::loadRecording)
            },
        )

        if (busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
        }

        error?.let { err ->
            ErrorBanner(err, onDismiss = viewModel::clearError)
            Spacer(Modifier.height(8.dp))
        }

        lastHit?.let { hit ->
            HitBanner(
                className = hit.topFrame.className,
                methodName = hit.topFrame.methodName,
                line = hit.topFrame.lineNumber,
                threadName = hit.threadName,
            )
            Spacer(Modifier.height(8.dp))
        }

        HorizontalDivider()
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Left column: Breakpoints + CallStack
            Column(
                modifier = Modifier.weight(1f).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                BreakpointsPanel(
                    breakpoints = breakpoints,
                    onToggleEnabled = viewModel::toggleEnabled,
                    onRemove = viewModel::removeBreakpoint,
                    onUpdateCondition = viewModel::updateCondition,
                    onUpdateSkipCount = viewModel::updateSkipCount,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                CallStackPanel(
                    frames = callStack,
                    selectedFrame = currentFrame,
                    onSelectFrame = viewModel::selectFrame,
                    onOpenInInspector = { frame ->
                        // Carry the frame's method + JVM-descriptor signature so
                        // Inspector preselects the method dropdown, not just the class.
                        onNavigateToInspector(
                            frame.className,
                            frame.methodName.ifEmpty { null },
                            frame.signature.ifEmpty { null },
                        )
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }

            // Right column: Threads + Variables
            Column(
                modifier = Modifier.weight(1f).fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ThreadsPanel(
                    threads = threads,
                    currentThreadId = currentThreadId,
                    onSelect = viewModel::selectThread,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
                VariablesPanel(
                    frame = currentFrame,
                    onAskAI = onAskAI,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }

        Timeline(
            events = recordedEvents,
            cursorMode = cursorMode,
            onSeek = viewModel::seekTo,
        )
    }
    }
}

private fun pickSaveFile(): Path? {
    val dlg = FileDialog(null as Frame?, "Save recording", FileDialog.SAVE).apply {
        file = "session.btsrec"
        isVisible = true
    }
    val dir = dlg.directory ?: return null
    val name = dlg.file ?: return null
    val withExt = if (name.endsWith(".btsrec", ignoreCase = true)) name else "$name.btsrec"
    return Paths.get(dir, withExt)
}

private fun pickLoadFile(): Path? {
    val dlg = FileDialog(null as Frame?, "Load recording", FileDialog.LOAD).apply {
        setFilenameFilter { _, n -> n.endsWith(".btsrec", ignoreCase = true) }
        isVisible = true
    }
    val dir = dlg.directory ?: return null
    val name = dlg.file ?: return null
    return Paths.get(dir, name)
}

@Composable
private fun ErrorBanner(error: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = error,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text("Dismiss", color = Color.White)
            }
        }
    }
}

@Composable
private fun HitBanner(className: String, methodName: String, line: Int, threadName: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = "● Paused at breakpoint",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
            )
            val lineSuffix = if (line > 0) ":$line" else ""
            Text(
                text = "$className#$methodName$lineSuffix   (thread: $threadName)",
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                color = Color.White,
            )
        }
    }
}
