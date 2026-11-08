package com.meenai.mina.core.router.picker

import kotlinx.serialization.Serializable

/**
 * Represents a model candidate available for routing.
 *
 * @property provider Model provider name (e.g. "openrouter", "google", "groq", "modelscope", "zai").
 * @property id Provider-specific model identifier (e.g. "meta-llama/llama-3.3-70b-instruct:free").
 * @property contextTokens Context window limit in tokens.
 * @property supportsTools Whether the model supports function / tool calling.
 * @property supportsImages Whether the model supports vision / image input.
 * @property isFree Whether the model is accessible on a free tier or with free key.
 * @property tierRank Legacy rank score (kept for backwards compatibility).
 * @property releaseDateEpoch Epoch timestamp (seconds or milliseconds) of model release/creation.
 * @property parameterSizeB Parameter size in billions (e.g. 70.0 for 70B, 8.0 for 8B).
 * @property totalParamsB Total parameter size in billions (e.g. 397.0 for 397B-A17B). Null if unknown.
 * @property activeParamsB Active parameter size in billions for MoE models (e.g. 17.0 for 397B-A17B).
 * @property maxOutputTokens Maximum output tokens limit.
 * @property supportsReasoning Whether the model supports extended reasoning / thinking.
 */
@Serializable
data class ModelCandidate(
    val provider: String,
    val id: String,
    val contextTokens: Int,
    val supportsTools: Boolean,
    val supportsImages: Boolean,
    val isFree: Boolean,
    val tierRank: Int = Int.MAX_VALUE,
    val releaseDateEpoch: Long = 0L,
    val parameterSizeB: Double = 0.0,
    val totalParamsB: Double? = if (parameterSizeB > 0.0) parameterSizeB else null,
    val activeParamsB: Double? = null,
    val maxOutputTokens: Int? = null,
    val supportsReasoning: Boolean = false,
) {
    val sizeLabel: String get() = when {
        totalParamsB != null && activeParamsB != null && activeParamsB != totalParamsB ->
            "${format(totalParamsB)}-A${format(activeParamsB)}"
        totalParamsB != null -> format(totalParamsB)
        else -> "Unknown"
    }

    private fun format(v: Double): String =
        if (v % 1.0 == 0.0) "${v.toLong()}B" else "${v}B"
}
