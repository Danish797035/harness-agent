package com.meenai.mina.core.image

import com.meenai.mina.core.router.MagicubeLedger

sealed class ImageResult {
    data class Success(
        val bytes: ByteArray,
        val model: String,
        val costMagicube: Double,
    ) : ImageResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Success
            if (!bytes.contentEquals(other.bytes)) return false
            if (model != other.model) return false
            if (costMagicube != other.costMagicube) return false
            return true
        }

        override fun hashCode(): Int {
            var result = bytes.contentHashCode()
            result = 31 * result + model.hashCode()
            result = 31 * result + costMagicube.hashCode()
            return result
        }
    }

    data class Error(
        val message: String,
        val cause: Throwable? = null,
    ) : ImageResult()
}

class ImageGenerationService(
    private val modelScopeClient: ImageClient,
    private val openRouterClient: ImageClient? = null,
    private val getApiKey: (provider: String) -> String?,
    private val getPinnedModel: (role: ImageRole) -> String? = { null },
    private val isPaidAllowed: () -> Boolean = { false },
    private val logger: ((String) -> Unit)? = null,
) {
    companion object {
        const val MAX_SWITCHES = 2
        const val INSUFFICIENT_MAGICUBE_MSG = "Aaj ke Magicube kam hain"
    }

    suspend fun generateImage(
        prompt: String,
        size: String? = null,
    ): ImageResult {
        return executeWithFailover(ImageRole.Image) { client, apiKey, model ->
            client.generate(apiKey, model, prompt, size)
        }
    }

    suspend fun editImage(
        prompt: String,
        imageBase64: String,
    ): ImageResult {
        return executeWithFailover(ImageRole.ImageEdit) { client, apiKey, model ->
            client.edit(apiKey, model, prompt, imageBase64)
        }
    }

    private suspend fun executeWithFailover(
        role: ImageRole,
        action: suspend (client: ImageClient, apiKey: String, model: String) -> ByteArray,
    ): ImageResult {
        // 1. Quota check: if ModelScope is used, check Magicube balance (minimum 5.0 required)
        if (!MagicubeLedger.canAffordModelScope()) {
            return ImageResult.Error(INSUFFICIENT_MAGICUBE_MSG)
        }

        // 2. Resolve candidate models
        val candidates = resolveCandidates(role)
        if (candidates.isEmpty()) {
            return ImageResult.Error("Koi image model uplabdh nahi hai")
        }

        var switches = 0
        var lastError: Throwable? = null

        for (candidate in candidates) {
            val apiKey = getApiKey(candidate.provider)
            if (apiKey.isNullOrBlank()) {
                continue
            }

            val client = when (candidate.provider) {
                ImageModelCatalog.PROVIDER_MODELSCOPE -> modelScopeClient
                ImageModelCatalog.PROVIDER_OPENROUTER -> openRouterClient
                else -> modelScopeClient
            } ?: continue

            try {
                logger?.invoke("Attempting image generation with model: ${candidate.id} (${candidate.provider})")
                val bytes = action(client, apiKey, candidate.id)

                // Successful call: deduct cost if modelscope
                val cost = if (candidate.provider == ImageModelCatalog.PROVIDER_MODELSCOPE) {
                    val c = MagicubeLedger.costForModel(candidate.id)
                    MagicubeLedger.deduct(c)
                    c
                } else {
                    0.0
                }

                return ImageResult.Success(bytes, candidate.id, cost)
            } catch (t: Throwable) {
                lastError = t
                switches++
                logger?.invoke("Model ${candidate.id} failed, silent failover count: $switches")
                if (switches > MAX_SWITCHES) {
                    break
                }
                // Silent failover: chupke se agla model, no toast/error shown
            }
        }

        return ImageResult.Error(
            lastError?.message ?: "Image generation failed",
            lastError
        )
    }

    fun resolveCandidates(role: ImageRole): List<ImageModelCandidate> {
        val baseList = when (role) {
            ImageRole.Image -> ImageModelCatalog.DEFAULT_IMAGE_MODELS
            ImageRole.ImageEdit -> ImageModelCatalog.DEFAULT_IMAGE_EDIT_MODELS
        }

        val paidAllowed = isPaidAllowed()
        val allList = if (paidAllowed && role == ImageRole.Image) {
            baseList + ImageModelCatalog.OPENROUTER_PAID_IMAGE_MODELS
        } else {
            baseList
        }

        val pinned = getPinnedModel(role)
        if (!pinned.isNullOrBlank()) {
            val match = allList.find { it.id == pinned }
            if (match != null) {
                return listOf(match) + allList.filter { it.id != pinned }
            }
        }

        return allList
    }
}
