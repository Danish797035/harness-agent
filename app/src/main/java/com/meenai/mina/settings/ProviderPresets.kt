package com.meenai.mina.settings

import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.PromptCaching
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Commonly used service provider templates when adding models: only the interface type, Base URL and
 * initial check of capabilities are prefilled. The model name and key are filled in by the user, and
 * capabilities can also be changed. [sessionHeader] is the request header name required by the service
 * provider to put the conversation session number (see [com.meenai.mina.core.model.ModelSession]); if
 * not required, it is null. [selfHosted] is a service running on the user's own machine: [baseUrl] is
 * just the default port of the machine. The form allows the user to change it to the address of that
 * machine. The Key does not need to be filled in. [familyTokens] is a word (lower case) that is included
 * in this company’s own model names: When there are other models listed under Key, the default is to try
 * the own model first (see [ModelOptions.candidates]); if you host multiple models, the ones you run
 * will not be given.
 */
data class ProviderPreset(
    val label: String,
    val kind: ProviderConfig.Kind,
    val baseUrl: String,
    val capabilities: Capabilities,
    val sessionHeader: String? = null,
    val selfHosted: Boolean = false,
    val familyTokens: List<String> = emptyList(),
)

object ProviderPresets {
    private fun caps(vision: Boolean = false, context: Int = 64_000, caching: PromptCaching = PromptCaching.Implicit) =
        Capabilities(toolCalls = true, vision = vision, contextTokens = context, reasoning = false, promptCaching = caching)

    /** A template that matches the Base URL (if another template is selected when adding, or when the address is given directly in the test, the initial value of the capability is filled in based on the address); if not, it is null. */
    fun forBaseUrl(url: String): ProviderPreset? = all.firstOrNull { it.baseUrl.isNotEmpty() && url.trimEnd('/') == it.baseUrl.trimEnd('/') }

    /**
     * The host name is the same as the address of the service provider template (the path can be
     * different): When customizing the official address of a certain company, it is recommended to use
     * that template instead. Services run by yourself (local address) do not count. If the address is
     * not recognized or does not match, it is null.
     */
    fun forHost(url: String): ProviderPreset? {
        val host = url.trim().toHttpUrlOrNull()?.host ?: return null
        return all.firstOrNull { !it.selfHosted && it.baseUrl.isNotEmpty() && it.baseUrl.toHttpUrlOrNull()?.host == host }
    }

    val all: List<ProviderPreset> = listOf(
        ProviderPreset("DeepSeek", ProviderConfig.Kind.OpenAiCompat, "https://api.deepseek.com", caps(), familyTokens = listOf("deepseek")),
        ProviderPreset("Kimi", ProviderConfig.Kind.OpenAiCompat, "https://api.moonshot.cn/v1", caps(), familyTokens = listOf("kimi", "moonshot")),
        // Domestic site; thinking is written in <think> in the reply text by default, and remains in
        // history as it is.
        ProviderPreset("MiniMax", ProviderConfig.Kind.OpenAiCompat, "https://api.minimax.cn/v1", caps(vision = true, context = 200_000), familyTokens = listOf("minimax")),
        ProviderPreset("OpenRouter", ProviderConfig.Kind.OpenAiCompat, "https://openrouter.ai/api/v1", caps()),
        // Subscription system, one Key uses multiple models; which protocol and address each model uses
        // are specified one by one in the model list. The interface type here is just a fallback when it
        // is not specified. Each request must include the session number of the conversation, otherwise
        // it will be rejected (HTTP 400)
        ProviderPreset("OpenCode Go", ProviderConfig.Kind.OpenAiCompat, "https://opencode.ai/zen/go/v1", caps(), sessionHeader = "x-opencode-session"),
        ProviderPreset("Anthropic", ProviderConfig.Kind.Anthropic, "https://api.anthropic.com", caps(vision = true, context = 200_000), familyTokens = listOf("claude")),
        ProviderPreset("Gemini", ProviderConfig.Kind.Gemini, "https://generativelanguage.googleapis.com", caps(vision = true, context = 128_000), familyTokens = listOf("gemini")),
        // The official API uses Responses: the new main model calling tool must use Responses, Chat
        // Completions with tools will be rejected. Column models are also GET /models
        ProviderPreset("OpenAI", ProviderConfig.Kind.OpenAiResponses, "https://api.openai.com/v1", caps(vision = true, context = 400_000), familyTokens = listOf("gpt")),
        ProviderPreset("xAI", ProviderConfig.Kind.OpenAiCompat, "https://api.x.ai/v1", caps(vision = true, context = 256_000), familyTokens = listOf("grok")),
        // The column model interface indicates whether each model can call tools, view images, and
        // context length.
        ProviderPreset("Mistral", ProviderConfig.Kind.OpenAiCompat, "https://api.mistral.ai/v1", caps(vision = true, context = 128_000), familyTokens = listOf("mistral")),
        ProviderPreset("Groq", ProviderConfig.Kind.OpenAiCompat, "https://api.groq.com/openai/v1", caps(context = 128_000)),
        ProviderPreset("Z.ai", ProviderConfig.Kind.OpenAiCompat, "https://api.z.ai/api/paas/v4", caps(vision = true, context = 128_000)),
        ProviderPreset("ModelScope", ProviderConfig.Kind.OpenAiCompat, "https://api-inference.modelscope.ai/v1", caps(vision = true, context = 128_000)),
        // When running on your own machine: the address is the default port of the machine. When running
        // on a computer, change it to the LAN address of the computer; no Key required.
        ProviderPreset("Ollama", ProviderConfig.Kind.OpenAiCompat, "http://127.0.0.1:11434/v1", caps(), selfHosted = true),
        ProviderPreset("LM Studio", ProviderConfig.Kind.OpenAiCompat, "http://127.0.0.1:1234/v1", caps(), selfHosted = true),
        ProviderPreset("Custom", ProviderConfig.Kind.OpenAiCompat, "", caps()),
    )

    val custom: ProviderPreset get() = all.last()
}
