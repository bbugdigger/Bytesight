package com.bugdigger.bytesight.ui.debugger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bugdigger.bytesight.debugger.DebuggerState
import com.bugdigger.bytesight.debugger.ExecutionCursor
import com.bugdigger.bytesight.service.AgentClient
import com.bugdigger.protocol.Breakpoint
import com.bugdigger.protocol.MethodBreakpointMode
import com.bugdigger.protocol.breakpoint
import com.bugdigger.protocol.methodLocation
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * ViewModel for the Debugger screen. Presents an [ExecutionCursor]-backed view of
 * program state and mediates user actions (install/remove/toggle breakpoints,
 * resume/stop) against the agent.
 *
 * The VM does **not** hold debugger state directly — threads / frames come from
 * the injected cursor (so a future ReplayCursor can swap in with no VM changes),
 * and breakpoints come from the session-scoped [DebuggerState] singleton.
 */
class DebuggerViewModel(
    private val agentClient: AgentClient,
    val cursor: ExecutionCursor,
    private val debuggerState: DebuggerState,
) : ViewModel() {

    val breakpoints: StateFlow<List<DebuggerState.UiBreakpoint>> = debuggerState.breakpoints
    val threads = cursor.threads
    val currentThreadId = cursor.currentThreadId
    val currentFrame = cursor.currentFrame
    val callStack = cursor.callStack
    val lastHit = cursor.lastHit

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private var connectionKey: String? = null
    private var pendingToggleJob: Job? = null

    fun setConnectionKey(key: String) {
        if (connectionKey == key) return
        connectionKey = key
        refreshBreakpointsFromAgent()
        observePendingToggle()
    }

    private fun refreshBreakpointsFromAgent() {
        val key = connectionKey ?: return
        viewModelScope.launch {
            agentClient.listBreakpoints(key)
                .onSuccess { list -> debuggerState.setBreakpoints(list.map { it.toUi(defaultLine = 0) }) }
                .onFailure { e -> _error.value = "Failed to list breakpoints: ${e.message}" }
        }
    }

    private fun observePendingToggle() {
        pendingToggleJob?.cancel()
        pendingToggleJob = viewModelScope.launch {
            debuggerState.pendingToggle.collect { toggle ->
                if (toggle != null) {
                    applyToggle(toggle)
                    debuggerState.clearPending()
                }
            }
        }
    }

    private fun applyToggle(toggle: DebuggerState.PendingToggle) {
        val existing = debuggerState.findAt(
            className = toggle.className,
            methodName = toggle.methodName,
            methodSignature = toggle.methodSignature,
            line = toggle.line,
        )
        if (existing != null) {
            removeBreakpoint(existing.id)
        } else {
            addMethodEntryBreakpoint(
                className = toggle.className,
                methodName = toggle.methodName,
                methodSignature = toggle.methodSignature,
                displayLine = toggle.line,
            )
        }
    }

    fun addMethodEntryBreakpoint(
        className: String,
        methodName: String,
        methodSignature: String,
        displayLine: Int,
    ) {
        val key = connectionKey ?: return
        val id = UUID.randomUUID().toString()
        viewModelScope.launch {
            _busy.value = true
            agentClient.setMethodBreakpoint(
                connectionKey = key,
                breakpointId = id,
                className = className,
                methodName = methodName,
                methodSignature = methodSignature,
                mode = MethodBreakpointMode.METHOD_BP_ENTRY,
                enabled = true,
            ).onSuccess { resp ->
                if (resp.success) {
                    debuggerState.addBreakpoint(
                        DebuggerState.UiBreakpoint(
                            id = id,
                            className = className,
                            methodName = methodName,
                            methodSignature = methodSignature,
                            displayLine = displayLine,
                            mode = MethodBreakpointMode.METHOD_BP_ENTRY,
                            enabled = true,
                        ),
                    )
                } else {
                    _error.value = resp.error.ifEmpty { "Failed to install breakpoint" }
                }
            }.onFailure { e -> _error.value = "Failed to install breakpoint: ${e.message}" }
            _busy.value = false
        }
    }

    fun removeBreakpoint(id: String) {
        val key = connectionKey ?: return
        viewModelScope.launch {
            _busy.value = true
            agentClient.removeBreakpoint(key, id)
                .onSuccess { resp ->
                    if (resp.success) {
                        debuggerState.removeBreakpoint(id)
                    } else {
                        _error.value = resp.error.ifEmpty { "Failed to remove breakpoint" }
                    }
                }
                .onFailure { e -> _error.value = "Failed to remove breakpoint: ${e.message}" }
            _busy.value = false
        }
    }

    fun toggleEnabled(id: String) {
        val key = connectionKey ?: return
        val bp = debuggerState.breakpoints.value.firstOrNull { it.id == id } ?: return
        val next = !bp.enabled
        viewModelScope.launch {
            _busy.value = true
            // Protocol has no update — remove + reinsert. The agent treats enabled=false as "never fire."
            agentClient.removeBreakpoint(key, bp.id)
            val proto = breakpoint {
                this.id = bp.id
                this.method = methodLocation {
                    this.className = bp.className
                    this.methodName = bp.methodName
                    this.methodSignature = bp.methodSignature
                    this.mode = bp.mode
                }
                this.enabled = next
            }
            agentClient.setBreakpoint(key, proto)
                .onSuccess { resp ->
                    if (resp.success) {
                        debuggerState.updateBreakpoint(bp.id) { it.copy(enabled = next) }
                    } else {
                        _error.value = resp.error.ifEmpty { "Failed to toggle breakpoint" }
                    }
                }
                .onFailure { e -> _error.value = "Failed to toggle breakpoint: ${e.message}" }
            _busy.value = false
        }
    }

    fun resume(threadId: Long = 0L) {
        val key = connectionKey ?: return
        viewModelScope.launch {
            agentClient.resume(key, threadId)
                .onFailure { e -> _error.value = "Failed to resume: ${e.message}" }
        }
    }

    fun resumeCurrentThread() {
        val tid = currentThreadId.value ?: return
        resume(tid)
    }

    fun resumeAll() = resume(0L)

    fun stopDebugging() {
        val key = connectionKey ?: return
        viewModelScope.launch {
            _busy.value = true
            val current = debuggerState.breakpoints.value
            for (bp in current) {
                agentClient.removeBreakpoint(key, bp.id)
            }
            debuggerState.setBreakpoints(emptyList())
            agentClient.resume(key, 0L)
            _busy.value = false
        }
    }

    fun selectThread(id: Long) = cursor.selectThread(id)

    fun selectFrame(depth: Int) = cursor.selectFrame(depth)

    fun clearError() {
        _error.value = null
    }

    private fun Breakpoint.toUi(defaultLine: Int): DebuggerState.UiBreakpoint {
        val m = method
        return DebuggerState.UiBreakpoint(
            id = id,
            className = m.className,
            methodName = m.methodName,
            methodSignature = m.methodSignature,
            displayLine = defaultLine,
            mode = m.mode,
            enabled = enabled,
        )
    }
}
