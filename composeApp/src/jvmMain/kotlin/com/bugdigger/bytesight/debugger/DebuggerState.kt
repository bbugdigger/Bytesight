package com.bugdigger.bytesight.debugger

import com.bugdigger.protocol.MethodBreakpointMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    )

    data class PendingToggle(
        val className: String,
        val methodName: String,
        val methodSignature: String,
        val line: Int,
    )
}
