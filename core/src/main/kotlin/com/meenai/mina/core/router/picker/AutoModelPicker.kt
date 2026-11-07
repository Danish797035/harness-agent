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
 * Auto model picker for Free Key Router (Part B).
 *
 * Automatically selects optimal free models based on curated tiers and heuristics:
 * - Filter: Free only, tools required, context >= 32k, vision input required for Vision role.
 * - Roles: Main (heavy frontier), Light (fast / low-latency), Vision (best image understanding).
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
     * 4. For Vision role, image input support required
     */
    fun filterCandidates(candidates: List<ModelCandidate>, role: ModelRole): List<ModelCandidate> {
        return candidates.filter { candidate ->
            val isFree = candidate.isFree || candidate.id.endsWith(":free")
            if (!isFree) return@filter false

            if (!candidate.supportsTools) return@filter false

            if (candidate.contextTokens < MIN_CONTEXT_TOKENS) return@filter false

            if (role == ModelRole.Vision && !candidate.supportsImages) return@filter false

            true
        }
    }

    /**
     * Ranks candidates based on role requirements, health scores, and tier/heuristic orders.
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
            ModelRole.Light -> rankLightRole(primaryPool)
            ModelRole.Vision -> rankVisionRole(primaryPool)
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
        return ranked.firstOrNull()
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
    ): ModelCandidate? {
        if (!pinnedModelId.isNullOrBlank()) {
            return pickFromCandidates(emptyList(), role, pinnedModelId, activeHealthSource)
        }

        val openRouterCandidates = getCandidates(forceRefresh)
        val combined = (openRouterCandidates + extraCandidates).distinctBy { it.id }
        return pickFromCandidates(combined, role, pinnedModelId, activeHealthSource)
    }

    /**
     * Main role: sabse heavy (flagship, reasoning, large capacity).
     */
    private fun rankMainRole(candidates: List<ModelCandidate>): List<ModelCandidate> {
        val comparator = Comparator<ModelCandidate> { a, b ->
            val aHeavy = a.parameterSizeB >= 20.0
            val bHeavy = b.parameterSizeB >= 20.0
            if (aHeavy != bHeavy) {
                return@Comparator if (aHeavy) -1 else 1
            }

            val rankDiff = a.tierRank.compareTo(b.tierRank)
            if (rankDiff != 0) return@Comparator rankDiff

            val sizeDiff = b.parameterSizeB.compareTo(a.parameterSizeB)
            if (sizeDiff != 0) return@Comparator sizeDiff

            val ctxDiff = b.contextTokens.compareTo(a.contextTokens)
            if (ctxDiff != 0) return@Comparator ctxDiff

            val dateDiff = b.releaseDateEpoch.compareTo(a.releaseDateEpoch)
            if (dateDiff != 0) return@Comparator dateDiff

            a.id.compareTo(b.id)
        }
        return candidates.sortedWith(comparator)
    }

    /**
     * Light role: tez (fast, lightweight, low-latency).
     * Prefers models in the <= 10B range or marked small/mini/lite, ordered by quality.
     */
    private fun rankLightRole(candidates: List<ModelCandidate>): List<ModelCandidate> {
        val lightModels = candidates.filter { isLightModel(it) }
        val pool = if (lightModels.isNotEmpty()) lightModels else candidates
        return pool.sortedWith(CuratedTiers.HEURISTIC_COMPARATOR)
    }

    /**
     * Vision role: best image understanding.
     */
    private fun rankVisionRole(candidates: List<ModelCandidate>): List<ModelCandidate> {
        return candidates.sortedWith(CuratedTiers.HEURISTIC_COMPARATOR)
    }

    private fun isLightModel(candidate: ModelCandidate): Boolean {
        if (candidate.parameterSizeB in 0.1..10.0) return true
        // Match whole tokens only: "gemini" must not count as "mini", "11b" must not count as "1b".
        val tokens = candidate.id.lowercase().split('/', '-', '_', ':', ' ')
        return tokens.any { token ->
            token in LIGHT_KEYWORDS ||
                (SIZE_TOKEN.matchEntire(token)?.groupValues?.get(1)?.toDoubleOrNull()?.let { it in 0.1..10.0 } == true)
        }
    }

    companion object {
        private val LIGHT_KEYWORDS = setOf("mini", "small", "lite", "nano")
        private val SIZE_TOKEN = Regex("""^(\d+(?:\.\d+)?)b$""")
        const val MIN_CONTEXT_TOKENS = 32_000
        const val ONE_DAY_MS = 24 * 60 * 60 * 1000L
    }
}
