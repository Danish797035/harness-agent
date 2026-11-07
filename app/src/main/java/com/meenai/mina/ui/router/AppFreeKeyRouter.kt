package com.meenai.mina.ui.router

import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.ListedModel
import com.meenai.mina.core.model.OpenAiCompatModel
import com.meenai.mina.core.model.PromptCaching
import com.meenai.mina.core.router.FreeKeyRouter
import com.meenai.mina.core.router.RouterCandidate
import com.meenai.mina.core.router.picker.ModelRole
import com.meenai.mina.core.router.preset.ModelScopePreset
import com.meenai.mina.core.router.preset.ZaiPreset
import com.meenai.mina.settings.ProviderStore
import okhttp3.OkHttpClient

/**
 * Helper to build FreeKeyRouter candidates from user-configured API keys.
 * Providers without configured API keys are skipped.
 */
object AppFreeKeyRouter {

    const val OPENROUTER_PROVIDER = "openrouter"
    const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"

    /**
     * Resolves API keys and creates candidate models from user settings.
     */
    fun createCandidates(
        store: ProviderStore,
        http: OkHttpClient = OkHttpClient(),
    ): List<RouterCandidate> {
        val openRouterKey = resolveOpenRouterKey(store)
        val zaiKey = resolveZaiKey(store)
        val modelScopeKey = resolveModelScopeKey(store)
        val openRouterProvider = store.providers.value.firstOrNull {
            it.name.contains("OpenRouter", ignoreCase = true) || it.baseUrl.contains("openrouter.ai", ignoreCase = true)
        }

        return createCandidates(
            openRouterKey = openRouterKey,
            zaiKey = zaiKey,
            modelScopeKey = modelScopeKey,
            http = http,
            openRouterModels = openRouterProvider?.models,
        )
    }

    /**
     * Builds candidate models from explicit API keys. Skips providers where key is null or blank.
     */
    fun createCandidates(
        openRouterKey: String? = null,
        zaiKey: String? = null,
        modelScopeKey: String? = null,
        http: OkHttpClient = OkHttpClient(),
        openRouterModels: List<ListedModel>? = null,
    ): List<RouterCandidate> {
        val list = mutableListOf<RouterCandidate>()

        if (!openRouterKey.isNullOrBlank()) {
            list.addAll(createOpenRouterCandidates(openRouterKey.trim(), http, openRouterModels))
        }

        if (!zaiKey.isNullOrBlank()) {
            list.addAll(ZaiPreset.createCandidates(zaiKey.trim(), http))
        }

        if (!modelScopeKey.isNullOrBlank()) {
            list.addAll(createModelScopeCandidates(modelScopeKey.trim(), http))
        }

        return list
    }

    /**
     * Creates a new [FreeKeyRouter] with candidates derived from user's configured API keys.
     */
    fun createRouter(
        store: ProviderStore,
        http: OkHttpClient = OkHttpClient(),
    ): FreeKeyRouter {
        val candidates = createCandidates(store, http)
        return FreeKeyRouter(candidates = candidates)
    }

    fun resolveOpenRouterKey(store: ProviderStore): String? =
        resolveKey(store, providerName = "OpenRouter", urlSubstring = "openrouter.ai")

    fun resolveZaiKey(store: ProviderStore): String? =
        resolveKey(store, providerName = "Z.ai", urlSubstring = "api.z.ai")

    fun resolveModelScopeKey(store: ProviderStore): String? =
        resolveKey(store, providerName = "ModelScope", urlSubstring = "modelscope.ai")

    private fun resolveKey(store: ProviderStore, providerName: String, urlSubstring: String): String? {
        val p = store.providers.value.firstOrNull {
            it.name.contains(providerName, ignoreCase = true) ||
                it.baseUrl.contains(urlSubstring, ignoreCase = true)
        } ?: return null
        return store.providerKey(p.id)?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun createOpenRouterCandidates(
        apiKey: String,
        http: OkHttpClient,
        listedModels: List<ListedModel>? = null,
    ): List<RouterCandidate> {
        val freeListed = listedModels?.filter { it.id.endsWith(":free") }.orEmpty()
        if (freeListed.isNotEmpty()) {
            return freeListed.map { listed ->
                val caps = Capabilities(
                    toolCalls = listed.toolCalls ?: true,
                    vision = listed.vision == true,
                    contextTokens = listed.contextTokens ?: 64_000,
                    reasoning = listed.reasoning ?: false,
                    promptCaching = PromptCaching.Implicit,
                )
                val model = OpenAiCompatModel(
                    baseUrl = OPENROUTER_BASE_URL,
                    apiKey = apiKey,
                    model = listed.id,
                    capabilities = caps,
                    http = http,
                )
                val roles = buildSet {
                    add(ModelRole.Main)
                    if (listed.vision == true) add(ModelRole.Vision)
                    if ((listed.contextTokens ?: 0) <= 32_000) add(ModelRole.Light)
                }
                RouterCandidate(
                    id = listed.id,
                    provider = OPENROUTER_PROVIDER,
                    model = model,
                    curatedRank = 75,
                    isFree = true,
                    isBackup = false,
                    roles = roles,
                )
            }
        }

        // Curated default free OpenRouter models
        val flashModel = OpenAiCompatModel(
            baseUrl = OPENROUTER_BASE_URL,
            apiKey = apiKey,
            model = "google/gemini-2.0-flash-exp:free",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 128_000, reasoning = false, promptCaching = PromptCaching.Implicit),
            http = http,
        )
        val llama70bModel = OpenAiCompatModel(
            baseUrl = OPENROUTER_BASE_URL,
            apiKey = apiKey,
            model = "meta-llama/llama-3.3-70b-instruct:free",
            capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 128_000, reasoning = false, promptCaching = PromptCaching.Implicit),
            http = http,
        )
        val llama8bModel = OpenAiCompatModel(
            baseUrl = OPENROUTER_BASE_URL,
            apiKey = apiKey,
            model = "meta-llama/llama-3.1-8b-instruct:free",
            capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 128_000, reasoning = false, promptCaching = PromptCaching.Implicit),
            http = http,
        )
        val qwenCoderModel = OpenAiCompatModel(
            baseUrl = OPENROUTER_BASE_URL,
            apiKey = apiKey,
            model = "qwen/qwen-2.5-coder-32b-instruct:free",
            capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 32_000, reasoning = false, promptCaching = PromptCaching.Implicit),
            http = http,
        )

        return listOf(
            RouterCandidate(
                id = "google/gemini-2.0-flash-exp:free",
                provider = OPENROUTER_PROVIDER,
                model = flashModel,
                curatedRank = 90,
                isFree = true,
                isBackup = false,
                roles = setOf(ModelRole.Main, ModelRole.Vision),
            ),
            RouterCandidate(
                id = "meta-llama/llama-3.3-70b-instruct:free",
                provider = OPENROUTER_PROVIDER,
                model = llama70bModel,
                curatedRank = 80,
                isFree = true,
                isBackup = false,
                roles = setOf(ModelRole.Main),
            ),
            RouterCandidate(
                id = "meta-llama/llama-3.1-8b-instruct:free",
                provider = OPENROUTER_PROVIDER,
                model = llama8bModel,
                curatedRank = 60,
                isFree = true,
                isBackup = false,
                roles = setOf(ModelRole.Light),
            ),
            RouterCandidate(
                id = "qwen/qwen-2.5-coder-32b-instruct:free",
                provider = OPENROUTER_PROVIDER,
                model = qwenCoderModel,
                curatedRank = 70,
                isFree = true,
                isBackup = false,
                roles = setOf(ModelRole.Main),
            ),
        )
    }

    private fun createModelScopeCandidates(
        apiKey: String,
        http: OkHttpClient,
    ): List<RouterCandidate> {
        val qwen72b = OpenAiCompatModel(
            baseUrl = ModelScopePreset.BASE_URL,
            apiKey = apiKey,
            model = "Qwen/Qwen2.5-72B-Instruct",
            capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 32_000, reasoning = false, promptCaching = PromptCaching.Implicit),
            http = http,
        )
        val qwen7b = OpenAiCompatModel(
            baseUrl = ModelScopePreset.BASE_URL,
            apiKey = apiKey,
            model = "Qwen/Qwen2.5-7B-Instruct",
            capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 32_000, reasoning = false, promptCaching = PromptCaching.Implicit),
            http = http,
        )
        val qwenVl = OpenAiCompatModel(
            baseUrl = ModelScopePreset.BASE_URL,
            apiKey = apiKey,
            model = "Qwen/Qwen2.5-VL-7B-Instruct",
            capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 32_000, reasoning = false, promptCaching = PromptCaching.Implicit),
            http = http,
        )

        return listOf(
            RouterCandidate(
                id = "Qwen/Qwen2.5-72B-Instruct",
                provider = ModelScopePreset.PROVIDER_ID,
                model = qwen72b,
                curatedRank = 75,
                isFree = true,
                isBackup = true, // ModelScope is lowest-priority backup
                roles = setOf(ModelRole.Main),
            ),
            RouterCandidate(
                id = "Qwen/Qwen2.5-7B-Instruct",
                provider = ModelScopePreset.PROVIDER_ID,
                model = qwen7b,
                curatedRank = 50,
                isFree = true,
                isBackup = true,
                roles = setOf(ModelRole.Light),
            ),
            RouterCandidate(
                id = "Qwen/Qwen2.5-VL-7B-Instruct",
                provider = ModelScopePreset.PROVIDER_ID,
                model = qwenVl,
                curatedRank = 55,
                isFree = true,
                isBackup = true,
                roles = setOf(ModelRole.Vision),
            ),
        )
    }
}
