package com.bugdigger.bytesight.ui.inspector

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bugdigger.bytesight.ui.components.CodeViewer
import com.bugdigger.core.analysis.DisassembledMethod
import com.bugdigger.core.analysis.Instruction
import com.bugdigger.core.analysis.InstructionCategory

/**
 * Screen for inspecting raw bytecode instructions alongside decompiled source.
 */
@Composable
fun InspectorScreen(
    viewModel: InspectorViewModel,
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
        InspectorHeader()

        Spacer(modifier = Modifier.height(16.dp))

        // Error display
        uiState.error?.let { error ->
            ErrorBanner(error = error, onDismiss = viewModel::clearError)
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Selectors row
        SelectorRow(
            classes = uiState.classes,
            selectedClassName = uiState.selectedClassName,
            methods = uiState.disassembledClass?.methods ?: emptyList(),
            selectedMethod = uiState.selectedMethod,
            onSelectClass = viewModel::selectClass,
            onSelectMethod = viewModel::selectMethod,
            isLoading = uiState.isLoading || uiState.isLoadingClasses,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Main content: bytecode on left, decompiled on right
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Bytecode panel
            BytecodePanel(
                method = uiState.selectedMethod,
                selectedInstruction = uiState.selectedInstruction,
                isLoading = uiState.isLoading,
                onSelectInstruction = viewModel::selectInstruction,
                modifier = Modifier.weight(1f),
            )

            // Decompiled source panel
            DecompiledPanel(
                source = uiState.decompiledSource,
                isLoading = uiState.isLoading,
                modifier = Modifier.weight(1f),
            )
        }

        // Instruction detail panel
        uiState.selectedInstruction?.let { instruction ->
            Spacer(modifier = Modifier.height(8.dp))
            InstructionDetailPanel(instruction = instruction)
        }
    }
}

@Composable
private fun InspectorHeader(modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = "Bytecode Inspector",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Inspect raw JVM bytecode instructions alongside decompiled source",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SelectorRow(
    classes: List<com.bugdigger.protocol.ClassInfo>,
    selectedClassName: String?,
    methods: List<DisassembledMethod>,
    selectedMethod: DisassembledMethod?,
    onSelectClass: (String) -> Unit,
    onSelectMethod: (String, String) -> Unit,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
) {
    var classDropdownExpanded by remember { mutableStateOf(false) }
    var methodDropdownExpanded by remember { mutableStateOf(false) }
    var classSearchQuery by remember { mutableStateOf("") }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Class selector
        Box(modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = selectedClassName ?: "",
                onValueChange = { classSearchQuery = it },
                placeholder = { Text("Select a class...") },
                label = { Text("Class") },
                singleLine = true,
                readOnly = false,
                modifier = Modifier.fillMaxWidth().clickable { classDropdownExpanded = true },
            )
            DropdownMenu(
                expanded = classDropdownExpanded,
                onDismissRequest = { classDropdownExpanded = false },
                modifier = Modifier.heightIn(max = 300.dp),
            ) {
                val filtered = if (classSearchQuery.isBlank()) classes
                else classes.filter { it.name.lowercase().contains(classSearchQuery.lowercase()) }

                filtered.take(50).forEach { classInfo ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(
                                    text = classInfo.name.substringAfterLast('.'),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = classInfo.name.substringBeforeLast('.', ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            onSelectClass(classInfo.name)
                            classDropdownExpanded = false
                            classSearchQuery = ""
                        },
                    )
                }
            }
        }

        // Method selector
        Box(modifier = Modifier.weight(1f)) {
            OutlinedTextField(
                value = selectedMethod?.let { "${it.name}${it.descriptor}" } ?: "",
                onValueChange = {},
                placeholder = { Text("Select a method...") },
                label = { Text("Method") },
                singleLine = true,
                readOnly = true,
                modifier = Modifier.fillMaxWidth().clickable { methodDropdownExpanded = true },
                enabled = methods.isNotEmpty(),
            )
            DropdownMenu(
                expanded = methodDropdownExpanded,
                onDismissRequest = { methodDropdownExpanded = false },
                modifier = Modifier.heightIn(max = 300.dp),
            ) {
                methods.forEach { method ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = "${method.accessString} ${method.name}${method.descriptor}",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            onSelectMethod(method.name, method.descriptor)
                            methodDropdownExpanded = false
                        },
                    )
                }
            }
        }

        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
        }
    }
}

@Composable
private fun BytecodePanel(
    method: DisassembledMethod?,
    selectedInstruction: Instruction?,
    isLoading: Boolean,
    onSelectInstruction: (Instruction?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxHeight(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Bytecode",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (method != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "(${method.instructions.size} instructions, stack=${method.maxStack}, locals=${method.maxLocals})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            when {
                isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                method == null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Select a class and method", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> {
                    // Header row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text("#", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(36.dp))
                        Text("Line", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(40.dp))
                        Text("Opcode", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(140.dp))
                        Text("Operands", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                    }

                    HorizontalDivider()

                    LazyColumn {
                        items(method.instructions) { instruction ->
                            InstructionRow(
                                instruction = instruction,
                                isSelected = instruction == selectedInstruction,
                                onClick = {
                                    onSelectInstruction(
                                        if (instruction == selectedInstruction) null else instruction
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InstructionRow(
    instruction: Instruction,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = instructionColor(instruction.type)

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = if (isSelected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            Color.Transparent
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Offset
            Text(
                text = "${instruction.offset}",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.width(36.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Line number
            Text(
                text = instruction.lineNumber?.toString() ?: "",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier.width(40.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Mnemonic
            Text(
                text = instruction.mnemonic,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = Modifier.width(140.dp),
                color = color,
            )

            // Operands
            Text(
                text = instruction.operands,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun DecompiledPanel(
    source: String?,
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
                text = "Decompiled Source",
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(modifier = Modifier.height(12.dp))

            when {
                isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                source == null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Select a class to decompile", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> {
                    CodeViewer(
                        code = source,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun InstructionDetailPanel(
    instruction: Instruction,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(100.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column {
                Text("Instruction", style = MaterialTheme.typography.labelSmall)
                Text(
                    text = instruction.mnemonic,
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    color = instructionColor(instruction.type),
                )
            }
            Column {
                Text("Opcode", style = MaterialTheme.typography.labelSmall)
                Text(
                    text = "0x${instruction.opcode.toString(16).uppercase()}  (${instruction.opcode})",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
            }
            Column {
                Text("Offset", style = MaterialTheme.typography.labelSmall)
                Text(
                    text = "#${instruction.offset}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
            }
            Column {
                Text("Category", style = MaterialTheme.typography.labelSmall)
                Text(
                    text = instruction.type.name.replace('_', ' '),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (instruction.operands.isNotBlank()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Operands", style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = instruction.operands,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (instruction.lineNumber != null) {
                Column {
                    Text("Source Line", style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = "${instruction.lineNumber}",
                        style = MaterialTheme.typography.bodyMedium,
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

/**
 * Returns the color for an instruction category.
 */
@Composable
private fun instructionColor(category: InstructionCategory): Color = when (category) {
    InstructionCategory.CONTROL_FLOW -> Color(0xFFEF5350)  // Red
    InstructionCategory.METHOD_CALL -> Color(0xFF42A5F5)   // Blue
    InstructionCategory.FIELD_ACCESS -> Color(0xFF66BB6A)  // Green
    InstructionCategory.LOAD_STORE -> Color(0xFF9E9E9E)    // Gray
    InstructionCategory.STACK -> Color(0xFFAB47BC)         // Purple
    InstructionCategory.CONSTANT -> Color(0xFFFFCA28)      // Amber
    InstructionCategory.ARITHMETIC -> Color(0xFFFF7043)    // Deep Orange
    InstructionCategory.TYPE_CHECK -> Color(0xFF26C6DA)    // Cyan
    InstructionCategory.ARRAY -> Color(0xFF8D6E63)         // Brown
    InstructionCategory.OTHER -> MaterialTheme.colorScheme.onSurface
}
