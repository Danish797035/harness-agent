package com.meenai.mina.core.image

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.random.Random

/**
 * 100% Free, zero-key image client using Pollinations.ai.
 * Acts as an infallible backup generator for FLUX models.
 */
class PollinationsImageClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build(),
    private val logger: ((String) -> Unit)? = null,
    private val baseUrl: String = "https://image.pollinations.ai",
) : ImageClient {

    override suspend fun generate(
        apiKey: String,
        model: String,
        prompt: String,
        size: String?,
    ): ByteArray {
        val encodedPrompt = URLEncoder.encode(prompt, "UTF-8").replace("+", "%20")
        val apiModel = model.trim().removeSuffix("-pollinations").ifBlank { "flux" }
        val encodedModel = URLEncoder.encode(apiModel, "UTF-8").replace("+", "%20")
        val seed = Random.nextInt(1, 999999)
        var width = 1024
        var height = 1024
        if (!size.isNullOrBlank() && size.contains("x")) {
            val parts = size.split("x")
            val w = parts.getOrNull(0)?.toIntOrNull()
            val h = parts.getOrNull(1)?.toIntOrNull()
            if (w != null && h != null && w in 256..2048 && h in 256..2048) {
                width = w
                height = h
            }
        }
        val url = "${baseUrl.trimEnd('/')}/prompt/$encodedPrompt?model=$encodedModel&width=$width&height=$height&nologo=true&seed=$seed"

        logger?.invoke("PollinationsImageClient: fetching image from $url")
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "HarnessAgent/1.0")
            .get()
            .build()

        return suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(ImageClientException("Pollinations network error: ${e.message}", e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use { res ->
                        if (!res.isSuccessful) {
                            if (cont.isActive) cont.resumeWithException(ImageClientException("Pollinations error HTTP ${res.code}: ${res.message}"))
                            return
                        }
                        val contentType = res.header("Content-Type")?.trim()?.lowercase().orEmpty()
                        if (!contentType.startsWith("image/")) {
                            if (cont.isActive) cont.resumeWithException(ImageClientException("Pollinations returned non-image content-type: $contentType"))
                            return
                        }
                        val bytes = res.body?.bytes()
                        if (bytes == null || bytes.isEmpty()) {
                            if (cont.isActive) cont.resumeWithException(ImageClientException("Pollinations returned empty image payload"))
                            return
                        }
                        if (cont.isActive) cont.resume(bytes)
                    }
                }
            })
        }
    }

    override suspend fun edit(
        apiKey: String,
        model: String,
        prompt: String,
        imageBase64: String,
    ): ByteArray {
        throw UnsupportedOperationException("Pollinations does not support image editing")
    }
}
