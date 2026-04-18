package com.bugdigger.bytesight.ui.inspector

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.bugdigger.bytesight.ui.components.CodeViewer
import com.bugdigger.bytesight.ui.components.CommentDialog
import com.bugdigger.bytesight.ui.components.GraphView
import com.bugdigger.bytesight.ui.components.SelectorRow
import com.bugdigger.core.analysis.BasicBlock
import com.bugdigger.core.analysis.BlockType
import com.bugdigger.core.analysis.CfgEdge
import com.bugdigger.core.analysis.DisassembledMethod
import com.bugdigger.core.analysis.EdgeType
import com.bugdigger.core.analysis.Instruction
import com.bugdigger.core.analysis.InstructionCategory

/**
 * Screen for inspecting raw bytecode. Linear list by default; press TAB to toggle
 * to the control-flow-graph view. Both views share the same class/method selector
 * and commenting (press `/` with a selection to comment).
 */
@Composable
fun InspectorScreen(
    viewModel: InspectorViewModel,
    connectionKey: String,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    val density = LocalDensity.current.density
    LaunchedEffect(density) {
        viewModel.setDensity(density)
    }

    LaunchedEffect(connectionKey) {
        viewModel.setConnectionKey(connectionKey)
    }

    // Dialog state for commenting.
    var showCommentDialog by remember { mutableStateOf(false) }
    var commentIsBlock by remember { mutableStateOf(false) }
    var commentTargetOffset by remember { mutableStateOf<Int?>(null) }
    var commentTargetBlockId by remember { mutableStateOf<String?>(null) }
    var commentInitialText by remember { mutableStateOf("") }

    // Dropdown-open flag suppresses TAB/slash handling so typing in the class
    // filter or navigating dropdown items with keys isn't hijacked.
    var dropdownOpen by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    fun openInstructionCommentDialog(offset: Int) {
        commentIsBlock = false
        commentTargetBlockId = null
        commentTargetOffset = offset
        commentInitialText = uiState.methodComments.instructionLevel[offset] ?: ""
        showCommentDialog = true
    }

    fun openBlockCommentDialog(blockId: String) {
        commentIsBlock = true
        commentTargetBlockId = blockId
        commentTargetOffset = null
        commentInitialText = uiState.methodComments.blockLevel[blockId] ?: ""
        showCommentDialog = true
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || dropdownOpen || showCommentDialog) {
                    return@onKeyEvent false
                }
                when (event.key) {
                    Key.Tab -> {
                        viewModel.toggleViewMode()
                        true
                    }
                    Key.Slash -> {
                        if (uiState.viewMode == ViewMode.LINEAR) {
                            val offset = uiState.selectedInstruction?.offset
                            if (offset != null) {
                                openInstructionCommentDialog(offset)
                                true
                            } else false
                        } else {
                            val blockId = uiState.selectedBlockId
                            if (blockId != null) {
                                if (uiState.isBlockHeaderSelected) {
                                    openBlockCommentDialog(blockId)
                                    true
                                } else {
                                    val offset = uiState.selectedInstructionOffset
                                    if (offset != null) {
                                        openInstructionCommentDialog(offset)
                                        true
                                    } else false
                                }
                            } else false
                        }
                    }
                    else -> false
                }
            },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
        ) {
            InspectorHeader(viewMode = uiState.viewMode)

            Spacer(modifier = Modifier.height(16.dp))

            uiState.error?.let { error ->
                ErrorBanner(error = error, onDismiss = viewModel::clearError)
                Spacer(modifier = Modifier.height(16.dp))
            }

            Box(modifier = Modifier.zIndex(1f)) {
                SelectorRow(
                    classes = uiState.classes,
                    selectedClassName = uiState.selectedClassName,
                    methods = uiState.disassembledClass?.methods ?: emptyList(),
                    selectedMethod = uiState.selectedMethod,
                    onSelectClass = viewModel::selectClass,
                    onSelectMethod = viewModel::selectMethod,
                    isLoading = uiState.isLoading || uiState.isLoadingClasses,
                    onDropdownExpandedChange = { dropdownOpen = it },
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                DecompiledPanel(
                    source = uiState.decompiledSource,
                    isLoading = uiState.isLoading,
                    modifier = Modifier.weight(1f),
                )

                when (uiState.viewMode) {
                    ViewMode.LINEAR -> BytecodePanel(
                        method = uiState.selectedMethod,
                        selectedInstruction = uiState.selectedInstruction,
                        instructionComments = uiState.methodComments.instructionLevel,
                        isLoading = uiState.isLoading,
                        onSelectInstruction = viewModel::selectInstruction,
                        modifier = Modifier.weight(1f),
                    )
                    ViewMode.CFG -> CfgPanel(
                        uiState = uiState,
                        onBlockClick = viewModel::selectBlock,
                        onBlockHeaderClick = viewModel::selectBlockHeader,
                        onInstructionClick = viewModel::selectCfgInstruction,
                        modifier = Modifier.weight(1f),
                    )
                }
            }

            if (uiState.viewMode == ViewMode.LINEAR) {
                uiState.selectedInstruction?.let { instruction ->
                    Spacer(modifier = Modifier.height(8.dp))
                    InstructionDetailPanel(instruction = instruction)
                }
            }
        }
    }

    if (showCommentDialog) {
        CommentDialog(
            initialText = commentInitialText,
            isBlockComment = commentIsBlock,
            onConfirm = { text ->
                if (commentIsBlock) {
                    commentTargetBlockId?.let { viewModel.addBlockComment(it, text) }
                } else {
                    commentTargetOffset?.let { viewModel.addInstructionComment(it, text) }
                }
                showCommentDialog = false
            },
            onDismiss = { showCommentDialog = false },
        )
    }
}

@Composable
private fun InspectorHeader(viewMode: ViewMode, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = "Bytecode Inspector",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val subtitle = when (viewMode) {
            ViewMode.LINEAR -> "Linear view. Press TAB for control flow graph. Press / to comment on the selected instruction."
            ViewMode.CFG -> "Control flow graph. Press TAB for linear view. Press / to comment on the selected block or instruction."
        }
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BytecodePanel(
    method: DisassembledMethod?,
    selectedInstruction: Instruction?,
    instructionComments: Map<Int, String>,
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
                    color = MaterialTheme.colorScheme.onSurface,
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
                        Text(
                            text = "Select a class and method",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text("#", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(36.dp))
                        Text("Line", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(40.dp))
                        Text("Opcode", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(140.dp))
                        Text("Operands", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    }

                    HorizontalDivider()

                    LazyColumn {
                        itemsIndexed(method.instructions, key = { index, _ -> index }) { _, instruction ->
                            InstructionRow(
                                instruction = instruction,
                                isSelected = instruction == selectedInstruction,
                                comment = instructionComments[instruction.offset],
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
    comment: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val color = linearInstructionColor(instruction.type)

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
        Column {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${instruction.offset}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.width(36.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = instruction.lineNumber?.toString() ?: "",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier.width(40.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = instruction.mnemonic,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    ),
                    modifier = Modifier.width(140.dp),
                    color = color,
                )
                Text(
                    text = instruction.operands,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                )
                if (comment != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "; $comment",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        fontStyle = FontStyle.Italic,
                        color = Color(0xFF6A9955),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }
    }
}

@Composable
private fun CfgPanel(
    uiState: InspectorUiState,
    onBlockClick: (String?) -> Unit,
    onBlockHeaderClick: (String) -> Unit,
    onInstructionClick: (String, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxHeight(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            val layout = uiState.graphLayout

            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                layout == null || layout.nodes.isEmpty() -> {
                    Text(
                        text = "Select a class and method to view its control flow graph",
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        EdgeLegend(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                        )

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
                                    blockComment = uiState.methodComments.blockLevel[node.id],
                                    instructionComments = uiState.methodComments.instructionLevel,
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
    blockComment: String?,
    instructionComments: Map<Int, String>,
    onBlockHeaderClick: () -> Unit,
    onInstructionClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = when (block.blockType) {
        BlockType.ENTRY -> Color(0xFF4A90D9)
        BlockType.NORMAL -> Color(0xFF888888)
        BlockType.LOOP_HEADER -> Color(0xFFE8A838)
        BlockType.CATCH_BLOCK -> Color(0xFFD94A4A)
        BlockType.EXIT -> Color(0xFF4AD94A)
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

            blockComment?.let { text ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = "; $text",
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontStyle = FontStyle.Italic,
                        color = Color(0xFF6A9955),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            for (instr in block.instructions) {
                val instrSelected = selectedInstructionOffset == instr.offset
                val instrComment = instructionComments[instr.offset]

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
                    Text(
                        text = String.format("%3d", instr.offset),
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = instr.mnemonic,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = cfgInstructionColor(instr.type),
                    )
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
                    if (instrComment != null) {
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = "; $instrComment",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF6A9955),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
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
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Decompiled Source",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
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
                        Text(
                            text = "Select a class to decompile",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                Text("Instruction", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    text = instruction.mnemonic,
                    style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    color = linearInstructionColor(instruction.type),
                )
            }
            Column {
                Text("Opcode", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    text = "0x${instruction.opcode.toString(16).uppercase()}  (${instruction.opcode})",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Column {
                Text("Offset", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    text = "#${instruction.offset}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Column {
                Text("Category", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(
                    text = instruction.type.name.replace('_', ' '),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            if (instruction.operands.isNotBlank()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Operands", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(
                        text = instruction.operands,
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (instruction.lineNumber != null) {
                Column {
                    Text("Source Line", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(
                        text = "${instruction.lineNumber}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
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

private fun edgeTypeColor(edge: CfgEdge): Color = edgeTypeColor(edge.type)

private fun edgeTypeColor(type: EdgeType): Color = when (type) {
    EdgeType.FALL_THROUGH -> Color(0xFF888888)
    EdgeType.UNCONDITIONAL_JUMP -> Color(0xFF4A90D9)
    EdgeType.BRANCH_TRUE -> Color(0xFF4AD94A)
    EdgeType.BRANCH_FALSE -> Color(0xFFD94A4A)
    EdgeType.EXCEPTION -> Color(0xFFE8A838)
    EdgeType.SWITCH_CASE -> Color(0xFF9B59B6)
    EdgeType.SWITCH_DEFAULT -> Color(0xFF9B59B6)
}

@Composable
private fun linearInstructionColor(category: InstructionCategory): Color = when (category) {
    InstructionCategory.CONTROL_FLOW -> Color(0xFFEF5350)
    InstructionCategory.METHOD_CALL -> Color(0xFF42A5F5)
    InstructionCategory.FIELD_ACCESS -> Color(0xFF66BB6A)
    InstructionCategory.LOAD_STORE -> Color(0xFF9E9E9E)
    InstructionCategory.STACK -> Color(0xFFAB47BC)
    InstructionCategory.CONSTANT -> Color(0xFFFFCA28)
    InstructionCategory.ARITHMETIC -> Color(0xFFFF7043)
    InstructionCategory.TYPE_CHECK -> Color(0xFF26C6DA)
    InstructionCategory.ARRAY -> Color(0xFF8D6E63)
    InstructionCategory.OTHER -> MaterialTheme.colorScheme.onSurface
}

@Composable
private fun cfgInstructionColor(type: InstructionCategory): Color = when (type) {
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
