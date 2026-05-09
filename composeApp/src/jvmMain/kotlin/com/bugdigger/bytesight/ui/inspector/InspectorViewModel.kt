package com.bugdigger.bytesight.ui.inspector

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bugdigger.bytesight.service.CommentStore
import com.bugdigger.bytesight.service.ConnectionRegistry
import com.bugdigger.bytesight.service.MethodComments
import com.bugdigger.bytesight.service.MethodKey
import com.bugdigger.bytesight.service.RenameStore
import com.bugdigger.bytesight.source.ClassSource
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
 * A symbol the user might be trying to rename when their click on the
 * decompiled source is ambiguous. Each variant carries enough information
 * to construct a precise [RenameStore] key.
 */
sealed interface RenameCandidate {
    data class ClassRef(val classFqn: String) : RenameCandidate
    data class Field(
        val classFqn: String,
        val name: String,
        val descriptor: String,
    ) : RenameCandidate

    data class Method(
        val classFqn: String,
        val name: String,
        val descriptor: String,
    ) : RenameCandidate
}

/**
 * Captured rename request awaiting disambiguation. Set on
 * [InspectorUiState.pendingRename] when the user's short-name click maps
 * to more than one symbol; cleared when they pick or cancel.
 */
data class PendingRename(
    val shortName: String,
    val newName: String,
    val candidates: List<RenameCandidate>,
)

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
    /**
     * Maps decompiled-source line numbers to the underlying bytecode line
     * numbers. Populated from Vineflower's `bsm` output. Null when the
     * decompiler didn't produce a mapping. Used by the decompiled-tab gutter
     * to set line breakpoints that resolve to a real bytecode location.
     */
    val decompiledLineMap: com.bugdigger.core.decompiler.DecompiledLineMap? = null,
    /** Short-name → new-name map for highlighting renamed identifiers in the decompiled source. */
    val renamedSymbols: Map<String, String> = emptyMap(),
    /**
     * FQN → user-assigned-name. Used by the class dropdown so the user
     * sees their renamed classes there instead of `o.j` etc.
     */
    val classRenames: Map<String, String> = emptyMap(),
    /**
     * Set when the user requested a rename of a simple name that has more
     * than one matching symbol in the current class (e.g. two fields named
     * `a` of different types, or a field named `a` plus a method named `a`).
     * The screen renders [com.bugdigger.bytesight.ui.components.RenameDisambiguationDialog]
     * when non-null.
     */
    val pendingRename: PendingRename? = null,
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
    private val connectionRegistry: ConnectionRegistry,
    private val decompiler: Decompiler,
    private val commentStore: CommentStore,
    private val renameStore: RenameStore,
) : ViewModel() {

    private val _innerState = MutableStateFlow(InspectorUiState())
    val uiState: StateFlow<InspectorUiState> = _innerState.asStateFlow()

    private val disassembler = BytecodeDisassembler()
    private val cfgBuilder = CfgBuilder()
    private val layoutEngine = SugiyamaLayout()
    private var activeSource: ClassSource? = null
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

        // Mirror rename store into uiState. Renames are now applied at the
        // bytecode layer by [RenameAwareDecompiler]; the source-layer text
        // substitution that used to live here (`applyToSource`) is gone
        // because it couldn't disambiguate same-named symbols. Instead,
        // when the rename map changes we re-decompile the currently
        // selected class so its source reflects the latest renames.
        viewModelScope.launch {
            renameStore.renameMap.collect { renameMap ->
                _innerState.update {
                    it.copy(
                        renamedSymbols = renameStore.shortNameMap(),
                        classRenames = renameMap,
                    )
                }
                // Re-run decompilation against the cached bytes if we're
                // currently viewing a class. The cached bytecode is the raw
                // bytecode from the agent / source; the decompiler applies
                // the new renames on top.
                rerunDecompilationOnCurrentClass()
            }
        }

        // React to active-source changes — replaces the old setConnectionKey path.
        viewModelScope.launch {
            connectionRegistry.classSource.collect { source ->
                if (activeSource !== source) {
                    activeSource = source
                    // The previous source's class list and any drilled-into selection
                    // are meaningless against a new source. Reset, but keep the user's
                    // view-mode preference (LINEAR vs CFG).
                    val prev = _innerState.value
                    _innerState.value = InspectorUiState(viewMode = prev.viewMode)
                    cachedBytecode = null
                    if (source != null) loadClasses()
                }
            }
        }
    }

    fun setDensity(density: Float) {
        if (screenDensity != density) {
            screenDensity = density
            recomputeLayout()
        }
    }

    private fun loadClasses() {
        val source = activeSource ?: return
        viewModelScope.launch {
            _innerState.update { it.copy(isLoadingClasses = true, error = null) }

            source.listClasses(includeSystemClasses = false)
                .onSuccess { classes ->
                    _innerState.update { it.copy(classes = classes, isLoadingClasses = false) }
                }
                .onFailure { e ->
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
        val classSource = activeSource ?: return

        _innerState.update {
            it.copy(
                selectedClassName = className,
                selectedMethod = null,
                selectedInstruction = null,
                cfg = null,
                graphLayout = null,
                decompiledSource = null,
                decompiledLineMap = null,
                selectedBlockId = null,
                selectedInstructionOffset = null,
                isBlockHeaderSelected = false,
                methodComments = MethodComments(),
                isLoading = true,
                error = null,
            )
        }

        viewModelScope.launch {
            classSource.getBytecode(className)
                .onSuccess { bytecode ->
                    cachedBytecode = bytecode

                    val disassembled = runCatching { disassembler.disassemble(bytecode) }.getOrNull()

                    val decompResult = decompiler.decompile(className, bytecode)
                    val sourceText = when (decompResult) {
                        is DecompilationResult.Success -> decompResult.sourceCode
                        is DecompilationResult.Failure -> "// Decompilation failed: ${decompResult.error}"
                    }
                    val lineMap = (decompResult as? DecompilationResult.Success)?.lineMap

                    val firstMethod = disassembled?.methods?.firstOrNull()
                    val comments = if (firstMethod != null) {
                        commentStore.commentsFor(MethodKey(className, firstMethod.name, firstMethod.descriptor))
                    } else MethodComments()

                    // Renames are now baked into `sourceText` by
                    // RenameAwareDecompiler. `displaySource` and
                    // `decompiledSource` carry identical content; we keep
                    // `displaySource` only for the existing API surface
                    // expected by InspectorScreen.
                    _innerState.update {
                        it.copy(
                            disassembledClass = disassembled,
                            decompiledSource = sourceText,
                            displaySource = sourceText,
                            decompiledLineMap = lineMap,
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
     * Begin a rename for the symbol that appears as [shortName] in the
     * decompiled source. We can't tell from the short name alone which
     * symbol the user means (in obfuscated code, `a` could simultaneously
     * be the class, a field, multiple overloaded methods, or all of the
     * above), so this method:
     *
     *   - Finds every candidate (class / fields / methods) in the current
     *     class with matching short name.
     *   - If exactly one candidate exists, applies the rename immediately
     *     using its precise key (descriptor included for fields & methods).
     *   - If multiple, parks the request in [InspectorUiState.pendingRename]
     *     so the screen can show a disambiguation picker; the user picks,
     *     then we apply via [resolveRenameAs].
     *   - If none, falls back to the legacy descriptor-less key shape so
     *     types of symbols we don't track here (locals, etc.) still get
     *     stored. They won't take effect at the bytecode layer but won't
     *     break anything either.
     */
    fun renameSymbol(shortName: String, newName: String) {
        val candidates = findRenameCandidates(shortName)
        when (candidates.size) {
            0 -> {
                // Legacy fallback for symbols we can't precisely identify.
                val className = _innerState.value.selectedClassName ?: return
                renameStore.rename("$className#$shortName", newName)
            }
            1 -> applyResolvedRename(candidates.single(), newName)
            else -> _innerState.update {
                it.copy(pendingRename = PendingRename(shortName, newName, candidates))
            }
        }
    }

    /** Apply the rename the user picked from the disambiguation dialog. */
    fun resolveRenameAs(candidate: RenameCandidate) {
        val pending = _innerState.value.pendingRename ?: return
        applyResolvedRename(candidate, pending.newName)
        _innerState.update { it.copy(pendingRename = null) }
    }

    /** User dismissed the disambiguation dialog without picking. */
    fun cancelPendingRename() {
        _innerState.update { it.copy(pendingRename = null) }
    }

    private fun applyResolvedRename(candidate: RenameCandidate, newName: String) {
        val key = when (candidate) {
            is RenameCandidate.ClassRef -> candidate.classFqn
            is RenameCandidate.Field ->
                RenameStore.fieldKey(candidate.classFqn, candidate.name, candidate.descriptor)
            is RenameCandidate.Method ->
                RenameStore.methodKey(candidate.classFqn, candidate.name, candidate.descriptor)
        }
        renameStore.rename(key, newName)
    }

    private fun findRenameCandidates(shortName: String): List<RenameCandidate> {
        val state = _innerState.value
        val className = state.selectedClassName ?: return emptyList()
        val candidates = mutableListOf<RenameCandidate>()
        if (shortName == className.substringAfterLast('.')) {
            candidates += RenameCandidate.ClassRef(className)
        }
        state.disassembledClass?.let { dc ->
            dc.fields.filter { it.name == shortName }.forEach { f ->
                candidates += RenameCandidate.Field(className, f.name, f.descriptor)
            }
            dc.methods.filter { it.name == shortName }.forEach { m ->
                candidates += RenameCandidate.Method(className, m.name, m.descriptor)
            }
        }
        return candidates
    }

    /**
     * Re-run decompilation against the cached bytecode. Called when renames
     * change so the view picks up the latest substitutions.
     */
    private fun rerunDecompilationOnCurrentClass() {
        val className = _innerState.value.selectedClassName ?: return
        val bytecode = cachedBytecode ?: return
        viewModelScope.launch {
            val decompResult = decompiler.decompile(className, bytecode)
            val sourceText = when (decompResult) {
                is DecompilationResult.Success -> decompResult.sourceCode
                is DecompilationResult.Failure -> "// Decompilation failed: ${decompResult.error}"
            }
            val lineMap = (decompResult as? DecompilationResult.Success)?.lineMap
            _innerState.update {
                it.copy(
                    decompiledSource = sourceText,
                    displaySource = sourceText,
                    decompiledLineMap = lineMap,
                )
            }
        }
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
