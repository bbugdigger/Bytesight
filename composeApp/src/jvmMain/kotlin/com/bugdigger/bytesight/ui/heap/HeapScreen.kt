package com.bugdigger.bytesight.ui.heap

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bugdigger.bytesight.ui.components.GraphView
import com.bugdigger.protocol.*
import java.text.NumberFormat

@Composable
fun HeapScreen(
    viewModel: HeapViewModel,
    connectionKey: String,
    onNavigateToInspector: (className: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(connectionKey) {
        viewModel.setConnectionKey(connectionKey)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        HeapHeader(
            snapshot = uiState.snapshot,
            isCapturing = uiState.isCapturing,
            onCapture = viewModel::capture,
        )

        Spacer(modifier = Modifier.height(12.dp))

        uiState.error?.let { err ->
            ErrorBanner(error = err, onDismiss = viewModel::clearError)
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Tab bar
        @Suppress("DEPRECATION")
        TabRow(
            selectedTabIndex = uiState.activeTab.ordinal,
            modifier = Modifier.fillMaxWidth(),
        ) {
            HeapTab.entries.forEach { tab ->
                Tab(
                    selected = uiState.activeTab == tab,
                    onClick = { viewModel.setActiveTab(tab) },
                    text = {
                        Text(
                            when (tab) {
                                HeapTab.HISTOGRAM -> "Histogram"
                                HeapTab.SEARCH -> "Search"
                                HeapTab.DUPLICATES -> "Duplicates"
                            },
                        )
                    },
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        when (uiState.activeTab) {
            HeapTab.HISTOGRAM -> {
                FilterAndSort(
                    filter = uiState.nameFilter,
                    onFilterChange = viewModel::setFilter,
                    sortMode = uiState.sortMode,
                    onSortChange = viewModel::setSortMode,
                )
                Spacer(modifier = Modifier.height(12.dp))
                HistogramContent(uiState, viewModel, onNavigateToInspector)
            }
            HeapTab.SEARCH -> SearchContent(uiState, viewModel, onNavigateToInspector)
            HeapTab.DUPLICATES -> DuplicatesContent(uiState, viewModel, onNavigateToInspector)
        }
    }
}

@Composable
private fun HeapHeader(
    snapshot: HeapSnapshotInfo?,
    isCapturing: Boolean,
    onCapture: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Heap", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.width(16.dp))

        if (snapshot != null && snapshot.available) {
            val fmt = NumberFormat.getInstance()
            Text(
                text = "Snapshot #${snapshot.snapshotId} — ${fmt.format(snapshot.objectCount)} objects, " +
                        "${fmt.format(snapshot.totalShallowBytes)} B shallow",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                text = "No snapshot yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(onClick = onCapture, enabled = !isCapturing) {
            if (isCapturing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Capturing…")
            } else {
                Text("Capture snapshot")
            }
        }
    }
}

@Composable
private fun FilterAndSort(
    filter: String,
    onFilterChange: (String) -> Unit,
    sortMode: HeapSortMode,
    onSortChange: (HeapSortMode) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = filter,
            onValueChange = onFilterChange,
            label = { Text("Filter by class name") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )

        Spacer(modifier = Modifier.width(16.dp))

        SingleChoiceSegmentedButtonRow {
            HeapSortMode.entries.forEachIndexed { idx, mode ->
                SegmentedButton(
                    selected = sortMode == mode,
                    onClick = { onSortChange(mode) },
                    shape = SegmentedButtonDefaults.itemShape(idx, HeapSortMode.entries.size),
                ) {
                    Text(
                        text = when (mode) {
                            HeapSortMode.BYTES_DESC -> "Bytes"
                            HeapSortMode.COUNT_DESC -> "Count"
                            HeapSortMode.NAME_ASC -> "Name"
                        },
                    )
                }
            }
        }
    }
}

// ========== Left Pane: Histogram ==========

@Composable
private fun HistogramPane(
    rows: List<ClassHistogramEntry>,
    selectedClass: String?,
    onClassClick: (String) -> Unit,
    onOpenInInspector: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            HistogramHeaderRow()
            HorizontalDivider()

            if (rows.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No data — capture a snapshot.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(rows) { row ->
                        HistogramRow(
                            row = row,
                            isSelected = row.className == selectedClass,
                            onClick = { onClassClick(row.className) },
                            onOpenInInspector = { onOpenInInspector(row.className) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistogramHeaderRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Class", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Text("Count", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(70.dp))
        Text("Bytes", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(90.dp))
    }
}

@Composable
private fun HistogramRow(
    row: ClassHistogramEntry,
    isSelected: Boolean,
    onClick: () -> Unit,
    onOpenInInspector: () -> Unit = {},
) {
    val fmt = NumberFormat.getInstance()
    val bgColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    else Color.Transparent

    var showContextMenu by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(bgColor)
                .clickable(onClick = onClick)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { /* consume */ },
                        onLongPress = { showContextMenu = true },
                    )
                }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = row.className,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = fmt.format(row.instanceCount),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(70.dp),
            )
            Text(
                text = fmt.format(row.shallowBytes),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.width(90.dp),
            )
        }

        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
            offset = DpOffset(8.dp, 0.dp),
        ) {
            DropdownMenuItem(
                text = { Text("Open in Inspector") },
                onClick = {
                    showContextMenu = false
                    onOpenInInspector()
                },
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

// ========== Middle Pane: Instance List ==========

@Composable
private fun InstancePane(
    selectedClass: String?,
    instances: List<InstanceSummary>,
    isLoading: Boolean,
    selectedTag: Long?,
    onInstanceClick: (Long) -> Unit,
    onOpenInInspector: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (selectedClass != null) "Instances of ${selectedClass.substringAfterLast('.')}"
                    else "Instances",
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (selectedClass != null) {
                    TextButton(
                        onClick = { onOpenInInspector(selectedClass) },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    ) {
                        Text("Inspect", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            HorizontalDivider()

            when {
                selectedClass == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Select a class from the histogram.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
                instances.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No instances found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(instances) { inst ->
                            InstanceRow(
                                instance = inst,
                                isSelected = inst.tag == selectedTag,
                                onClick = { onInstanceClick(inst.tag) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstanceRow(
    instance: InstanceSummary,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val fmt = NumberFormat.getInstance()
    val bgColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    else Color.Transparent

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = "#${instance.tag}  (${fmt.format(instance.shallowBytes)} B)",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
        )
        if (instance.preview.isNotEmpty()) {
            Text(
                text = instance.preview,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

// ========== Right Pane: Object Graph ==========

@Composable
private fun ObjectGraphPane(
    graph: com.bugdigger.bytesight.ui.components.GraphLayout<ObjectNode, ObjectEdge>?,
    exploredObjects: Map<Long, ObjectDetail>,
    selectedTag: Long?,
    isLoading: Boolean,
    onExpandRef: (Long) -> Unit,
    onClear: () -> Unit,
    onOpenInInspector: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Object Graph", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.weight(1f))
                if (graph != null) {
                    Text(
                        text = "${exploredObjects.size} nodes",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onClear) { Text("Clear", style = MaterialTheme.typography.labelSmall) }
                }
            }
            HorizontalDivider()

            when {
                isLoading && graph == null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
                graph == null || graph.nodes.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "Select an instance to explore its object graph.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    GraphView(
                        layout = graph,
                        onNodeClick = {},
                        selectedNodeId = selectedTag?.toString(),
                        nodeContent = { node ->
                            ObjectNodeView(
                                node = node.data,
                                onExpandRef = onExpandRef,
                                exploredTags = exploredObjects.keys,
                                onOpenInInspector = onOpenInInspector,
                            )
                        },
                        edgeColor = { Color(0xFF6B7280) },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ObjectNodeView(
    node: ObjectNode,
    onExpandRef: (Long) -> Unit,
    exploredTags: Set<Long>,
    onOpenInInspector: (String) -> Unit = {},
) {
    val fmt = NumberFormat.getInstance()
    Card(
        modifier = Modifier.fillMaxSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${node.className.substringAfterLast('.')}@#${node.tag}",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                        .clickable { onOpenInInspector(node.className) },
                )
                Text(
                    text = "${fmt.format(node.shallowBytes)} B",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Fields (limited to 15)
            val maxFields = 15
            val fieldsToShow = node.fields.take(maxFields)

            for (field in fieldsToShow) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 1.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${field.name}: ",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    when (field.kind) {
                        FieldKind.FIELD_KIND_REF -> {
                            val tag = field.refTag
                            if (tag != 0L) {
                                val refLabel = node.outgoingRefs
                                    .firstOrNull { it.targetTag == tag }
                                    ?.let { "${it.targetClassName.substringAfterLast('.')}@#$tag" }
                                    ?: "#$tag"
                                val alreadyExplored = tag in exploredTags
                                Text(
                                    text = refLabel,
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                    fontFamily = FontFamily.Monospace,
                                    color = if (alreadyExplored) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.tertiary,
                                    modifier = Modifier.clickable { onExpandRef(tag) },
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            } else {
                                Text(
                                    text = "null",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        FieldKind.FIELD_KIND_NULL -> {
                            Text(
                                text = "null",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FieldKind.FIELD_KIND_STRING -> {
                            Text(
                                text = "\"${field.stringValue}\"",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF6A8759),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        FieldKind.FIELD_KIND_INT -> {
                            Text(
                                text = field.intValue.toString(),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF6897BB),
                            )
                        }
                        FieldKind.FIELD_KIND_DOUBLE -> {
                            Text(
                                text = field.doubleValue.toString(),
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFF6897BB),
                            )
                        }
                        else -> {
                            Text(
                                text = "?",
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
            }

            if (node.fields.size > maxFields) {
                Text(
                    text = "  … ${node.fields.size - maxFields} more fields",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp),
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

// ========== Histogram Tab Content (three-pane) ==========

@Composable
private fun HistogramContent(uiState: HeapUiState, viewModel: HeapViewModel, onNavigateToInspector: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxSize()) {
        HistogramPane(
            rows = uiState.filteredHistogram,
            selectedClass = uiState.selectedClass,
            onClassClick = viewModel::selectClass,
            onOpenInInspector = onNavigateToInspector,
            modifier = Modifier.weight(0.30f).fillMaxHeight(),
        )
        Spacer(modifier = Modifier.width(8.dp))
        InstancePane(
            selectedClass = uiState.selectedClass,
            instances = uiState.instances,
            isLoading = uiState.isLoadingInstances,
            selectedTag = uiState.selectedTag,
            onInstanceClick = viewModel::selectInstance,
            onOpenInInspector = onNavigateToInspector,
            modifier = Modifier.weight(0.25f).fillMaxHeight(),
        )
        Spacer(modifier = Modifier.width(8.dp))
        ObjectGraphPane(
            graph = uiState.objectGraph,
            exploredObjects = uiState.exploredObjects,
            selectedTag = uiState.selectedTag,
            isLoading = uiState.isLoadingObject,
            onExpandRef = viewModel::expandReference,
            onClear = viewModel::clearObjectGraph,
            onOpenInInspector = onNavigateToInspector,
            modifier = Modifier.weight(0.45f).fillMaxHeight(),
        )
    }
}

// ========== Search Tab Content ==========

@Composable
private fun SearchContent(uiState: HeapUiState, viewModel: HeapViewModel, onNavigateToInspector: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Mode toggle
        SingleChoiceSegmentedButtonRow {
            SearchMode.entries.forEachIndexed { idx, mode ->
                SegmentedButton(
                    selected = uiState.searchMode == mode,
                    onClick = { viewModel.setSearchMode(mode) },
                    shape = SegmentedButtonDefaults.itemShape(idx, SearchMode.entries.size),
                ) {
                    Text(
                        when (mode) {
                            SearchMode.STRING_CONTAINS -> "String contains"
                            SearchMode.FIELD_EQUALS -> "Field equals"
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Search inputs
        when (uiState.searchMode) {
            SearchMode.STRING_CONTAINS -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = uiState.searchQuery,
                        onValueChange = viewModel::setSearchQuery,
                        label = { Text("Search substring") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = viewModel::executeSearch,
                        enabled = !uiState.isSearching && uiState.snapshot != null && uiState.searchQuery.isNotBlank(),
                    ) {
                        if (uiState.isSearching) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text("Search")
                    }
                }
            }
            SearchMode.FIELD_EQUALS -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = uiState.searchFieldClassName,
                        onValueChange = viewModel::setSearchFieldClassName,
                        label = { Text("Class name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = uiState.searchFieldName,
                        onValueChange = viewModel::setSearchFieldName,
                        label = { Text("Field name") },
                        singleLine = true,
                        modifier = Modifier.weight(0.6f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = uiState.searchFieldValue,
                        onValueChange = viewModel::setSearchFieldValue,
                        label = { Text("Value") },
                        singleLine = true,
                        modifier = Modifier.weight(0.6f),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = viewModel::executeSearch,
                        enabled = !uiState.isSearching && uiState.snapshot != null
                                && uiState.searchFieldClassName.isNotBlank() && uiState.searchFieldName.isNotBlank(),
                    ) {
                        if (uiState.isSearching) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text("Search")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Results
        if (uiState.searchResults.isEmpty() && !uiState.isSearching) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (uiState.snapshot == null) "Capture a snapshot first."
                    else "No results yet. Enter a query and click Search.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            SearchResultsHeader(count = uiState.searchResults.size)
            HorizontalDivider()
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(uiState.searchResults) { match ->
                    SearchResultRow(match, onOpenInInspector = { onNavigateToInspector(match.className) })
                }
            }
        }
    }
}

@Composable
private fun SearchResultsHeader(count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Tag", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(80.dp))
        Text("Class", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Text("Field", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(100.dp))
        Text("Value", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text("$count results", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SearchResultRow(match: ValueMatch, onOpenInInspector: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "#${match.tag}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(80.dp),
        )
        Text(
            text = match.className,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).clickable(onClick = onOpenInInspector),
        )
        Text(
            text = match.matchedField.ifEmpty { "-" },
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(100.dp),
        )
        Text(
            text = match.matchedValue,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF6A8759),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

// ========== Duplicates Tab Content ==========

@Composable
private fun DuplicatesContent(uiState: HeapUiState, viewModel: HeapViewModel, onNavigateToInspector: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = uiState.dupMinCount.toString(),
                onValueChange = { viewModel.setDupMinCount(it.toIntOrNull() ?: 2) },
                label = { Text("Min count") },
                singleLine = true,
                modifier = Modifier.width(120.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedTextField(
                value = uiState.dupMinLength.toString(),
                onValueChange = { viewModel.setDupMinLength(it.toIntOrNull() ?: 1) },
                label = { Text("Min length") },
                singleLine = true,
                modifier = Modifier.width(120.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = viewModel::findDuplicateStrings,
                enabled = !uiState.isLoadingDuplicates && uiState.snapshot != null,
            ) {
                if (uiState.isLoadingDuplicates) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text("Find duplicates")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (uiState.duplicateGroups.isEmpty() && !uiState.isLoadingDuplicates) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (uiState.snapshot == null) "Capture a snapshot first."
                    else "Click 'Find duplicates' to scan for duplicate strings.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
    DuplicatesHeader(count = uiState.duplicateGroups.size, onOpenInInspector = { onNavigateToInspector("java.lang.String") })
            HorizontalDivider()
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(uiState.duplicateGroups) { group ->
                    DuplicateGroupRow(group)
                }
            }
        }
    }
}

@Composable
private fun DuplicatesHeader(count: Int, onOpenInInspector: () -> Unit = {}) {
    val fmt = NumberFormat.getInstance()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Value", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Text("Count", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(70.dp))
        Text("Wasted", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(90.dp))
        Spacer(Modifier.width(8.dp))
        Text("$count groups", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (count > 0) {
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onOpenInInspector,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text("Inspect String", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun DuplicateGroupRow(group: DuplicateStringGroup) {
    val fmt = NumberFormat.getInstance()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "\"${group.value}\"",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = Color(0xFF6A8759),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = fmt.format(group.count),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(70.dp),
        )
        Text(
            text = "${fmt.format(group.wastedBytes)} B",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.width(90.dp),
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

@Composable
private fun ErrorBanner(error: String, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
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
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}
