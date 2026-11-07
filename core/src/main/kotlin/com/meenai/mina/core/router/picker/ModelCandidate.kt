package com.meenai.mina.core.router.picker

import kotlinx.serialization.Serializable

/**
 * Represents a model candidate available for routing.
 *
 * @property provider Model provider name (e.g. "openrouter", "google", "groq").
 * @property id Provider-specific model identifier (e.g. "meta-llama/llama-3.3-70b-instruct:free").
 * @property contextTokens Context window limit in tokens.
 * @property supportsTools Whether the model supports function / tool calling.
 * @property supportsImages Whether the model supports vision / image input.
 * @property isFree Whether the model is accessible on a free tier or with free key.
 * @property tierRank Tier rank score (lower is higher priority; e.g. 1 is highest priority).
 * @property releaseDateEpoch Epoch timestamp (seconds or milliseconds) of model release/creation.
 * @property parameterSizeB Parameter size in billions (e.g. 70.0 for 70B, 8.0 for 8B).
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
)
