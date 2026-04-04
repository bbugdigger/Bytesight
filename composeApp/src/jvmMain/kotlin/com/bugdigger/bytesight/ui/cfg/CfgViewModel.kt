package com.bugdigger.bytesight.ui.cfg

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bugdigger.bytesight.service.AgentClient
import com.bugdigger.bytesight.ui.components.GraphLayout
import com.bugdigger.bytesight.ui.components.SugiyamaLayout
import com.bugdigger.core.analysis.*
import com.bugdigger.core.decompiler.Decompiler
import com.bugdigger.core.decompiler.DecompilationResult
import com.bugdigger.protocol.ClassInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CfgUiState(
    val classes: List<ClassInfo> = emptyList(),
    val isLoadingClasses: Boolean = false,
    val selectedClassName: String? = null,
    val disassembledClass: DisassembledClass? = null,
    val selectedMethod: DisassembledMethod? = null,
    val cfg: ControlFlowGraph? = null,
    val graphLayout: GraphLayout<BasicBlock, CfgEdge>? = null,
    val decompiledSource: String? = null,
    val selectedBlockId: String? = null,
    val selectedInstructionOffset: Int? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

class CfgViewModel(
    private val agentClient: AgentClient,
    private val decompiler: Decompiler,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CfgUiState())
    val uiState: StateFlow<CfgUiState> = _uiState.asStateFlow()

    private val disassembler = BytecodeDisassembler()
    private val cfgBuilder = CfgBuilder()
    private val layoutEngine = SugiyamaLayout()
    private var connectionKey: String? = null
    private var cachedBytecode: ByteArray? = null

    fun setConnectionKey(key: String) {
        if (connectionKey != key) {
            connectionKey = key
            loadClasses()
        }
    }

    private fun loadClasses() {
        val key = connectionKey ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingClasses = true, error = null) }

            agentClient.listClasses(
                connectionKey = key,
                includeSystemClasses = false,
            ).onSuccess { classes ->
                _uiState.update { it.copy(classes = classes, isLoadingClasses = false) }
            }.onFailure { e ->
                _uiState.update {
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

        _uiState.update {
            it.copy(
                selectedClassName = className,
                selectedMethod = null,
                cfg = null,
                graphLayout = null,
                decompiledSource = null,
                selectedBlockId = null,
                selectedInstructionOffset = null,
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

                    _uiState.update {
                        it.copy(
                            disassembledClass = disassembled,
                            decompiledSource = source,
                            selectedMethod = firstMethod,
                            isLoading = false,
                        )
                    }

                    // Auto-build CFG for first method
                    if (firstMethod != null) {
                        buildCfg(bytecode, firstMethod.name, firstMethod.descriptor)
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = "Failed to fetch bytecode: ${e.message}",
                        )
                    }
                }
        }
    }

    fun selectMethod(methodName: String, descriptor: String) {
        val method = _uiState.value.disassembledClass?.methods?.find {
            it.name == methodName && it.descriptor == descriptor
        } ?: return

        _uiState.update {
            it.copy(
                selectedMethod = method,
                cfg = null,
                graphLayout = null,
                selectedBlockId = null,
                selectedInstructionOffset = null,
            )
        }

        val bytecode = cachedBytecode ?: return
        buildCfg(bytecode, methodName, descriptor)
    }

    private fun buildCfg(bytecode: ByteArray, methodName: String, descriptor: String) {
        viewModelScope.launch(Dispatchers.Default) {
            _uiState.update { it.copy(isLoading = true, error = null) }

            runCatching {
                val cfg = cfgBuilder.buildCfg(bytecode, methodName, descriptor)
                val layout = layoutEngine.layout(
                    nodes = cfg.blocks.map { it.id to it },
                    edges = cfg.edges.map { Triple(it.sourceId, it.targetId, it) },
                    entryId = cfg.entryBlockId,
                    nodeSize = ::computeBlockSize,
                )
                Pair(cfg, layout)
            }.onSuccess { (cfg, layout) ->
                _uiState.update {
                    it.copy(cfg = cfg, graphLayout = layout, isLoading = false)
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(isLoading = false, error = "Failed to build CFG: ${e.message}")
                }
            }
        }
    }

    fun selectBlock(blockId: String?) {
        _uiState.update { it.copy(selectedBlockId = blockId, selectedInstructionOffset = null) }
    }

    fun selectInstruction(blockId: String, offset: Int) {
        _uiState.update { it.copy(selectedBlockId = blockId, selectedInstructionOffset = offset) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    companion object {
        private const val BLOCK_WIDTH = 300f
        private const val HEADER_HEIGHT = 28f
        private const val INSTRUCTION_HEIGHT = 20f
        private const val PADDING = 12f

        fun computeBlockSize(block: BasicBlock): Pair<Float, Float> {
            val height = HEADER_HEIGHT + (block.instructions.size * INSTRUCTION_HEIGHT) + PADDING
            return Pair(BLOCK_WIDTH, height)
        }
    }
}
