package com.meenai.mina.core.image

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

open class ImageClientException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
class ImageTimeoutException(message: String) : ImageClientException(message)

interface ImageClient {
    suspend fun generate(
        apiKey: String,
        model: String,
        prompt: String,
        size: String? = null,
    ): ByteArray

    suspend fun edit(
        apiKey: String,
        model: String,
        prompt: String,
        imageBase64: String,
    ): ByteArray
}

class ModelScopeImageClient(
    private val baseUrl: String = "https://api-inference.modelscope.ai",
    private val http: OkHttpClient = OkHttpClient(),
    private val pollIntervalMs: Long = 5000L,
    private val maxTimeoutMs: Long = 180_000L,
    private val logger: ((String) -> Unit)? = null,
) : ImageClient {

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun log(message: String) {
        // Strict privacy rule: Log mein key ya image ka base64 kabhi nahi
        logger?.invoke(message)
    }

    override suspend fun generate(
        apiKey: String,
        model: String,
        prompt: String,
        size: String?,
    ): ByteArray {
        val root = baseUrl.trimEnd('/')
        val submitUrl = "$root/v1/images/generations"

        log("Submitting image generation task: model=$model, promptLength=${prompt.length}")

        val payload = buildJsonObject {
            put("model", model)
            put("prompt", prompt)
            if (!size.isNullOrBlank()) {
                put("size", size)
            }
        }.toString()

        val submitReq = Request.Builder()
            .url(submitUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("X-ModelScope-Async-Mode", "true")
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        val submitRespBody = executeHttp(submitReq)
        val taskId = extractTaskId(submitRespBody)
        log("Task submitted successfully: taskId=$taskId")

        return pollTask(root, apiKey, taskId)
    }

    override suspend fun edit(
        apiKey: String,
        model: String,
        prompt: String,
        imageBase64: String,
    ): ByteArray {
        val root = baseUrl.trimEnd('/')
        val submitUrl = "$root/v1/images/generations"

        val formattedImage = if (imageBase64.startsWith("data:")) imageBase64 else "data:image/jpeg;base64,$imageBase64"

        // Notice: NEVER log formattedImage or base64
        log("Submitting image edit task: model=$model, promptLength=${prompt.length}")

        val payload = buildJsonObject {
            put("model", model)
            put("prompt", prompt)
            put("image", formattedImage)
            put("image_url", formattedImage)
        }.toString()

        val submitReq = Request.Builder()
            .url(submitUrl)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .header("X-ModelScope-Async-Mode", "true")
            .post(payload.toRequestBody(jsonMediaType))
            .build()

        val submitRespBody = executeHttp(submitReq)
        val taskId = extractTaskId(submitRespBody)
        log("Edit task submitted successfully: taskId=$taskId")

        return pollTask(root, apiKey, taskId)
    }

    private suspend fun pollTask(root: String, apiKey: String, taskId: String): ByteArray {
        val pollUrl = "$root/v1/tasks/$taskId"
        val startTime = System.currentTimeMillis()
        var consecutiveFailures = 0

        while (true) {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed >= maxTimeoutMs) {
                log("Task $taskId timed out after ${elapsed}ms")
                throw ImageTimeoutException("Task timed out after ${maxTimeoutMs}ms")
            }

            if (pollIntervalMs > 0) {
                delay(pollIntervalMs)
            }

            val pollReq = Request.Builder()
                .url(pollUrl)
                .header("Authorization", "Bearer $apiKey")
                .header("X-ModelScope-Task-Type", "image_generation")
                .get()
                .build()

            val parsed = try {
                val respBody = executeHttp(pollReq)
                val obj = json.parseToJsonElement(respBody).jsonObject
                consecutiveFailures = 0
                obj
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                consecutiveFailures++
                log("Transient poll error ($consecutiveFailures/5): ${e.message}")
                if (consecutiveFailures >= 5) {
                    throw ImageClientException("ModelScope task polling failed after 5 consecutive errors", e)
                }
                null
            } ?: continue

            val status = parsed["task_status"]?.jsonPrimitive?.content
                ?: parsed["status"]?.jsonPrimitive?.content
            log("Polled taskId=$taskId: status=$status")

            when (status) {
                "SUCCEED" -> {
                    val imageUrl = extractImageUrl(parsed)
                    log("Task succeeded, downloading image")
                    return downloadImage(imageUrl)
                }
                "FAILED" -> {
                    val message = parsed["message"]?.jsonPrimitive?.content ?: "Task failed"
                    log("Task failed: $message")
                    throw ImageClientException("ModelScope task failed: $message")
                }
                else -> {
                    // PENDING, RUNNING, or other progress status: continue loop
                }
            }
        }
    }

    private fun extractTaskId(responseBody: String): String {
        return try {
            val obj = json.parseToJsonElement(responseBody).jsonObject
            obj["task_id"]?.jsonPrimitive?.content
                ?: throw ImageClientException("task_id missing in response: $responseBody")
        } catch (e: Exception) {
            if (e is ImageClientException) throw e
            throw ImageClientException("Invalid JSON response when submitting task", e)
        }
    }

    private fun extractImageUrl(obj: JsonObject): String {
        val array = obj["output_images"]?.jsonArray
        if (array != null && array.isNotEmpty()) {
            return array[0].jsonPrimitive.content
        }
        val results = obj["results"]?.jsonArray
        if (results != null && results.isNotEmpty()) {
            val first = results[0]
            if (first is JsonObject) {
                first["url"]?.jsonPrimitive?.content?.let { return it }
            } else {
                return first.jsonPrimitive.content
            }
        }
        val outputObj = obj["output"]?.jsonObject
        outputObj?.get("image_url")?.jsonPrimitive?.content?.let { return it }

        throw ImageClientException("No image URL found in output_images")
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

    private suspend fun downloadImage(url: String): ByteArray = suspendCancellableCoroutine { cont ->
        val req = Request.Builder().url(url).get().build()
        val call = http.newCall(req)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(ImageClientException("Failed to download image: ${e.message}", e))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { res ->
                    if (!res.isSuccessful) {
                        if (cont.isActive) cont.resumeWithException(ImageClientException("HTTP ${res.code} downloading image"))
                    } else {
                        val bytes = res.body?.bytes() ?: ByteArray(0)
                        if (cont.isActive) cont.resume(bytes)
                    }
                }
            }
        })
    }
}
