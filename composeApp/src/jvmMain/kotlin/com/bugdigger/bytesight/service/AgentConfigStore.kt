package com.bugdigger.bytesight.service

import com.bugdigger.ai.AgentConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Session-scoped store for the AI agent configuration (provider, model, API key,
 * temperature, maxIterations). Shared between the Settings screen (which edits it)
 * and the AI screen (which reads it to build the Koog agent).
 */
class AgentConfigStore {

    private val _config = MutableStateFlow(AgentConfig())
    val config: StateFlow<AgentConfig> = _config.asStateFlow()

    fun update(newConfig: AgentConfig) {
        _config.value = newConfig
    }
}
