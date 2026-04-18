package com.bugdigger.bytesight.ui.heap

import androidx.compose.ui.geometry.Offset
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bugdigger.bytesight.service.AgentClient
import com.bugdigger.bytesight.ui.components.GraphLayout
import com.bugdigger.bytesight.ui.components.LayoutEdge
import com.bugdigger.bytesight.ui.components.LayoutNode
import com.bugdigger.bytesight.ui.components.SugiyamaLayout
import com.bugdigger.protocol.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HeapSortMode { BYTES_DESC, COUNT_DESC, NAME_ASC }

/** Which sub-tab is active in the heap screen. */
enum class HeapTab { HISTOGRAM, SEARCH, DUPLICATES }

/** Which search mode is active. */
enum class SearchMode { STRING_CONTAINS, FIELD_EQUALS }

/**
 * Data model for a node in the object reference graph.
 */
data class ObjectNode(
    val tag: Long,
    val className: String,
    val shallowBytes: Long,
    val fields: List<FieldValue>,
    val outgoingRefs: List<ReferenceEdge>,
)

/**
 * Data model for an edge in the object reference graph.
 */
data class ObjectEdge(
    val fieldName: String,
)

data class HeapUiState(
    val snapshot: HeapSnapshotInfo? = null,
    val histogram: List<ClassHistogramEntry> = emptyList(),
    val filteredHistogram: List<ClassHistogramEntry> = emptyList(),
    val nameFilter: String = "",
    val sortMode: HeapSortMode = HeapSortMode.BYTES_DESC,
    val isCapturing: Boolean = false,
    val error: String? = null,
    // Phase 2 additions
    val selectedClass: String? = null,
    val instances: List<InstanceSummary> = emptyList(),
    val isLoadingInstances: Boolean = false,
    val selectedTag: Long? = null,
    val objectDetail: ObjectDetail? = null,
    val isLoadingObject: Boolean = false,
    val exploredObjects: Map<Long, ObjectDetail> = emptyMap(),
    val objectGraph: GraphLayout<ObjectNode, ObjectEdge>? = null,
    // Phase 3 additions
    val activeTab: HeapTab = HeapTab.HISTOGRAM,
    val searchMode: SearchMode = SearchMode.STRING_CONTAINS,
    val searchQuery: String = "",
    val searchFieldClassName: String = "",
    val searchFieldName: String = "",
    val searchFieldValue: String = "",
    val searchResults: List<ValueMatch> = emptyList(),
    val isSearching: Boolean = false,
    val duplicateGroups: List<DuplicateStringGroup> = emptyList(),
    val isLoadingDuplicates: Boolean = false,
    val dupMinCount: Int = 2,
    val dupMinLength: Int = 1,
)

class HeapViewModel(
    private val agentClient: AgentClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HeapUiState())
    val uiState: StateFlow<HeapUiState> = _uiState.asStateFlow()

    private var connectionKey: String? = null
    private val sugiyamaLayout = SugiyamaLayout(horizontalSpacing = 60f, verticalSpacing = 80f)

    fun setConnectionKey(key: String) {
        connectionKey = key
    }

    fun capture() {
        val key = connectionKey ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isCapturing = true, error = null) }

            agentClient.captureHeapSnapshot(key).onSuccess { info ->
                if (!info.available) {
                    _uiState.update {
                        it.copy(
                            isCapturing = false,
                            snapshot = info,
                            histogram = emptyList(),
                            filteredHistogram = emptyList(),
                            error = info.error.ifEmpty { "Heap helper unavailable" },
                        )
                    }
                    return@onSuccess
                }

                agentClient.getClassHistogram(key, info.snapshotId, "").onSuccess { rows ->
                    _uiState.update {
                        it.copy(
                            isCapturing = false,
                            snapshot = info,
                            histogram = rows,
                            filteredHistogram = applyView(rows, it.nameFilter, it.sortMode),
                            error = null,
                            // Reset Phase 2 state on new snapshot
                            selectedClass = null,
                            instances = emptyList(),
                            selectedTag = null,
                            objectDetail = null,
                            exploredObjects = emptyMap(),
                            objectGraph = null,
                        )
                    }
                }.onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isCapturing = false,
                            snapshot = info,
                            error = "Failed to fetch histogram: ${e.message}",
                        )
                    }
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        isCapturing = false,
                        error = "Capture failed: ${e.message}",
                    )
                }
            }
        }
    }

    fun setFilter(filter: String) {
        _uiState.update {
            it.copy(
                nameFilter = filter,
                filteredHistogram = applyView(it.histogram, filter, it.sortMode),
            )
        }
    }

    fun setSortMode(mode: HeapSortMode) {
        _uiState.update {
            it.copy(
                sortMode = mode,
                filteredHistogram = applyView(it.histogram, it.nameFilter, mode),
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /** Select a class from the histogram and load its instances. */
    fun selectClass(className: String) {
        val key = connectionKey ?: return
        val snapshotId = _uiState.value.snapshot?.snapshotId ?: return

        _uiState.update {
            it.copy(
                selectedClass = className,
                instances = emptyList(),
                isLoadingInstances = true,
                selectedTag = null,
                objectDetail = null,
                exploredObjects = emptyMap(),
                objectGraph = null,
            )
        }

        viewModelScope.launch {
            agentClient.listInstances(key, snapshotId, className, 500).onSuccess { list ->
                _uiState.update { it.copy(instances = list, isLoadingInstances = false) }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        isLoadingInstances = false,
                        error = "Failed to list instances: ${e.message}",
                    )
                }
            }
        }
    }

    /** Select an instance and load its object detail into the graph. */
    fun selectInstance(tag: Long) {
        _uiState.update {
            it.copy(
                selectedTag = tag,
                exploredObjects = emptyMap(),
                objectGraph = null,
            )
        }
        loadAndAddToGraph(tag)
    }

    /** Expand a reference in the graph — load the target object and add it. */
    fun expandReference(tag: Long) {
        if (_uiState.value.exploredObjects.containsKey(tag)) return
        loadAndAddToGraph(tag)
    }

    /** Clear the object graph exploration. */
    fun clearObjectGraph() {
        _uiState.update {
            it.copy(
                selectedTag = null,
                objectDetail = null,
                exploredObjects = emptyMap(),
                objectGraph = null,
            )
        }
    }

    // ========== Phase 3: Tab, Search & Duplicates ==========

    fun setActiveTab(tab: HeapTab) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun setSearchMode(mode: SearchMode) {
        _uiState.update { it.copy(searchMode = mode) }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setSearchFieldClassName(value: String) {
        _uiState.update { it.copy(searchFieldClassName = value) }
    }

    fun setSearchFieldName(value: String) {
        _uiState.update { it.copy(searchFieldName = value) }
    }

    fun setSearchFieldValue(value: String) {
        _uiState.update { it.copy(searchFieldValue = value) }
    }

    fun setDupMinCount(value: Int) {
        _uiState.update { it.copy(dupMinCount = value) }
    }

    fun setDupMinLength(value: Int) {
        _uiState.update { it.copy(dupMinLength = value) }
    }

    /** Execute a value search against the current snapshot. */
    fun executeSearch() {
        val key = connectionKey ?: return
        val snapshotId = _uiState.value.snapshot?.snapshotId ?: return
        val state = _uiState.value

        _uiState.update { it.copy(isSearching = true, searchResults = emptyList(), error = null) }

        viewModelScope.launch {
            val result = when (state.searchMode) {
                SearchMode.STRING_CONTAINS -> agentClient.searchValues(
                    key, snapshotId, stringContains = state.searchQuery, limit = 500,
                )
                SearchMode.FIELD_EQUALS -> agentClient.searchValues(
                    key, snapshotId,
                    fieldClassName = state.searchFieldClassName,
                    fieldName = state.searchFieldName,
                    fieldValue = state.searchFieldValue,
                    limit = 500,
                )
            }

            result.onSuccess { matches ->
                _uiState.update { it.copy(isSearching = false, searchResults = matches) }
            }.onFailure { e ->
                _uiState.update { it.copy(isSearching = false, error = "Search failed: ${e.message}") }
            }
        }
    }

    /** Find duplicate strings in the current snapshot. */
    fun findDuplicateStrings() {
        val key = connectionKey ?: return
        val snapshotId = _uiState.value.snapshot?.snapshotId ?: return
        val state = _uiState.value

        _uiState.update { it.copy(isLoadingDuplicates = true, duplicateGroups = emptyList(), error = null) }

        viewModelScope.launch {
            agentClient.findDuplicateStrings(
                key, snapshotId,
                minCount = state.dupMinCount,
                minLength = state.dupMinLength,
                limitGroups = 100,
            ).onSuccess { groups ->
                _uiState.update { it.copy(isLoadingDuplicates = false, duplicateGroups = groups) }
            }.onFailure { e ->
                _uiState.update { it.copy(isLoadingDuplicates = false, error = "Duplicate search failed: ${e.message}") }
            }
        }
    }

    private fun loadAndAddToGraph(tag: Long) {
        val key = connectionKey ?: return
        val snapshotId = _uiState.value.snapshot?.snapshotId ?: return

        _uiState.update { it.copy(isLoadingObject = true) }

        viewModelScope.launch {
            agentClient.getObject(key, snapshotId, tag).onSuccess { detail ->
                if (!detail.found) {
                    _uiState.update {
                        it.copy(
                            isLoadingObject = false,
                            error = detail.error.ifEmpty { "Object not found for tag $tag" },
                        )
                    }
                    return@onSuccess
                }

                _uiState.update { state ->
                    val newExplored = state.exploredObjects + (tag to detail)
                    val graph = buildGraph(newExplored)
                    state.copy(
                        isLoadingObject = false,
                        objectDetail = detail,
                        exploredObjects = newExplored,
                        objectGraph = graph,
                    )
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        isLoadingObject = false,
                        error = "Failed to load object: ${e.message}",
                    )
                }
            }
        }
    }

    private fun buildGraph(explored: Map<Long, ObjectDetail>): GraphLayout<ObjectNode, ObjectEdge> {
        val nodes = explored.map { (tag, detail) ->
            val nodeData = ObjectNode(
                tag = tag,
                className = detail.className,
                shallowBytes = detail.shallowBytes,
                fields = detail.fieldsList,
                outgoingRefs = detail.outgoingRefsList,
            )
            tag.toString() to nodeData
        }

        val edges = mutableListOf<Triple<String, String, ObjectEdge>>()
        for ((tag, detail) in explored) {
            for (ref in detail.outgoingRefsList) {
                if (ref.targetTag != 0L && ref.targetTag in explored) {
                    edges.add(Triple(tag.toString(), ref.targetTag.toString(), ObjectEdge(ref.fieldName)))
                }
            }
        }

        return sugiyamaLayout.layout(
            nodes = nodes,
            edges = edges,
            entryId = explored.keys.firstOrNull()?.toString(),
            nodeSize = { node -> computeNodeSize(node) },
        )
    }

    private fun computeNodeSize(node: ObjectNode): Pair<Float, Float> {
        val headerHeight = 40f
        val fieldHeight = 20f
        val maxFields = 15
        val padding = 16f
        val width = 340f
        val fieldsShown = node.fields.size.coerceAtMost(maxFields)
        val refsNotInFields = node.outgoingRefs.count { ref ->
            node.fields.none { f -> f.refTag == ref.targetTag && f.refTag != 0L }
        }
        val height = headerHeight + (fieldsShown * fieldHeight) + padding
        return width to height
    }

    private fun applyView(
        rows: List<ClassHistogramEntry>,
        filter: String,
        sortMode: HeapSortMode,
    ): List<ClassHistogramEntry> {
        val trimmed = filter.trim()
        val filtered = if (trimmed.isEmpty()) rows
        else rows.filter { it.className.contains(trimmed, ignoreCase = true) }

        return when (sortMode) {
            HeapSortMode.BYTES_DESC -> filtered.sortedByDescending { it.shallowBytes }
            HeapSortMode.COUNT_DESC -> filtered.sortedByDescending { it.instanceCount }
            HeapSortMode.NAME_ASC -> filtered.sortedBy { it.className }
        }
    }
}
