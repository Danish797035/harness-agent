package com.meenai.mina.core.router.picker

import kotlin.math.ln

/**
 * Scoring and ranking engine for model candidates.
 *
 * Implements heavy-to-light ranking and internet benchmark score integration:
 * 1. Sabse heavy upar (largest parameter capacity / MoE total parameters).
 * 2. Unknown size gets a fair baseline (~20B), NOT 0 (so it never sinks below 0.5B/1B models).
 * 3. Context window (logarithmic scaling).
 * 4. Extended reasoning / thinking capabilities.
 * 5. Vision / multimodal capabilities.
 * 6. Nayapan (newer release date timestamp as fine tie-breaker).
 * 7. Leaderboard ranking match (LMSYS Chatbot Arena, Open LLM Leaderboard):
 *    - Overall score, coding score, tool use score, vision score, speed score.
 *    - If no match found, scores purely using live data.
 */
object ModelScorer {

    /**
     * Computes the heaviness / capability score for a [ModelCandidate] using live data,
     * enhanced by public leaderboard rankings, probe measurements, and 7-day real-use stats.
     */
    fun computeScore(
        model: ModelCandidate,
        ranking: ModelRankingEntry? = null,
        role: ModelRole = ModelRole.Main,
        probeResult: ProbeResult? = null,
        statsTracker: ModelStatsTracker? = null,
    ): Double {
        // Probe disqualification: models that failed probe today get disqualified
        if (probeResult != null && !probeResult.passed) {
            return -1000.0
        }

        // 1. Parameter size component
        val sizeScore = when {
            model.totalParamsB != null -> model.totalParamsB
            model.parameterSizeB > 0.0 -> model.parameterSizeB
            else -> {
                // Size na mile to unknown, 0 nahi!
                // Baseline for modern models with unknown size (e.g. Gemini 2.0 Flash)
                var estimate = 20.0
                if (model.contextTokens >= 500_000) estimate += 15.0
                if (model.supportsReasoning) estimate += 10.0
                estimate
            }
        }

        // 2. Context tokens score (32k -> ~6.9, 128k -> ~9.7, 1M -> ~13.8)
        val ctxScore = if (model.contextTokens > 0) {
            ln((model.contextTokens / 1000.0).coerceAtLeast(1.0)) * 2.0
        } else 0.0

        // 3. Reasoning & Vision capabilities
        val featureScore = (if (model.supportsReasoning) 5.0 else 0.0) +
            (if (model.supportsImages) 2.0 else 0.0)

        // 4. Recency bonus (small tie-breaker for newer models)
        val recencyScore = if (model.releaseDateEpoch > 0L) {
            (model.releaseDateEpoch.toDouble() / 1_000_000_000.0) * 0.001
        } else 0.0

        // 5. Probe measurement bonus/penalty
        val probeBonus = if (probeResult != null && probeResult.passed) {
            when {
                probeResult.firstTokenMs in 1..3000 -> 10.0
                probeResult.firstTokenMs in 3001..8000 -> 5.0
                probeResult.firstTokenMs > 15000 -> -15.0
                else -> 2.0
            }
        } else 0.0

        // 6. Asli use bonus (from 7-day stats)
        val realUseBonus = statsTracker?.computeRealUseBonus(model.id) ?: 0.0

        val liveDataScore = sizeScore + ctxScore + featureScore + recencyScore + probeBonus + realUseBonus

        // 7. Leaderboard score enhancement if matched
        if (ranking == null) {
            // Match na ho to sirf live data se score
            return liveDataScore
        }

        val benchmarkComponent = when (role) {
            ModelRole.Coding -> ranking.codingScore * 0.6 + ranking.overallScore * 0.4
            ModelRole.Vision -> ranking.visionScore * 0.6 + ranking.overallScore * 0.4
            ModelRole.Main -> ranking.toolUseScore * 0.5 + ranking.overallScore * 0.5
            ModelRole.Light -> ranking.speedScore * 0.5 + (100.0 - (model.parameterSizeB.coerceAtMost(70.0))) * 0.5
            ModelRole.Long -> ranking.overallScore * 0.5 + ctxScore * 5.0
        }

        // Blend live capability score with benchmark score (50% live capacity + 50% benchmark score)
        return (liveDataScore * 0.5) + (benchmarkComponent * 0.5)
    }

    /**
     * Heavy-to-light comparator using live data only.
     */
    val HEAVY_TO_LIGHT_COMPARATOR: Comparator<ModelCandidate> = Comparator { a, b ->
        val scoreDiff = computeScore(b).compareTo(computeScore(a))
        if (scoreDiff != 0) return@Comparator scoreDiff

        val ctxDiff = b.contextTokens.compareTo(a.contextTokens)
        if (ctxDiff != 0) return@Comparator ctxDiff

        val dateDiff = b.releaseDateEpoch.compareTo(a.releaseDateEpoch)
        if (dateDiff != 0) return@Comparator dateDiff

        a.id.compareTo(b.id)
    }

    /**
     * Creates a comparator taking into account role-specific requirements, leaderboard rankings, probe, and stats.
     */
    fun createComparator(
        repository: ModelRankingsRepository? = null,
        role: ModelRole = ModelRole.Main,
        probeService: ModelProbeService? = null,
        statsTracker: ModelStatsTracker? = null,
    ): Comparator<ModelCandidate> = Comparator { a, b ->
        val rankA = repository?.findRanking(a.id)
        val rankB = repository?.findRanking(b.id)
        val probeA = probeService?.getProbeResult(a.id)
        val probeB = probeService?.getProbeResult(b.id)

        val scoreA = computeScore(a, rankA, role, probeA, statsTracker)
        val scoreB = computeScore(b, rankB, role, probeB, statsTracker)

        val scoreDiff = scoreB.compareTo(scoreA)
        if (scoreDiff != 0) return@Comparator scoreDiff

        val ctxDiff = b.contextTokens.compareTo(a.contextTokens)
        if (ctxDiff != 0) return@Comparator ctxDiff

        a.id.compareTo(b.id)
    }
}
