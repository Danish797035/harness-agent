package com.meenai.mina.core.router.picker

/**
 * Curated tiers helper.
 * Fixed name lists have been removed in favor of dynamic capability scoring.
 */
object CuratedTiers {

    /**
     * Empty list: fixed model name list has been removed from ranking.
     */
    val CURATED_TIER_LIST: List<String> = emptyList()

    /**
     * Normalizes a model identifier by trimming, lowercasing, and stripping provider prefix or ":free".
     */
    fun normalizeModelId(rawId: String): String {
        return rawId.trim().lowercase().removeSuffix(":free")
    }

    /**
     * Legacy rank finder. Always returns null since hardcoded list is removed.
     */
    fun findCuratedRank(modelId: String): Int? = null

    /**
     * Legacy tier rank. Returns [Int.MAX_VALUE].
     */
    fun computeTierRank(modelId: String): Int = Int.MAX_VALUE

    /**
     * Extracts parameter size in billions dynamically via [ModelSizeParser].
     */
    fun extractParameterSizeB(modelId: String, name: String = "", description: String = ""): Double {
        return ModelSizeParser.extractParameterSizeB(modelId, name, description)
    }

    /**
     * Comparator for heavy-to-light sorting without hardcoded lists.
     */
    val HEURISTIC_COMPARATOR: Comparator<ModelCandidate> = ModelScorer.HEAVY_TO_LIGHT_COMPARATOR
}
