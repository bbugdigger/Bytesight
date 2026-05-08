package com.bugdigger.bytesight.debugger

import com.bugdigger.protocol.MethodBreakpointMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Session-scoped state shared across tabs for the debugger. Mirrors the
 * role of [com.bugdigger.bytesight.service.ConnectionRegistry]: anything that
 * crosses tab boundaries (Inspector toggling a gutter breakpoint, the breakpoint
 * list rendered by the Debugger tab) is read/written here.
 */
class DebuggerState {

    private val _breakpoints = MutableStateFlow<List<UiBreakpoint>>(emptyList())
    val breakpoints: StateFlow<List<UiBreakpoint>> = _breakpoints.asStateFlow()

    private val _pendingToggle = MutableStateFlow<PendingToggle?>(null)
    val pendingToggle: StateFlow<PendingToggle?> = _pendingToggle.asStateFlow()

    /**
     * Called from the Inspector gutter. The Debugger tab observes this flow and
     * installs / removes the matching breakpoint against the agent.
     */
    fun requestToggleFromInspector(
        className: String,
        methodName: String,
        methodSignature: String,
        line: Int,
    ) {
        _pendingToggle.value = PendingToggle(className, methodName, methodSignature, line)
    }

    fun clearPending() {
        _pendingToggle.value = null
    }

    fun setBreakpoints(list: List<UiBreakpoint>) {
        _breakpoints.value = list
    }

    fun addBreakpoint(bp: UiBreakpoint) {
        _breakpoints.value = _breakpoints.value + bp
    }

    fun removeBreakpoint(id: String) {
        _breakpoints.value = _breakpoints.value.filter { it.id != id }
    }

    fun updateBreakpoint(id: String, transform: (UiBreakpoint) -> UiBreakpoint) {
        _breakpoints.value = _breakpoints.value.map { if (it.id == id) transform(it) else it }
    }

    fun findAt(
        className: String,
        methodName: String,
        methodSignature: String,
        line: Int,
    ): UiBreakpoint? = _breakpoints.value.firstOrNull {
        it.className == className &&
            it.methodName == methodName &&
            (it.methodSignature.isEmpty() || it.methodSignature == methodSignature) &&
            it.displayLine == line
    }

    data class UiBreakpoint(
        val id: String,
        val className: String,
        val methodName: String,
        val methodSignature: String,
        val displayLine: Int,
        val mode: MethodBreakpointMode,
        val enabled: Boolean,
        /** Empty = unconditional. */
        val condition: String = "",
        /** Skip first N hits before suspending. 0 = no skip. */
        val skipCount: Int = 0,
        /** Server-reported total hits (incremented on every call regardless of gate). */
        val hitCount: Int = 0,
        /** Set when the agent's condition evaluator failed parse/eval — fail-open warning. */
        val conditionError: String? = null,
    )

    data class PendingToggle(
        val className: String,
        val methodName: String,
        val methodSignature: String,
        val line: Int,
    )

    /**
     * Serialize the breakpoint list. Persists only the static fields
     * (id, location, mode, enabled, condition, skipCount). Runtime fields
     * (hitCount, conditionError) are reset to defaults on restore.
     */
    fun serialize(json: Json = DEFAULT_JSON): String {
        val list = _breakpoints.value.map { bp ->
            SerializedBp(
                id = bp.id,
                className = bp.className,
                methodName = bp.methodName,
                methodSignature = bp.methodSignature,
                displayLine = bp.displayLine,
                mode = bp.mode.name,
                enabled = bp.enabled,
                condition = bp.condition,
                skipCount = bp.skipCount,
            )
        }
        return json.encodeToString(LIST_SERIALIZER, list)
    }

    fun restore(text: String, json: Json = DEFAULT_JSON) {
        val list: List<SerializedBp> = json.decodeFromString(LIST_SERIALIZER, text)
        _breakpoints.value = list.map { s ->
            UiBreakpoint(
                id = s.id,
                className = s.className,
                methodName = s.methodName,
                methodSignature = s.methodSignature,
                displayLine = s.displayLine,
                mode = MethodBreakpointMode.valueOf(s.mode),
                enabled = s.enabled,
                condition = s.condition,
                skipCount = s.skipCount,
                hitCount = 0,
                conditionError = null,
            )
        }
    }

    @Serializable
    private data class SerializedBp(
        val id: String,
        val className: String,
        val methodName: String,
        val methodSignature: String,
        val displayLine: Int,
        /** Stored by enum name for forward-compat across proto changes. */
        val mode: String,
        val enabled: Boolean,
        val condition: String,
        val skipCount: Int,
    )

    companion object {
        private val DEFAULT_JSON = Json { prettyPrint = true; ignoreUnknownKeys = true }
        private val LIST_SERIALIZER = ListSerializer(SerializedBp.serializer())
    }
}
