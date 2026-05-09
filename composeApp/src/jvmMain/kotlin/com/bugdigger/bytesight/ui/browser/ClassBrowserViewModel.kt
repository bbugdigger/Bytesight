package com.bugdigger.bytesight.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bugdigger.bytesight.service.ConnectionRegistry
import com.bugdigger.bytesight.service.RenameStore
import com.bugdigger.bytesight.source.ClassSource
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
    /**
     * Active rename map (FQN → user-assigned name). Forwarded from
     * [RenameStore] so the screen can render renamed class names without
     * touching the store directly.
     */
    val renames: Map<String, String> = emptyMap(),
    val error: String? = null,
)

/**
 * ViewModel for the Class Browser screen.
 * Reads class metadata + bytecode through the active [ClassSource]
 * (installed by the Attach screen via [ConnectionRegistry]).
 */
class ClassBrowserViewModel(
    private val connectionRegistry: ConnectionRegistry,
    private val decompiler: Decompiler,
    private val renameStore: RenameStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ClassBrowserUiState())
    val uiState: StateFlow<ClassBrowserUiState> = _uiState.asStateFlow()

    private var activeSource: ClassSource? = null

    init {
        // Re-apply renames whenever the rename map changes. Both the
        // decompiled-source overlay AND the renamed list display depend
        // on this — re-filter so the search box matches against the new
        // display names too.
        viewModelScope.launch {
            renameStore.renameMap.collect { renameMap ->
                _uiState.update { state ->
                    state.copy(
                        renames = renameMap,
                        filteredClasses = filterClasses(state.classes, state.searchQuery, renameMap),
                        displayDecompiled = state.decompiled?.let { renameStore.applyToSource(it) },
                    )
                }
            }
        }
        // React to source changes — replaces the old setConnectionKey call site.
        viewModelScope.launch {
            connectionRegistry.classSource.collect { source ->
                onSourceChanged(source)
            }
        }
    }

    private fun onSourceChanged(source: ClassSource?) {
        if (activeSource === source) return
        activeSource = source
        // Drop the previous source's class list and any drilled-into class.
        // Keep user preferences (searchQuery, includeSystemClasses) so the
        // browser opens with the user's last-used filter against the new
        // source — usually what they want when they reattach to retry.
        val prev = _uiState.value
        _uiState.value = ClassBrowserUiState(
            searchQuery = prev.searchQuery,
            includeSystemClasses = prev.includeSystemClasses,
        )
        if (source != null) refreshClasses()
    }

    /**
     * Refreshes the list of loaded classes from the active source.
     */
    fun refreshClasses() {
        val source = activeSource ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            source.listClasses(_uiState.value.includeSystemClasses)
                .onSuccess { classes ->
                    _uiState.update {
                        it.copy(
                            classes = classes,
                            filteredClasses = filterClasses(classes, it.searchQuery, it.renames),
                            isLoading = false,
                        )
                    }
                }
                .onFailure { e ->
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
                filteredClasses = filterClasses(it.classes, query, it.renames),
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
        val source = activeSource ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingBytecode = true) }

            source.getBytecode(className)
                .onSuccess { bytecode ->
                    _uiState.update {
                        it.copy(
                            bytecode = bytecode,
                            decompiled = "// Decompiling...",
                        )
                    }
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

    /**
     * Filter the class list against the search query. Matches against the
     * original FQN AND the user-assigned display name (if any), so renaming
     * `o.j` to `Product` lets the user search for "Product" and find the
     * class.
     */
    private fun filterClasses(
        classes: List<ClassInfo>,
        query: String,
        renames: Map<String, String>,
    ): List<ClassInfo> {
        if (query.isBlank()) return classes

        val lowerQuery = query.lowercase()
        return classes.filter { classInfo ->
            if (classInfo.name.lowercase().contains(lowerQuery)) return@filter true
            val display = renames[classInfo.name] ?: return@filter false
            display.lowercase().contains(lowerQuery)
        }
    }
}
