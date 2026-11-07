package com.meenai.mina.core.router.preset

import com.meenai.mina.core.router.picker.CuratedTiers
import com.meenai.mina.core.router.picker.ModelCandidate
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

                val lowerId = id.lowercase()
                val supportsImages = lowerId.contains("vl") ||
                    lowerId.contains("vision") ||
                    lowerId.contains("omni")
                val paramSize = CuratedTiers.extractParameterSizeB(id)
                val rank = CuratedTiers.computeTierRank(id)

                ModelCandidate(
                    provider = "modelscope",
                    id = id,
                    contextTokens = 32_000,
                    supportsTools = true,
                    supportsImages = supportsImages,
                    isFree = true,
                    tierRank = rank,
                    releaseDateEpoch = created,
                    parameterSizeB = paramSize,
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
