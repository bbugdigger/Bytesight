package com.bugdigger.bytesight.ui.strings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bugdigger.bytesight.service.AgentClient
import com.bugdigger.core.analysis.ConstantExtractor
import com.bugdigger.core.analysis.ConstantType
import com.bugdigger.core.analysis.ExtractedConstant
import com.bugdigger.core.analysis.StringPattern
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the Strings/Constants screen.
 */
data class StringsUiState(
    val constants: List<ExtractedConstant> = emptyList(),
    val filteredConstants: List<ExtractedConstant> = emptyList(),
    val searchQuery: String = "",
    val typeFilter: Set<ConstantType> = ConstantType.entries.toSet(),
    val patternFilter: Set<StringPattern>? = null,
    val isExtracting: Boolean = false,
    val progress: Float = 0f,
    val totalClasses: Int = 0,
    val processedClasses: Int = 0,
    val error: String? = null,
)

/**
 * ViewModel for the String/Constant Extraction screen.
 * Scans loaded classes for hardcoded strings, numeric constants, and other literals.
 */
class StringsViewModel(
    private val agentClient: AgentClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(StringsUiState())
    val uiState: StateFlow<StringsUiState> = _uiState.asStateFlow()

    private val extractor = ConstantExtractor()
    private var connectionKey: String? = null

    /**
     * Sets the connection key for agent communication.
     */
    fun setConnectionKey(key: String) {
        connectionKey = key
    }

    /**
     * Extracts constants from all loaded classes.
     */
    fun extractAll() {
        val key = connectionKey ?: return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isExtracting = true,
                    progress = 0f,
                    error = null,
                    constants = emptyList(),
                    filteredConstants = emptyList(),
                    processedClasses = 0,
                )
            }

            agentClient.listClasses(
                connectionKey = key,
                includeSystemClasses = false,
            ).onSuccess { classes ->
                _uiState.update { it.copy(totalClasses = classes.size) }

                val allConstants = mutableListOf<ExtractedConstant>()
                var processed = 0

                for (classInfo in classes) {
                    agentClient.getClassBytecode(key, classInfo.name)
                        .onSuccess { response ->
                            val bytecode = response.bytecode.toByteArray()
                            if (bytecode.isNotEmpty()) {
                                runCatching {
                                    allConstants.addAll(extractor.extract(bytecode))
                                }
                            }
                        }

                    processed++
                    if (processed % 10 == 0 || processed == classes.size) {
                        _uiState.update {
                            it.copy(
                                processedClasses = processed,
                                progress = processed.toFloat() / classes.size,
                                constants = allConstants.toList(),
                                filteredConstants = applyFilters(
                                    allConstants,
                                    it.searchQuery,
                                    it.typeFilter,
                                    it.patternFilter,
                                ),
                            )
                        }
                    }
                }

                _uiState.update {
                    it.copy(
                        isExtracting = false,
                        progress = 1f,
                        constants = allConstants.toList(),
                        filteredConstants = applyFilters(
                            allConstants,
                            it.searchQuery,
                            it.typeFilter,
                            it.patternFilter,
                        ),
                    )
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        isExtracting = false,
                        error = "Failed to list classes: ${e.message}",
                    )
                }
            }
        }
    }

    /**
     * Updates the search query and re-filters.
     */
    fun setSearchQuery(query: String) {
        _uiState.update {
            it.copy(
                searchQuery = query,
                filteredConstants = applyFilters(it.constants, query, it.typeFilter, it.patternFilter),
            )
        }
    }

    /**
     * Toggles a constant type in the type filter.
     */
    fun toggleTypeFilter(type: ConstantType) {
        _uiState.update {
            val newFilter = if (type in it.typeFilter) {
                it.typeFilter - type
            } else {
                it.typeFilter + type
            }
            it.copy(
                typeFilter = newFilter,
                filteredConstants = applyFilters(it.constants, it.searchQuery, newFilter, it.patternFilter),
            )
        }
    }

    /**
     * Toggles a pattern filter.
     */
    fun togglePatternFilter(pattern: StringPattern) {
        _uiState.update {
            val current = it.patternFilter ?: emptySet()
            val newFilter = if (pattern in current) {
                val remaining = current - pattern
                remaining.ifEmpty { null }
            } else {
                current + pattern
            }
            it.copy(
                patternFilter = newFilter,
                filteredConstants = applyFilters(it.constants, it.searchQuery, it.typeFilter, newFilter),
            )
        }
    }

    /**
     * Clears any error message.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun applyFilters(
        constants: List<ExtractedConstant>,
        query: String,
        typeFilter: Set<ConstantType>,
        patternFilter: Set<StringPattern>?,
    ): List<ExtractedConstant> {
        return constants.filter { constant ->
            // Type filter
            constant.type in typeFilter &&
                    // Search query
                    (query.isBlank() || constant.value.toString().lowercase().contains(query.lowercase()) ||
                            constant.className.lowercase().contains(query.lowercase()) ||
                            constant.location.lowercase().contains(query.lowercase())) &&
                    // Pattern filter (only applies to strings with matched patterns)
                    (patternFilter == null || constant.matchedPatterns.any { it in patternFilter })
        }
    }
}
