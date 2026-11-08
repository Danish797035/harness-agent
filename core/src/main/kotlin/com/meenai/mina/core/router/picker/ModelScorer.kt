package com.meenai.mina.core.router.picker

import kotlin.math.ln

/**
 * Scoring and ranking engine for model candidates.
 *
 * Implements heavy-to-light ranking:
 * 1. Sabse heavy upar (largest parameter capacity / MoE total parameters).
 * 2. Unknown size gets a fair baseline (~20B), NOT 0 (so it never sinks below 0.5B/1B models).
 * 3. Context window (logarithmic scaling).
 * 4. Extended reasoning / thinking capabilities.
 * 5. Vision / multimodal capabilities.
 * 6. Nayapan (newer release date timestamp as fine tie-breaker).
 */
object ModelScorer {

    /**
     * Computes the heaviness / capability score for a [ModelCandidate].
     */
    fun computeScore(model: ModelCandidate): Double {
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

        return sizeScore + ctxScore + featureScore + recencyScore
    }

    /**
     * Comparator for sorting models from heaviest to lightest:
     * - Heaviest score first
     * - Larger context window next
     * - Model ID ascending as deterministic tie-breaker
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
}
