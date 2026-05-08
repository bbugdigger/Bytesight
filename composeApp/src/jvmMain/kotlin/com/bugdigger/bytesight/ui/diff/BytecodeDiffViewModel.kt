package com.bugdigger.bytesight.ui.diff

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bugdigger.bytesight.source.BtsProjectClassSource
import com.bugdigger.bytesight.source.ClassSource
import com.bugdigger.bytesight.source.JarClassSource
import com.bugdigger.core.analysis.StaticHierarchyExtractor
import com.bugdigger.core.diff.MatchedPair
import com.bugdigger.core.diff.MethodFingerprint
import com.bugdigger.core.diff.ProjectDiffer
import com.bugdigger.core.source.JarReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File

/**
 * UI state for the BytecodeDiff tab.
 */
data class BytecodeDiffUiState(
    val leftLabel: String = "",
    val rightLabel: String = "",
    val isRunning: Boolean = false,
    val matchedPairs: List<MatchedPair> = emptyList(),
    val addedInNew: List<MethodFingerprint> = emptyList(),
    val removedFromOld: List<MethodFingerprint> = emptyList(),
    val selectedPair: MatchedPair? = null,
    val error: String? = null,
    /** Renames extracted from the left side's `.bts` (empty for JARs). */
    val leftRenames: Map<String, String> = emptyMap(),
    /**
     * Renames on the right side. Starts as the right `.bts`'s `renames.json`
     * (or empty for JARs), then mutates locally as the user clicks
     * "Apply old name". Saving these back into a project file is left to a
     * follow-up; for now this view is read-mostly + transient annotations.
     */
    val rightRenames: Map<String, String> = emptyMap(),
)

/**
 * Loads two `ClassSource`s (each either a `.bts` or `.jar` from disk),
 * fingerprints both, and runs [ProjectDiffer]. Self-contained — does not
 * touch the active session in [com.bugdigger.bytesight.service.ConnectionRegistry].
 */
class BytecodeDiffViewModel(
    private val differ: ProjectDiffer,
    private val jarReader: JarReader,
    private val hierarchyExtractor: StaticHierarchyExtractor,
    private val json: Json,
) : ViewModel() {

    private val logger = LoggerFactory.getLogger(BytecodeDiffViewModel::class.java)

    private val _uiState = MutableStateFlow(BytecodeDiffUiState())
    val uiState: StateFlow<BytecodeDiffUiState> = _uiState.asStateFlow()

    private var leftSource: ClassSource? = null
    private var rightSource: ClassSource? = null

    /** Open a file for the left ("old") side. Triggers diff if both sides loaded. */
    fun openLeft(file: File) = openSide(file, isLeft = true)

    /** Open a file for the right ("new") side. */
    fun openRight(file: File) = openSide(file, isLeft = false)

    private fun openSide(file: File, isLeft: Boolean) {
        viewModelScope.launch {
            _uiState.update { it.copy(isRunning = true, error = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    constructSource(file)
                }
            }.onSuccess { (source, renames) ->
                if (isLeft) {
                    leftSource?.close()
                    leftSource = source
                    _uiState.update { it.copy(leftLabel = source.displayName, leftRenames = renames) }
                } else {
                    rightSource?.close()
                    rightSource = source
                    _uiState.update { it.copy(rightLabel = source.displayName, rightRenames = renames) }
                }

                if (leftSource != null && rightSource != null) {
                    runDiffInternal()
                } else {
                    _uiState.update { it.copy(isRunning = false) }
                }
            }.onFailure { e ->
                logger.warn("Failed to open ${if (isLeft) "left" else "right"} source", e)
                _uiState.update {
                    it.copy(isRunning = false, error = "Failed to open ${file.name}: ${e.message}")
                }
            }
        }
    }

    fun selectPair(pair: MatchedPair?) {
        _uiState.update { it.copy(selectedPair = pair) }
    }

    /**
     * Apply the matched pair's old-side rename to [BytecodeDiffUiState.rightRenames].
     * No-op if the old side has no rename for this method (or class).
     */
    fun applyOldRename(pair: MatchedPair) {
        _uiState.update {
            it.copy(
                rightRenames = RenameTransfer.applyMethodRename(
                    oldRenames = it.leftRenames,
                    newRenames = it.rightRenames,
                    pair = pair,
                ),
            )
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        super.onCleared()
        dispose()
    }

    /**
     * Public lifecycle hook for callers that don't go through the
     * `ViewModelStoreOwner` lifecycle (e.g. tests). Closes the loaded
     * `ClassSource`s so file handles release; safe to call multiple times.
     */
    fun dispose() {
        leftSource?.close()
        rightSource?.close()
        leftSource = null
        rightSource = null
    }

    /**
     * Constructs a [ClassSource] from a file, plus the side's `renames.json`
     * if applicable. JARs always carry empty renames.
     */
    private fun constructSource(file: File): Pair<ClassSource, Map<String, String>> {
        val ext = file.extension.lowercase()
        return when (ext) {
            "bts" -> {
                val source = BtsProjectClassSource.open(file, hierarchyExtractor, json)
                val renames = runCatching {
                    val text = source.underlyingProjectFile().readJsonEntry("renames.json")
                        ?: return@runCatching emptyMap<String, String>()
                    json.decodeFromString(MAP_SERIALIZER, text)
                }.getOrElse { emptyMap() }
                source to renames
            }
            "jar" -> JarClassSource(file, jarReader, hierarchyExtractor) to emptyMap()
            else -> error("Unsupported file type: .$ext (expected .bts or .jar)")
        }
    }

    private suspend fun runDiffInternal() {
        val left = leftSource ?: return
        val right = rightSource ?: return
        runCatching {
            withContext(Dispatchers.Default) {
                val oldClasses = collectBytecode(left)
                val newClasses = collectBytecode(right)
                differ.diff(oldClasses, newClasses)
            }
        }.onSuccess { result ->
            _uiState.update {
                it.copy(
                    isRunning = false,
                    matchedPairs = result.matched,
                    addedInNew = result.addedInNew,
                    removedFromOld = result.removedFromOld,
                    selectedPair = null,
                )
            }
        }.onFailure { e ->
            logger.warn("Diff failed", e)
            _uiState.update { it.copy(isRunning = false, error = "Diff failed: ${e.message}") }
        }
    }

    private suspend fun collectBytecode(source: ClassSource): Map<String, ByteArray> {
        val list = source.listClasses(includeSystemClasses = false).getOrThrow()
        val out = mutableMapOf<String, ByteArray>()
        for (info in list) {
            source.getBytecode(info.name).onSuccess { out[info.name] = it }
        }
        return out
    }

    companion object {
        private val MAP_SERIALIZER = MapSerializer(String.serializer(), String.serializer())
    }
}
