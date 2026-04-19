package com.bugdigger.ai

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.config.AIAgentConfig
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.ext.agent.reActStrategy
import ai.koog.prompt.executor.clients.anthropic.AnthropicModels
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.clients.openrouter.OpenRouterModels
import ai.koog.prompt.executor.llms.all.simpleAnthropicExecutor
import ai.koog.prompt.executor.llms.all.simpleOllamaAIExecutor
import ai.koog.prompt.executor.llms.all.simpleOpenAIExecutor
import ai.koog.prompt.executor.llms.all.simpleOpenRouterExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.bugdigger.ai.prompts.SystemPrompts
import org.slf4j.LoggerFactory

/**
 * High-level facade over Koog's [AIAgent]. Wraps provider/model selection, tool
 * registration, and the system prompt. Consumers just call [ask] with a natural
 * language prompt and get the agent's final response.
 *
 * The underlying Koog agent is built lazily on first use so construction is cheap,
 * and the config can be swapped at runtime by calling [updateConfig] — the next
 * [ask] call will rebuild against the new settings.
 */
class BytesightAgent(
    initialConfig: AgentConfig,
    private val services: BytesightAgentServices,
) {

    private val logger = LoggerFactory.getLogger(BytesightAgent::class.java)

    @Volatile
    private var config: AgentConfig = initialConfig

    @Volatile
    private var cached: AIAgent<String, String>? = null

    @Volatile
    private var cachedConfigSignature: String? = null

    /** Replace the LLM configuration. The next [ask] rebuilds the underlying agent. */
    fun updateConfig(newConfig: AgentConfig) {
        config = newConfig
        cached = null
        cachedConfigSignature = null
    }

    /**
     * Ask the agent a question. The agent may call tools (decompile, list classes,
     * rename, etc.) any number of times before producing a final text answer.
     */
    suspend fun ask(prompt: String): String {
        val current = config
        if (!current.isUsable) {
            return "AI agent is not configured. Open Settings and enter an API key for " +
                "${current.provider.displayName}."
        }

        val agent = getOrBuildAgent(current)
        return runCatching { agent.run(prompt) }
            .onFailure { logger.error("Agent run failed", it) }
            .getOrElse { "Agent error: ${it.message ?: it::class.simpleName}" }
    }

    private fun getOrBuildAgent(c: AgentConfig): AIAgent<String, String> {
        val signature = configSignature(c)
        val existing = cached
        if (existing != null && cachedConfigSignature == signature) return existing

        val built = buildAgent(c)
        cached = built
        cachedConfigSignature = signature
        return built
    }

    private fun buildAgent(c: AgentConfig): AIAgent<String, String> {
        val executor = buildExecutor(c)
        val model = resolveModel(c)
        val tools = BytesightTools(services)
        val registry = ToolRegistry { tools(tools) }

        val agentConfig = AIAgentConfig.withSystemPrompt(
            SystemPrompts.REVERSE_ENGINEERING,
            model,
            "bytesight-agent",
            c.maxIterations,
        )

        return AIAgent(
            executor,
            agentConfig,
            reActStrategy(c.maxIterations),
            registry,
        )
    }

    private fun buildExecutor(c: AgentConfig): PromptExecutor = when (c.provider) {
        AIProvider.OPEN_ROUTER -> simpleOpenRouterExecutor(c.apiKey)
        AIProvider.OPENAI -> simpleOpenAIExecutor(c.apiKey)
        AIProvider.ANTHROPIC -> simpleAnthropicExecutor(c.apiKey)
        AIProvider.OLLAMA -> simpleOllamaAIExecutor()
    }

    private fun resolveModel(c: AgentConfig): LLModel = when (c.provider) {
        AIProvider.OPEN_ROUTER -> OpenRouterModels.Claude4_6Sonnet
        AIProvider.OPENAI -> OpenAIModels.Chat.GPT4o
        AIProvider.ANTHROPIC -> AnthropicModels.Sonnet_4_6
        AIProvider.OLLAMA -> LLModel(
            provider = LLMProvider.Ollama,
            id = c.model.ifBlank { AgentConfig.DEFAULT_OLLAMA_MODEL },
            capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools),
        )
    }

    private fun configSignature(c: AgentConfig): String =
        "${c.provider}:${c.model}:${c.temperature}:${c.maxIterations}:${c.apiKey.hashCode()}"
}
