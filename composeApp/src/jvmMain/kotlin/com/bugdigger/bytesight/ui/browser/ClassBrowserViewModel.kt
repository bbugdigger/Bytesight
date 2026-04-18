package com.bugdigger.bytesight.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bugdigger.bytesight.service.AgentClient
import com.bugdigger.bytesight.service.RenameStore
import com.bugdigger.core.decompiler.Decompiler
import com.bugdigger.core.decompiler.DecompilationResult
import com.bugdigger.protocol.ClassInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the Class Browser screen.
 */
data class ClassBrowserUiState(
    val classes: List<ClassInfo> = emptyList(),
    val filteredClasses: List<ClassInfo> = emptyList(),
    val selectedClass: ClassInfo? = null,
    val searchQuery: String = "",
    val includeSystemClasses: Boolean = false,
    val isLoading: Boolean = false,
    val isLoadingBytecode: Boolean = false,
    val bytecode: ByteArray? = null,
    val decompiled: String? = null,
    val decompilationWarnings: List<String> = emptyList(),
    /** Decompiled source with user renames applied (display layer). */
    val displayDecompiled: String? = null,
    val error: String? = null,
)

/**
 * ViewModel for the Class Browser screen.
 * Handles class listing, filtering, and bytecode retrieval.
 */
class ClassBrowserViewModel(
    private val agentClient: AgentClient,
    private val decompiler: Decompiler,
    private val renameStore: RenameStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ClassBrowserUiState())
    val uiState: StateFlow<ClassBrowserUiState> = _uiState.asStateFlow()

    private var connectionKey: String? = null

    init {
        // Re-apply renames whenever the rename map changes
        viewModelScope.launch {
            renameStore.renameMap.collect { _ ->
                _uiState.update { state ->
                    state.copy(
                        displayDecompiled = state.decompiled?.let { renameStore.applyToSource(it) },
                    )
                }
            }
        }
    }

    /**
     * Sets the connection key to use for agent communication.
     */
    fun setConnectionKey(key: String) {
        if (connectionKey != key) {
            connectionKey = key
            refreshClasses()
        }
    }

    /**
     * Refreshes the list of loaded classes from the agent.
     */
    fun refreshClasses() {
        val key = connectionKey ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            agentClient.listClasses(
                connectionKey = key,
                includeSystemClasses = _uiState.value.includeSystemClasses,
            ).onSuccess { classes ->
                _uiState.update {
                    it.copy(
                        classes = classes,
                        filteredClasses = filterClasses(classes, it.searchQuery),
                        isLoading = false,
                    )
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Failed to load classes: ${e.message}",
                    )
                }
            }
        }
    }

    /**
     * Updates the search query and filters the class list.
     */
    fun setSearchQuery(query: String) {
        _uiState.update {
            it.copy(
                searchQuery = query,
                filteredClasses = filterClasses(it.classes, query),
            )
        }
    }

    /**
     * Toggles inclusion of system classes.
     */
    fun setIncludeSystemClasses(include: Boolean) {
        _uiState.update { it.copy(includeSystemClasses = include) }
        refreshClasses()
    }

    /**
     * Selects a class and fetches its bytecode.
     */
    fun selectClass(classInfo: ClassInfo?) {
        _uiState.update {
            it.copy(
                selectedClass = classInfo,
                bytecode = null,
                decompiled = null,
                decompilationWarnings = emptyList(),
            )
        }

        if (classInfo != null) {
            fetchBytecode(classInfo.name)
        }
    }

    /**
     * Fetches bytecode for the specified class and decompiles it.
     */
    private fun fetchBytecode(className: String) {
        val key = connectionKey ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingBytecode = true) }

            agentClient.getClassBytecode(key, className)
                .onSuccess { response ->
                    val bytecode = response.bytecode.toByteArray()
                    _uiState.update {
                        it.copy(
                            bytecode = bytecode,
                            decompiled = "// Decompiling...",
                        )
                    }

                    // Decompile the bytecode
                    decompileBytecode(className, bytecode)
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isLoadingBytecode = false,
                            error = "Failed to fetch bytecode: ${e.message}",
                        )
                    }
                }
        }
    }

    /**
     * Decompiles bytecode using Vineflower.
     */
    private suspend fun decompileBytecode(className: String, bytecode: ByteArray) {
        when (val result = decompiler.decompile(className, bytecode)) {
            is DecompilationResult.Success -> {
                _uiState.update {
                    it.copy(
                        isLoadingBytecode = false,
                        decompiled = result.sourceCode,
                        displayDecompiled = renameStore.applyToSource(result.sourceCode),
                        decompilationWarnings = result.warnings,
                    )
                }
            }
            is DecompilationResult.Failure -> {
                _uiState.update {
                    it.copy(
                        isLoadingBytecode = false,
                        decompiled = buildString {
                            appendLine("// Decompilation failed: ${result.error}")
                            appendLine("// Class: $className")
                            appendLine("// Size: ${bytecode.size} bytes")
                            result.exception?.let { e ->
                                appendLine("//")
                                appendLine("// Exception: ${e.javaClass.simpleName}")
                                appendLine("// ${e.message}")
                            }
                        },
                    )
                }
            }
        }
    }

    /**
     * Clears any error message.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun filterClasses(classes: List<ClassInfo>, query: String): List<ClassInfo> {
        if (query.isBlank()) return classes

        val lowerQuery = query.lowercase()
        return classes.filter { classInfo ->
            classInfo.name.lowercase().contains(lowerQuery)
        }
    }
}
