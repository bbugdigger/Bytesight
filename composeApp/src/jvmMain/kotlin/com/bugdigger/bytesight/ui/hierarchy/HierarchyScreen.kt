package com.bugdigger.bytesight.ui.hierarchy

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Screen for exploring class inheritance hierarchies.
 */
@Composable
fun HierarchyScreen(
    viewModel: HierarchyViewModel,
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
        HierarchyHeader(
            isLoading = uiState.isLoading,
            onRefresh = viewModel::loadHierarchy,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Error display
        uiState.error?.let { error ->
            ErrorBanner(error = error, onDismiss = viewModel::clearError)
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Search and filters
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = uiState.searchQuery,
                onValueChange = viewModel::setSearchQuery,
                placeholder = { Text("Search classes...") },
                leadingIcon = { Text("🔍") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = uiState.showClasses,
                    onCheckedChange = { viewModel.setShowClasses(it) },
                )
                Text("Classes", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = uiState.showInterfaces,
                    onCheckedChange = { viewModel.setShowInterfaces(it) },
                )
                Text("Interfaces", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Main content: tree on left, detail on right
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Tree view
            HierarchyTree(
                roots = uiState.filteredRoots,
                expandedNodes = uiState.expandedNodes,
                selectedClass = uiState.selectedClass,
                isLoading = uiState.isLoading,
                onToggleExpand = viewModel::toggleExpanded,
                onSelectClass = viewModel::selectClass,
                modifier = Modifier.weight(1f),
            )

            // Detail panel
            ClassDetailPanel(
                selectedClass = uiState.selectedClass,
                classInfo = uiState.selectedClassInfo,
                ancestors = uiState.selectedAncestors,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun HierarchyHeader(
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
                text = "Class Hierarchy",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Explore inheritance relationships across loaded classes",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        IconButton(onClick = onRefresh, enabled = !isLoading) {
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
private fun HierarchyTree(
    roots: List<HierarchyNode>,
    expandedNodes: Set<String>,
    selectedClass: String?,
    isLoading: Boolean,
    onToggleExpand: (String) -> Unit,
    onSelectClass: (String) -> Unit,
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
                text = "Hierarchy (${roots.size} roots)",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (isLoading && roots.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (roots.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No classes found", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                // Flatten the tree for LazyColumn
                val flatNodes = mutableListOf<Pair<HierarchyNode, Int>>()
                fun flatten(nodes: List<HierarchyNode>, depth: Int) {
                    for (node in nodes) {
                        flatNodes.add(node to depth)
                        if (node.className in expandedNodes && node.children.isNotEmpty()) {
                            flatten(node.children, depth + 1)
                        }
                    }
                }
                flatten(roots, 0)

                LazyColumn {
                    items(flatNodes, key = { it.first.className }) { (node, depth) ->
                        TreeNodeRow(
                            node = node,
                            depth = depth,
                            isExpanded = node.className in expandedNodes,
                            isSelected = node.className == selectedClass,
                            onToggleExpand = { onToggleExpand(node.className) },
                            onSelect = { onSelectClass(node.className) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TreeNodeRow(
    node: HierarchyNode,
    depth: Int,
    isExpanded: Boolean,
    isSelected: Boolean,
    onToggleExpand: () -> Unit,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon = when {
        node.isInterface -> "🔷"
        node.isEnum -> "📋"
        else -> "📦"
    }

    val expandIcon = when {
        node.children.isEmpty() -> "  "
        isExpanded -> "▼"
        else -> "▶"
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0f)
        },
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier
                .padding(start = (depth * 20 + 4).dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Expand/collapse arrow
            Text(
                text = expandIcon,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .clickable(enabled = node.children.isNotEmpty()) { onToggleExpand() }
                    .padding(end = 4.dp),
            )

            // Type icon
            Text(text = icon, modifier = Modifier.padding(end = 4.dp))

            // Class name
            val simpleName = node.className.substringAfterLast('.')
            val packageName = node.className.substringBeforeLast('.', "")

            Column {
                Text(
                    text = simpleName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (packageName.isNotEmpty()) {
                    Text(
                        text = packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Child count badge
            if (node.children.isNotEmpty()) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                ) {
                    Text(
                        text = "${node.children.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ClassDetailPanel(
    selectedClass: String?,
    classInfo: com.bugdigger.protocol.ClassInfo?,
    ancestors: List<String>,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxHeight(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (selectedClass == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Select a class to view details",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    text = selectedClass.substringAfterLast('.'),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = selectedClass,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Ancestor chain
                if (ancestors.isNotEmpty()) {
                    Text("Inheritance Chain", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                    Spacer(modifier = Modifier.height(4.dp))
                    for ((i, ancestor) in ancestors.reversed().withIndex()) {
                        Text(
                            text = "${"  ".repeat(i)}↳ ${ancestor.substringAfterLast('.')}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = "${"  ".repeat(ancestors.size)}↳ ${selectedClass.substringAfterLast('.')}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Class info details
                if (classInfo != null) {
                    // Interfaces
                    if (classInfo.interfacesList.isNotEmpty()) {
                        Text("Implements", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.height(4.dp))
                        for (iface in classInfo.interfacesList) {
                            Text(
                                text = "  🔷 ${iface.substringAfterLast('.')}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Methods
                    if (classInfo.methodsList.isNotEmpty()) {
                        Text("Methods (${classInfo.methodsList.size})", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.height(4.dp))
                        LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                            items(classInfo.methodsList) { method ->
                                Text(
                                    text = "  ${method.name}${method.signature}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }

                    // Fields
                    if (classInfo.fieldsList.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Fields (${classInfo.fieldsList.size})", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        Spacer(modifier = Modifier.height(4.dp))
                        for (field in classInfo.fieldsList) {
                            Text(
                                text = "  ${field.type} ${field.name}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
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
