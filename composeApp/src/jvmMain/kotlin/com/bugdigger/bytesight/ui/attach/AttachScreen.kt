package com.bugdigger.bytesight.ui.attach

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bugdigger.bytesight.service.AttachService

/**
 * Screen for discovering and attaching to JVM processes.
 */
@Composable
fun AttachScreen(
    viewModel: AttachViewModel,
    onConnected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    // Notify parent when connection is established
    uiState.connectionKey?.let { key ->
        onConnected(key)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        // Header
        AttachHeader(
            isLoading = uiState.isLoading,
            onRefresh = viewModel::refreshProcesses,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Error display
        uiState.error?.let { error ->
            ErrorCard(
                error = error,
                onDismiss = viewModel::clearError,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Main content
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Process list
            ProcessList(
                processes = uiState.processes,
                selectedProcess = uiState.selectedProcess,
                isLoading = uiState.isLoading,
                onSelect = viewModel::selectProcess,
                modifier = Modifier.weight(1f),
            )

            // Attachment controls
            AttachmentPanel(
                selectedProcess = uiState.selectedProcess,
                agentPort = uiState.agentPort,
                isAttaching = uiState.isAttaching,
                isConnected = uiState.connectionKey != null,
                onPortChange = viewModel::setAgentPort,
                onAttach = viewModel::attachToSelected,
                onDisconnect = viewModel::disconnect,
                modifier = Modifier.width(300.dp),
            )
        }
    }
}

@Composable
private fun AttachHeader(
    isLoading: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "Attach to JVM",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Select a running JVM process to analyze",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        IconButton(
            onClick = onRefresh,
            enabled = !isLoading,
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            } else {
                Text("🔄", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun ProcessList(
    processes: List<AttachService.JvmProcess>,
    selectedProcess: AttachService.JvmProcess?,
    isLoading: Boolean,
    onSelect: (AttachService.JvmProcess?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxHeight(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Running JVMs (${processes.size})",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (isLoading && processes.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (processes.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No JVM processes found",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(processes, key = { it.pid }) { process ->
                        ProcessItem(
                            process = process,
                            isSelected = process == selectedProcess,
                            onClick = { onSelect(if (process == selectedProcess) null else process) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessItem(
    process: AttachService.JvmProcess,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = process.isAttachable, onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = when {
                isSelected -> MaterialTheme.colorScheme.primaryContainer
                !process.isAttachable -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "PID: ${process.pid}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )

                if (!process.isAttachable) {
                    Text(
                        text = "Self",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = process.displayName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun AttachmentPanel(
    selectedProcess: AttachService.JvmProcess?,
    agentPort: Int,
    isAttaching: Boolean,
    isConnected: Boolean,
    onPortChange: (Int) -> Unit,
    onAttach: () -> Unit,
    onDisconnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxHeight(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = "Attachment",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (selectedProcess != null) {
                // Selected process info
                Text(
                    text = "Selected Process",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "PID ${selectedProcess.pid}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = selectedProcess.displayName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Port configuration
                OutlinedTextField(
                    value = agentPort.toString(),
                    onValueChange = { value ->
                        value.toIntOrNull()?.let { onPortChange(it) }
                    },
                    label = { Text("Agent Port") },
                    singleLine = true,
                    enabled = !isConnected,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Action buttons
                if (isConnected) {
                    Button(
                        onClick = onDisconnect,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("Disconnect")
                    }
                } else {
                    Button(
                        onClick = onAttach,
                        enabled = !isAttaching,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (isAttaching) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Attaching...")
                        } else {
                            Text("Attach & Connect")
                        }
                    }
                }
            } else {
                // No selection prompt
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Select a process to attach",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorCard(
    error: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}
