package com.bugdigger.bytesight.ui.strings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bugdigger.core.analysis.ConstantType
import com.bugdigger.core.analysis.ExtractedConstant
import com.bugdigger.core.analysis.StringPattern

/**
 * Screen for extracting and browsing string/constant values from loaded classes.
 */
@Composable
fun StringsScreen(
    viewModel: StringsViewModel,
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
        StringsHeader(
            isExtracting = uiState.isExtracting,
            onExtract = viewModel::extractAll,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Error display
        uiState.error?.let { error ->
            ErrorBanner(error = error, onDismiss = viewModel::clearError)
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Progress bar
        if (uiState.isExtracting) {
            Column {
                LinearProgressIndicator(
                    progress = { uiState.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Scanning ${uiState.processedClasses}/${uiState.totalClasses} classes...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Search and filters
        SearchAndFilters(
            query = uiState.searchQuery,
            onQueryChange = viewModel::setSearchQuery,
            typeFilter = uiState.typeFilter,
            onToggleType = viewModel::toggleTypeFilter,
            patternFilter = uiState.patternFilter,
            onTogglePattern = viewModel::togglePatternFilter,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Results count
        Text(
            text = "${uiState.filteredConstants.size} constants found" +
                    if (uiState.filteredConstants.size != uiState.constants.size) {
                        " (${uiState.constants.size} total)"
                    } else "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Constants table
        ConstantsTable(
            constants = uiState.filteredConstants,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun StringsHeader(
    isExtracting: Boolean,
    onExtract: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "String & Constant Extraction",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Extract hardcoded strings, numbers, and references from loaded classes",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(
            onClick = onExtract,
            enabled = !isExtracting,
        ) {
            if (isExtracting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(if (isExtracting) "Extracting..." else "Extract All")
        }
    }
}

@Composable
private fun SearchAndFilters(
    query: String,
    onQueryChange: (String) -> Unit,
    typeFilter: Set<ConstantType>,
    onToggleType: (ConstantType) -> Unit,
    patternFilter: Set<StringPattern>?,
    onTogglePattern: (StringPattern) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        // Search
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = { Text("Search constants...") },
            leadingIcon = { Text("🔍") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Type filter chips
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Types:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            ConstantType.entries.forEach { type ->
                FilterChip(
                    selected = type in typeFilter,
                    onClick = { onToggleType(type) },
                    label = { Text(type.name, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Pattern filter chips
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Patterns:",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.CenterVertically),
            )
            StringPattern.entries.forEach { pattern ->
                FilterChip(
                    selected = patternFilter != null && pattern in patternFilter,
                    onClick = { onTogglePattern(pattern) },
                    label = { Text(pattern.label, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }
}

@Composable
private fun ConstantsTable(
    constants: List<ExtractedConstant>,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        if (constants.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No constants found. Click \"Extract All\" to scan loaded classes.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(modifier = Modifier.padding(8.dp)) {
                // Table header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("Value", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(2f))
                    Text("Type", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(80.dp))
                    Text("Pattern", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.width(100.dp))
                    Text("Location", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1.5f))
                }

                HorizontalDivider()

                // Table rows
                LazyColumn {
                    itemsIndexed(constants, key = { index, constant -> "$index.${constant.className}.${constant.methodName}" }) { _, constant ->
                        ConstantRow(constant)
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConstantRow(
    constant: ExtractedConstant,
    modifier: Modifier = Modifier,
) {
    val patternColor = when {
        constant.matchedPatterns.contains(StringPattern.URL) -> MaterialTheme.colorScheme.primary
        constant.matchedPatterns.contains(StringPattern.IP_ADDRESS) -> MaterialTheme.colorScheme.tertiary
        constant.matchedPatterns.contains(StringPattern.CRYPTO_KEY) -> MaterialTheme.colorScheme.error
        constant.matchedPatterns.contains(StringPattern.EMAIL) -> MaterialTheme.colorScheme.secondary
        constant.matchedPatterns.contains(StringPattern.SQL) -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Value
        Text(
            text = constant.value.toString(),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(2f),
            color = patternColor,
        )

        // Type
        Text(
            text = constant.type.name,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(80.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Pattern
        Text(
            text = constant.matchedPatterns.joinToString(", ") { it.label }.ifEmpty { "—" },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(100.dp),
            color = if (constant.matchedPatterns.isNotEmpty()) patternColor else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // Location
        Text(
            text = constant.location,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1.5f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
