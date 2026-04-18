package com.bugdigger.bytesight.ui.inspector

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bugdigger.bytesight.service.AgentClient
import com.bugdigger.bytesight.service.CommentStore
import com.bugdigger.bytesight.service.MethodComments
import com.bugdigger.bytesight.service.MethodKey
import com.bugdigger.bytesight.service.RenameStore
import com.bugdigger.bytesight.ui.components.GraphLayout
import com.bugdigger.bytesight.ui.components.SugiyamaLayout
import com.bugdigger.core.analysis.BasicBlock
import com.bugdigger.core.analysis.BytecodeDisassembler
import com.bugdigger.core.analysis.CfgBuilder
import com.bugdigger.core.analysis.CfgEdge
import com.bugdigger.core.analysis.ControlFlowGraph
import com.bugdigger.core.analysis.DisassembledClass
import com.bugdigger.core.analysis.DisassembledMethod
import com.bugdigger.core.analysis.Instruction
import com.bugdigger.core.decompiler.DecompilationResult
import com.bugdigger.core.decompiler.Decompiler
import com.bugdigger.protocol.ClassInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ViewMode { LINEAR, CFG }

/**
 * UI state for the Bytecode Inspector screen, which can render bytecode either as
 * a linear list (default) or as a control flow graph (TAB to toggle).
 */
data class InspectorUiState(
    val classes: List<ClassInfo> = emptyList(),
    val isLoadingClasses: Boolean = false,
    val selectedClassName: String? = null,
    val disassembledClass: DisassembledClass? = null,
    val selectedMethod: DisassembledMethod? = null,
    val selectedInstruction: Instruction? = null,
    val decompiledSource: String? = null,
    val displaySource: String? = null,
    val renamedSymbols: Map<String, String> = emptyMap(),
    val viewMode: ViewMode = ViewMode.LINEAR,
    val cfg: ControlFlowGraph? = null,
    val graphLayout: GraphLayout<BasicBlock, CfgEdge>? = null,
    val selectedBlockId: String? = null,
    val selectedInstructionOffset: Int? = null,
    val isBlockHeaderSelected: Boolean = false,
    val methodComments: MethodComments = MethodComments(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

class InspectorViewModel(
    private val agentClient: AgentClient,
    private val decompiler: Decompiler,
    private val commentStore: CommentStore,
    private val renameStore: RenameStore,
) : ViewModel() {

    private val _innerState = MutableStateFlow(InspectorUiState())
    val uiState: StateFlow<InspectorUiState> = _innerState.asStateFlow()

    private val disassembler = BytecodeDisassembler()
    private val cfgBuilder = CfgBuilder()
    private val layoutEngine = SugiyamaLayout()
    private var connectionKey: String? = null
    private var cachedBytecode: ByteArray? = null
    private var screenDensity: Float = 1f

    init {
        // Mirror the comment store into uiState.methodComments for the current method,
        // and re-layout the CFG when block-level comments change (block size depends on them).
        viewModelScope.launch {
            commentStore.state.collect { store ->
                val current = _innerState.value
                val key = currentMethodKey(current)
                val comments = if (key != null) store[key] ?: MethodComments() else MethodComments()
                if (current.methodComments != comments) {
                    _innerState.update { it.copy(methodComments = comments) }
                }
                if (current.cfg != null) recomputeLayout()
            }
        }

        // Mirror rename store into uiState and re-apply renames to decompiled source.
        viewModelScope.launch {
            renameStore.renameMap.collect { renames ->
                val current = _innerState.value
                val shortNames = renameStore.shortNameMap()
                val displaySource = current.decompiledSource?.let { renameStore.applyToSource(it) }
                _innerState.update {
                    it.copy(
                        displaySource = displaySource,
                        renamedSymbols = shortNames,
                    )
                }
            }
        }
    }

    fun setConnectionKey(key: String) {
        if (connectionKey != key) {
            connectionKey = key
            loadClasses()
        }
    }

    fun setDensity(density: Float) {
        if (screenDensity != density) {
            screenDensity = density
            recomputeLayout()
        }
    }

    private fun loadClasses() {
        val key = connectionKey ?: return
        viewModelScope.launch {
            _innerState.update { it.copy(isLoadingClasses = true, error = null) }

            agentClient.listClasses(
                connectionKey = key,
                includeSystemClasses = false,
            ).onSuccess { classes ->
                _innerState.update { it.copy(classes = classes, isLoadingClasses = false) }
            }.onFailure { e ->
                _innerState.update {
                    it.copy(
                        isLoadingClasses = false,
                        error = "Failed to load classes: ${e.message}",
                    )
                }
            }
        }
    }

    fun selectClass(className: String) {
        val key = connectionKey ?: return

        _innerState.update {
            it.copy(
                selectedClassName = className,
                selectedMethod = null,
                selectedInstruction = null,
                cfg = null,
                graphLayout = null,
                decompiledSource = null,
                selectedBlockId = null,
                selectedInstructionOffset = null,
                isBlockHeaderSelected = false,
                methodComments = MethodComments(),
                isLoading = true,
                error = null,
            )
        }

        viewModelScope.launch {
            agentClient.getClassBytecode(key, className)
                .onSuccess { response ->
                    val bytecode = response.bytecode.toByteArray()
                    cachedBytecode = bytecode

                    val disassembled = runCatching { disassembler.disassemble(bytecode) }.getOrNull()

                    val source = when (val result = decompiler.decompile(className, bytecode)) {
                        is DecompilationResult.Success -> result.sourceCode
                        is DecompilationResult.Failure -> "// Decompilation failed: ${result.error}"
                    }

                    val firstMethod = disassembled?.methods?.firstOrNull()
                    val comments = if (firstMethod != null) {
                        commentStore.commentsFor(MethodKey(className, firstMethod.name, firstMethod.descriptor))
                    } else MethodComments()

                    _innerState.update {
                        it.copy(
                            disassembledClass = disassembled,
                            decompiledSource = source,
                            displaySource = renameStore.applyToSource(source),
                            renamedSymbols = renameStore.shortNameMap(),
                            selectedMethod = firstMethod,
                            methodComments = comments,
                            isLoading = false,
                        )
                    }

                    if (firstMethod != null) {
                        buildCfg(bytecode, firstMethod.name, firstMethod.descriptor)
                    }
                }
                .onFailure { e ->
                    _innerState.update {
                        it.copy(
                            isLoading = false,
                            error = "Failed to fetch bytecode: ${e.message}",
                        )
                    }
                }
        }
    }

    fun selectMethod(methodName: String, descriptor: String) {
        val state = _innerState.value
        val method = state.disassembledClass?.methods?.find {
            it.name == methodName && it.descriptor == descriptor
        } ?: return
        val className = state.selectedClassName
        val comments = if (className != null) {
            commentStore.commentsFor(MethodKey(className, methodName, descriptor))
        } else MethodComments()

        _innerState.update {
            it.copy(
                selectedMethod = method,
                selectedInstruction = null,
                cfg = null,
                graphLayout = null,
                selectedBlockId = null,
                selectedInstructionOffset = null,
                isBlockHeaderSelected = false,
                methodComments = comments,
            )
        }

        val bytecode = cachedBytecode ?: return
        buildCfg(bytecode, methodName, descriptor)
    }

    private fun buildCfg(bytecode: ByteArray, methodName: String, descriptor: String) {
        viewModelScope.launch(Dispatchers.Default) {
            _innerState.update { it.copy(isLoading = true, error = null) }

            val className = _innerState.value.selectedClassName
            val comments = if (className != null) {
                commentStore.commentsFor(MethodKey(className, methodName, descriptor))
            } else MethodComments()

            runCatching {
                val cfg = cfgBuilder.buildCfg(bytecode, methodName, descriptor)
                val layout = layoutEngine.layout(
                    nodes = cfg.blocks.map { it.id to it },
                    edges = cfg.edges.map { Triple(it.sourceId, it.targetId, it) },
                    entryId = cfg.entryBlockId,
                    nodeSize = { block ->
                        computeBlockSize(
                            block = block,
                            hasBlockComment = comments.blockLevel.containsKey(block.id),
                            density = screenDensity,
                        )
                    },
                )
                Pair(cfg, layout)
            }.onSuccess { (cfg, layout) ->
                _innerState.update {
                    it.copy(cfg = cfg, graphLayout = layout, isLoading = false)
                }
            }.onFailure { e ->
                _innerState.update {
                    it.copy(isLoading = false, error = "Failed to build CFG: ${e.message}")
                }
            }
        }
    }

    private fun recomputeLayout() {
        val state = _innerState.value
        val cfg = state.cfg ?: return
        val key = currentMethodKey(state) ?: return
        val comments = commentStore.commentsFor(key)

        viewModelScope.launch(Dispatchers.Default) {
            runCatching {
                layoutEngine.layout(
                    nodes = cfg.blocks.map { it.id to it },
                    edges = cfg.edges.map { Triple(it.sourceId, it.targetId, it) },
                    entryId = cfg.entryBlockId,
                    nodeSize = { block ->
                        computeBlockSize(
                            block = block,
                            hasBlockComment = comments.blockLevel.containsKey(block.id),
                            density = screenDensity,
                        )
                    },
                )
            }.onSuccess { layout ->
                _innerState.update { it.copy(graphLayout = layout) }
            }
        }
    }

    fun selectInstruction(instruction: Instruction?) {
        _innerState.update { it.copy(selectedInstruction = instruction) }
    }

    fun selectBlock(blockId: String?) {
        _innerState.update {
            it.copy(
                selectedBlockId = blockId,
                selectedInstructionOffset = null,
                isBlockHeaderSelected = false,
            )
        }
    }

    fun selectBlockHeader(blockId: String) {
        _innerState.update {
            it.copy(
                selectedBlockId = blockId,
                selectedInstructionOffset = null,
                isBlockHeaderSelected = true,
            )
        }
    }

    fun selectCfgInstruction(blockId: String, offset: Int) {
        _innerState.update {
            it.copy(
                selectedBlockId = blockId,
                selectedInstructionOffset = offset,
                isBlockHeaderSelected = false,
            )
        }
    }

    fun toggleViewMode() {
        _innerState.update {
            it.copy(viewMode = if (it.viewMode == ViewMode.LINEAR) ViewMode.CFG else ViewMode.LINEAR)
        }
    }

    fun addInstructionComment(offset: Int, text: String) {
        val key = currentMethodKey(_innerState.value) ?: return
        commentStore.setInstructionComment(key, offset, text)
    }

    fun addBlockComment(blockId: String, text: String) {
        val key = currentMethodKey(_innerState.value) ?: return
        commentStore.setBlockComment(key, blockId, text)
    }

    /**
     * Rename a symbol identified by its short name as it appears in the decompiled source.
     * Builds a fully-qualified name using the current class context and registers
     * the rename in the [RenameStore].
     */
    fun renameSymbol(shortName: String, newName: String) {
        val className = _innerState.value.selectedClassName ?: return
        // Heuristic: if the short name matches the class simple name, rename the class.
        // Otherwise assume it's a method/field of the current class.
        val classSimpleName = className.substringAfterLast('.')
        val fqn = if (shortName == classSimpleName) {
            className
        } else {
            "$className#$shortName"
        }
        renameStore.rename(fqn, newName)
    }

    fun clearError() {
        _innerState.update { it.copy(error = null) }
    }

    private fun currentMethodKey(state: InspectorUiState): MethodKey? {
        val className = state.selectedClassName ?: return null
        val method = state.selectedMethod ?: return null
        return MethodKey(className, method.name, method.descriptor)
    }

    companion object {
        // Block size constants in dp. The layout engine needs pixel sizes, so we
        // multiply by screen density. Must stay in sync with BasicBlockNodeView's
        // padding/spacing for correct edge routing.
        private const val BLOCK_WIDTH_DP = 300f
        private const val HEADER_HEIGHT_DP = 24f
        private const val BLOCK_COMMENT_HEIGHT_DP = 18f
        private const val INSTRUCTION_HEIGHT_DP = 20f
        private const val PADDING_DP = 4f

        fun computeBlockSize(
            block: BasicBlock,
            hasBlockComment: Boolean = false,
            density: Float = 1f,
        ): Pair<Float, Float> {
            var heightDp = HEADER_HEIGHT_DP +
                (block.instructions.size * INSTRUCTION_HEIGHT_DP) +
                PADDING_DP
            if (hasBlockComment) heightDp += BLOCK_COMMENT_HEIGHT_DP
            return Pair(BLOCK_WIDTH_DP * density, heightDp * density)
        }
    }
}
