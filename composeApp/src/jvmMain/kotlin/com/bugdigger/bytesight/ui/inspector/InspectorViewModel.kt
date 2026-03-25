package com.bugdigger.bytesight.ui.inspector

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bugdigger.bytesight.service.AgentClient
import com.bugdigger.core.analysis.BytecodeDisassembler
import com.bugdigger.core.analysis.DisassembledClass
import com.bugdigger.core.analysis.DisassembledMethod
import com.bugdigger.core.analysis.Instruction
import com.bugdigger.core.decompiler.Decompiler
import com.bugdigger.core.decompiler.DecompilationResult
import com.bugdigger.protocol.ClassInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the Bytecode Inspector screen.
 */
data class InspectorUiState(
    val classes: List<ClassInfo> = emptyList(),
    val selectedClassName: String? = null,
    val selectedMethodName: String? = null,
    val disassembledClass: DisassembledClass? = null,
    val selectedMethod: DisassembledMethod? = null,
    val selectedInstruction: Instruction? = null,
    val decompiledSource: String? = null,
    val isLoading: Boolean = false,
    val isLoadingClasses: Boolean = false,
    val error: String? = null,
)

/**
 * ViewModel for the Bytecode Inspector screen.
 * Shows raw bytecode instructions alongside decompiled Java source.
 */
class InspectorViewModel(
    private val agentClient: AgentClient,
    private val decompiler: Decompiler,
) : ViewModel() {

    private val _uiState = MutableStateFlow(InspectorUiState())
    val uiState: StateFlow<InspectorUiState> = _uiState.asStateFlow()

    private val disassembler = BytecodeDisassembler()
    private var connectionKey: String? = null

    /**
     * Sets the connection key and loads the class list.
     */
    fun setConnectionKey(key: String) {
        if (connectionKey != key) {
            connectionKey = key
            loadClasses()
        }
    }

    /**
     * Loads the list of classes from the agent.
     */
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

    /**
     * Selects a class and disassembles it.
     */
    fun selectClass(className: String) {
        val key = connectionKey ?: return

        _uiState.update {
            it.copy(
                selectedClassName = className,
                selectedMethodName = null,
                selectedMethod = null,
                selectedInstruction = null,
                disassembledClass = null,
                decompiledSource = null,
                isLoading = true,
                error = null,
            )
        }

        viewModelScope.launch {
            agentClient.getClassBytecode(key, className)
                .onSuccess { response ->
                    val bytecode = response.bytecode.toByteArray()

                    // Disassemble
                    val disassembled = runCatching { disassembler.disassemble(bytecode) }
                        .getOrNull()

                    // Decompile
                    val source = when (val result = decompiler.decompile(className, bytecode)) {
                        is DecompilationResult.Success -> result.sourceCode
                        is DecompilationResult.Failure -> "// Decompilation failed: ${result.error}"
                    }

                    val firstMethod = disassembled?.methods?.firstOrNull()

                    _uiState.update {
                        it.copy(
                            disassembledClass = disassembled,
                            decompiledSource = source,
                            selectedMethodName = firstMethod?.name,
                            selectedMethod = firstMethod,
                            isLoading = false,
                        )
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

    /**
     * Selects a method from the disassembled class.
     */
    fun selectMethod(methodName: String, descriptor: String) {
        _uiState.update {
            val method = it.disassembledClass?.methods?.find { m ->
                m.name == methodName && m.descriptor == descriptor
            }
            it.copy(
                selectedMethodName = methodName,
                selectedMethod = method,
                selectedInstruction = null,
            )
        }
    }

    /**
     * Selects a specific instruction.
     */
    fun selectInstruction(instruction: Instruction?) {
        _uiState.update { it.copy(selectedInstruction = instruction) }
    }

    /**
     * Clears any error message.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
