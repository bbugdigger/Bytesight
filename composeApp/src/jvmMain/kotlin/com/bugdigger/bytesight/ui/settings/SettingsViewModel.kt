package com.bugdigger.bytesight.ui.settings

import androidx.lifecycle.ViewModel
import com.bugdigger.ai.AIProvider
import com.bugdigger.ai.AgentConfig
import com.bugdigger.bytesight.service.AgentConfigStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * UI state for the Settings screen.
 */
data class SettingsUiState(
    // Decompiler settings
    val showLineNumbers: Boolean = true,
    val showBytecodeComments: Boolean = false,
    val simplifyLambdas: Boolean = true,

    // Trace settings
    val maxTraceEvents: Int = 1000,
    val traceArguments: Boolean = true,
    val traceReturnValues: Boolean = true,
    val traceExceptions: Boolean = true,
    val traceTiming: Boolean = true,

    // Connection settings
    val defaultPort: Int = 5050,
    val connectionTimeoutMs: Int = 5000,

    // UI settings
    val fontSize: Int = 13,
)

/**
 * ViewModel for the Settings screen.
 * Manages application-wide settings.
 */
class SettingsViewModel(
    private val agentConfigStore: AgentConfigStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    /** Live AI agent config — edited via [updateAgentConfig]. */
    val agentConfig: StateFlow<AgentConfig> = agentConfigStore.config

    fun setAIProvider(provider: AIProvider) {
        val current = agentConfig.value
        val defaultModel = when (provider) {
            AIProvider.OPEN_ROUTER -> AgentConfig.DEFAULT_OPEN_ROUTER_MODEL
            AIProvider.OPENAI -> AgentConfig.DEFAULT_OPENAI_MODEL
            AIProvider.ANTHROPIC -> AgentConfig.DEFAULT_ANTHROPIC_MODEL
            AIProvider.OLLAMA -> AgentConfig.DEFAULT_OLLAMA_MODEL
        }
        agentConfigStore.update(current.copy(provider = provider, model = defaultModel))
    }

    fun setAIApiKey(key: String) {
        agentConfigStore.update(agentConfig.value.copy(apiKey = key))
    }

    fun setAIModel(model: String) {
        agentConfigStore.update(agentConfig.value.copy(model = model))
    }

    fun setAITemperature(temperature: Double) {
        agentConfigStore.update(agentConfig.value.copy(temperature = temperature.coerceIn(0.0, 1.0)))
    }

    fun setAIMaxIterations(max: Int) {
        agentConfigStore.update(agentConfig.value.copy(maxIterations = max.coerceIn(1, 100)))
    }

    // Decompiler settings
    fun setShowLineNumbers(enabled: Boolean) {
        _uiState.update { it.copy(showLineNumbers = enabled) }
    }

    fun setShowBytecodeComments(enabled: Boolean) {
        _uiState.update { it.copy(showBytecodeComments = enabled) }
    }

    fun setSimplifyLambdas(enabled: Boolean) {
        _uiState.update { it.copy(simplifyLambdas = enabled) }
    }

    // Trace settings
    fun setMaxTraceEvents(count: Int) {
        _uiState.update { it.copy(maxTraceEvents = count.coerceIn(100, 10000)) }
    }

    fun setTraceArguments(enabled: Boolean) {
        _uiState.update { it.copy(traceArguments = enabled) }
    }

    fun setTraceReturnValues(enabled: Boolean) {
        _uiState.update { it.copy(traceReturnValues = enabled) }
    }

    fun setTraceExceptions(enabled: Boolean) {
        _uiState.update { it.copy(traceExceptions = enabled) }
    }

    fun setTraceTiming(enabled: Boolean) {
        _uiState.update { it.copy(traceTiming = enabled) }
    }

    // Connection settings
    fun setDefaultPort(port: Int) {
        _uiState.update { it.copy(defaultPort = port.coerceIn(1024, 65535)) }
    }

    fun setConnectionTimeout(timeoutMs: Int) {
        _uiState.update { it.copy(connectionTimeoutMs = timeoutMs.coerceIn(1000, 30000)) }
    }

    // UI settings
    fun setFontSize(size: Int) {
        _uiState.update { it.copy(fontSize = size.coerceIn(10, 24)) }
    }

    /**
     * Resets all settings to their default values.
     */
    fun resetToDefaults() {
        _uiState.value = SettingsUiState()
    }
}
