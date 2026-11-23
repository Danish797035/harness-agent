package com.meenai.mina.core.router.picker

/**
 * Clock abstraction for testable time calculations.
 */
fun interface Clock {
    fun currentTimeMillis(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

/**
 * Auto model picker for Free Key Router.
 *
 * Automatically selects optimal free models based on dynamic heavy-to-light scoring and heuristics:
 * - Filter: Free only, tools required, context >= 32k, excludes non-chat/image-edit/image-gen.
 * - Roles: Main (heavy frontier), Coding, Light (fast / low-latency), Vision, Long.
 * - Pin respect: If a pinned model is configured, Auto does not change it.
 * - Cache: Caches OpenRouter models once a day (24h), falls back to cache when offline.
 * - Health score hook: Uses [HealthSource] (default 1.0) to avoid failing/unhealthy models.
 */
class AutoModelPicker(
    private val apiClient: OpenRouterApiClient,
    private val cacheStore: ModelCacheStore = InMemoryModelCacheStore(),
    private val parser: OpenRouterModelParser = OpenRouterModelParser(),
    private val healthSource: HealthSource = HealthSource.DEFAULT,
    private val clock: Clock = Clock.SYSTEM,
    private val cacheTtlMs: Long = ONE_DAY_MS,
    private val rankingsRepository: ModelRankingsRepository? = null,
    val probeService: ModelProbeService? = null,
    val statsTracker: ModelStatsTracker? = null,
    val candidateProviders: List<suspend () -> List<ModelCandidate>> = emptyList(),
) {

    /**
     * Retrieves model candidates, using the cache if fresh (< 24h).
     * If the cache is expired or [forceRefresh] is true, queries the network.
     * When network fails (offline), gracefully falls back to the existing cache.
     * Merges OpenRouter candidates with unified candidates from all connected providers.
     */
    suspend fun getCandidates(forceRefresh: Boolean = false): List<ModelCandidate> {
        val now = clock.currentTimeMillis()
        val cached = cacheStore.load()

        val openRouterModels = if (!forceRefresh && cached != null && (now - cached.timestampMs) < cacheTtlMs && cached.models.isNotEmpty()) {
            cached.models
        } else {
            try {
                val json = apiClient.fetchModelsJson()
                val parsed = parser.parse(json)
                if (parsed.isNotEmpty()) {
                    cacheStore.save(CachedModelData(timestampMs = now, models = parsed))
                    parsed
                } else {
                    cached?.models.orEmpty()
                }
            } catch (_: Exception) {
                // Offline fallback: return cached data even if expired
                if (cached != null && cached.models.isNotEmpty()) {
                    cached.models
                } else {
                    emptyList()
                }
            }
        }

        val dynamicExtra = candidateProviders.flatMap { it.invoke() }
        val allCandidates = mutableListOf<ModelCandidate>()
        allCandidates.addAll(openRouterModels)
        allCandidates.addAll(DEFAULT_CONNECTED_CANDIDATES)
        allCandidates.addAll(dynamicExtra)

        val seenIds = mutableSetOf<String>()
        return allCandidates.filter { candidate ->
            // Strict rule: Kimi K2 is not available on this account
            if (candidate.provider.equals("kimi", ignoreCase = true) && candidate.id.contains("k2", ignoreCase = true)) {
                return@filter false
            }
            if (candidate.id.contains("carbon", ignoreCase = true)) {
                return@filter false
            }
            seenIds.add(candidate.id)
        }
    }

    /**
     * Filters candidate models according to criteria:
     * 1. Free only (OpenRouter ":free" or candidate marked isFree) if [freeOnly] is true
     * 2. Active round-trip tools support verified via probeService
     * 3. Context window >= 32,000 tokens
     * 4. Excludes non-chat, embedding, rerank, tts, asr, image-gen, and image-edit models
     * 5. For Vision role, image input support required (image-edit/gen excluded)
     */
    fun filterCandidates(candidates: List<ModelCandidate>, role: ModelRole, freeOnly: Boolean = true): List<ModelCandidate> {
        return candidates.filter { candidate ->
            val isFree = candidate.isFree || candidate.id.endsWith(":free")
            if (freeOnly && !isFree) return@filter false

            val probe = probeService?.getProbeResult(candidate.id)
            val toolsSupported = if (probe != null) {
                probe.passed && probe.supportsTools
            } else {
                candidate.supportsTools
            }
            if (!toolsSupported) return@filter false

            if (candidate.contextTokens < MIN_CONTEXT_TOKENS) return@filter false

            // Exclude non-chat and image-gen/edit models
            if (ChatModelFilter.isExcluded(candidate.id)) return@filter false

            // Main role requires capable models: sub-3B tiny models cannot handle complex multi-tool agentic loops
            val params = candidate.totalParamsB ?: candidate.parameterSizeB
            if (role == ModelRole.Main && params in 0.01..2.9) return@filter false

            // Exclude models that failed probe today
            if (probeService?.isFailedToday(candidate.id) == true) return@filter false

            if (role == ModelRole.Vision && (!candidate.supportsImages || ChatModelFilter.isImageEditOrGen(candidate.id))) {
                return@filter false
            }

            true
        }
    }

    /**
     * Returns the complete ranked list of candidates from heaviest to lightest.
     */
    fun getFullRankedList(candidates: List<ModelCandidate>): List<ModelCandidate> {
        return candidates
            .filter { candidate ->
                val isFree = candidate.isFree || candidate.id.endsWith(":free")
                val probe = probeService?.getProbeResult(candidate.id)
                val toolsSupported = if (probe != null) probe.passed && probe.supportsTools else candidate.supportsTools
                isFree && toolsSupported &&
                    candidate.contextTokens >= MIN_CONTEXT_TOKENS &&
                    !ChatModelFilter.isExcluded(candidate.id) &&
                    probeService?.isFailedToday(candidate.id) != true
            }
            .sortedWith(ModelScorer.HEAVY_TO_LIGHT_COMPARATOR)
    }

    /**
     * Ranks candidates based on role requirements, health scores, and dynamic scoring.
     */
    fun rankCandidates(
        candidates: List<ModelCandidate>,
        role: ModelRole,
        activeHealthSource: HealthSource = healthSource,
    ): List<ModelCandidate> {
        if (candidates.isEmpty()) return emptyList()

        // Health score separation: healthy models (score > 0.0) take priority
        val (healthy, unhealthy) = candidates.partition { activeHealthSource.score(it.id) > 0.0 }
        val primaryPool = if (healthy.isNotEmpty()) healthy else unhealthy

        return when (role) {
            ModelRole.Main -> rankMainRole(primaryPool)
            ModelRole.Coding -> rankCodingRole(primaryPool)
            ModelRole.Light -> rankLightRole(primaryPool)
            ModelRole.Vision -> rankVisionRole(primaryPool)
            ModelRole.Long -> rankLongRole(primaryPool)
        }
    }

    /**
     * Picks the best candidate from a given list, strictly respecting any pinned model.
     */
    fun pickFromCandidates(
        candidates: List<ModelCandidate>,
        role: ModelRole,
        pinnedModelId: String? = null,
        activeHealthSource: HealthSource = healthSource,
        currentModelId: String? = null,
    ): ModelCandidate? {
        // Pin respect: if pinned, Auto does not change it
        if (!pinnedModelId.isNullOrBlank()) {
            return candidates.firstOrNull { it.id.equals(pinnedModelId, ignoreCase = true) }
                ?: ModelCandidate(
                    provider = "pinned",
                    id = pinnedModelId,
                    contextTokens = MIN_CONTEXT_TOKENS,
                    supportsTools = true,
                    supportsImages = (role == ModelRole.Vision),
                    isFree = true,
                    tierRank = 0,
                )
        }

        val filtered = filterCandidates(candidates, role)
        val ranked = rankCandidates(filtered, role, activeHealthSource)
        val best = ranked.firstOrNull() ?: return null

        // Apply hysteresis if current model is known and still in candidate pool
        if (currentModelId != null && statsTracker != null && currentModelId != best.id) {
            val current = filtered.firstOrNull { it.id == currentModelId }
            if (current != null) {
                val currentScore = ModelScorer.computeScore(
                    current,
                    rankingsRepository?.findRanking(current.id),
                    role,
                    probeService?.getProbeResult(current.id),
                    statsTracker,
                )
                val bestScore = ModelScorer.computeScore(
                    best,
                    rankingsRepository?.findRanking(best.id),
                    role,
                    probeService?.getProbeResult(best.id),
                    statsTracker,
                )
                if (!statsTracker.shouldSwitch(currentScore, bestScore, 10.0)) {
                    return current
                }
            }
        }

        return best
    }

    /**
     * Fetches candidates (with cache support) and picks the best model for [role].
     */
    suspend fun pick(
        role: ModelRole,
        pinnedModelId: String? = null,
        extraCandidates: List<ModelCandidate> = emptyList(),
        forceRefresh: Boolean = false,
        activeHealthSource: HealthSource = healthSource,
        currentModelId: String? = null,
    ): ModelCandidate? {
        if (!pinnedModelId.isNullOrBlank()) {
            return pickFromCandidates(emptyList(), role, pinnedModelId, activeHealthSource)
        }

        val openRouterCandidates = getCandidates(forceRefresh)
        val combined = (openRouterCandidates + extraCandidates).distinctBy { it.id }
        return pickFromCandidates(combined, role, pinnedModelId, activeHealthSource, currentModelId)
    }

    /**
     * Main role: sabse heavy (flagship, reasoning, large capacity).
     */
    private fun rankMainRole(candidates: List<ModelCandidate>): List<ModelCandidate> {
        val comparator = ModelScorer.createComparator(rankingsRepository, ModelRole.Main, probeService, statsTracker)
        return candidates.sortedWith(comparator)
    }

    /**
     * Coding role: coding-specialized models or heaviest capable models.
     */
    private fun rankCodingRole(candidates: List<ModelCandidate>): List<ModelCandidate> {
        val comparator = ModelScorer.createComparator(rankingsRepository, ModelRole.Coding, probeService, statsTracker)
        val codingModels = candidates.filter {
            it.id.contains("coder", ignoreCase = true) ||
                it.id.contains("code", ignoreCase = true)
        }
        val pool = if (codingModels.isNotEmpty()) codingModels else candidates
        return pool.sortedWith(comparator)
    }

    /**
     * Light role: tez (fast, lightweight, low-latency).
     * Prefers models in the <= 10B range or marked small/mini/lite, ordered by quality.
     */
    private fun rankLightRole(candidates: List<ModelCandidate>): List<ModelCandidate> {
        val comparator = ModelScorer.createComparator(rankingsRepository, ModelRole.Light, probeService, statsTracker)
        val lightModels = candidates.filter { isLightModel(it) }
        val pool = if (lightModels.isNotEmpty()) lightModels else candidates
        return pool.sortedWith(comparator)
    }

    /**
     * Vision role: best image understanding (excluding image-gen/edit models).
     */
    private fun rankVisionRole(candidates: List<ModelCandidate>): List<ModelCandidate> {
        val comparator = ModelScorer.createComparator(rankingsRepository, ModelRole.Vision, probeService, statsTracker)
        val visionModels = candidates.filter { it.supportsImages && !ChatModelFilter.isImageEditOrGen(it.id) }
        val pool = if (visionModels.isNotEmpty()) visionModels else candidates
        return pool.sortedWith(comparator)
    }

    /**
     * Long role: models with massive context windows.
     */
    private fun rankLongRole(candidates: List<ModelCandidate>): List<ModelCandidate> {
        val baseComparator = ModelScorer.createComparator(rankingsRepository, ModelRole.Long, probeService, statsTracker)
        val comparator = compareByDescending<ModelCandidate> { it.contextTokens }
            .thenComparing(baseComparator)
        return candidates.sortedWith(comparator)
    }

    private fun isLightModel(candidate: ModelCandidate): Boolean = Companion.isLightModel(candidate)

    companion object {
        private val LIGHT_KEYWORDS = setOf("mini", "small", "lite", "nano")
        private val SIZE_TOKEN = Regex("""^(\d+(?:\.\d+)?)b$""")
        const val MIN_CONTEXT_TOKENS = 32_000
        const val ONE_DAY_MS = 24 * 60 * 60 * 1000L

        val DEFAULT_CONNECTED_CANDIDATES: List<ModelCandidate> = listOf(
            // Gemini
            ModelCandidate(
                provider = "gemini",
                id = "gemini-2.5-flash",
                contextTokens = 1_000_000,
                supportsTools = true,
                supportsImages = true,
                isFree = true,
                tierRank = 95,
                parameterSizeB = 70.0,
                totalParamsB = 70.0,
            ),
            ModelCandidate(
                provider = "gemini",
                id = "gemini-3.1-flash-lite",
                contextTokens = 1_000_000,
                supportsTools = true,
                supportsImages = true,
                isFree = true,
                tierRank = 85,
                parameterSizeB = 8.0,
                totalParamsB = 8.0,
            ),
            ModelCandidate(
                provider = "gemini",
                id = "gemini-2.0-flash",
                contextTokens = 1_000_000,
                supportsTools = true,
                supportsImages = true,
                isFree = true,
                tierRank = 90,
                parameterSizeB = 70.0,
                totalParamsB = 70.0,
            ),
            // Groq
            ModelCandidate(
                provider = "groq",
                id = "llama-3.3-70b-versatile",
                contextTokens = 128_000,
                supportsTools = true,
                supportsImages = false,
                isFree = true,
                tierRank = 88,
                parameterSizeB = 70.0,
                totalParamsB = 70.0,
            ),
            ModelCandidate(
                provider = "groq",
                id = "llama-3.1-8b-instant",
                contextTokens = 128_000,
                supportsTools = true,
                supportsImages = false,
                isFree = true,
                tierRank = 60,
                parameterSizeB = 8.0,
                totalParamsB = 8.0,
            ),
            ModelCandidate(
                provider = "groq",
                id = "qwen-2.5-coder-32b",
                contextTokens = 128_000,
                supportsTools = true,
                supportsImages = false,
                isFree = true,
                tierRank = 78,
                parameterSizeB = 32.0,
                totalParamsB = 32.0,
            ),
            // Z.ai
            ModelCandidate(
                provider = "zai",
                id = "glm-4.7-flash",
                contextTokens = 128_000,
                supportsTools = true,
                supportsImages = false,
                isFree = true,
                tierRank = 86,
                parameterSizeB = 70.0,
                totalParamsB = 70.0,
            ),
            ModelCandidate(
                provider = "zai",
                id = "glm-4.6v-flash",
                contextTokens = 128_000,
                supportsTools = true,
                supportsImages = true,
                isFree = true,
                tierRank = 70,
                parameterSizeB = 9.0,
                totalParamsB = 9.0,
            ),
            // Kimi: ONLY kimi-k3 and kimi-k3.1-pro (paid). K2 is strictly omitted!
            ModelCandidate(
                provider = "kimi",
                id = "kimi-k3",
                contextTokens = 128_000,
                supportsTools = true,
                supportsImages = false,
                isFree = false,
                tierRank = 85,
                parameterSizeB = 70.0,
                totalParamsB = 70.0,
            ),
            ModelCandidate(
                provider = "kimi",
                id = "kimi-k3.1-pro",
                contextTokens = 128_000,
                supportsTools = true,
                supportsImages = false,
                isFree = false,
                tierRank = 92,
                parameterSizeB = 100.0,
                totalParamsB = 100.0,
            ),
            // DeepSeek
            ModelCandidate(
                provider = "deepseek",
                id = "deepseek-chat",
                contextTokens = 64_000,
                supportsTools = true,
                supportsImages = false,
                isFree = false,
                tierRank = 89,
                parameterSizeB = 671.0,
                totalParamsB = 671.0,
                activeParamsB = 37.0,
            ),
            ModelCandidate(
                provider = "deepseek",
                id = "deepseek-reasoner",
                contextTokens = 64_000,
                supportsTools = true,
                supportsImages = false,
                isFree = false,
                supportsReasoning = true,
                tierRank = 94,
                parameterSizeB = 671.0,
                totalParamsB = 671.0,
                activeParamsB = 37.0,
            ),
            // MiniMax
            ModelCandidate(
                provider = "minimax",
                id = "MiniMax-Text-01",
                contextTokens = 200_000,
                supportsTools = true,
                supportsImages = true,
                isFree = false,
                tierRank = 87,
                parameterSizeB = 456.0,
                totalParamsB = 456.0,
                activeParamsB = 45.9,
            ),
            // ModelScope
            ModelCandidate(
                provider = "modelscope",
                id = "Qwen/Qwen2.5-72B-Instruct",
                contextTokens = 32_000,
                supportsTools = true,
                supportsImages = false,
                isFree = true,
                tierRank = 75,
                parameterSizeB = 72.0,
                totalParamsB = 72.0,
            ),
            ModelCandidate(
                provider = "modelscope",
                id = "Qwen/Qwen2.5-7B-Instruct",
                contextTokens = 32_000,
                supportsTools = true,
                supportsImages = false,
                isFree = true,
                tierRank = 50,
                parameterSizeB = 7.0,
                totalParamsB = 7.0,
            ),
            ModelCandidate(
                provider = "modelscope",
                id = "Qwen/Qwen2.5-VL-7B-Instruct",
                contextTokens = 32_000,
                supportsTools = true,
                supportsImages = true,
                isFree = true,
                tierRank = 55,
                parameterSizeB = 7.0,
                totalParamsB = 7.0,
            ),
            // NVIDIA NIM custom
            ModelCandidate(
                provider = "nvidia",
                id = "meta/llama-3.3-70b-instruct",
                contextTokens = 128_000,
                supportsTools = true,
                supportsImages = false,
                isFree = true,
                tierRank = 82,
                parameterSizeB = 70.0,
                totalParamsB = 70.0,
            ),
        )

        fun isLightModel(candidate: ModelCandidate): Boolean {
            val total = candidate.totalParamsB ?: candidate.parameterSizeB
            if (total in 0.1..10.0) return true
            if (total > 10.0) return false
            // Match whole tokens only: "gemini" must not count as "mini", "11b" must not count as "1b".
            val tokens = candidate.id.lowercase().split('/', '-', '_', ':', ' ')
            return tokens.any { token ->
                token in LIGHT_KEYWORDS ||
                    (SIZE_TOKEN.matchEntire(token)?.groupValues?.get(1)?.toDoubleOrNull()?.let { it in 0.1..10.0 } == true)
            }
        }
    }
}
