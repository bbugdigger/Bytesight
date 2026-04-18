package com.bugdigger.bytesight.ui.trace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bugdigger.bytesight.service.AgentClient
import com.bugdigger.bytesight.service.RenameStore
import com.bugdigger.protocol.ClassInfo
import com.bugdigger.protocol.HookInfo
import com.bugdigger.protocol.HookType
import com.bugdigger.protocol.MethodInfo
import com.bugdigger.protocol.MethodTraceEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Represents a trace event with formatted display information.
 */
data class TraceEventDisplay(
    val id: String,
    val timestamp: Long,
    val threadName: String,
    val className: String,
    val methodName: String,
    val eventType: MethodTraceEvent.TraceEventType,
    val depth: Int,
    val arguments: String?,
    val returnValue: String?,
    val durationNanos: Long?,
    val exceptionInfo: String?,
)

/**
 * UI state for the Trace screen.
 */
data class TraceUiState(
    val hooks: List<HookInfo> = emptyList(),
    val traceEvents: List<TraceEventDisplay> = emptyList(),
    val isLoadingHooks: Boolean = false,
    val isStreaming: Boolean = false,
    val error: String? = null,

    // Class/method selection
    val classes: List<ClassInfo> = emptyList(),
    val isLoadingClasses: Boolean = false,
    val selectedClass: ClassInfo? = null,
    val selectedMethod: MethodInfo? = null,

    // New hook form
    val newHookClassName: String = "",
    val newHookMethodName: String = "",
    val newHookMethodSignature: String = "",
    val newHookType: HookType = HookType.LOG_ENTRY_EXIT,
    val isAddingHook: Boolean = false,

    // Settings
    val maxEvents: Int = 1000,
    val autoScroll: Boolean = true,
)

/**
 * ViewModel for the Trace screen.
 * Handles method hooking and trace event streaming.
 */
class TraceViewModel(
    private val agentClient: AgentClient,
    private val renameStore: RenameStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TraceUiState())
    val uiState: StateFlow<TraceUiState> = _uiState.asStateFlow()

    private var connectionKey: String? = null
    private var streamingJob: Job? = null

    /**
     * Sets the connection key to use for agent communication.
     */
    fun setConnectionKey(key: String) {
        if (connectionKey != key) {
            connectionKey = key
            loadClasses()
            refreshHooks()
        }
    }

    private fun loadClasses() {
        val key = connectionKey ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingClasses = true) }

            agentClient.listClasses(
                connectionKey = key,
                includeSystemClasses = false,
            ).onSuccess { classes ->
                _uiState.update { it.copy(classes = classes, isLoadingClasses = false) }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        isLoadingClasses = false,
                        error = "Failed to load classes: ${e.message}",
                    )
                }
            }
        }
    }

    fun selectClass(classInfo: ClassInfo) {
        _uiState.update {
            it.copy(
                selectedClass = classInfo,
                selectedMethod = null,
                newHookClassName = classInfo.name,
                newHookMethodName = "",
                newHookMethodSignature = "",
            )
        }
    }

    fun selectMethod(methodInfo: MethodInfo) {
        _uiState.update {
            it.copy(
                selectedMethod = methodInfo,
                newHookMethodName = methodInfo.name,
                newHookMethodSignature = methodInfo.signature,
            )
        }
    }

    /**
     * Refreshes the list of active hooks.
     */
    fun refreshHooks() {
        val key = connectionKey ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingHooks = true, error = null) }

            agentClient.listHooks(key)
                .onSuccess { response ->
                    _uiState.update {
                        it.copy(
                            hooks = response.hooksList,
                            isLoadingHooks = false,
                        )
                    }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isLoadingHooks = false,
                            error = "Failed to load hooks: ${e.message}",
                        )
                    }
                }
        }
    }

    /**
     * Updates the new hook class name field.
     */
    fun setNewHookClassName(className: String) {
        _uiState.update { it.copy(newHookClassName = className) }
    }

    /**
     * Updates the new hook method name field.
     */
    fun setNewHookMethodName(methodName: String) {
        _uiState.update { it.copy(newHookMethodName = methodName) }
    }

    /**
     * Updates the new hook method signature field.
     */
    fun setNewHookMethodSignature(signature: String) {
        _uiState.update { it.copy(newHookMethodSignature = signature) }
    }

    /**
     * Updates the new hook type.
     */
    fun setNewHookType(hookType: HookType) {
        _uiState.update { it.copy(newHookType = hookType) }
    }

    /**
     * Adds a new hook with the current form values.
     */
    fun addHook() {
        val key = connectionKey ?: return
        val state = _uiState.value

        if (state.newHookClassName.isBlank() || state.newHookMethodName.isBlank()) {
            _uiState.update { it.copy(error = "Class name and method name are required") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isAddingHook = true, error = null) }

            val hookId = UUID.randomUUID().toString()

            agentClient.addHook(
                connectionKey = key,
                hookId = hookId,
                className = state.newHookClassName,
                methodName = state.newHookMethodName,
                methodSignature = state.newHookMethodSignature,
                hookType = state.newHookType,
            ).onSuccess { response ->
                if (response.success) {
                    _uiState.update {
                        it.copy(
                            isAddingHook = false,
                            selectedClass = null,
                            selectedMethod = null,
                            newHookClassName = "",
                            newHookMethodName = "",
                            newHookMethodSignature = "",
                        )
                    }
                    refreshHooks()
                } else {
                    _uiState.update {
                        it.copy(
                            isAddingHook = false,
                            error = "Failed to add hook: ${response.error}",
                        )
                    }
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        isAddingHook = false,
                        error = "Failed to add hook: ${e.message}",
                    )
                }
            }
        }
    }

    /**
     * Removes a hook by its ID.
     */
    fun removeHook(hookId: String) {
        val key = connectionKey ?: return

        viewModelScope.launch {
            agentClient.removeHook(key, hookId)
                .onSuccess { response ->
                    if (response.success) {
                        refreshHooks()
                    } else {
                        _uiState.update { it.copy(error = "Failed to remove hook: ${response.error}") }
                    }
                }
                .onFailure { e ->
                    _uiState.update { it.copy(error = "Failed to remove hook: ${e.message}") }
                }
        }
    }

    /**
     * Starts streaming trace events from the agent.
     */
    fun startStreaming() {
        val key = connectionKey ?: return

        if (_uiState.value.isStreaming) return

        agentClient.streamTraceEvents(key)
            .onSuccess { flow ->
                _uiState.update { it.copy(isStreaming = true) }

                streamingJob = viewModelScope.launch {
                    flow.collect { event ->
                        val display = event.toDisplay()
                        _uiState.update { state ->
                            val events = state.traceEvents + display
                            val trimmed = if (events.size > state.maxEvents) {
                                events.drop(events.size - state.maxEvents)
                            } else {
                                events
                            }
                            state.copy(traceEvents = trimmed)
                        }
                    }
                }
            }
            .onFailure { e ->
                _uiState.update { it.copy(error = "Failed to start streaming: ${e.message}") }
            }
    }

    /**
     * Stops streaming trace events.
     */
    fun stopStreaming() {
        streamingJob?.cancel()
        streamingJob = null
        _uiState.update { it.copy(isStreaming = false) }
    }

    /**
     * Clears all trace events.
     */
    fun clearEvents() {
        _uiState.update { it.copy(traceEvents = emptyList()) }
    }

    /**
     * Toggles auto-scroll behavior.
     */
    fun setAutoScroll(enabled: Boolean) {
        _uiState.update { it.copy(autoScroll = enabled) }
    }

    /**
     * Clears any error message.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        super.onCleared()
        stopStreaming()
    }

    private fun MethodTraceEvent.toDisplay(): TraceEventDisplay {
        // Create a unique ID by combining callId with eventType
        // This ensures ENTRY and EXIT events with the same callId have different IDs
        val uniqueId = if (callId.isEmpty()) {
            UUID.randomUUID().toString()
        } else {
            "$callId-${eventType.name}"
        }

        // Apply user renames to displayed class/method names
        val shortNames = renameStore.shortNameMap()
        val displayClassName = shortNames.entries
            .fold(className) { name, (old, new) ->
                name.replace(old, new)
            }
        val displayMethodName = shortNames[methodName] ?: methodName

        return TraceEventDisplay(
            id = uniqueId,
            timestamp = timestamp,
            threadName = threadName,
            className = displayClassName,
            methodName = displayMethodName,
            eventType = eventType,
            depth = depth,
            arguments = if (argumentsList.isNotEmpty()) {
                argumentsList.joinToString(", ") { arg ->
                    "${arg.name}: ${arg.type} = ${if (arg.isNull) "null" else arg.value}"
                }
            } else null,
            returnValue = if (eventType == MethodTraceEvent.TraceEventType.EXIT && returnValue != null) {
                if (returnValue.isNull) "null" else returnValue.value
            } else null,
            durationNanos = if (eventType == MethodTraceEvent.TraceEventType.EXIT) durationNanos else null,
            exceptionInfo = if (eventType == MethodTraceEvent.TraceEventType.EXCEPTION) {
                "$exceptionClass: $exceptionMessage"
            } else null,
        )
    }
}
