package com.bugdigger.bytesight.ui.trace

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bugdigger.protocol.ClassInfo
import com.bugdigger.protocol.HookInfo
import com.bugdigger.protocol.HookType
import com.bugdigger.protocol.MethodInfo
import com.bugdigger.protocol.MethodTraceEvent

/**
 * Screen for managing method hooks and viewing trace events.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TraceScreen(
    viewModel: TraceViewModel,
    connectionKey: String,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(connectionKey) {
        viewModel.setConnectionKey(connectionKey)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        // Header
        TraceHeader(
            isStreaming = uiState.isStreaming,
            onStartStreaming = viewModel::startStreaming,
            onStopStreaming = viewModel::stopStreaming,
            onClearEvents = viewModel::clearEvents,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Error display
        uiState.error?.let { error ->
            ErrorBanner(
                error = error,
                onDismiss = viewModel::clearError,
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Main content
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Left panel: Hooks management
            HooksPanel(
                hooks = uiState.hooks,
                isLoading = uiState.isLoadingHooks,
                classes = uiState.classes,
                isLoadingClasses = uiState.isLoadingClasses,
                selectedClass = uiState.selectedClass,
                selectedMethod = uiState.selectedMethod,
                newHookClassName = uiState.newHookClassName,
                newHookMethodName = uiState.newHookMethodName,
                newHookMethodSignature = uiState.newHookMethodSignature,
                newHookType = uiState.newHookType,
                isAddingHook = uiState.isAddingHook,
                onSelectClass = viewModel::selectClass,
                onSelectMethod = viewModel::selectMethod,
                onHookTypeChange = viewModel::setNewHookType,
                onAddHook = viewModel::addHook,
                onRemoveHook = viewModel::removeHook,
                onRefresh = viewModel::refreshHooks,
                modifier = Modifier.width(400.dp),
            )

            // Right panel: Trace events
            TraceEventsPanel(
                events = uiState.traceEvents,
                isStreaming = uiState.isStreaming,
                autoScroll = uiState.autoScroll,
                onAutoScrollChange = viewModel::setAutoScroll,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TraceHeader(
    isStreaming: Boolean,
    onStartStreaming: () -> Unit,
    onStopStreaming: () -> Unit,
    onClearEvents: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "Method Tracing",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Hook methods and trace their execution in real-time",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isStreaming) {
                Button(
                    onClick = onStopStreaming,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("Stop")
                }
            } else {
                Button(onClick = onStartStreaming) {
                    Text("Start")
                }
            }

            OutlinedButton(onClick = onClearEvents) {
                Text("Clear")
            }
        }
    }
}

@Composable
private fun HooksPanel(
    hooks: List<HookInfo>,
    isLoading: Boolean,
    classes: List<ClassInfo>,
    isLoadingClasses: Boolean,
    selectedClass: ClassInfo?,
    selectedMethod: MethodInfo?,
    newHookClassName: String,
    newHookMethodName: String,
    newHookMethodSignature: String,
    newHookType: HookType,
    isAddingHook: Boolean,
    onSelectClass: (ClassInfo) -> Unit,
    onSelectMethod: (MethodInfo) -> Unit,
    onHookTypeChange: (HookType) -> Unit,
    onAddHook: () -> Unit,
    onRemoveHook: (String) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxHeight(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Hooks (${hooks.size})",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                IconButton(
                    onClick = onRefresh,
                    enabled = !isLoading,
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text("🔄")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Add hook form
            AddHookForm(
                classes = classes,
                isLoadingClasses = isLoadingClasses,
                selectedClass = selectedClass,
                selectedMethod = selectedMethod,
                className = newHookClassName,
                methodName = newHookMethodName,
                methodSignature = newHookMethodSignature,
                hookType = newHookType,
                isAdding = isAddingHook,
                onSelectClass = onSelectClass,
                onSelectMethod = onSelectMethod,
                onHookTypeChange = onHookTypeChange,
                onAdd = onAddHook,
            )

            Spacer(modifier = Modifier.height(16.dp))

            HorizontalDivider()

            Spacer(modifier = Modifier.height(12.dp))

            // Hooks list
            if (hooks.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No active hooks",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(hooks, key = { it.id }) { hook ->
                        HookItem(
                            hook = hook,
                            onRemove = { onRemoveHook(hook.id) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddHookForm(
    classes: List<ClassInfo>,
    isLoadingClasses: Boolean,
    selectedClass: ClassInfo?,
    selectedMethod: MethodInfo?,
    className: String,
    methodName: String,
    methodSignature: String,
    hookType: HookType,
    isAdding: Boolean,
    onSelectClass: (ClassInfo) -> Unit,
    onSelectMethod: (MethodInfo) -> Unit,
    onHookTypeChange: (HookType) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Class dropdown (searchable)
        var classExpanded by remember { mutableStateOf(false) }
        var classSearchText by remember { mutableStateOf("") }

        LaunchedEffect(selectedClass) {
            classSearchText = selectedClass?.name ?: ""
        }

        ExposedDropdownMenuBox(
            expanded = classExpanded,
            onExpandedChange = { classExpanded = it },
        ) {
            OutlinedTextField(
                value = classSearchText,
                onValueChange = { text ->
                    classSearchText = text
                    classExpanded = true
                },
                label = { Text("Class") },
                placeholder = {
                    if (isLoadingClasses) Text("Loading classes...")
                    else Text("Type to search classes...")
                },
                singleLine = true,
                trailingIcon = {
                    if (isLoadingClasses) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = classExpanded)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
            )

            val filteredClasses = remember(classSearchText, classes) {
                if (classSearchText.isBlank() || classSearchText == selectedClass?.name) {
                    classes.take(100)
                } else {
                    classes.filter {
                        it.name.lowercase().contains(classSearchText.lowercase())
                    }.take(100)
                }
            }

            ExposedDropdownMenu(
                expanded = classExpanded,
                onDismissRequest = {
                    classExpanded = false
                    if (classSearchText != selectedClass?.name) {
                        classSearchText = selectedClass?.name ?: ""
                    }
                },
                modifier = Modifier.heightIn(max = 300.dp),
            ) {
                if (filteredClasses.isEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (isLoadingClasses) "Loading..." else "No classes found",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        onClick = {},
                        enabled = false,
                    )
                } else {
                    filteredClasses.forEach { classInfo ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(
                                        classInfo.simpleName,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    Text(
                                        classInfo.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = {
                                classSearchText = classInfo.name
                                onSelectClass(classInfo)
                                classExpanded = false
                            },
                        )
                    }
                }
            }
        }

        // Method dropdown
        var methodExpanded by remember { mutableStateOf(false) }
        val methods = selectedClass?.methodsList ?: emptyList()

        ExposedDropdownMenuBox(
            expanded = methodExpanded,
            onExpandedChange = { if (methods.isNotEmpty()) methodExpanded = it },
        ) {
            OutlinedTextField(
                value = selectedMethod?.let { "${it.name}${it.signature}" } ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text("Method") },
                placeholder = {
                    Text(
                        if (selectedClass == null) "Select a class first..."
                        else "Select a method...",
                    )
                },
                singleLine = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = methodExpanded) },
                enabled = methods.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )

            ExposedDropdownMenu(
                expanded = methodExpanded,
                onDismissRequest = { methodExpanded = false },
                modifier = Modifier.heightIn(max = 300.dp),
            ) {
                methods.forEach { method ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "${method.name}${method.signature}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            onSelectMethod(method)
                            methodExpanded = false
                        },
                    )
                }
            }
        }

        // Hook type selector
        var expanded by remember { mutableStateOf(false) }

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
        ) {
            OutlinedTextField(
                value = hookType.toDisplayName(),
                onValueChange = {},
                readOnly = true,
                label = { Text("Hook type") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                HookType.entries.filter { it != HookType.UNRECOGNIZED }.forEach { type ->
                    DropdownMenuItem(
                        text = { Text(type.toDisplayName()) },
                        onClick = {
                            onHookTypeChange(type)
                            expanded = false
                        },
                    )
                }
            }
        }

        Button(
            onClick = onAdd,
            enabled = !isAdding && className.isNotBlank() && methodName.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isAdding) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Add Hook")
            }
        }
    }
}

@Composable
private fun HookItem(
    hook: HookInfo,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (hook.active) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${hook.className}.${hook.methodName}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = hook.hookType.toDisplayName(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Hits: ${hook.hitCount}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            IconButton(onClick = onRemove) {
                Text("✕")
            }
        }
    }
}

@Composable
private fun TraceEventsPanel(
    events: List<TraceEventDisplay>,
    isStreaming: Boolean,
    autoScroll: Boolean,
    onAutoScrollChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new events arrive
    LaunchedEffect(events.size, autoScroll) {
        if (autoScroll && events.isNotEmpty()) {
            listState.animateScrollToItem(events.size - 1)
        }
    }

    Card(
        modifier = modifier.fillMaxHeight(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Trace Events (${events.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (isStreaming) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = MaterialTheme.shapes.small,
                        ) {
                            Text(
                                text = "LIVE",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = autoScroll,
                        onCheckedChange = onAutoScrollChange,
                    )
                    Text(
                        text = "Auto-scroll",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Events list
            if (events.isEmpty()) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = if (isStreaming) "Waiting for events..." else "No trace events",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!isStreaming) {
                            Text(
                                text = "Add hooks and start streaming to see events",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(events, key = { it.id }) { event ->
                        TraceEventItem(event = event)
                    }
                }
            }
        }
    }
}

@Composable
private fun TraceEventItem(
    event: TraceEventDisplay,
    modifier: Modifier = Modifier,
) {
    val backgroundColor = when (event.eventType) {
        MethodTraceEvent.TraceEventType.ENTRY -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        MethodTraceEvent.TraceEventType.EXIT -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        MethodTraceEvent.TraceEventType.EXCEPTION -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.surface
    }

    val eventTypeLabel = when (event.eventType) {
        MethodTraceEvent.TraceEventType.ENTRY -> "ENTER"
        MethodTraceEvent.TraceEventType.EXIT -> "EXIT"
        MethodTraceEvent.TraceEventType.EXCEPTION -> "EXCEPTION"
        else -> "?"
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = backgroundColor,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier
                .padding(8.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Indentation based on depth
            if (event.depth > 0) {
                Spacer(modifier = Modifier.width((event.depth * 16).dp))
            }

            // Event type badge
            Surface(
                color = when (event.eventType) {
                    MethodTraceEvent.TraceEventType.ENTRY -> MaterialTheme.colorScheme.primary
                    MethodTraceEvent.TraceEventType.EXIT -> MaterialTheme.colorScheme.secondary
                    MethodTraceEvent.TraceEventType.EXCEPTION -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.outline
                },
                shape = MaterialTheme.shapes.extraSmall,
            ) {
                Text(
                    text = eventTypeLabel,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                    fontFamily = FontFamily.Monospace,
                )
            }

            // Thread name
            Text(
                text = "[${event.threadName}]",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )

            // Class and method
            Text(
                text = "${event.className.substringAfterLast('.')}.${event.methodName}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface,
            )

            // Arguments or return value
            event.arguments?.let { args ->
                Text(
                    text = "($args)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }

            event.returnValue?.let { returnVal ->
                Text(
                    text = "-> $returnVal",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontFamily = FontFamily.Monospace,
                )
            }

            // Duration
            event.durationNanos?.let { nanos ->
                val duration = formatDuration(nanos)
                Text(
                    text = "($duration)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
            }

            // Exception info
            event.exceptionInfo?.let { exInfo ->
                Text(
                    text = exInfo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun ErrorBanner(
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

private fun HookType.toDisplayName(): String = when (this) {
    HookType.LOG_ENTRY_EXIT -> "Log Entry/Exit"
    HookType.LOG_ARGUMENTS -> "Log Arguments"
    HookType.LOG_RETURN_VALUE -> "Log Return Value"
    HookType.MODIFY_RETURN -> "Modify Return"
    HookType.SKIP_EXECUTION -> "Skip Execution"
    HookType.CUSTOM -> "Custom"
    HookType.UNRECOGNIZED -> "Unknown"
}

private fun formatDuration(nanos: Long): String {
    return when {
        nanos < 1_000 -> "${nanos}ns"
        nanos < 1_000_000 -> "${nanos / 1_000}µs"
        nanos < 1_000_000_000 -> "${nanos / 1_000_000}ms"
        else -> "${nanos / 1_000_000_000}s"
    }
}
