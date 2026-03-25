package com.bugdigger.bytesight.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Screen for managing application settings.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        // Header
        SettingsHeader(
            onResetToDefaults = viewModel::resetToDefaults,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Settings content
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            // Decompiler Settings
            SettingsSection(title = "Decompiler") {
                SwitchSetting(
                    title = "Show line numbers",
                    description = "Display line numbers in decompiled source",
                    checked = uiState.showLineNumbers,
                    onCheckedChange = viewModel::setShowLineNumbers,
                )

                SwitchSetting(
                    title = "Show bytecode comments",
                    description = "Include bytecode offset comments in output",
                    checked = uiState.showBytecodeComments,
                    onCheckedChange = viewModel::setShowBytecodeComments,
                )

                SwitchSetting(
                    title = "Simplify lambdas",
                    description = "Use simplified lambda syntax when possible",
                    checked = uiState.simplifyLambdas,
                    onCheckedChange = viewModel::setSimplifyLambdas,
                )
            }

            // Trace Settings
            SettingsSection(title = "Method Tracing") {
                SliderSetting(
                    title = "Max trace events",
                    description = "Maximum number of trace events to keep in memory",
                    value = uiState.maxTraceEvents.toFloat(),
                    valueRange = 100f..10000f,
                    steps = 98,
                    valueLabel = "${uiState.maxTraceEvents}",
                    onValueChange = { viewModel.setMaxTraceEvents(it.toInt()) },
                )

                SwitchSetting(
                    title = "Trace arguments",
                    description = "Capture method arguments in trace events",
                    checked = uiState.traceArguments,
                    onCheckedChange = viewModel::setTraceArguments,
                )

                SwitchSetting(
                    title = "Trace return values",
                    description = "Capture return values in trace events",
                    checked = uiState.traceReturnValues,
                    onCheckedChange = viewModel::setTraceReturnValues,
                )

                SwitchSetting(
                    title = "Trace exceptions",
                    description = "Capture exception information in trace events",
                    checked = uiState.traceExceptions,
                    onCheckedChange = viewModel::setTraceExceptions,
                )

                SwitchSetting(
                    title = "Trace timing",
                    description = "Measure method execution time",
                    checked = uiState.traceTiming,
                    onCheckedChange = viewModel::setTraceTiming,
                )
            }

            // Connection Settings
            SettingsSection(title = "Connection") {
                SliderSetting(
                    title = "Default port",
                    description = "Default agent port to connect to",
                    value = uiState.defaultPort.toFloat(),
                    valueRange = 1024f..65535f,
                    steps = 0,
                    valueLabel = "${uiState.defaultPort}",
                    onValueChange = { viewModel.setDefaultPort(it.toInt()) },
                )

                SliderSetting(
                    title = "Connection timeout",
                    description = "Timeout for agent connections",
                    value = uiState.connectionTimeoutMs.toFloat(),
                    valueRange = 1000f..30000f,
                    steps = 28,
                    valueLabel = "${uiState.connectionTimeoutMs / 1000}s",
                    onValueChange = { viewModel.setConnectionTimeout(it.toInt()) },
                )
            }

            // UI Settings
            SettingsSection(title = "Appearance") {
                SliderSetting(
                    title = "Code font size",
                    description = "Font size for code viewer",
                    value = uiState.fontSize.toFloat(),
                    valueRange = 10f..24f,
                    steps = 13,
                    valueLabel = "${uiState.fontSize}px",
                    onValueChange = { viewModel.setFontSize(it.toInt()) },
                )
            }

            // About Section
            SettingsSection(title = "About") {
                AboutInfo()
            }
        }
    }
}

@Composable
private fun SettingsHeader(
    onResetToDefaults: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Configure Bytesight preferences",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        OutlinedButton(onClick = onResetToDefaults) {
            Text("Reset to Defaults")
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(16.dp))
            content()
        }
    }
}

@Composable
private fun SwitchSetting(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
private fun SliderSetting(
    title: String,
    description: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: String,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = valueLabel,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
        )
    }
}

@Composable
private fun AboutInfo(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InfoRow(label = "Application", value = "Bytesight")
        InfoRow(label = "Version", value = "0.1.0-SNAPSHOT")
        InfoRow(label = "Platform", value = "JVM / Compose Desktop")

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "A JVM reverse engineering utility for analyzing obfuscated applications. " +
                "Attach to running JVMs, decompile bytecode, and trace method execution.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
