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
) {

    /**
     * Retrieves model candidates, using the cache if fresh (< 24h).
     * If the cache is expired or [forceRefresh] is true, queries the network.
     * When network fails (offline), gracefully falls back to the existing cache.
     */
    suspend fun getCandidates(forceRefresh: Boolean = false): List<ModelCandidate> {
        val now = clock.currentTimeMillis()
        val cached = cacheStore.load()

        if (!forceRefresh && cached != null && (now - cached.timestampMs) < cacheTtlMs && cached.models.isNotEmpty()) {
            return cached.models
        }

        return try {
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

    /**
     * Filters candidate models according to criteria:
     * 1. Free only (OpenRouter ":free" or candidate marked isFree)
     * 2. Tools support required
     * 3. Context window >= 32,000 tokens
     * 4. Excludes non-chat, embedding, rerank, tts, asr, image-gen, and image-edit models
     * 5. For Vision role, image input support required (image-edit/gen excluded)
     */
    fun filterCandidates(candidates: List<ModelCandidate>, role: ModelRole): List<ModelCandidate> {
        return candidates.filter { candidate ->
            val isFree = candidate.isFree || candidate.id.endsWith(":free")
            if (!isFree) return@filter false

            if (!candidate.supportsTools) return@filter false

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
                isFree && candidate.supportsTools &&
                    candidate.contextTokens >= MIN_CONTEXT_TOKENS &&
                    !ChatModelFilter.isExcluded(candidate.id)
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
