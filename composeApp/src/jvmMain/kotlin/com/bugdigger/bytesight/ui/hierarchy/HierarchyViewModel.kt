package com.bugdigger.bytesight.ui.hierarchy

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bugdigger.bytesight.service.AgentClient
import com.bugdigger.protocol.ClassInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the Class Hierarchy screen.
 */
data class HierarchyUiState(
    val allRoots: List<HierarchyNode> = emptyList(),
    val filteredRoots: List<HierarchyNode> = emptyList(),
    val selectedClass: String? = null,
    val selectedAncestors: List<String> = emptyList(),
    val selectedClassInfo: ClassInfo? = null,
    val searchQuery: String = "",
    val showInterfaces: Boolean = true,
    val showClasses: Boolean = true,
    val expandedNodes: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

/**
 * ViewModel for the Class Hierarchy Explorer screen.
 * Shows inheritance relationships (extends/implements) across all loaded classes.
 */
class HierarchyViewModel(
    private val agentClient: AgentClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HierarchyUiState())
    val uiState: StateFlow<HierarchyUiState> = _uiState.asStateFlow()

    private var connectionKey: String? = null
    private var allClasses: List<ClassInfo> = emptyList()

    /**
     * Sets the connection key for agent communication.
     */
    fun setConnectionKey(key: String) {
        if (connectionKey != key) {
            connectionKey = key
            loadHierarchy()
        }
    }

    /**
     * Loads the class hierarchy from the agent.
     */
    fun loadHierarchy() {
        val key = connectionKey ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            agentClient.listClasses(
                connectionKey = key,
                includeSystemClasses = false,
            ).onSuccess { classes ->
                allClasses = classes
                val roots = HierarchyBuilder.buildHierarchy(classes)
                val filtered = applyVisibilityFilter(roots, _uiState.value)
                _uiState.update {
                    it.copy(
                        allRoots = roots,
                        filteredRoots = HierarchyBuilder.filterTree(filtered, it.searchQuery),
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
     * Selects a class and computes its ancestor chain.
     */
    fun selectClass(className: String?) {
        val classInfo = allClasses.find { it.name == className }
        val ancestors = if (className != null) {
            HierarchyBuilder.findAncestors(className, allClasses)
        } else {
            emptyList()
        }

        _uiState.update {
            it.copy(
                selectedClass = className,
                selectedClassInfo = classInfo,
                selectedAncestors = ancestors,
            )
        }
    }

    /**
     * Updates the search query and filters the tree.
     */
    fun setSearchQuery(query: String) {
        _uiState.update {
            val filtered = applyVisibilityFilter(it.allRoots, it.copy(searchQuery = query))
            it.copy(
                searchQuery = query,
                filteredRoots = HierarchyBuilder.filterTree(filtered, query),
            )
        }
    }

    /**
     * Toggles showing interface types.
     */
    fun setShowInterfaces(show: Boolean) {
        _uiState.update {
            val newState = it.copy(showInterfaces = show)
            val filtered = applyVisibilityFilter(it.allRoots, newState)
            newState.copy(filteredRoots = HierarchyBuilder.filterTree(filtered, it.searchQuery))
        }
    }

    /**
     * Toggles showing class types.
     */
    fun setShowClasses(show: Boolean) {
        _uiState.update {
            val newState = it.copy(showClasses = show)
            val filtered = applyVisibilityFilter(it.allRoots, newState)
            newState.copy(filteredRoots = HierarchyBuilder.filterTree(filtered, it.searchQuery))
        }
    }

    /**
     * Toggles expansion of a tree node.
     */
    fun toggleExpanded(className: String) {
        _uiState.update {
            val newExpanded = if (className in it.expandedNodes) {
                it.expandedNodes - className
            } else {
                it.expandedNodes + className
            }
            it.copy(expandedNodes = newExpanded)
        }
    }

    /**
     * Clears any error message.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun applyVisibilityFilter(roots: List<HierarchyNode>, state: HierarchyUiState): List<HierarchyNode> {
        return roots.filter { node ->
            (state.showInterfaces || !node.isInterface) &&
                    (state.showClasses || node.isInterface)
        }
    }
}
