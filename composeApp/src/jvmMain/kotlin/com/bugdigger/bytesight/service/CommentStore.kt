package com.bugdigger.bytesight.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MethodKey(
    val className: String,
    val methodName: String,
    val descriptor: String,
)

data class MethodComments(
    val blockLevel: Map<String, String> = emptyMap(),
    val instructionLevel: Map<Int, String> = emptyMap(),
) {
    val isEmpty: Boolean get() = blockLevel.isEmpty() && instructionLevel.isEmpty()
}

/**
 * Session-scoped in-memory store for user comments on bytecode. Singleton so that
 * comments survive ViewModel recreation when the user navigates between screens
 * or switches the selected class/method.
 */
class CommentStore {
    private val _state = MutableStateFlow<Map<MethodKey, MethodComments>>(emptyMap())
    val state: StateFlow<Map<MethodKey, MethodComments>> = _state.asStateFlow()

    fun commentsFor(key: MethodKey): MethodComments = _state.value[key] ?: MethodComments()

    fun setInstructionComment(key: MethodKey, offset: Int, text: String) {
        _state.update { current ->
            val existing = current[key] ?: MethodComments()
            val updated = existing.copy(
                instructionLevel = if (text.isBlank()) {
                    existing.instructionLevel - offset
                } else {
                    existing.instructionLevel + (offset to text)
                },
            )
            if (updated.isEmpty) current - key else current + (key to updated)
        }
    }

    fun setBlockComment(key: MethodKey, blockId: String, text: String) {
        _state.update { current ->
            val existing = current[key] ?: MethodComments()
            val updated = existing.copy(
                blockLevel = if (text.isBlank()) {
                    existing.blockLevel - blockId
                } else {
                    existing.blockLevel + (blockId to text)
                },
            )
            if (updated.isEmpty) current - key else current + (key to updated)
        }
    }
}
