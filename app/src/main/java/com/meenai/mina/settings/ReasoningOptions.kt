package com.meenai.mina.settings

import com.meenai.mina.core.model.ListedModel

/**
 * Think gears. The service provider list specifies the gear model, and the four protocols (Chat
 * Completions, Messages, generateContent, and Responses) are all given according to it (the Anthropic
 * list specifies the gears and thinking methods supported by each model item by item, see ModelCatalog);
 * when not specified, only the Responses family gives the general manual gear (with a prompt, the actual
 * support situation is subject to the server), No guessing for other protocols: each model recognizes
 * different gears and writing methods. If you guess wrong, every request will be rejected.
 */
data class ReasoningOptions(val levels: List<String>, val fromProvider: Boolean) {
    companion object {
        // If the list does not specify the gear, it can be selected manually, and it does not claim that
        // every model supports it; the interface will explain it, and the default is never specified.
        private val manualLevels = listOf("none", "minimal", "low", "medium", "high", "xhigh", "max")

        fun of(config: ProviderConfig, listed: ListedModel?): ReasoningOptions? {
            listed?.reasoningEfforts?.let { return ReasoningOptions(it, fromProvider = true) }
            // Still returns an empty option so that the old saved gears can be restored to default in
            // the interface.
            if (listed?.reasoning == false) return ReasoningOptions(emptyList(), fromProvider = true)
            return when (ProviderStore.adapterFor(config, listed?.endpoint).first) {
                ProviderConfig.Kind.OpenAiResponses, ProviderConfig.Kind.ChatGpt, ProviderConfig.Kind.Xai ->
                    ReasoningOptions(manualLevels, fromProvider = false)
                ProviderConfig.Kind.OpenAiCompat, ProviderConfig.Kind.Anthropic, ProviderConfig.Kind.Gemini, ProviderConfig.Kind.GeminiWeb -> null
            }
        }
    }
}
