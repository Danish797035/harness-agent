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
 * Interface to fetch dynamic models from Z.ai's OpenAI-compatible /models endpoint.
 */
interface ZaiApiClient {
    suspend fun listModels(apiKey: String): List<ModelCandidate>
}

/**
 * Default HTTP client for querying Z.ai /models endpoint.
 */
class HttpZaiApiClient(
    val baseUrl: String = "https://api.z.ai/api/paas/v4",
    val httpClient: OkHttpClient = OkHttpClient(),
) : ZaiApiClient {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun listModels(apiKey: String): List<ModelCandidate> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext defaultModels()

        try {
            val request = Request.Builder()
                .url(baseUrl.trimEnd('/') + "/models")
                .header("Authorization", "Bearer $apiKey")
                .header("Accept", "application/json")
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext defaultModels()
                }
                val body = response.body?.string().orEmpty()
                val parsed = parseModelsJson(body)
                if (parsed.isNotEmpty()) parsed else defaultModels()
            }
        } catch (_: Exception) {
            defaultModels()
        }
    }

    fun parseModelsJson(jsonString: String): List<ModelCandidate> {
        return try {
            val root = json.parseToJsonElement(jsonString).jsonObject
            val data = (root["data"] ?: root["models"])?.jsonArray ?: return emptyList()

            data.mapNotNull { element ->
                val obj = element.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val created = obj["created"]?.jsonPrimitive?.longOrNull ?: 0L

                if (ChatModelFilter.isExcluded(id)) return@mapNotNull null

                val supportsImages = ChatModelFilter.supportsVision(id)
                val size = ModelSizeParser.parse(id)

                val supportsReasoning = id.contains("r1", ignoreCase = true) ||
                    id.contains("reasoner", ignoreCase = true) ||
                    id.contains("thinking", ignoreCase = true)

                ModelCandidate(
                    provider = "zai",
                    id = id,
                    contextTokens = 128_000,
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

    private fun defaultModels(): List<ModelCandidate> {
        val flashSize = ModelSizeParser.parse(ZaiPreset.MODEL_FLASH)
        val visionSize = ModelSizeParser.parse(ZaiPreset.MODEL_VISION)

        return listOf(
            ModelCandidate(
                provider = "zai",
                id = ZaiPreset.MODEL_FLASH,
                contextTokens = 128_000,
                supportsTools = true,
                supportsImages = false,
                isFree = true,
                parameterSizeB = flashSize.totalParamsB ?: 0.0,
                totalParamsB = flashSize.totalParamsB,
                activeParamsB = flashSize.activeParamsB,
            ),
            ModelCandidate(
                provider = "zai",
                id = ZaiPreset.MODEL_VISION,
                contextTokens = 128_000,
                supportsTools = true,
                supportsImages = true,
                isFree = true,
                parameterSizeB = visionSize.totalParamsB ?: 0.0,
                totalParamsB = visionSize.totalParamsB,
                activeParamsB = visionSize.activeParamsB,
            ),
        )
    }
}
