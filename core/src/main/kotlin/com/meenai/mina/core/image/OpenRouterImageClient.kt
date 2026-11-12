package com.meenai.mina.core.image

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OpenRouterImageClient(
    private val baseUrl: String = "https://openrouter.ai/api/v1",
    private val http: OkHttpClient = OkHttpClient(),
    private val logger: ((String) -> Unit)? = null,
) : ImageClient {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun generate(
        apiKey: String,
        model: String,
        prompt: String,
        size: String?,
    ): ByteArray {
        val payload = buildJsonObject {
            put("model", model)
            putJsonArray("modalities") {
                add(JsonPrimitive("image"))
                add(JsonPrimitive("text"))
            }
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", prompt)
                })
            }
        }
        return executeChatCompletion(apiKey, payload)
    }

    override suspend fun edit(
        apiKey: String,
        model: String,
        prompt: String,
        imageBase64: String,
    ): ByteArray {
        val imageUrl = if (imageBase64.startsWith("data:")) imageBase64 else "data:image/png;base64,$imageBase64"
        val payload = buildJsonObject {
            put("model", model)
            putJsonArray("modalities") {
                add(JsonPrimitive("image"))
                add(JsonPrimitive("text"))
            }
            putJsonArray("messages") {
                add(buildJsonObject {
                    put("role", "user")
                    putJsonArray("content") {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", prompt)
                        })
                        add(buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") {
                                put("url", imageUrl)
                            }
                        })
                    }
                })
            }
        }
        return executeChatCompletion(apiKey, payload)
    }

    private suspend fun executeChatCompletion(apiKey: String, payload: JsonObject): ByteArray {
        val url = if (baseUrl.endsWith("/chat/completions")) {
            baseUrl
        } else {
            "${baseUrl.trimEnd('/')}/chat/completions"
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = payload.toString().toRequestBody(mediaType)
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody)
            .build()

        logger?.invoke("Submitting OpenRouter image request to $url with model ${payload["model"]}")
        val responseBody = executeHttp(request)
        return extractImageBytes(responseBody)
    }

    private fun extractImageBytes(responseBody: String): ByteArray {
        val root = try {
            json.parseToJsonElement(responseBody).jsonObject
        } catch (e: Exception) {
            throw ImageClientException("Invalid JSON response from OpenRouter", e)
        }

        if (root["error"] != null) {
            val errorMsg = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                ?: root["error"].toString()
            throw ImageClientException("OpenRouter error: $errorMsg")
        }

        val choices = root["choices"]?.jsonArray
        val choice = choices?.firstOrNull()?.jsonObject
            ?: throw ImageClientException("No choices returned from OpenRouter")
        val message = choice["message"]?.jsonObject
            ?: throw ImageClientException("No message in choice from OpenRouter")

        // 1. Check message["images"] array
        val images = message["images"]?.jsonArray
        if (images != null && images.isNotEmpty()) {
            val firstImg = images[0]
            val imgStr = when (firstImg) {
                is JsonObject -> firstImg["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content
                    ?: firstImg["url"]?.jsonPrimitive?.content
                is JsonPrimitive -> firstImg.content
                else -> null
            }
            if (!imgStr.isNullOrBlank()) {
                return parseOrDownloadImage(imgStr)
            }
        }

        // 2. Check message["content"]
        when (val content = message["content"]) {
            is JsonPrimitive -> {
                val text = content.content
                val extracted = extractUrlOrBase64(text)
                if (extracted != null) {
                    return parseOrDownloadImage(extracted)
                }
            }
            is JsonArray -> {
                for (part in content) {
                    val obj = part as? JsonObject ?: continue
                    if (obj["type"]?.jsonPrimitive?.content == "image_url") {
                        val url = obj["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content
                        if (!url.isNullOrBlank()) {
                            return parseOrDownloadImage(url)
                        }
                    }
                }
            }
            else -> {}
        }

        throw ImageClientException("No image found in OpenRouter response: $responseBody")
    }

    private fun extractUrlOrBase64(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.startsWith("data:image/") || trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }
        val mdMatch = Regex("""!\[.*?\]\((https?://[^\s)]+|data:image/[^\s)]+)\)""").find(text)
        if (mdMatch != null) {
            return mdMatch.groupValues[1]
        }
        val urlMatch = Regex("""(https?://[^\s)]+|data:image/[a-zA-Z0-9+.-]+;base64,[A-Za-z0-9+/=]+)""").find(text)
        if (urlMatch != null) {
            return urlMatch.groupValues[1]
        }
        return null
    }

    private fun parseOrDownloadImage(target: String): ByteArray {
        if (target.startsWith("data:image/")) {
            val commaIdx = target.indexOf(',')
            if (commaIdx != -1) {
                val b64 = target.substring(commaIdx + 1)
                return Base64.getDecoder().decode(b64.trim())
            }
        }
        if (target.startsWith("http://") || target.startsWith("https://")) {
            return downloadImageSync(target)
        }
        return runCatching { Base64.getDecoder().decode(target.trim()) }
            .getOrElse { throw ImageClientException("Unsupported image format in OpenRouter response: ${target.take(100)}") }
    }

    private fun downloadImageSync(url: String): ByteArray {
        val req = Request.Builder().url(url).get().build()
        val response = try {
            http.newCall(req).execute()
        } catch (e: Exception) {
            throw ImageClientException("Failed to download image from $url: ${e.message}", e)
        }
        response.use { res ->
            if (!res.isSuccessful) {
                throw ImageClientException("HTTP ${res.code} downloading image from $url")
            }
            return res.body?.bytes() ?: ByteArray(0)
        }
    }

    private suspend fun executeHttp(req: Request): String = suspendCancellableCoroutine { cont ->
        val call = http.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(ImageClientException("HTTP request failed: ${e.message}", e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    val body = res.body?.string().orEmpty()
                    if (!res.isSuccessful) {
                        if (cont.isActive) {
                            cont.resumeWithException(ImageClientException("HTTP ${res.code}: $body"))
                        }
                    } else {
                        if (cont.isActive) cont.resume(body)
                    }
                }
            }
        })
    }
}
