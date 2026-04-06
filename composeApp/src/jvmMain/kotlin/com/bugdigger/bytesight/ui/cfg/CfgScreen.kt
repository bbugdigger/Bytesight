package com.bugdigger.bytesight.ui.cfg

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bugdigger.bytesight.ui.components.CodeViewer
import com.bugdigger.bytesight.ui.components.GraphView
import com.bugdigger.bytesight.ui.components.LayoutNode
import com.bugdigger.core.analysis.*
import androidx.compose.ui.platform.LocalDensity

@Composable
fun CfgScreen(
    viewModel: CfgViewModel,
    connectionKey: String,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    // Pass screen density to ViewModel for correct block size calculations
    val density = LocalDensity.current.density
    LaunchedEffect(density) {
        viewModel.setDensity(density)
    }

    // Comment dialog state
    var showCommentDialog by remember { mutableStateOf(false) }
    var commentTargetBlockId by remember { mutableStateOf<String?>(null) }
    var commentTargetOffset by remember { mutableStateOf<Int?>(null) }
    var commentInitialText by remember { mutableStateOf("") }
    LaunchedEffect(connectionKey) {
        viewModel.setConnectionKey(connectionKey)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        // Header
        Column {
            Text(
                text = "Control Flow Graph",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Visualize method control flow alongside decompiled source",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Error display
        uiState.error?.let { error ->
            ErrorBanner(error = error, onDismiss = viewModel::clearError)
            Spacer(modifier = Modifier.height(16.dp))
        }

        // Selectors row — zIndex ensures dropdowns render above the panels below
        Box(modifier = Modifier.zIndex(1f)) {
            SelectorRow(
                classes = uiState.classes,
                selectedClassName = uiState.selectedClassName,
                methods = uiState.disassembledClass?.methods ?: emptyList(),
                selectedMethod = uiState.selectedMethod,
                onSelectClass = viewModel::selectClass,
                onSelectMethod = viewModel::selectMethod,
                isLoading = uiState.isLoading || uiState.isLoadingClasses,
                onDropdownExpandedChange = {},
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Main content: decompiled source + CFG graph
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Left: Decompiled source
            DecompiledPanel(
                source = uiState.decompiledSource,
                isLoading = uiState.isLoading,
                modifier = Modifier.weight(1f),
            )

            // Right: CFG graph
            CfgGraphPanel(
                uiState = uiState,
                onBlockClick = { viewModel.selectBlock(it) },
                onBlockHeaderClick = { viewModel.selectBlockHeader(it) },
                onInstructionClick = { blockId, offset -> viewModel.selectInstruction(blockId, offset) },
                onRequestComment = { blockId, offset ->
                    commentTargetBlockId = blockId
                    commentTargetOffset = offset
                    // Pre-fill with existing comment if present
                    commentInitialText = uiState.comments[blockId]?.get(offset) ?: ""
                    showCommentDialog = true
                },
                modifier = Modifier.weight(1f),
            )
        }
    }

    // Comment dialog
    if (showCommentDialog) {
        CommentDialog(
            initialText = commentInitialText,
            isBlockComment = commentTargetOffset == null,
            onConfirm = { text ->
                val blockId = commentTargetBlockId
                if (blockId != null) {
                    viewModel.addComment(blockId, commentTargetOffset, text)
                }
                showCommentDialog = false
            },
            onDismiss = { showCommentDialog = false },
        )
    }
}

@Composable
private fun CfgGraphPanel(
    uiState: CfgUiState,
    onBlockClick: (String?) -> Unit,
    onBlockHeaderClick: (String) -> Unit,
    onInstructionClick: (String, Int) -> Unit,
    onRequestComment: (blockId: String, instructionOffset: Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Focus requester so the panel can capture keyboard events
    val focusRequester = remember { FocusRequester() }

    Card(
        modifier = modifier
            .fillMaxHeight()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Slash) {
                    val blockId = uiState.selectedBlockId
                    if (blockId != null) {
                        if (uiState.isBlockHeaderSelected) {
                            // Block-level comment
                            onRequestComment(blockId, null)
                        } else if (uiState.selectedInstructionOffset != null) {
                            // Instruction-level comment
                            onRequestComment(blockId, uiState.selectedInstructionOffset)
                        }
                        true
                    } else false
                } else false
            },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val layout = uiState.graphLayout

            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                    )
                }

                layout == null || layout.nodes.isEmpty() -> {
                    Text(
                        text = "Select a class and method to view its control flow graph",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                else -> {
                    // Request focus so key events work
                    LaunchedEffect(Unit) { focusRequester.requestFocus() }

                    Column(modifier = Modifier.fillMaxSize()) {
                        // Edge legend
                        EdgeLegend(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                        )

                        // Hint for comment shortcut
                        Text(
                            text = "Press / to add a comment on the selected instruction or block header",
                            modifier = Modifier.padding(horizontal = 8.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        )

                        // Graph
                        GraphView(
                            layout = layout,
                            onNodeClick = { node -> onBlockClick(node.id) },
                            selectedNodeId = uiState.selectedBlockId,
                            nodeContent = { node ->
                                BasicBlockNodeView(
                                    block = node.data,
                                    isSelected = node.id == uiState.selectedBlockId,
                                    isBlockHeaderSelected = node.id == uiState.selectedBlockId && uiState.isBlockHeaderSelected,
                                    selectedInstructionOffset = if (node.id == uiState.selectedBlockId) {
                                        uiState.selectedInstructionOffset
                                    } else null,
                                    blockComments = uiState.comments[node.id] ?: emptyMap(),
                                    onBlockHeaderClick = { onBlockHeaderClick(node.id) },
                                    onInstructionClick = { offset -> onInstructionClick(node.id, offset) },
                                )
                            },
                            edgeColor = ::edgeTypeColor,
                            edgeDashPattern = { edge ->
                                if (edge.type == EdgeType.EXCEPTION) {
                                    PathEffect.dashPathEffect(floatArrayOf(8f, 4f))
                                } else null
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BasicBlockNodeView(
    block: BasicBlock,
    isSelected: Boolean,
    isBlockHeaderSelected: Boolean,
    selectedInstructionOffset: Int?,
    blockComments: Map<Int?, String>,
    onBlockHeaderClick: () -> Unit,
    onInstructionClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = when (block.blockType) {
        BlockType.ENTRY -> Color(0xFF4A90D9)     // Blue
        BlockType.NORMAL -> Color(0xFF888888)     // Gray
        BlockType.LOOP_HEADER -> Color(0xFFE8A838) // Orange
        BlockType.CATCH_BLOCK -> Color(0xFFD94A4A) // Red
        BlockType.EXIT -> Color(0xFF4AD94A)       // Green
    }

    val bgColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = modifier.fillMaxSize(),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        border = CardDefaults.outlinedCardBorder().copy(
            width = if (isSelected) 2.dp else 1.dp,
            brush = androidx.compose.ui.graphics.SolidColor(borderColor),
        ),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Block header (clickable for block-level comments)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isBlockHeaderSelected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        } else {
                            borderColor.copy(alpha = 0.15f)
                        }
                    )
                    .clickable { onBlockHeaderClick() }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = block.id,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Surface(
                    color = borderColor.copy(alpha = 0.3f),
                    shape = MaterialTheme.shapes.extraSmall,
                ) {
                    Text(
                        text = block.blockType.name,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // Block-level comment (below header)
            blockComments[null]?.let { blockComment ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = "; $blockComment",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontStyle = FontStyle.Italic,
                        color = Color(0xFF6A9955),  // Green-gray like IDA comments
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Instructions
            for (instr in block.instructions) {
                val instrSelected = selectedInstructionOffset == instr.offset
                val instrComment = blockComments[instr.offset]

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (instrSelected) {
                                Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                            } else Modifier
                        )
                        .clickable { onInstructionClick(instr.offset) }
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Offset
                    Text(
                        text = String.format("%3d", instr.offset),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Mnemonic
                    Text(
                        text = instr.mnemonic,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = instructionColor(instr.type),
                    )
                    // Operands
                    if (instr.operands.isNotEmpty()) {
                        Text(
                            text = instr.operands,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (instrComment != null) Modifier.weight(1f, fill = false) else Modifier,
                        )
                    }
                    // Inline comment (IDA-style: "; comment text")
                    if (instrComment != null) {
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "; $instrComment",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF6A9955),  // Green-gray comment color
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dialog for entering or editing a comment on a block or instruction.
 */
@Composable
private fun CommentDialog(
    initialText: String,
    isBlockComment: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initialText) }
    val focusRequester = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (isBlockComment) "Block Comment" else "Instruction Comment",
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        text = {
            Column {
                Text(
                    text = if (isBlockComment) {
                        "Enter a comment for this basic block. Leave empty to remove."
                    } else {
                        "Enter a comment for this instruction. Leave empty to remove."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("Type your comment...") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
                LaunchedEffect(Unit) { focusRequester.requestFocus() }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun EdgeLegend(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendItem("Fall-through", edgeTypeColor(EdgeType.FALL_THROUGH))
        LegendItem("Jump", edgeTypeColor(EdgeType.UNCONDITIONAL_JUMP))
        LegendItem("True", edgeTypeColor(EdgeType.BRANCH_TRUE))
        LegendItem("False", edgeTypeColor(EdgeType.BRANCH_FALSE))
        LegendItem("Exception", edgeTypeColor(EdgeType.EXCEPTION))
        LegendItem("Switch", edgeTypeColor(EdgeType.SWITCH_CASE))
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Surface(
            modifier = Modifier.size(10.dp),
            color = color,
            shape = MaterialTheme.shapes.extraSmall,
        ) {}
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            Text(
                text = "Decompiled Source",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(modifier = Modifier.height(8.dp))

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }

                source != null -> {
                    CodeViewer(
                        code = source,
                        language = "java",
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                    )
                }

                else -> {
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Select a class to view decompiled source",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelectorRow(
    classes: List<com.bugdigger.protocol.ClassInfo>,
    selectedClassName: String?,
    methods: List<DisassembledMethod>,
    selectedMethod: DisassembledMethod?,
    onSelectClass: (String) -> Unit,
    onSelectMethod: (String, String) -> Unit,
    isLoading: Boolean,
    onDropdownExpandedChange: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var classExpanded by remember { mutableStateOf(false) }
    var methodExpanded by remember { mutableStateOf(false) }
    var classSearchText by remember { mutableStateOf("") }

    LaunchedEffect(classExpanded, methodExpanded) {
        onDropdownExpandedChange(classExpanded || methodExpanded)
    }

    LaunchedEffect(selectedClassName) {
        classSearchText = selectedClassName ?: ""
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Class selector
        ExposedDropdownMenuBox(
            expanded = classExpanded,
            onExpandedChange = { classExpanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = classSearchText,
                onValueChange = { text ->
                    classSearchText = text
                    classExpanded = true
                },
                placeholder = { Text("Type to search classes...") },
                label = { Text("Class") },
                singleLine = true,
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = classExpanded)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryEditable),
            )

            val filteredClasses = remember(classSearchText, classes) {
                if (classSearchText.isBlank() || classSearchText == selectedClassName) {
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
                    if (classSearchText != selectedClassName) {
                        classSearchText = selectedClassName ?: ""
                    }
                },
                modifier = Modifier.heightIn(max = 300.dp),
            ) {
                if (filteredClasses.isEmpty()) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                "No classes found",
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
                                onSelectClass(classInfo.name)
                                classExpanded = false
                            },
                        )
                    }
                }
            }
        }

        // Method selector
        ExposedDropdownMenuBox(
            expanded = methodExpanded,
            onExpandedChange = { if (methods.isNotEmpty()) methodExpanded = it },
            modifier = Modifier.weight(1f),
        ) {
            OutlinedTextField(
                value = selectedMethod?.let { "${it.name}${it.descriptor}" } ?: "",
                onValueChange = {},
                placeholder = { Text("Select a method...") },
                label = { Text("Method") },
                singleLine = true,
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = methodExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                enabled = methods.isNotEmpty(),
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
                                text = "${method.accessString} ${method.name}${method.descriptor}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            onSelectMethod(method.name, method.descriptor)
                            methodExpanded = false
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

private fun edgeTypeColor(edge: CfgEdge): Color = edgeTypeColor(edge.type)

private fun edgeTypeColor(type: EdgeType): Color = when (type) {
    EdgeType.FALL_THROUGH -> Color(0xFF888888)        // Gray
    EdgeType.UNCONDITIONAL_JUMP -> Color(0xFF4A90D9)  // Blue
    EdgeType.BRANCH_TRUE -> Color(0xFF4AD94A)         // Green
    EdgeType.BRANCH_FALSE -> Color(0xFFD94A4A)        // Red
    EdgeType.EXCEPTION -> Color(0xFFE8A838)           // Orange
    EdgeType.SWITCH_CASE -> Color(0xFF9B59B6)         // Purple
    EdgeType.SWITCH_DEFAULT -> Color(0xFF9B59B6)      // Purple
}

@Composable
private fun instructionColor(type: InstructionCategory): Color = when (type) {
    InstructionCategory.CONTROL_FLOW -> Color(0xFFD94A4A)
    InstructionCategory.METHOD_CALL -> Color(0xFF4A90D9)
    InstructionCategory.FIELD_ACCESS -> Color(0xFF4AD94A)
    InstructionCategory.LOAD_STORE -> Color(0xFF888888)
    InstructionCategory.STACK -> Color(0xFF888888)
    InstructionCategory.CONSTANT -> Color(0xFFE8A838)
    InstructionCategory.ARITHMETIC -> MaterialTheme.colorScheme.onSurface
    InstructionCategory.TYPE_CHECK -> Color(0xFF9B59B6)
    InstructionCategory.ARRAY -> Color(0xFF4AD94A)
    InstructionCategory.OTHER -> MaterialTheme.colorScheme.onSurface
}
