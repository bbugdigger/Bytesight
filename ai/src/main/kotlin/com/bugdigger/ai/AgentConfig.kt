package com.bugdigger.ai

/**
 * Configuration for the Bytesight AI agent. Selects the LLM provider, model,
 * authentication, and sampling parameters. Defaults target OpenRouter because
 * one API key there unlocks Claude / GPT / Gemini / DeepSeek.
 */
data class AgentConfig(
    val provider: AIProvider = AIProvider.OPEN_ROUTER,
    val apiKey: String = "",
    val model: String = DEFAULT_OPEN_ROUTER_MODEL,
    val temperature: Double = 0.3,
    val maxIterations: Int = 20,
) {
    /** True when the config has everything needed to actually call the LLM. */
    val isUsable: Boolean
        get() = model.isNotBlank() && (!provider.requiresApiKey || apiKey.isNotBlank())

    companion object {
        const val DEFAULT_OPEN_ROUTER_MODEL = "anthropic/claude-sonnet-4-6"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o"
        const val DEFAULT_ANTHROPIC_MODEL = "claude-sonnet-4-6"
        const val DEFAULT_OLLAMA_MODEL = "llama3.1"
    }
}

/** Supported LLM providers. The Koog framework has first-class clients for all of these. */
enum class AIProvider(val displayName: String, val requiresApiKey: Boolean) {
    OPEN_ROUTER("OpenRouter", true),
    OPENAI("OpenAI", true),
    ANTHROPIC("Anthropic", true),
    OLLAMA("Ollama (local)", false),
}
