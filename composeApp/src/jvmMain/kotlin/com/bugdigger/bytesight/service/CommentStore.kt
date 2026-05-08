package com.bugdigger.bytesight.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

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

    /** Serialize all method comments as a flat JSON list. */
    fun serialize(json: Json = DEFAULT_JSON): String {
        val list = _state.value.map { (key, value) ->
            SerializedCommentEntry(
                className = key.className,
                methodName = key.methodName,
                descriptor = key.descriptor,
                blockLevel = value.blockLevel,
                instructionLevel = value.instructionLevel,
            )
        }
        return json.encodeToString(LIST_SERIALIZER, list)
    }

    /** Replace the comment store with the contents of the given JSON. */
    fun restore(text: String, json: Json = DEFAULT_JSON) {
        val list: List<SerializedCommentEntry> = json.decodeFromString(LIST_SERIALIZER, text)
        _state.value = list.associate { e ->
            MethodKey(e.className, e.methodName, e.descriptor) to
                MethodComments(blockLevel = e.blockLevel, instructionLevel = e.instructionLevel)
        }
    }

    @Serializable
    private data class SerializedCommentEntry(
        val className: String,
        val methodName: String,
        val descriptor: String,
        val blockLevel: Map<String, String> = emptyMap(),
        val instructionLevel: Map<Int, String> = emptyMap(),
    )

    companion object {
        private val DEFAULT_JSON = Json { prettyPrint = true; ignoreUnknownKeys = true }
        private val LIST_SERIALIZER = ListSerializer(SerializedCommentEntry.serializer())
    }
}
