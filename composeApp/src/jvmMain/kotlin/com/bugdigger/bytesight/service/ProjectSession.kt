package com.bugdigger.bytesight.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * Tracks "what file does Save target?" — set on a successful Open .bts or
 * Save As, cleared on any other session change (live attach, JAR open,
 * Disconnect). The header bar reads [currentFile] to decide whether Save
 * needs a Save-As prompt.
 *
 * Kept separate from [ConnectionRegistry] so the registry stays focused on
 * source-of-bytes concerns; this is purely about "where on disk are we
 * persisting this session".
 */
class ProjectSession {

    private val _currentFile = MutableStateFlow<File?>(null)
    val currentFile: StateFlow<File?> = _currentFile.asStateFlow()

    fun setCurrentFile(file: File?) {
        _currentFile.value = file
    }

    fun reset() {
        _currentFile.value = null
    }
}
