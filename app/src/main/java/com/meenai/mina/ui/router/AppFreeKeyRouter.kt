package com.meenai.mina.ui.router

import com.meenai.mina.core.gemini.GeminiUsage
import com.meenai.mina.core.gemini.GeminiWarmupOutcome
import com.meenai.mina.core.gemini.GeminiWebClient
import com.meenai.mina.core.gemini.GeminiWebSession
import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.GeminiModel
import com.meenai.mina.core.model.GeminiWebModel
import com.meenai.mina.core.model.ListedModel
import com.meenai.mina.core.model.OpenAiCompatModel
import com.meenai.mina.core.net.NetProxy
import com.meenai.mina.settings.ProviderConfig
import com.meenai.mina.core.model.PromptCaching
import com.meenai.mina.core.router.FreeKeyRouter
import com.meenai.mina.core.router.RouterCandidate
import com.meenai.mina.core.router.RouterUsageTracker
import com.meenai.mina.core.router.picker.AutoModelPicker
import com.meenai.mina.core.router.picker.ChatModelFilter
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
import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import com.meenai.mina.core.router.ModelCooldownStore
import com.meenai.mina.core.router.picker.FileModelStatsStore
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
import okhttp3.Request

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
        http: OkHttpClient = sharedApiHttp,
        reasoningEffortProvider: (() -> String?)? = null,
    ): List<RouterCandidate> {
        val list = mutableListOf<RouterCandidate>()
        store.repairGeminiWebBindings()

        store.providers.value.forEach { p ->
            when {
                isGeminiWebProvider(p) -> {
                    val session = store.geminiWebSession(p.id)
                    if (session != null && session.signedIn) {
                        list.addAll(
                            createGeminiWebCandidates(
                                session = session,
                                http = http,
                                listedModels = p.models,
                                reasoningEffortProvider = reasoningEffortProvider,
                            ) { newSess ->
                                store.updateGeminiWebSession(p.id, newSess)
                            }
                        )
                    }
                }
                else -> {
                    val key = store.providerKey(p.id)?.trim()
                    val preset = ProviderPresets.forBaseUrl(p.baseUrl) ?: ProviderPresets.forHost(p.baseUrl)
                    val isSelfHosted = preset?.selfHosted == true
                    val hasAccountAuth = when (p.kind) {
                        ProviderConfig.Kind.ChatGpt -> store.chatGptSession(p.id)?.signedIn == true
                        ProviderConfig.Kind.Xai -> store.accountSession(p.id)?.signedIn == true
                        else -> false
                    }
                    if (!key.isNullOrBlank() || isSelfHosted || hasAccountAuth) {
                        when {
                            isOpenRouterProvider(p) -> list.addAll(createOpenRouterCandidates(key.orEmpty(), http, p.models))
                            isZaiProvider(p) -> list.addAll(ZaiPreset.createCandidates(key.orEmpty(), http))
                            isModelScopeProvider(p) -> list.addAll(createModelScopeCandidates(key.orEmpty(), http, p.models))
                            isGeminiProvider(p) -> list.addAll(createGeminiCandidates(key.orEmpty(), http, p.models, p.baseUrl))
                            isGroqProvider(p) -> list.addAll(createGroqCandidates(key.orEmpty(), http, p.models, p.baseUrl))
                            isDeepSeekProvider(p) -> list.addAll(createDeepSeekCandidates(key.orEmpty(), http, p.models, p.baseUrl))
                            isKimiProvider(p) -> list.addAll(createKimiCandidates(key.orEmpty(), http, p.models, p.baseUrl))
                            isMiniMaxProvider(p) -> list.addAll(createMiniMaxCandidates(key.orEmpty(), http, p.models, p.baseUrl))
                            else -> list.addAll(createStoreBackedCandidates(store, p, reasoningEffortProvider))
                        }
                    }
                }
            }
        }

        return list
    }

    /**
     * Builds candidate models from explicit API keys. Skips providers where key is null or blank.
     */
    fun createCandidates(
        openRouterKey: String? = null,
        zaiKey: String? = null,
        modelScopeKey: String? = null,
        geminiKey: String? = null,
        groqKey: String? = null,
        deepSeekKey: String? = null,
        kimiKey: String? = null,
        miniMaxKey: String? = null,
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

        if (!geminiKey.isNullOrBlank()) {
            list.addAll(createGeminiCandidates(geminiKey.trim(), http))
        }

        if (!groqKey.isNullOrBlank()) {
            list.addAll(createGroqCandidates(groqKey.trim(), http))
        }

        if (!deepSeekKey.isNullOrBlank()) {
            list.addAll(createDeepSeekCandidates(deepSeekKey.trim(), http))
        }

        if (!kimiKey.isNullOrBlank()) {
            list.addAll(createKimiCandidates(kimiKey.trim(), http))
        }

        if (!miniMaxKey.isNullOrBlank()) {
            list.addAll(createMiniMaxCandidates(miniMaxKey.trim(), http))
        }

        return list
    }

    private val sharedApiHttp: OkHttpClient by lazy {
        NetProxy.shared.client(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
        )
    }

    /**
     * Non-blocking startup warm-up for the currently active model/provider:
     * - For `GeminiWeb`: seeds cookies, refreshes `SNlM0e` / `cfb2h` / rotated cookies, validates `GetUserStatus`,
     *   pre-warms the `gemini.google.com` HTTP/2 + TLS socket, and fetches live quota %.
     * - For API providers: pre-warms TLS/HTTP connection (and fetches OpenRouter key quota when applicable).
     */
    suspend fun warmUpActiveConnection(
        store: ProviderStore,
        router: FreeKeyRouter,
        openRouterKeyClient: OpenRouterKeyClient = HttpOpenRouterKeyClient(sharedApiHttp),
        http: OkHttpClient = sharedApiHttp,
    ): ConnectionWarmupResult {
        val activeCandidate = router.activeCandidate
        val mainCfg = store.configFor(com.meenai.mina.core.model.ModelRole.Main)
        val activeProvider = when {
            activeCandidate != null -> store.providers.value.firstOrNull { providerMatches(it, activeCandidate.provider) }
            mainCfg != null -> store.provider(mainCfg.providerId)
            else -> store.providers.value.firstOrNull()
        }

        if (activeCandidate == null && mainCfg == null && activeProvider == null) {
            return ConnectionWarmupResult(ConnectionStatus.Idle)
        }

        val isGeminiWeb = activeCandidate?.provider.equals("gemini_web", ignoreCase = true) ||
            (activeProvider != null && isGeminiWebProvider(activeProvider))

        if (isGeminiWeb) {
            val webProvider = activeProvider ?: store.geminiWebProvider()
                ?: return ConnectionWarmupResult(ConnectionStatus.SignInRequired, "Google Gemini not connected")
            val session = store.geminiWebSession(webProvider.id)
                ?: return ConnectionWarmupResult(ConnectionStatus.SignInRequired, "Sign in to Google Gemini")
            return when (val outcome = GeminiWebClient.warmUpSession(GeminiWebClient.geminiWebHttp, session)) {
                is GeminiWarmupOutcome.Ready -> {
                    if (outcome.session != session) {
                        store.updateGeminiWebSession(webProvider.id, outcome.session)
                    }
                    clearGeminiWebAuthFailure(router.cooldownStore)
                    val usageFetch = runCatching {
                        GeminiWebClient.fetchUsage(GeminiWebClient.geminiWebHttp, outcome.session)
                    }.getOrNull()
                    if (usageFetch != null && usageFetch.session != outcome.session) {
                        store.updateGeminiWebSession(webProvider.id, usageFetch.session)
                    }
                    ConnectionWarmupResult(
                        status = ConnectionStatus.Connected,
                        geminiUsage = usageFetch?.usage,
                    )
                }
                is GeminiWarmupOutcome.SignInExpired -> {
                    ConnectionWarmupResult(ConnectionStatus.SignInRequired, outcome.reason)
                }
                is GeminiWarmupOutcome.NetworkError -> {
                    ConnectionWarmupResult(ConnectionStatus.Error, outcome.reason)
                }
            }
        }

        val providerName = activeCandidate?.provider ?: activeProvider?.name ?: mainCfg?.name.orEmpty()
        if (router.cooldownStore.isKeyBad(providerName) ||
            (activeProvider != null && router.cooldownStore.isKeyBad(activeProvider.id))
        ) {
            return ConnectionWarmupResult(ConnectionStatus.Error, "API key is invalid or expired")
        }

        if (providerName.equals(OPENROUTER_PROVIDER, ignoreCase = true) ||
            (activeProvider != null && isOpenRouterProvider(activeProvider))
        ) {
            val key = resolveOpenRouterKey(store)
                ?: return ConnectionWarmupResult(ConnectionStatus.SignInRequired, "OpenRouter API key missing")
            val keyInfo = openRouterKeyClient.getKeyInfo(key)
            if (keyInfo != null) {
                return ConnectionWarmupResult(ConnectionStatus.Connected, openRouterInfo = keyInfo)
            }
            return preWarmHttpEndpoint(http, "$OPENROUTER_BASE_URL/models", "Bearer $key", headerName = "Authorization")
        }

        val baseUrl = activeProvider?.baseUrl?.takeIf { it.isNotBlank() }
            ?: mainCfg?.baseUrl?.takeIf { it.isNotBlank() }
            ?: return ConnectionWarmupResult(ConnectionStatus.Connected)
        val kind = activeProvider?.kind ?: mainCfg?.kind ?: ProviderConfig.Kind.OpenAiCompat
        val token = when (kind) {
            ProviderConfig.Kind.ChatGpt -> activeProvider?.id?.let { store.chatGptSession(it)?.accessToken }
            ProviderConfig.Kind.Xai -> activeProvider?.id?.let { store.accountSession(it)?.accessToken }
            else -> activeProvider?.id?.let { store.providerKey(it) }
        }?.trim()

        val targetUrl = when (kind) {
            ProviderConfig.Kind.Gemini -> baseUrl.trimEnd('/') + "/v1beta/models"
            ProviderConfig.Kind.Anthropic -> baseUrl.trimEnd('/') + "/v1/models"
            else -> baseUrl.trimEnd('/') + "/models"
        }
        val (authHeader, authValue) = when {
            token.isNullOrBlank() -> null to null
            kind == ProviderConfig.Kind.Gemini -> "x-goog-api-key" to token
            kind == ProviderConfig.Kind.Anthropic -> "x-api-key" to token
            else -> "Authorization" to "Bearer $token"
        }
        val extraHeaders = if (kind == ProviderConfig.Kind.Anthropic) {
            mapOf("anthropic-version" to "2023-06-01")
        } else {
            emptyMap()
        }
        return preWarmHttpEndpoint(http, targetUrl, authValue, authHeader, extraHeaders)
    }

    private fun preWarmHttpEndpoint(
        http: OkHttpClient,
        url: String,
        authValue: String?,
        headerName: String?,
        extraHeaders: Map<String, String> = emptyMap(),
    ): ConnectionWarmupResult {
        val warmHttp = http.newBuilder().callTimeout(8, TimeUnit.SECONDS).build()
        val reqBuilder = Request.Builder().url(url).get()
        if (!headerName.isNullOrBlank() && !authValue.isNullOrBlank()) {
            reqBuilder.header(headerName, authValue)
        }
        extraHeaders.forEach { (k, v) -> reqBuilder.header(k, v) }
        return try {
            warmHttp.newCall(reqBuilder.build()).execute().use { resp ->
                when (resp.code) {
                    401, 403 -> ConnectionWarmupResult(ConnectionStatus.SignInRequired, "Authentication failed (HTTP ${resp.code})")
                    else -> ConnectionWarmupResult(ConnectionStatus.Connected)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ConnectionWarmupResult(ConnectionStatus.Error, e.message ?: "Connection failed")
        }
    }

    fun providerMatches(p: Provider, candidateProvider: String): Boolean {
        val cand = candidateProvider.lowercase()
        return p.id.equals(candidateProvider, ignoreCase = true) ||
            p.name.equals(candidateProvider, ignoreCase = true) ||
            (cand == "gemini_web" && isGeminiWebProvider(p)) ||
            (cand == "openrouter" && isOpenRouterProvider(p)) ||
            ((cand == "zai" || cand == "z.ai") && isZaiProvider(p)) ||
            (cand == "modelscope" && isModelScopeProvider(p)) ||
            (cand == "gemini" && isGeminiProvider(p)) ||
            (cand == "groq" && isGroqProvider(p)) ||
            (cand == "deepseek" && isDeepSeekProvider(p)) ||
            (cand == "kimi" && isKimiProvider(p)) ||
            (cand == "minimax" && isMiniMaxProvider(p)) ||
            (cand == "anthropic" && p.kind == ProviderConfig.Kind.Anthropic) ||
            (cand == "openai" && p.kind == ProviderConfig.Kind.OpenAiResponses) ||
            (cand == "chatgpt" && p.kind == ProviderConfig.Kind.ChatGpt) ||
            (cand == "xai" && p.kind == ProviderConfig.Kind.Xai)
    }

    fun canonicalProviderId(p: Provider): String = when {
        isGeminiWebProvider(p) -> "gemini_web"
        isOpenRouterProvider(p) -> OPENROUTER_PROVIDER
        isZaiProvider(p) -> "zai"
        isModelScopeProvider(p) -> ModelScopePreset.PROVIDER_ID
        isGeminiProvider(p) -> "gemini"
        isGroqProvider(p) -> "groq"
        isDeepSeekProvider(p) -> "deepseek"
        isKimiProvider(p) -> "kimi"
        isMiniMaxProvider(p) -> "minimax"
        p.kind == ProviderConfig.Kind.Anthropic -> "anthropic"
        p.kind == ProviderConfig.Kind.OpenAiResponses -> "openai"
        p.kind == ProviderConfig.Kind.ChatGpt -> "chatgpt"
        p.kind == ProviderConfig.Kind.Xai -> "xai"
        else -> p.id
    }

    private fun createStoreBackedCandidates(
        store: ProviderStore,
        p: Provider,
        reasoningEffortProvider: (() -> String?)?,
    ): List<RouterCandidate> {
        val preset = ProviderPresets.forBaseUrl(p.baseUrl) ?: ProviderPresets.forHost(p.baseUrl)
        val isSelfHosted = preset?.selfHosted == true
        val providerId = canonicalProviderId(p)
        val configuredIds = store.configs.value.filter { it.providerId == p.id }.map { it.model }
        val listedById = p.models.orEmpty().associateBy { it.id }
        val orderedIds = linkedSetOf<String>().apply {
            configuredIds.forEach { add(it) }
            p.typedModels.forEach { add(it) }
            p.models.orEmpty()
                .filter { it.chat != false && it.usable && !ChatModelFilter.isExcluded(it.id) }
                .take(24)
                .forEach { add(it.id) }
        }
        val activeEffort = reasoningEffortProvider?.invoke() ?: store.roles.value.reasoningEffort(com.meenai.mina.core.model.ModelRole.Main)
        return orderedIds.map { modelId ->
            val listed = listedById[modelId]
            val caps = Capabilities(
                toolCalls = listed?.toolCalls ?: preset?.capabilities?.toolCalls ?: true,
                vision = listed?.vision ?: preset?.capabilities?.vision ?: ChatModelFilter.supportsVision(modelId, false),
                contextTokens = listed?.contextTokens ?: preset?.capabilities?.contextTokens ?: 128_000,
                reasoning = listed?.reasoning ?: preset?.capabilities?.reasoning ?: (p.kind == ProviderConfig.Kind.Anthropic || p.kind == ProviderConfig.Kind.OpenAiResponses || p.kind == ProviderConfig.Kind.ChatGpt || p.kind == ProviderConfig.Kind.Xai),
                promptCaching = preset?.capabilities?.promptCaching ?: PromptCaching.Implicit,
            )
            val effortForModel = activeEffort?.takeIf { e ->
                val allowed = listed?.reasoningEfforts
                when {
                    allowed != null -> e in allowed
                    p.kind == ProviderConfig.Kind.OpenAiResponses || p.kind == ProviderConfig.Kind.ChatGpt || p.kind == ProviderConfig.Kind.Xai -> true
                    p.kind == ProviderConfig.Kind.Anthropic -> caps.reasoning && e in listOf("low", "medium", "high")
                    else -> false
                }
            }
            val model = store.buildCandidateModel(
                provider = p,
                modelId = modelId,
                capabilities = caps,
                endpoint = listed?.endpoint,
                reasoningEffort = effortForModel,
                thinkingTypes = listed?.thinkingTypes,
            )
            RouterCandidate(
                id = modelId,
                provider = providerId,
                model = model,
                curatedRank = 0,
                isFree = isSelfHosted,
                roles = buildSet {
                    add(ModelRole.Main)
                    if (caps.vision) add(ModelRole.Vision)
                    if (caps.contextTokens >= 64_000) add(ModelRole.Long)
                },
            )
        }
    }

    private var sharedRankingsRepo: ModelRankingsRepository? = null
    private var sharedProbeService: ModelProbeService? = null
    private var sharedStatsTracker: ModelStatsTracker? = null
    private var sharedCooldownStore: ModelCooldownStore? = null

    fun getRankingsRepository(): ModelRankingsRepository =
        sharedRankingsRepo ?: ModelRankingsRepository().also { sharedRankingsRepo = it }

    fun getProbeService(): ModelProbeService =
        sharedProbeService ?: ModelProbeService().also { sharedProbeService = it }

    fun getStatsTracker(): ModelStatsTracker =
        sharedStatsTracker ?: ModelStatsTracker().also { sharedStatsTracker = it }

    fun getCooldownStore(): ModelCooldownStore =
        sharedCooldownStore ?: ModelCooldownStore().also { sharedCooldownStore = it }

    /**
     * A successful Gemini Web sign-in proves the session again. The router marks
     * `gemini_web` as a bad key on [com.meenai.mina.core.model.ModelError.SignInExpired],
     * and that flag is persisted; without clearing it, re-login still leaves every
     * Gemini Web model filtered out and the router quietly answers with another
     * provider (usually flash-lite).
     */
    fun clearGeminiWebAuthFailure(store: ModelCooldownStore = getCooldownStore()) {
        store.clearBadKey("gemini_web")
    }

    fun initRankingsRepository(filesDir: File, http: OkHttpClient = OkHttpClient()): ModelRankingsRepository {
        val cacheFile = File(filesDir, "model-rankings.json")
        val repo = ModelRankingsRepository(cacheStore = FileModelRankingsCacheStore(cacheFile), httpClient = http)
        sharedRankingsRepo = repo
        return repo
    }

    fun initStatsTracker(filesDir: File): ModelStatsTracker {
        val statsFile = File(filesDir, "model-stats.json")
        val tracker = ModelStatsTracker(store = FileModelStatsStore(statsFile))
        sharedStatsTracker = tracker
        return tracker
    }

    fun initCooldownStore(context: Context): ModelCooldownStore {
        val prefs = context.getSharedPreferences("model_cooldown_store", Context.MODE_PRIVATE)
        val store = ModelCooldownStore(
            persistCallback = { json ->
                prefs.edit().putString("cooldown_state", json).apply()
            }
        )
        val saved = prefs.getString("cooldown_state", null)
        if (!saved.isNullOrBlank()) {
            store.loadFromJson(saved)
        }
        sharedCooldownStore = store
        return store
    }

    /**
     * Creates a new [FreeKeyRouter] wired with shared rankings, probe, stats, and cooldown stores.
     */
    fun createRouterForCandidates(
        candidates: List<RouterCandidate>,
        http: OkHttpClient = OkHttpClient(),
        rankingsRepository: ModelRankingsRepository = getRankingsRepository(),
        probeService: ModelProbeService = getProbeService(),
        statsTracker: ModelStatsTracker = getStatsTracker(),
        cooldownStore: ModelCooldownStore = getCooldownStore(),
        preferences: com.meenai.mina.core.router.RouterPreferences = com.meenai.mina.core.router.DefaultRouterPreferences,
    ): FreeKeyRouter {
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
            cooldownStore = cooldownStore,
            firstTokenTimeoutMs = 15_000L,
            rankingsRepository = rankingsRepository,
            probeService = probeService,
            statsTracker = statsTracker,
            preferences = preferences,
        )
    }

    /**
     * Creates a new [FreeKeyRouter] with candidates derived from user's configured API keys.
     */
    fun createRouter(
        store: ProviderStore,
        http: OkHttpClient = OkHttpClient(),
        rankingsRepository: ModelRankingsRepository = getRankingsRepository(),
        probeService: ModelProbeService = getProbeService(),
        statsTracker: ModelStatsTracker = getStatsTracker(),
        cooldownStore: ModelCooldownStore = getCooldownStore(),
        preferences: com.meenai.mina.core.router.RouterPreferences = com.meenai.mina.core.router.DefaultRouterPreferences,
        reasoningEffortProvider: (() -> String?)? = null,
    ): FreeKeyRouter = createRouterForCandidates(
        candidates = createCandidates(store, http, reasoningEffortProvider = reasoningEffortProvider),
        http = http,
        rankingsRepository = rankingsRepository,
        probeService = probeService,
        statsTracker = statsTracker,
        cooldownStore = cooldownStore,
        preferences = preferences,
    )

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
        store.repairGeminiWebBindings()

        val currentRoles = store.roles.value
        val currentMainModel = currentRoles.main?.let { cfgId -> store.configs.value.firstOrNull { it.id == cfgId }?.model }
        val isMainSub3bOrExcluded = currentMainModel != null && (
            ChatModelFilter.isExcluded(currentMainModel) ||
            (ModelSizeParser.parse(currentMainModel).totalParamsB?.let { it in 0.01..2.9 } == true)
        )
        val mainChosen = currentRoles.main != null && !isMainSub3bOrExcluded
        val lightChosen = currentRoles.light !is com.meenai.mina.settings.RoleChoice.SameAsMain
        val visionChosen = currentRoles.vision !is com.meenai.mina.settings.RoleChoice.Off

        // If user explicitly chose all 3 roles, preserve everything
        if (mainChosen && lightChosen && visionChosen) return

        val pool = candidates ?: createCandidates(store).map { it.toModelCandidate() }
        if (pool.isEmpty()) return

        fun findProvider(candidate: ModelCandidate): Provider? {
            return store.providers.value.firstOrNull { p ->
                when (candidate.provider.lowercase()) {
                    "gemini_web" -> isGeminiWebProvider(p)
                    "openrouter" -> isOpenRouterProvider(p)
                    "zai", "z.ai" -> isZaiProvider(p)
                    "modelscope" -> isModelScopeProvider(p)
                    "gemini" -> isGeminiProvider(p)
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

    fun isGeminiWebProvider(p: Provider): Boolean {
        if (p.kind == ProviderConfig.Kind.GeminiWeb) return true
        if (p.signIn == ProviderStore.GEMINI_WEB_SIGN_IN) return true
        if (p.name.equals("Google Gemini", ignoreCase = true)) return true
        if (p.baseUrl.contains("gemini.google.com", ignoreCase = true)) return true
        return false
    }

    fun isGeminiProvider(p: Provider): Boolean {
        if (isGeminiWebProvider(p)) return false
        if (p.kind == ProviderConfig.Kind.Gemini) return true
        if (p.baseUrl.contains("generativelanguage", ignoreCase = true)) return true
        if (p.name.equals("Gemini", ignoreCase = true)) return true
        val preset = ProviderPresets.forBaseUrl(p.baseUrl) ?: ProviderPresets.forHost(p.baseUrl)
        return preset?.label.equals("Gemini", ignoreCase = true)
    }

    fun isGroqProvider(p: Provider): Boolean {
        if (p.baseUrl.contains("groq.com", ignoreCase = true)) return true
        if (p.name.contains("Groq", ignoreCase = true)) return true
        val preset = ProviderPresets.forBaseUrl(p.baseUrl) ?: ProviderPresets.forHost(p.baseUrl)
        return preset?.label.equals("Groq", ignoreCase = true)
    }

    fun isDeepSeekProvider(p: Provider): Boolean {
        if (p.baseUrl.contains("deepseek.com", ignoreCase = true)) return true
        if (p.name.contains("DeepSeek", ignoreCase = true)) return true
        val preset = ProviderPresets.forBaseUrl(p.baseUrl) ?: ProviderPresets.forHost(p.baseUrl)
        return preset?.label.equals("DeepSeek", ignoreCase = true)
    }

    fun isKimiProvider(p: Provider): Boolean {
        if (p.baseUrl.contains("moonshot.cn", ignoreCase = true)) return true
        if (p.name.contains("Kimi", ignoreCase = true) || p.name.contains("Moonshot", ignoreCase = true)) return true
        val preset = ProviderPresets.forBaseUrl(p.baseUrl) ?: ProviderPresets.forHost(p.baseUrl)
        return preset?.label.equals("Kimi", ignoreCase = true)
    }

    fun isMiniMaxProvider(p: Provider): Boolean {
        if (p.baseUrl.contains("minimax.cn", ignoreCase = true)) return true
        if (p.name.contains("MiniMax", ignoreCase = true)) return true
        val preset = ProviderPresets.forBaseUrl(p.baseUrl) ?: ProviderPresets.forHost(p.baseUrl)
        return preset?.label.equals("MiniMax", ignoreCase = true)
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
                val size = ModelSizeParser.parse(listed.id).totalParamsB
                val roles = buildSet {
                    if (size == null || size >= 3.0) {
                        add(ModelRole.Main)
                    }
                    if (supportsImages) add(ModelRole.Vision)
                    if (listed.id.contains("coder", ignoreCase = true)) add(ModelRole.Coding)
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
                    isBackup = true,
                    roles = roles,
                )
            }
        }

        // Hardcoded fallback list if no dynamic models provided
        val candidates = mutableListOf<RouterCandidate>()
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
                isBackup = true,
                roles = setOf(ModelRole.Main),
            )
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

        candidates.add(
            RouterCandidate(
                id = "Qwen/Qwen2.5-7B-Instruct",
                provider = ModelScopePreset.PROVIDER_ID,
                model = qwen7b,
                curatedRank = 50,
                isFree = true,
                isBackup = true,
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
                isBackup = true,
                roles = setOf(ModelRole.Vision),
            )
        )
        return candidates
    }

    fun createGeminiWebCandidates(
        session: GeminiWebSession,
        http: OkHttpClient,
        listedModels: List<ListedModel>? = null,
        reasoningEffortProvider: (() -> String?)? = null,
        onSessionUpdated: ((GeminiWebSession) -> Unit)? = null,
    ): List<RouterCandidate> {
        val models = if (!listedModels.isNullOrEmpty()) {
            listedModels.filter { it.chat != false && !ChatModelFilter.isExcluded(it.id) }
        } else {
            GeminiWebClient.DEFAULT_MODELS
        }

        return models.map { m ->
            val caps = Capabilities(
                toolCalls = m.toolCalls ?: true,
                vision = m.vision ?: true,
                contextTokens = m.contextTokens ?: 1_000_000,
                reasoning = m.reasoning ?: true,
                promptCaching = PromptCaching.Implicit,
            )
            val model = GeminiWebModel(
                session = session,
                model = m.id,
                capabilities = caps,
                http = GeminiWebClient.geminiWebHttp,
                onSessionUpdated = onSessionUpdated,
                reasoningEffortProvider = reasoningEffortProvider,
            )
            val rank = when {
                m.id.contains("argon", ignoreCase = true) -> 98
                m.id.contains("3.1-pro", ignoreCase = true) -> 95
                m.id.contains("hydrogen", ignoreCase = true) -> 92
                m.id.contains("flash-lite", ignoreCase = true) -> 85
                else -> 90
            }
            RouterCandidate(
                id = m.id,
                provider = "gemini_web",
                model = model,
                curatedRank = rank,
                isFree = true,
                roles = buildSet {
                    add(ModelRole.Main)
                    if (caps.vision) add(ModelRole.Vision)
                    add(ModelRole.Light)
                    add(ModelRole.Coding)
                    add(ModelRole.Long)
                },
            )
        }
    }

    /** Model IDs that are exclusively available via Gemini Web (gemini.google.com) and must NOT
     *  be routed through the AI Studio / generativelanguage API key endpoint. */
    private val GEMINI_WEB_ONLY_IDS: Set<String> =
        GeminiWebClient.DEFAULT_MODELS.map { it.id.lowercase() }.toSet()

    fun createGeminiCandidates(
        apiKey: String,
        http: OkHttpClient,
        listedModels: List<ListedModel>? = null,
        baseUrl: String = "https://generativelanguage.googleapis.com",
    ): List<RouterCandidate> {
        val models = if (!listedModels.isNullOrEmpty()) {
            listedModels.filter {
                it.chat != false &&
                    !ChatModelFilter.isExcluded(it.id) &&
                    it.id.lowercase() !in GEMINI_WEB_ONLY_IDS &&
                    !it.id.startsWith("gemini-4-", ignoreCase = true) &&
                    !it.id.contains("carbon", ignoreCase = true)
            }
        } else {
            listOf(
                ListedModel("gemini-2.5-flash", vision = true, toolCalls = true, contextTokens = 1_000_000),
                ListedModel("gemini-3.1-flash-lite", vision = true, toolCalls = true, contextTokens = 1_000_000),
                ListedModel("gemini-2.0-flash", vision = true, toolCalls = true, contextTokens = 128_000),
                ListedModel("gemini-1.5-flash", vision = true, toolCalls = true, contextTokens = 128_000),
            )
        }

        return models.map { m ->
            val caps = Capabilities(
                toolCalls = m.toolCalls ?: true,
                vision = m.vision ?: true,
                contextTokens = m.contextTokens ?: 128_000,
                reasoning = m.reasoning ?: false,
                promptCaching = PromptCaching.Implicit,
            )
            val model = GeminiModel(
                baseUrl = baseUrl.ifEmpty { "https://generativelanguage.googleapis.com" },
                apiKey = apiKey,
                model = m.id,
                capabilities = caps,
                http = http,
            )
            RouterCandidate(
                id = m.id,
                provider = "gemini",
                model = model,
                curatedRank = 0,
                isFree = true,
                roles = buildSet {
                    add(ModelRole.Main)
                    if (caps.vision) add(ModelRole.Vision)
                    add(ModelRole.Light)
                },
            )
        }
    }

    fun createGroqCandidates(
        apiKey: String,
        http: OkHttpClient,
        listedModels: List<ListedModel>? = null,
        baseUrl: String = "https://api.groq.com/openai/v1",
    ): List<RouterCandidate> {
        val models = if (!listedModels.isNullOrEmpty()) {
            listedModels.filter { it.chat != false && !ChatModelFilter.isExcluded(it.id) }
        } else {
            listOf(
                ListedModel("llama-3.3-70b-versatile", vision = false, toolCalls = true, contextTokens = 128_000),
                ListedModel("llama-3.1-8b-instant", vision = false, toolCalls = true, contextTokens = 128_000),
                ListedModel("qwen-2.5-coder-32b", vision = false, toolCalls = true, contextTokens = 128_000),
            )
        }

        return models.map { m ->
            val caps = Capabilities(
                toolCalls = m.toolCalls ?: true,
                vision = m.vision ?: false,
                contextTokens = m.contextTokens ?: 128_000,
                reasoning = m.reasoning ?: false,
                promptCaching = PromptCaching.Implicit,
            )
            val model = OpenAiCompatModel(
                baseUrl = baseUrl.ifEmpty { "https://api.groq.com/openai/v1" },
                apiKey = apiKey,
                model = m.id,
                capabilities = caps,
                http = http,
            )
            RouterCandidate(
                id = m.id,
                provider = "groq",
                model = model,
                curatedRank = 0,
                isFree = true,
                roles = buildSet {
                    add(ModelRole.Main)
                    if (m.id.contains("coder", ignoreCase = true)) add(ModelRole.Coding)
                    if (m.id.contains("8b", ignoreCase = true)) add(ModelRole.Light)
                },
            )
        }
    }

    fun createDeepSeekCandidates(
        apiKey: String,
        http: OkHttpClient,
        listedModels: List<ListedModel>? = null,
        baseUrl: String = "https://api.deepseek.com",
    ): List<RouterCandidate> {
        val models = if (!listedModels.isNullOrEmpty()) {
            listedModels.filter { it.chat != false && !ChatModelFilter.isExcluded(it.id) }
        } else {
            listOf(
                ListedModel("deepseek-chat", vision = false, toolCalls = true, contextTokens = 64_000),
                ListedModel("deepseek-reasoner", vision = false, toolCalls = true, contextTokens = 64_000, reasoning = true),
            )
        }

        return models.map { m ->
            val isReasoner = m.id.contains("reasoner", ignoreCase = true)
            val caps = Capabilities(
                toolCalls = m.toolCalls ?: !isReasoner,
                vision = false,
                contextTokens = m.contextTokens ?: 64_000,
                reasoning = m.reasoning ?: isReasoner,
                promptCaching = PromptCaching.Implicit,
            )
            val model = OpenAiCompatModel(
                baseUrl = baseUrl.ifEmpty { "https://api.deepseek.com" },
                apiKey = apiKey,
                model = m.id,
                capabilities = caps,
                http = http,
            )
            RouterCandidate(
                id = m.id,
                provider = "deepseek",
                model = model,
                curatedRank = 0,
                isFree = false,
                roles = setOf(ModelRole.Main, ModelRole.Coding),
            )
        }
    }

    fun createKimiCandidates(
        apiKey: String,
        http: OkHttpClient,
        listedModels: List<ListedModel>? = null,
        baseUrl: String = "https://api.moonshot.cn/v1",
    ): List<RouterCandidate> {
        val models = if (!listedModels.isNullOrEmpty()) {
            listedModels.filter { it.chat != false && !ChatModelFilter.isExcluded(it.id) && !it.id.contains("k2", ignoreCase = true) }
        } else {
            listOf(
                ListedModel("kimi-k3", vision = false, toolCalls = true, contextTokens = 128_000),
                ListedModel("kimi-k3.1-pro", vision = false, toolCalls = true, contextTokens = 128_000),
            )
        }

        return models.map { m ->
            val caps = Capabilities(
                toolCalls = m.toolCalls ?: true,
                vision = false,
                contextTokens = m.contextTokens ?: 32_000,
                reasoning = false,
                promptCaching = PromptCaching.Implicit,
            )
            val model = OpenAiCompatModel(
                baseUrl = baseUrl.ifEmpty { "https://api.moonshot.cn/v1" },
                apiKey = apiKey,
                model = m.id,
                capabilities = caps,
                http = http,
            )
            RouterCandidate(
                id = m.id,
                provider = "kimi",
                model = model,
                curatedRank = 0,
                isFree = false,
                roles = setOf(ModelRole.Main),
            )
        }
    }

    fun createMiniMaxCandidates(
        apiKey: String,
        http: OkHttpClient,
        listedModels: List<ListedModel>? = null,
        baseUrl: String = "https://api.minimax.cn/v1",
    ): List<RouterCandidate> {
        val models = if (!listedModels.isNullOrEmpty()) {
            listedModels.filter { it.chat != false && !ChatModelFilter.isExcluded(it.id) }
        } else {
            listOf(
                ListedModel("MiniMax-Text-01", vision = true, toolCalls = true, contextTokens = 200_000),
            )
        }

        return models.map { m ->
            val caps = Capabilities(
                toolCalls = m.toolCalls ?: true,
                vision = m.vision ?: true,
                contextTokens = m.contextTokens ?: 200_000,
                reasoning = false,
                promptCaching = PromptCaching.Implicit,
            )
            val model = OpenAiCompatModel(
                baseUrl = baseUrl.ifEmpty { "https://api.minimax.cn/v1" },
                apiKey = apiKey,
                model = m.id,
                capabilities = caps,
                http = http,
            )
            RouterCandidate(
                id = m.id,
                provider = "minimax",
                model = model,
                curatedRank = 0,
                isFree = false,
                roles = setOf(ModelRole.Main, ModelRole.Vision, ModelRole.Long),
            )
        }
    }
}
