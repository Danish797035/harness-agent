package com.meenai.mina.core.router.picker

/**
 * Curated tier rankings and heuristic evaluation for model candidates.
 * Curated list orders models from strongest to weakest.
 * Models outside the curated list fall back to heuristic ranking:
 * 1. Bada model (parameter size)
 * 2. Bada context (context tokens)
 * 3. Naya release (release date timestamp)
 */
object CuratedTiers {

    /**
     * Curated list of top models, ordered from strongest first.
     */
    val CURATED_TIER_LIST: List<String> = listOf(
        // Tier 1: Frontier reasoning & heavy models
        "google/gemini-2.0-flash-thinking-exp",
        "google/gemini-2.0-pro-exp-02-05",
        "deepseek/deepseek-r1",
        "google/gemini-2.0-flash-exp",
        "google/gemini-2.0-flash",
        "deepseek/deepseek-chat",
        "meta-llama/llama-3.3-70b-instruct",

        // Tier 2: Strong coding & large models
        "qwen/qwen-2.5-coder-32b-instruct",
        "qwen/qwen-2.5-72b-instruct",
        "meta-llama/llama-3.1-70b-instruct",
        "deepseek/deepseek-r1-distill-llama-70b",
        "mistralai/mistral-small-24b-instruct-2501",
        "mistralai/mistral-small-24b-instruct",

        // Tier 3: High-quality vision & mid-range models
        "meta-llama/llama-3.2-11b-vision-instruct",
        "google/gemini-flash-1.5",
        "google/gemini-1.5-flash",
        "google/gemini-1.5-pro",
        "qwen/qwen-2.5-14b-instruct",

        // Tier 4: Fast / compact models
        "meta-llama/llama-3.1-8b-instruct",
        "meta-llama/llama-3-8b-instruct",
        "google/gemini-flash-1.5-8b",
        "qwen/qwen-2.5-7b-instruct",
        "mistralai/mistral-7b-instruct",
        "meta-llama/llama-3.2-3b-instruct",
        "meta-llama/llama-3.2-1b-instruct",
    )

    private val PARAM_SIZE_REGEX = Regex("""(?i)\b(\d+(?:\.\d+)?)\s*[bB]\b""")

    /**
     * Normalizes a model identifier by trimming, lowercasing, and stripping provider prefix or ":free".
     */
    fun normalizeModelId(rawId: String): String {
        return rawId.trim().lowercase().removeSuffix(":free")
    }

    /**
     * Resolves the curated tier rank for a model (1 = best, 2 = next best, etc.).
     * Returns null if not in the curated tier list.
     */
    fun findCuratedRank(modelId: String): Int? {
        val normalized = normalizeModelId(modelId)
        val index = CURATED_TIER_LIST.indexOfFirst { curated ->
            val curNorm = normalizeModelId(curated)
            normalized == curNorm ||
                normalized.endsWith("/$curNorm") ||
                curNorm.endsWith("/$normalized") ||
                normalized.startsWith(curNorm) ||
                curNorm.startsWith(normalized)
        }
        return if (index >= 0) index + 1 else null
    }

    /**
     * Computes tier rank. Returns 1..N if curated, or [Int.MAX_VALUE] if not curated.
     */
    fun computeTierRank(modelId: String): Int {
        return findCuratedRank(modelId) ?: Int.MAX_VALUE
    }

    /**
     * Extracts parameter size in billions (e.g. 70.0 for 70B, 8.0 for 8B)
     * using regex from ID, name, or description, with heuristic fallbacks.
     */
    fun extractParameterSizeB(modelId: String, name: String = "", description: String = ""): Double {
        val combined = "$modelId $name $description"
        val match = PARAM_SIZE_REGEX.find(combined)
        if (match != null) {
            val size = match.groupValues[1].toDoubleOrNull()
            if (size != null && size in 0.1..2000.0) {
                return size
            }
        }

        val lower = combined.lowercase()
        return when {
            "deepseek-r1" in lower || "deepseek-v3" in lower -> 671.0
            "llama-3.3" in lower || "llama-3-70b" in lower || "llama-3.1-70b" in lower -> 70.0
            "qwen-2.5-72b" in lower -> 72.0
            "qwen-2.5-32b" in lower || "coder-32b" in lower -> 32.0
            "mistral-small" in lower -> 24.0
            "gemini-2" in lower -> 70.0
            "gemini-1.5-pro" in lower -> 70.0
            "gemini-1.5-flash" in lower || "gemini-flash" in lower -> 8.0
            "llama-3.1-8b" in lower || "llama-3-8b" in lower -> 8.0
            "mistral-7b" in lower -> 7.0
            "llama-3.2-3b" in lower -> 3.0
            "llama-3.2-1b" in lower -> 1.0
            else -> 0.0
        }
    }

    /**
     * Heuristic comparator:
     * 1. Curated tier list order (lowest rank number first)
     * 2. Heuristic fallback:
     *    - Bada model (parameterSizeB descending)
     *    - Bada context (contextTokens descending)
     *    - Naya release (releaseDateEpoch descending)
     * 3. ID ascending (deterministic tie-breaker)
     */
    val HEURISTIC_COMPARATOR: Comparator<ModelCandidate> = Comparator { a, b ->
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
}
