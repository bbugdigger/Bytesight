package com.bugdigger.bytesight.ui.browser

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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bugdigger.bytesight.ui.components.CodeViewer
import com.bugdigger.protocol.ClassInfo

/**
 * Screen for browsing classes loaded in the target JVM.
 */
@Composable
fun ClassBrowserScreen(
    viewModel: ClassBrowserViewModel,
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
        ClassBrowserHeader(
            isLoading = uiState.isLoading,
            onRefresh = viewModel::refreshClasses,
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

        // Search and filters
        SearchBar(
            query = uiState.searchQuery,
            onQueryChange = viewModel::setSearchQuery,
            includeSystemClasses = uiState.includeSystemClasses,
            onIncludeSystemClassesChange = viewModel::setIncludeSystemClasses,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Main content
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Class list
            ClassList(
                classes = uiState.filteredClasses,
                selectedClass = uiState.selectedClass,
                isLoading = uiState.isLoading,
                onSelect = viewModel::selectClass,
                modifier = Modifier.width(400.dp),
            )

            // Code viewer
            CodePanel(
                selectedClass = uiState.selectedClass,
                decompiled = uiState.decompiled,
                isLoading = uiState.isLoadingBytecode,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun ClassBrowserHeader(
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
                text = "Class Browser",
                style = MaterialTheme.typography.headlineMedium,
            )
            Text(
                text = "Browse and decompile classes from the target JVM",
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
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    includeSystemClasses: Boolean,
    onIncludeSystemClassesChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search classes...") },
            leadingIcon = { Text("🔍") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = includeSystemClasses,
                onCheckedChange = onIncludeSystemClassesChange,
            )
            Text(
                text = "Include system classes",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun ClassList(
    classes: List<ClassInfo>,
    selectedClass: ClassInfo?,
    isLoading: Boolean,
    onSelect: (ClassInfo?) -> Unit,
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
                text = "Classes (${classes.size})",
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (isLoading && classes.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (classes.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No classes found",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(classes, key = { it.name }) { classInfo ->
                        ClassListItem(
                            classInfo = classInfo,
                            isSelected = classInfo == selectedClass,
                            onClick = { onSelect(if (classInfo == selectedClass) null else classInfo) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ClassListItem(
    classInfo: ClassInfo,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surface
        },
        shape = MaterialTheme.shapes.small,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            // Simple class name
            val simpleName = classInfo.name.substringAfterLast('.')
            val packageName = classInfo.name.substringBeforeLast('.', "")

            Text(
                text = simpleName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isSelected) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )

            if (packageName.isNotEmpty()) {
                Text(
                    text = packageName,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isSelected) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun CodePanel(
    selectedClass: ClassInfo?,
    decompiled: String?,
    isLoading: Boolean,
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
                text = selectedClass?.name ?: "Select a class",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(modifier = Modifier.height(12.dp))

            when {
                selectedClass == null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Select a class to view its source",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Decompiling...",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                decompiled != null -> {
                    CodeViewer(
                        code = decompiled,
                        modifier = Modifier.fillMaxSize(),
                    )
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
