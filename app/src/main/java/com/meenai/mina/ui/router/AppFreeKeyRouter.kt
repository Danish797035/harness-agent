package com.meenai.mina.ui.router

import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.ListedModel
import com.meenai.mina.core.model.OpenAiCompatModel
import com.meenai.mina.core.model.PromptCaching
import com.meenai.mina.core.router.FreeKeyRouter
import com.meenai.mina.core.router.RouterCandidate
import com.meenai.mina.core.router.RouterUsageTracker
import com.meenai.mina.core.router.picker.AutoModelPicker
import com.meenai.mina.core.router.picker.ChatModelFilter
import com.meenai.mina.core.router.picker.CuratedTiers
import com.meenai.mina.core.router.picker.DifficultyRouter
import com.meenai.mina.core.router.picker.FileModelRankingsCacheStore
import com.meenai.mina.core.router.picker.HttpOpenRouterApiClient
import com.meenai.mina.core.router.picker.ModelCandidate
import com.meenai.mina.core.router.picker.ModelProbeService
import com.meenai.mina.core.router.picker.ModelRankingsRepository
import com.meenai.mina.core.router.picker.ModelRole
import com.meenai.mina.core.router.picker.ModelSizeParser
import com.meenai.mina.core.router.picker.ModelStatsTracker
import com.meenai.mina.core.router.preset.ModelScopePreset
import com.meenai.mina.core.router.preset.ZaiPreset
import com.meenai.mina.settings.Provider
import com.meenai.mina.settings.ProviderPresets
import com.meenai.mina.settings.ProviderStore
import okhttp3.OkHttpClient

/**
 * Helper to build FreeKeyRouter candidates from user-configured API keys.
 * Providers without configured API keys are skipped.
 */
object AppFreeKeyRouter {

    const val OPENROUTER_PROVIDER = "openrouter"
    const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"

    private val MODEL_SCOPE_NON_CHAT = listOf(
        "embedding", "embeddings", "rerank", "reranker", "bge",
        "image", "images", "flux", "stable-diffusion", "diffusers",
        "video", "audio", "tts", "stt", "asr", "whisper", "speech", "voice",
        "moderation", "guard", "reward", "discriminator",
        "image-edit", "image_edit", "imageedit",
        "image-gen", "image_gen", "imagegen", "image-generation", "image_generation",
        "t2i", "i2i", "text-to-image", "image-to-image"
    )

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
        val openRouterProvider = store.providers.value.firstOrNull { isOpenRouterProvider(it) }
        val modelScopeProvider = store.providers.value.firstOrNull { isModelScopeProvider(it) }

        return createCandidates(
            openRouterKey = openRouterKey,
            zaiKey = zaiKey,
            modelScopeKey = modelScopeKey,
            http = http,
            openRouterModels = openRouterProvider?.models,
            modelScopeModels = modelScopeProvider?.models,
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
        modelScopeModels: List<ListedModel>? = null,
    ): List<RouterCandidate> {
        val list = mutableListOf<RouterCandidate>()

        if (!openRouterKey.isNullOrBlank()) {
            list.addAll(createOpenRouterCandidates(openRouterKey.trim(), http, openRouterModels))
        }

        if (!zaiKey.isNullOrBlank()) {
            list.addAll(ZaiPreset.createCandidates(zaiKey.trim(), http))
        }

        if (!modelScopeKey.isNullOrBlank()) {
            list.addAll(createModelScopeCandidates(modelScopeKey.trim(), http, modelScopeModels))
        }

        return list
    }

    @Volatile
    private var sharedRankingsRepo: ModelRankingsRepository? = null

    fun initRankingsRepository(filesDir: java.io.File, http: OkHttpClient = OkHttpClient()): ModelRankingsRepository {
        val cacheFile = java.io.File(filesDir, "model-rankings.json")
        val repo = ModelRankingsRepository(
            cacheStore = FileModelRankingsCacheStore(cacheFile),
            httpClient = http,
        )
        sharedRankingsRepo = repo
        return repo
    }

    private val sharedProbeService: ModelProbeService by lazy {
        ModelProbeService()
    }

    private val sharedStatsTracker: ModelStatsTracker by lazy {
        ModelStatsTracker()
    }

    fun getRankingsRepository(): ModelRankingsRepository =
        sharedRankingsRepo ?: ModelRankingsRepository().also { sharedRankingsRepo = it }

    fun getProbeService(): ModelProbeService = sharedProbeService
    fun getStatsTracker(): ModelStatsTracker = sharedStatsTracker

    /**
     * Creates a new [FreeKeyRouter] with candidates derived from user's configured API keys.
     */
    fun createRouter(
        store: ProviderStore,
        http: OkHttpClient = OkHttpClient(),
        rankingsRepository: ModelRankingsRepository = getRankingsRepository(),
        probeService: ModelProbeService = sharedProbeService,
        statsTracker: ModelStatsTracker = sharedStatsTracker,
    ): FreeKeyRouter {
        val candidates = createCandidates(store, http)
        val picker = AutoModelPicker(
            apiClient = HttpOpenRouterApiClient(http),
            rankingsRepository = rankingsRepository,
            probeService = probeService,
            statsTracker = statsTracker,
        )
        val diffRouter = DifficultyRouter(picker = picker)
        return FreeKeyRouter(
            candidates = candidates,
            difficultyRouter = diffRouter,
            firstTokenTimeoutMs = 15_000L,
            rankingsRepository = rankingsRepository,
            probeService = probeService,
            statsTracker = statsTracker,
        )
    }

    /**
     * Auto-populates unconfigured roles in Settings (Main, Light, Vision) from Smart Model Picker.
     * Guarantees:
     * - User-chosen models are strictly preserved (never overwritten).
     * - Coding and Long roles remain exclusively inside Smart Picker (never added to Settings UI).
     */
    fun autoPopulateSettingsRoles(
        store: ProviderStore,
        picker: AutoModelPicker,
        candidates: List<ModelCandidate>? = null,
    ) {
        val currentRoles = store.roles.value
        val mainChosen = currentRoles.main != null
        val lightChosen = currentRoles.light !is com.meenai.mina.settings.RoleChoice.SameAsMain
        val visionChosen = currentRoles.vision !is com.meenai.mina.settings.RoleChoice.Off

        // If user explicitly chose all 3 roles, preserve everything
        if (mainChosen && lightChosen && visionChosen) return

        val pool = candidates ?: createCandidates(store).map { it.toModelCandidate() }
        if (pool.isEmpty()) return

        fun findProvider(candidate: ModelCandidate): Provider? {
            return store.providers.value.firstOrNull { p ->
                when (candidate.provider.lowercase()) {
                    "openrouter" -> isOpenRouterProvider(p)
                    "zai", "z.ai" -> isZaiProvider(p)
                    "modelscope" -> isModelScopeProvider(p)
                    else -> p.id == candidate.provider || p.name.contains(candidate.provider, ignoreCase = true)
                }
            }
        }

        // 1. Auto-fill Main if user has not chosen
        if (!mainChosen) {
            val bestMain = picker.pickFromCandidates(pool, ModelRole.Main)
            if (bestMain != null) {
                val p = findProvider(bestMain)
                if (p != null) {
                    val caps = Capabilities(
                        toolCalls = bestMain.supportsTools,
                        vision = bestMain.supportsImages,
                        contextTokens = bestMain.contextTokens,
                        reasoning = bestMain.supportsReasoning,
                        promptCaching = PromptCaching.Implicit,
                    )
                    store.use(com.meenai.mina.core.model.ModelRole.Main, p.id, bestMain.id, caps)
                }
            }
        }

        // 2. Auto-fill Light if user has not chosen (currently SameAsMain)
        if (!lightChosen) {
            val bestLight = picker.pickFromCandidates(pool, ModelRole.Light)
            if (bestLight != null) {
                val p = findProvider(bestLight)
                if (p != null) {
                    val caps = Capabilities(
                        toolCalls = bestLight.supportsTools,
                        vision = bestLight.supportsImages,
                        contextTokens = bestLight.contextTokens,
                        reasoning = bestLight.supportsReasoning,
                        promptCaching = PromptCaching.Implicit,
                    )
                    store.use(com.meenai.mina.core.model.ModelRole.Light, p.id, bestLight.id, caps)
                }
            }
        }

        // 3. Auto-fill Vision if user has not chosen (currently Off)
        if (!visionChosen) {
            val bestVision = picker.pickFromCandidates(pool, ModelRole.Vision)
            if (bestVision != null) {
                val p = findProvider(bestVision)
                if (p != null) {
                    val caps = Capabilities(
                        toolCalls = bestVision.supportsTools,
                        vision = true,
                        contextTokens = bestVision.contextTokens,
                        reasoning = bestVision.supportsReasoning,
                        promptCaching = PromptCaching.Implicit,
                    )
                    store.use(com.meenai.mina.core.model.ModelRole.Vision, p.id, bestVision.id, caps)
                }
            }
        }
    }

    fun isOpenRouterProvider(p: Provider): Boolean {
        if (p.signIn == ProviderStore.OPENROUTER_SIGN_IN) return true
        if (p.name.contains("OpenRouter", ignoreCase = true)) return true
        if (p.baseUrl.contains("openrouter.ai", ignoreCase = true)) return true
        val preset = ProviderPresets.forBaseUrl(p.baseUrl) ?: ProviderPresets.forHost(p.baseUrl)
        return preset?.label.equals("OpenRouter", ignoreCase = true)
    }

    fun isZaiProvider(p: Provider): Boolean {
        if (p.name.contains("Z.ai", ignoreCase = true)) return true
        if (p.name.contains("智谱", ignoreCase = true) ||
            p.name.contains("Zhipu", ignoreCase = true) ||
            p.name.contains("BigModel", ignoreCase = true) ||
            p.name.contains("GLM", ignoreCase = true)
        ) return true
        if (p.baseUrl.contains("api.z.ai", ignoreCase = true) ||
            p.baseUrl.contains("bigmodel.cn", ignoreCase = true)
        ) return true
        val preset = ProviderPresets.forBaseUrl(p.baseUrl) ?: ProviderPresets.forHost(p.baseUrl)
        return preset?.label.equals("Z.ai", ignoreCase = true)
    }

    fun isModelScopeProvider(p: Provider): Boolean {
        if (p.name.contains("ModelScope", ignoreCase = true)) return true
        if (p.name.contains("魔搭", ignoreCase = true)) return true
        if (p.baseUrl.contains("modelscope.ai", ignoreCase = true) || p.baseUrl.contains("modelscope.cn", ignoreCase = true)) return true
        val preset = ProviderPresets.forBaseUrl(p.baseUrl) ?: ProviderPresets.forHost(p.baseUrl)
        return preset?.label.equals("ModelScope", ignoreCase = true)
    }

    fun resolveOpenRouterKey(store: ProviderStore): String? {
        val p = store.providers.value.firstOrNull { isOpenRouterProvider(it) } ?: return null
        return store.providerKey(p.id)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun resolveZaiKey(store: ProviderStore): String? {
        val p = store.providers.value.firstOrNull { isZaiProvider(it) } ?: return null
        return store.providerKey(p.id)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun resolveModelScopeKey(store: ProviderStore): String? {
        val p = store.providers.value.firstOrNull { isModelScopeProvider(it) } ?: return null
        return store.providerKey(p.id)?.trim()?.takeIf { it.isNotEmpty() }
    }

    fun isModelScopeChatModel(m: ListedModel): Boolean {
        if (m.chat == false) return false
        if (ChatModelFilter.isExcluded(m.id)) return false
        val lower = m.id.lowercase()
        return MODEL_SCOPE_NON_CHAT.none { lower.contains(it) }
    }

    private fun createOpenRouterCandidates(
        apiKey: String,
        http: OkHttpClient,
        listedModels: List<ListedModel>? = null,
    ): List<RouterCandidate> {
        val freeListed = listedModels?.filter { it.id.endsWith(":free") && !ChatModelFilter.isExcluded(it.id) }.orEmpty()
        if (freeListed.isNotEmpty()) {
            return freeListed.sortedWith(
                compareByDescending<ListedModel> { ModelSizeParser.parse(it.id).totalParamsB ?: 20.0 }
                    .thenByDescending { it.contextTokens ?: 0 }
            ).map { listed ->
                val supportsImages = ChatModelFilter.supportsVision(listed.id, listed.vision == true)
                val caps = Capabilities(
                    toolCalls = listed.toolCalls ?: true,
                    vision = supportsImages,
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
                    if (supportsImages) add(ModelRole.Vision)
                    if (listed.id.contains("coder", ignoreCase = true)) add(ModelRole.Coding)
                    val size = ModelSizeParser.parse(listed.id).totalParamsB
                    if ((size != null && size in 0.1..10.0) || (listed.contextTokens ?: 0) <= 32_000) add(ModelRole.Light)
                }
                RouterCandidate(
                    id = listed.id,
                    provider = OPENROUTER_PROVIDER,
                    model = model,
                    curatedRank = 0,
                    isFree = true,
                    isBackup = false,
                    roles = roles,
                )
            }
        }

        // Curated default free OpenRouter models (fallback only if no live free models)
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
        listedModels: List<ListedModel>? = null,
    ): List<RouterCandidate> {
        val chatModels = listedModels?.filter { isModelScopeChatModel(it) }.orEmpty()
        if (chatModels.isNotEmpty()) {
            return chatModels.sortedWith(
                compareByDescending<ListedModel> { ModelSizeParser.parse(it.id).totalParamsB ?: 20.0 }
                    .thenByDescending { it.contextTokens ?: 0 }
            ).map { listed ->
                val supportsImages = ChatModelFilter.supportsVision(listed.id, listed.vision == true)
                val paramSize = ModelSizeParser.extractParameterSizeB(listed.id)

                val caps = Capabilities(
                    toolCalls = listed.toolCalls ?: true,
                    vision = supportsImages,
                    contextTokens = listed.contextTokens ?: 32_000,
                    reasoning = listed.reasoning ?: false,
                    promptCaching = PromptCaching.Implicit,
                )
                val model = OpenAiCompatModel(
                    baseUrl = ModelScopePreset.BASE_URL,
                    apiKey = apiKey,
                    model = listed.id,
                    capabilities = caps,
                    http = http,
                )
                val roles = buildSet {
                    if (supportsImages) add(ModelRole.Vision)
                    if (listed.id.contains("coder", ignoreCase = true)) add(ModelRole.Coding)
                    if (paramSize in 0.1..10.0 || ((listed.contextTokens ?: 0) <= 32_000 && paramSize < 14.0)) {
                        add(ModelRole.Light)
                    } else {
                        add(ModelRole.Main)
                    }
                }
                RouterCandidate(
                    id = listed.id,
                    provider = ModelScopePreset.PROVIDER_ID,
                    model = model,
                    curatedRank = 0,
                    isFree = true,
                    isBackup = false,
                    roles = roles,
                )
            }
        }

        // Hardcoded fallback list if no dynamic models provided
        val candidates = mutableListOf<RouterCandidate>()
        if (RouterUsageTracker.getTodayCalls("Qwen/Qwen2.5-72B-Instruct") > 0) {
            val qwen72b = OpenAiCompatModel(
                baseUrl = ModelScopePreset.BASE_URL,
                apiKey = apiKey,
                model = "Qwen/Qwen2.5-72B-Instruct",
                capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 32_000, reasoning = false, promptCaching = PromptCaching.Implicit),
                http = http,
            )
            candidates.add(
                RouterCandidate(
                    id = "Qwen/Qwen2.5-72B-Instruct",
                    provider = ModelScopePreset.PROVIDER_ID,
                    model = qwen72b,
                    curatedRank = 75,
                    isFree = true,
                    isBackup = false,
                    roles = setOf(ModelRole.Main),
                )
            )
        }
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

        candidates.add(
            RouterCandidate(
                id = "Qwen/Qwen2.5-7B-Instruct",
                provider = ModelScopePreset.PROVIDER_ID,
                model = qwen7b,
                curatedRank = 50,
                isFree = true,
                isBackup = false,
                roles = setOf(ModelRole.Light),
            )
        )
        candidates.add(
            RouterCandidate(
                id = "Qwen/Qwen2.5-VL-7B-Instruct",
                provider = ModelScopePreset.PROVIDER_ID,
                model = qwenVl,
                curatedRank = 55,
                isFree = true,
                isBackup = false,
                roles = setOf(ModelRole.Vision),
            )
        )
        return candidates
    }
}
