package com.bugdigger.bytesight.ui.attach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bugdigger.bytesight.service.AgentClient
import com.bugdigger.bytesight.service.AttachService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the Attach screen.
 */
data class AttachUiState(
    val processes: List<AttachService.JvmProcess> = emptyList(),
    val selectedProcess: AttachService.JvmProcess? = null,
    val isLoading: Boolean = false,
    val isAttaching: Boolean = false,
    val error: String? = null,
    val agentPort: Int = 50051,
    val connectionKey: String? = null,
)

/**
 * ViewModel for the Attach screen.
 * Handles JVM process discovery and agent attachment.
 */
class AttachViewModel(
    private val attachService: AttachService,
    private val agentClient: AgentClient,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AttachUiState())
    val uiState: StateFlow<AttachUiState> = _uiState.asStateFlow()

    init {
        refreshProcesses()
    }

    /**
     * Refreshes the list of running JVM processes.
     */
    fun refreshProcesses() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val processes = attachService.listJvmProcesses()

            _uiState.update {
                it.copy(
                    processes = processes,
                    isLoading = false,
                    // Clear selection if the selected process is no longer running
                    selectedProcess = if (it.selectedProcess != null &&
                        processes.none { p -> p.pid == it.selectedProcess.pid }
                    ) null else it.selectedProcess,
                )
            }
        }
    }

    /**
     * Selects a process for attachment.
     */
    fun selectProcess(process: AttachService.JvmProcess?) {
        _uiState.update { it.copy(selectedProcess = process) }
    }

    /**
     * Updates the agent port.
     */
    fun setAgentPort(port: Int) {
        _uiState.update { it.copy(agentPort = port) }
    }

    /**
     * Attaches to the selected process.
     */
    fun attachToSelected() {
        val state = _uiState.value
        val process = state.selectedProcess ?: return

        viewModelScope.launch {
            _uiState.update { it.copy(isAttaching = true, error = null) }

            // First, attach the agent to the target JVM
            attachService.attachAgent(process.pid, state.agentPort)
                .onSuccess { port ->
                    // Then connect our gRPC client to the agent
                    agentClient.connect(port = port)
                        .onSuccess { key ->
                            _uiState.update {
                                it.copy(
                                    isAttaching = false,
                                    connectionKey = key,
                                )
                            }
                        }
                        .onFailure { e ->
                            _uiState.update {
                                it.copy(
                                    isAttaching = false,
                                    error = "Connected agent but failed to establish gRPC connection: ${e.message}",
                                )
                            }
                        }
                }
                .onFailure { e ->
                    _uiState.update {
                        it.copy(
                            isAttaching = false,
                            error = "Failed to attach: ${e.message}",
                        )
                    }
                }
        }
    }

    /**
     * Disconnects from the current agent.
     */
    fun disconnect() {
        _uiState.value.connectionKey?.let { key ->
            agentClient.disconnect(key)
            _uiState.update { it.copy(connectionKey = null) }
        }
    }

    /**
     * Clears any error message.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
