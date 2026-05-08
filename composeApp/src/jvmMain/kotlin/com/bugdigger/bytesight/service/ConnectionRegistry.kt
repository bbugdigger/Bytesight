package com.bugdigger.bytesight.service

import com.bugdigger.bytesight.source.ClassSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Session-scoped holder for the current source of class data, the live
 * connection key (when one exists), and the last captured heap snapshot.
 *
 * Two parallel pieces of state:
 *
 * - [classSource] — what migrated VMs (ClassBrowser, Hierarchy, Inspector,
 *   Strings) read class metadata + bytecode from. May be backed by a live
 *   agent or, in later steps, by a JAR/APK/.bts file.
 * - [connectionKey] — set only when [classSource] is an
 *   [com.bugdigger.bytesight.source.AgentClassSource]. Runtime VMs (Trace,
 *   Heap, Debugger) and the AI-services impl read this for direct AgentClient
 *   calls. Null when the active source is static.
 */
class ConnectionRegistry {

    private val _classSource = MutableStateFlow<ClassSource?>(null)
    val classSource: StateFlow<ClassSource?> = _classSource.asStateFlow()

    private val _connectionKey = MutableStateFlow<String?>(null)
    val connectionKey: StateFlow<String?> = _connectionKey.asStateFlow()

    private val _snapshotId = MutableStateFlow<Long?>(null)
    val snapshotId: StateFlow<Long?> = _snapshotId.asStateFlow()

    /** Install a new active source. Closes the previous source if any. */
    fun setSource(source: ClassSource?, connectionKey: String? = null) {
        _classSource.value?.close()
        _classSource.value = source
        _connectionKey.value = connectionKey
        if (source == null) _snapshotId.value = null
    }

    /**
     * Convenience setter kept for callers that only know about the connection
     * key (e.g. legacy paths). New code should call [setSource] with a
     * [ClassSource]. After Step 1 the only intended caller of the no-source
     * shape is teardown (`setConnection(null)`), which clears everything.
     */
    @Deprecated("Use setSource() — passing only a key leaves classSource null", ReplaceWith("setSource(null, key)"))
    fun setConnection(key: String?) {
        if (key == null) {
            setSource(null, null)
        } else {
            _connectionKey.value = key
        }
    }

    fun setSnapshot(id: Long?) {
        _snapshotId.value = id
    }
}
