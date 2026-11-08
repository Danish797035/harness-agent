package com.meenai.mina.core.router.preset

import com.meenai.mina.core.router.picker.ChatModelFilter
import com.meenai.mina.core.router.picker.ModelCandidate
import com.meenai.mina.core.router.picker.ModelSizeParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Interface to fetch dynamic models from ModelScope's OpenAI-compatible /v1/models endpoint.
 */
interface ModelScopeApiClient {
    suspend fun listModels(apiKey: String): List<ModelCandidate>
}

/**
 * Default HTTP implementation querying ModelScope /v1/models.
 */
class HttpModelScopeApiClient(
    val baseUrl: String = "https://api-inference.modelscope.ai/v1",
    val httpClient: OkHttpClient = OkHttpClient(),
) : ModelScopeApiClient {

    companion object {
        val NON_CHAT_TOKENS = listOf(
            "embedding", "embeddings", "rerank", "reranker", "bge",
            "image", "images", "flux", "stable-diffusion", "diffusers",
            "video", "audio", "tts", "stt", "asr", "whisper", "speech", "voice",
            "moderation", "guard", "reward", "discriminator",
            "image-edit", "image_edit", "imageedit",
            "image-gen", "image_gen", "imagegen", "image-generation", "image_generation",
            "t2i", "i2i", "text-to-image", "image-to-image"
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun listModels(apiKey: String): List<ModelCandidate> = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/models")
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .get()
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                return@withContext emptyList()
            }
            val body = response.body?.string().orEmpty()
            parseModelsJson(body)
        }
    }

    fun parseModelsJson(jsonString: String): List<ModelCandidate> {
        return try {
            val root = json.parseToJsonElement(jsonString).jsonObject
            val data = root["data"]?.jsonArray ?: return emptyList()

            data.mapNotNull { element ->
                val obj = element.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val created = obj["created"]?.jsonPrimitive?.longOrNull ?: 0L

                // Filter out non-chat, image-gen, and image-edit models
                if (ChatModelFilter.isExcluded(id)) return@mapNotNull null

                val lowerId = id.lowercase()
                if (NON_CHAT_TOKENS.any { lowerId.contains(it) }) return@mapNotNull null

                val supportsImages = ChatModelFilter.supportsVision(id)
                val size = ModelSizeParser.parse(id)

                val supportsReasoning = id.contains("r1", ignoreCase = true) ||
                    id.contains("reasoner", ignoreCase = true) ||
                    id.contains("thinking", ignoreCase = true)

                ModelCandidate(
                    provider = "modelscope",
                    id = id,
                    contextTokens = 32_000,
                    supportsTools = true,
                    supportsImages = supportsImages,
                    isFree = true,
                    tierRank = Int.MAX_VALUE,
                    releaseDateEpoch = created,
                    parameterSizeB = size.totalParamsB ?: 0.0,
                    totalParamsB = size.totalParamsB,
                    activeParamsB = size.activeParamsB,
                    supportsReasoning = supportsReasoning,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
