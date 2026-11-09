package com.meenai.mina.core.router.picker

/**
 * Normalizes model identifiers across OpenRouter, ModelScope, Z.ai, and external benchmark leaderboards.
 * Strips provider prefixes, :free / :beta tags, trailing tags, and separator differences.
 */
object ModelNameNormalizer {

    private val PREFIX_REGEX = Regex(
        "^(?:meta-llama|meta[.-]llama|meta|qwen|deepseek|mistralai|mistral|google|microsoft|zai|z-ai|alibaba|anthropic|openai|models|modelscope)/+",
        RegexOption.IGNORE_CASE
    )

    private val SUFFIX_REGEX = Regex(
        "[:@](?:free|beta|extended|latest)$|-(?:free|beta|extended)$",
        RegexOption.IGNORE_CASE
    )

    private val SEPARATORS_REGEX = Regex("[/_\\s]+")

    /**
     * Normalizes a model identifier into a canonical lowercased kebab-case name.
     * Example: "meta-llama/llama-3.3-70b-instruct:free" -> "llama-3.3-70b-instruct"
     * Example: "Qwen/Qwen2.5-72B-Instruct" -> "qwen2.5-72b-instruct"
     * Example: "deepseek/deepseek-r1:free" -> "deepseek-r1"
     */
    fun normalize(id: String): String {
        var s = id.trim()
        s = PREFIX_REGEX.replace(s, "")
        s = SUFFIX_REGEX.replace(s, "")
        // Handle repeated prefixes if any (e.g., "qwen/qwen2.5...")
        s = PREFIX_REGEX.replace(s, "")
        s = s.lowercase()
        // Replace separators with '-'
        s = SEPARATORS_REGEX.replace(s, "-")
        // Trim leading and trailing hyphens
        return s.trim('-')
    }

    /**
     * Checks if a candidate identifier matches a leaderboard model entry.
     */
    fun matches(candidateId: String, leaderboardModel: String): Boolean {
        val normCandidate = normalize(candidateId)
        val normLeaderboard = normalize(leaderboardModel)

        if (normCandidate == normLeaderboard) return true

        // Clean hyphen-less versions for fuzzy containment
        val compC = normCandidate.replace("-", "")
        val compL = normLeaderboard.replace("-", "")

        if (compC == compL) return true
        if (compC.contains(compL) || compL.contains(compC)) return true

        return false
    }

    /**
     * Finds the best matching [ModelRankingEntry] for a given candidate ID, or null if no match.
     */
    fun findMatch(candidateId: String, rankings: List<ModelRankingEntry>): ModelRankingEntry? {
        val normCandidate = normalize(candidateId)

        // 1. Exact match on normalized form
        rankings.firstOrNull { normalize(it.model) == normCandidate }?.let { return it }

        // 2. Exact match without hyphens
        val compCandidate = normCandidate.replace("-", "")
        rankings.firstOrNull { normalize(it.model).replace("-", "") == compCandidate }?.let { return it }

        // 3. Substring match (longest matching leaderboard model wins)
        return rankings
            .filter { entry ->
                val compL = normalize(entry.model).replace("-", "")
                compCandidate.contains(compL) || compL.contains(compCandidate)
            }
            .maxByOrNull { normalize(it.model).length }
    }
}
