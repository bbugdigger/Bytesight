package com.bugdigger.bytesight.ui.attach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bugdigger.bytesight.service.AgentClient
import com.bugdigger.bytesight.service.AttachService
import com.bugdigger.bytesight.service.ConnectionRegistry
import com.bugdigger.bytesight.service.ProjectSession
import com.bugdigger.bytesight.source.AgentClassSource
import com.bugdigger.bytesight.source.JarClassSource
import com.bugdigger.core.analysis.StaticHierarchyExtractor
import com.bugdigger.core.source.JarReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    private val connectionRegistry: ConnectionRegistry,
    private val projectSession: ProjectSession,
    private val jarReader: JarReader,
    private val hierarchyExtractor: StaticHierarchyExtractor,
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
                            // Build the live source and install it on the registry
                            // so VMs that read classSource pick it up immediately.
                            val source = AgentClassSource(agentClient, key)
                            connectionRegistry.setSource(source, connectionKey = key)
                            // The header bar's "currentFile" no longer applies
                            // — this is a fresh live attach, not a loaded .bts.
                            projectSession.reset()

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
     * Opens a JAR file and installs it as the active static-only source.
     *
     * The connection key is set to a synthetic `jar://...` identifier so the
     * existing `App.onConnected` callback fires (it routes to the Classes
     * tab and treats any non-null key as "we have a session"). Runtime tabs
     * stay disabled because [JarClassSource] only declares STATIC_ONLY.
     */
    fun openJar(path: String) {
        val file = File(path)
        viewModelScope.launch {
            _uiState.update { it.copy(isAttaching = true, error = null) }

            // JarClassSource reads + extracts metadata for every entry on
            // construction — keep that off the main thread for big JARs.
            val constructed = runCatching {
                withContext(Dispatchers.IO) {
                    JarClassSource(file, jarReader, hierarchyExtractor)
                }
            }

            constructed
                .onSuccess { source ->
                    val key = "jar://${file.absolutePath}"
                    connectionRegistry.setSource(source, connectionKey = null)
                    // Fresh JAR opens are not associated with a saved .bts.
                    projectSession.reset()
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
                            error = "Failed to open JAR: ${e.message}",
                        )
                    }
                }
        }
    }

    /**
     * Disconnects from the current agent or static source.
     */
    fun disconnect() {
        val key = _uiState.value.connectionKey ?: return
        // Only disconnect the gRPC client when the active source actually came
        // from a live agent. Static sources (jar://...) have no channel to close.
        if (!key.startsWith("jar://") && !key.startsWith("apk://") && !key.startsWith("bts://")) {
            agentClient.disconnect(key)
        }
        connectionRegistry.setSource(null, null)
        projectSession.reset()
        _uiState.update { it.copy(connectionKey = null) }
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
