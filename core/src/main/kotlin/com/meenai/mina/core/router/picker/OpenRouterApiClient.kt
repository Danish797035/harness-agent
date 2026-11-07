package com.meenai.mina.core.router.picker

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * Interface for fetching the OpenRouter models catalog.
 * Placed behind an interface to allow fake/mock HTTP responses in tests.
 */
fun interface OpenRouterApiClient {
    suspend fun fetchModelsJson(): String
}

/**
 * Production OkHttp implementation of [OpenRouterApiClient].
 */
class HttpOpenRouterApiClient(
    private val http: OkHttpClient = OkHttpClient(),
    private val endpointUrl: String = DEFAULT_ENDPOINT,
) : OpenRouterApiClient {

    override suspend fun fetchModelsJson(): String = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(endpointUrl)
            .get()
            .header("User-Agent", "HarnessAgent")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("OpenRouter API returned HTTP ${resp.code}")
            }
            resp.body?.string() ?: throw IOException("Empty response body from OpenRouter")
        }
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://openrouter.ai/api/v1/models"
    }
}
