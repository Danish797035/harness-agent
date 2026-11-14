package com.meenai.mina.core.image

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Image client connecting to the local harness-image microservice
 * (OpenAI-compatible image generation API driving LM Arena).
 */
class HarnessImageClient(
    private val baseUrl: String = "http://127.0.0.1:3000/v1",
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(130, TimeUnit.SECONDS)
        .build(),
    private val logger: ((String) -> Unit)? = null,
) : ImageClient {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun generate(
        apiKey: String,
        model: String,
        prompt: String,
        size: String?,
    ): ByteArray {
        val root = baseUrl.trimEnd('/')
        val url = "$root/images/generations"

        logger?.invoke("HarnessImageClient: generating with model=$model at $url")

        val payload = buildJsonObject {
            put("prompt", prompt)
            put("model", model.ifBlank { "seedream-5.0-lite" })
            if (!size.isNullOrBlank()) {
                put("size", size)
            }
        }.toString()

        val req = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        val respBody = executeHttp(req)
        return parseImageResponse(respBody)
    }

    override suspend fun edit(
        apiKey: String,
        model: String,
        prompt: String,
        imageBase64: String,
    ): ByteArray {
        val root = baseUrl.trimEnd('/')
        val url = "$root/images/edits"

        val payload = buildJsonObject {
            put("prompt", prompt)
            put("model", model)
            put("image", imageBase64)
        }.toString()

        val req = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        val respBody = executeHttp(req)
        return parseImageResponse(respBody)
    }

    private suspend fun parseImageResponse(responseJson: String): ByteArray {
        val obj = json.parseToJsonElement(responseJson).jsonObject
        val dataArray = obj["data"]?.jsonArray
            ?: throw ImageClientException("Invalid response: missing 'data' array")

        if (dataArray.isEmpty()) {
            throw ImageClientException("Empty image data returned from harness-image service")
        }

        val firstItem = dataArray[0].jsonObject
        val b64 = firstItem["b64_json"]?.jsonPrimitive?.content
        if (!b64.isNullOrBlank()) {
            return Base64.getDecoder().decode(b64)
        }

        val imgUrl = firstItem["url"]?.jsonPrimitive?.content
            ?: throw ImageClientException("No url or b64_json found in image response")

        return downloadImageBytes(imgUrl)
    }

    private suspend fun downloadImageBytes(imageUrl: String): ByteArray = suspendCancellableCoroutine { cont ->
        val req = Request.Builder().url(imageUrl).get().build()
        val call = http.newCall(req)
        cont.invokeOnCancellation { call.cancel() }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(ImageClientException("Failed to download generated image: ${e.message}", e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    if (!res.isSuccessful) {
                        if (cont.isActive) cont.resumeWithException(ImageClientException("HTTP ${res.code} while downloading image: $imageUrl"))
                        return
                    }
                    val bytes = res.body?.bytes()
                    if (bytes != null && bytes.isNotEmpty()) {
                        if (cont.isActive) cont.resume(bytes)
                    } else {
                        if (cont.isActive) cont.resumeWithException(ImageClientException("Empty image bytes downloaded from $imageUrl"))
                    }
                }
            }
        })
    }

    private suspend fun executeHttp(req: Request): String = suspendCancellableCoroutine { cont ->
        val call = http.newCall(req)
        cont.invokeOnCancellation { call.cancel() }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(ImageClientException("Local harness-image service connection failed: ${e.message}", e))
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
