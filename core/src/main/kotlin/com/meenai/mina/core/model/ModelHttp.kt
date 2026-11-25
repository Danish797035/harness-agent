package com.meenai.mina.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * In addition to the conversation adapter, the same method should be used to report errors (such as
 * voice-to-text): send a request to convert the HTTP status and error content into [ModelError]. The
 * mapping itself is moved from each adapter as it is, and the adapter is changed here to avoid changing
 * the two copies.
 */
object ModelHttp {
    class Reply(val status: Int, val retryAfter: String?, val body: String) {
        val ok: Boolean get() = status in 200..299
    }

    /** Send a request and read the reply; if the network fails, [ModelError.Network] will be thrown. Canceling the coroutine will cancel the request. */
    suspend fun send(http: OkHttpClient, request: Request): Reply =
        HttpSupport.execute(http, request).let { Reply(it.status, it.retryAfter, it.body) }

    /** Wrong mapping of OpenAI compatible interfaces (same set of dialogue and transcription). [model] is the requested model name. */
    fun openAiError(status: Int, retryAfter: String?, body: String, model: String): ModelError =
        ModelAccessErrors.of(status, body, model) ?: when {
            status == 401 -> ModelError.Auth()
            status == 402 || body.contains("insufficient_quota") -> ModelError.QuotaExceeded()
            status == 429 -> ModelError.RateLimited(HttpSupport.retryAfterMs(retryAfter))
            status == 400 && (body.contains("context_length") || body.contains("maximum context")) -> ModelError.ContextTooLong()
            status == 404 -> ModelError.ModelNotFound(model, ModelAccessErrors.jsonDetail(body))
            status in 500..599 -> ModelError.Server(status)
            status == 403 -> ModelError.Auth()
            else -> ModelError.BadResponse("HTTP $status ${body.take(200)}")
        }

    private val json = Json { ignoreUnknownKeys = true }

    /** Wrong mapping of Gemini native interfaces. */
    fun geminiError(status: Int, retryAfter: String?, body: String, model: String): ModelError {
        val err = try { json.parseToJsonElement(body).jsonObject["error"] as? JsonObject } catch (e: Exception) { null }
        val st = (err?.get("status") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val msg = (err?.get("message") as? JsonPrimitive)?.contentOrNull ?: body
        val lower = msg.lowercase()
        ModelAccessErrors.of(status, body, model)?.let { return it }
        return when {
            status == 401 || status == 403 -> ModelError.Auth()
            // The 429 of this model with zero quota have been deemed unusable; the remaining 429 are
            // treated as retryable current limits (its current limit and quota copy are mixed and cannot
            // be reliably distinguished)
            status == 429 || st == "RESOURCE_EXHAUSTED" -> ModelError.RateLimited(HttpSupport.retryAfterMs(retryAfter))
            status == 404 -> ModelError.ModelNotFound(model, ModelAccessErrors.jsonDetail(body))
            status == 413 || (status == 400 && lower.contains("token") &&
                (lower.contains("exceed") || lower.contains("too long") || lower.contains("maximum"))) -> ModelError.ContextTooLong()
            status in 500..599 -> ModelError.Server(status)
            else -> ModelError.BadResponse("HTTP $status ${body.take(200)}")
        }
    }
}
