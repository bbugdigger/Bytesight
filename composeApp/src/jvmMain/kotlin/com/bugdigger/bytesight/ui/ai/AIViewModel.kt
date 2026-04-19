package com.bugdigger.bytesight.ui.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bugdigger.ai.BytesightAgent
import com.bugdigger.ai.BytesightAgentServices
import com.bugdigger.bytesight.service.AgentConfigStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class ChatRole { USER, AGENT, SYSTEM }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: ChatRole,
    val text: String,
)

data class AIUiState(
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val isThinking: Boolean = false,
    val error: String? = null,
    val isConfigured: Boolean = false,
)

/**
 * ViewModel for the AI chat screen. Holds the chat log, forwards user prompts to the
 * [BytesightAgent] on a background coroutine, and appends the agent's answer back to
 * the log. The agent is rebuilt automatically when the user changes config in Settings.
 */
class AIViewModel(
    private val services: BytesightAgentServices,
    private val configStore: AgentConfigStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AIUiState())
    val uiState: StateFlow<AIUiState> = _uiState.asStateFlow()

    private val agent: BytesightAgent = BytesightAgent(configStore.config.value, services)

    private var currentJob: Job? = null

    init {
        viewModelScope.launch {
            configStore.config.collect { cfg ->
                agent.updateConfig(cfg)
                _uiState.update { it.copy(isConfigured = cfg.isUsable) }
            }
        }
    }

    fun setInput(text: String) {
        _uiState.update { it.copy(input = text) }
    }

    /** Send the current input (or a programmatic prompt) to the agent. */
    fun sendMessage(promptOverride: String? = null) {
        val text = (promptOverride ?: _uiState.value.input).trim()
        if (text.isEmpty() || _uiState.value.isThinking) return

        val userMsg = ChatMessage(role = ChatRole.USER, text = text)
        _uiState.update {
            it.copy(
                messages = it.messages + userMsg,
                input = "",
                isThinking = true,
                error = null,
            )
        }

        currentJob = viewModelScope.launch {
            val answer = runCatching { agent.ask(text) }
                .getOrElse { "Agent error: ${it.message ?: it::class.simpleName}" }
            _uiState.update {
                it.copy(
                    messages = it.messages + ChatMessage(role = ChatRole.AGENT, text = answer),
                    isThinking = false,
                )
            }
        }
    }

    /** Send a pre-populated prompt (used by "Ask AI" entry points on other screens). */
    fun sendPrompt(prompt: String) {
        _uiState.update { it.copy(input = prompt) }
        sendMessage(prompt)
    }

    fun clearChat() {
        currentJob?.cancel()
        _uiState.update { AIUiState(isConfigured = configStore.config.value.isUsable) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
