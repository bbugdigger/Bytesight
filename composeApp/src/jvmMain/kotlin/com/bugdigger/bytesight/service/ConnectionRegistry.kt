package com.bugdigger.bytesight.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Session-scoped holder for the current agent connection key and the last captured
 * heap snapshot. Exists so cross-cutting consumers (the AI agent services impl, for
 * example) can read the current connection without plumbing it through every call.
 */
class ConnectionRegistry {

    private val _connectionKey = MutableStateFlow<String?>(null)
    val connectionKey: StateFlow<String?> = _connectionKey.asStateFlow()

    private val _snapshotId = MutableStateFlow<Long?>(null)
    val snapshotId: StateFlow<Long?> = _snapshotId.asStateFlow()

    fun setConnection(key: String?) {
        _connectionKey.value = key
        if (key == null) _snapshotId.value = null
    }

    fun setSnapshot(id: Long?) {
        _snapshotId.value = id
    }
}
