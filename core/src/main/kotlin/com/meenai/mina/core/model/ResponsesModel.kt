package com.meenai.mina.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The Bearer token brought with the request. If [fresh] is true, it means that the previous one was
 * rejected (HTTP 401), and a new one needs to be replaced first; if it cannot be replaced, throw
 * [ModelError]. No token (local service) is null. [refreshable] is false (fixed API Key) and will not be
 * retried after being rejected.
 */
interface BearerToken {
    val refreshable: Boolean get() = false
    suspend fun get(fresh: Boolean): String?

    companion object {
        /** Fixed API Key. */
        fun fixed(key: String?): BearerToken = object : BearerToken {
            override suspend fun get(fresh: Boolean): String? = key
        }
    }
}

/**
 * The OpenAI Responses interface (`POST {base}/responses`) is always streaming and does not store
 * conversations on the server (`store:false`): each time the context to be used is sent in the `input`
 * array, the system prompt word is placed in `instructions`, and the system messages in the conversation
 * are changed to developer messages (some system message entries are not accepted by the server). The
 * tool is a function tool (`strict:false`, Mina’s parameter schema is not written in strict mode); the
 * result is returned to the model in `function_call_output`. By default, only these items and `include`
 * are sent; the user selects the thinking gear to send `reasoning.effort`. The temperature and output
 * upper limit are not sent (the package interface is not collected).
 *
 * Thinking of the inference model: The encrypted thinking content (`include:
 * reasoning.encrypted_content`) of the request is stored as it is in [Part.ToolCall.opaque] of the first
 * tool call in this round. During playback, it is put back to the front of the tool call, and the model
 * continues the idea of the previous step; in the round that only gives text answers, there is no need
 * to continue thinking and it is not saved. When the server says it will not receive `include`, remove
 * it and send it again.
 *
 * Only when `response.completed` (or `response.incomplete`) is received is it considered successful; if
 * the stream is disconnected before this, it is treated as a network failure (will be retried). When
 * [chatGptPlan] is true, the request uses the user's ChatGPT package quota: HTTP 429 means the quota has
 * reached the upper limit, 403 means the account cannot be used in this way, and there is no retry.
 * [accountLogin] is the name of that company when you log in with another account (such as xAI): HTTP
 * 403 means that the subscription level cannot use this interface ([ModelError.AccountPlanNotAllowed]).
 */
class ResponsesModel(
    baseUrl: String,
    private val token: BearerToken,
    private val model: String,
    override val capabilities: Capabilities,
    private val http: OkHttpClient,
    private val chatGptPlan: Boolean = false,
    /** Each request carries a header (see [ClientHeaders]); the token header is added by the adapter itself. This interface does not give a reply upper limit (the package route does not charge). */
    private val headers: Map<String, String> = emptyMap(),
    /** null Use the server default; when the server rejects the selected gear, it will report an error as usual and will not quietly change gears and try again. */
    private val reasoningEffort: String? = null,
    private val accountLogin: String? = null,
) : StreamingChatModel {
    override val name: String get() = model
    private val endpoint = baseUrl.trim().trimEnd('/') + "/responses"
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
        completeStreaming(system, messages, tools) {}

    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply {
        var includeReasoning = true
        var fresh = false
        while (true) {
            val stream = ResponsesStream(onText, ::errorOf)
            val bearer = token.get(fresh)
            val (status, retryAfter, text) = HttpSupport.stream(http, request(bearer, system, messages, tools, includeReasoning), stream::accept)
            if (status in 200..299) {
                if (text.isNotEmpty()) return stream.fromBody(text).also { reply ->
                    reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.takeIf(String::isNotEmpty)?.let(onText)
                }
                return stream.finish()
            }
            val err = errorBody(text)
            when {
                status == 401 && token.refreshable && !fresh -> fresh = true
                status == 400 && includeReasoning && (err?.param == "include" || text.contains("reasoning.encrypted_content")) ->
                    includeReasoning = false
                else -> throw httpError(status, retryAfter, text, err)
            }
        }
    }

    internal fun body(system: String, messages: List<Message>, tools: List<ToolSpec>, includeReasoning: Boolean = true): JsonObject =
        buildJsonObject {
            put("model", model)
            if (system.isNotEmpty()) put("instructions", system)
            put("input", JsonArray(messages.flatMap(::serialize)))
            if (tools.isNotEmpty()) putJsonArray("tools") { tools.forEach { add(toolJson(it)) } }
            put("store", false)
            put("stream", true)
            reasoningEffort?.let { effort -> putJsonObject("reasoning") { put("effort", effort) } }
            if (includeReasoning) putJsonArray("include") { add(ENCRYPTED_REASONING) }
        }

    private suspend fun request(bearer: String?, system: String, messages: List<Message>, tools: List<ToolSpec>, includeReasoning: Boolean): Request =
        Request.Builder().url(endpoint)
            .withHeaders(headers)
            .apply { if (!bearer.isNullOrEmpty()) header("Authorization", "Bearer $bearer") }
            .header("Accept", "text/event-stream")
            .postInference(InferenceProtocol.Responses, body(system, messages, tools, includeReasoning)) {
                if (chatGptPlan || endpoint.toHttpUrlOrNull()?.host == "api.openai.com") "openai" else "openai_compatible"
            }
            .build()

    private fun toolJson(t: ToolSpec): JsonObject = buildJsonObject {
        put("type", "function")
        put("name", t.name)
        put("description", t.description)
        put("parameters", try { json.parseToJsonElement(t.parametersJsonSchema) } catch (e: Exception) {
            throw IllegalArgumentException("工具 ${t.name} 的参数 schema 不是合法 JSON", e)
        })
        put("strict", false)
    }

    /** An internal message is expanded into several `input` items: assistant's words, thoughts, tool calls; tool results each account for one; text and pictures are combined into one message. */
    private fun serialize(m: Message): List<JsonObject> {
        if (m.role == Role.Assistant) return assistantItems(m)
        val out = mutableListOf<JsonObject>()
        m.parts.filterIsInstance<Part.ToolResult>().forEach { r ->
            out += buildJsonObject { put("type", "function_call_output"); put("call_id", r.callId); put("output", r.content) }
        }
        val rest = m.parts.filter { it is Part.Text || it is Part.Image }
        if (rest.isNotEmpty()) out += buildJsonObject {
            put("type", "message")
            put("role", if (m.role == Role.System) "developer" else "user")
            putJsonArray("content") {
                rest.forEach { p ->
                    addJsonObject {
                        if (p is Part.Image && capabilities.vision) {
                            put("type", "input_image"); put("image_url", "data:${p.mime};base64,${p.pngBase64}")
                        } else {
                            put("type", "input_text"); put("text", if (p is Part.Text) p.text else IMAGE_OMITTED)
                        }
                    }
                }
            }
        }
        return out
    }

    private fun assistantItems(m: Message): List<JsonObject> {
        val calls = m.parts.filterIsInstance<Part.ToolCall>()
        val reasoning = calls.mapNotNull { it.opaque }.flatMap { o ->
            runCatching { json.parseToJsonElement(o) as? JsonArray }.getOrNull().orEmpty().filterIsInstance<JsonObject>()
        }
        val text = m.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
        return buildList {
            addAll(reasoning)
            if (text.isNotEmpty()) add(buildJsonObject {
                put("type", "message"); put("role", "assistant")
                putJsonArray("content") { addJsonObject { put("type", "output_text"); put("text", text) } }
            })
            calls.forEach { c ->
                add(buildJsonObject { put("type", "function_call"); put("call_id", c.id); put("name", c.name); put("arguments", c.argsJson) })
            }
        }
    }

    internal data class ErrorBody(val code: String?, val message: String, val param: String?)

    private fun errorBody(text: String): ErrorBody? {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val e = root["error"] as? JsonObject
        if (e != null) return ErrorBody(e.str("code") ?: e.str("type"), e.str("message").orEmpty(), e.str("param"))
        return root.str("detail")?.let { ErrorBody(null, it, null) }
    }

    /** `response.failed` / `error` in the stream, and `error.code` in the content when HTTP error occurs. Unrecognized code is null. */
    private fun errorOf(code: String?, message: String, param: String?): ModelError? = when (code) {
        "subscription_sharing_usage_limit_exceeded" -> ModelError.ChatGptLimitReached()
        "subscription_sharing_user_not_eligible" -> ModelError.ChatGptNotEligible(message.take(200))
        // The server is temporarily unavailable: keep the login and try again later.
        "subscription_sharing_usage_unavailable", "subscription_sharing_user_unavailable" -> ModelError.Server(503)
        "subscription_sharing_unsupported_capability" -> ModelError.BadResponse("服务端不支持请求里的 ${param ?: "某项内容"}")
        "subscription_sharing_route_not_supported" -> ModelError.BadResponse("服务端不支持这个接口")
        "subscription_sharing_invalid_user", "chatpass_v2_scope_not_authorized", "chatpass_v2_invalid_authorization_context" ->
            ModelError.Auth()
        "context_length_exceeded" -> ModelError.ContextTooLong()
        "insufficient_quota" -> ModelError.QuotaExceeded()
        "rate_limit_exceeded" -> if (chatGptPlan) ModelError.ChatGptLimitReached() else ModelError.RateLimited(null)
        "model_not_found" -> ModelError.ModelNotFound(model, ModelAccessErrors.clip(message))
        "server_error" -> ModelError.Server(500)
        else -> null
    }

    private fun httpError(status: Int, retryAfter: String?, body: String, err: ErrorBody?): ModelError =
        err?.let { errorOf(it.code, it.message, it.param) }
            // The account has been logged in and the identity has been verified: 403 with no recognized
            // error code means that the gear level is not enough and should not be considered as a wrong
            // Key or a certain model is not activated.
            ?: (if (status == 403 && accountLogin != null) ModelError.AccountPlanNotAllowed(accountLogin, err?.message.orEmpty().take(200)) else null)
            ?: ModelAccessErrors.of(status, body, model) ?: when {
            status == 401 -> ModelError.Auth()
            status == 403 -> if (chatGptPlan) ModelError.ChatGptNotEligible(err?.message.orEmpty().take(200)) else ModelError.Auth()
            status == 429 -> if (chatGptPlan) ModelError.ChatGptLimitReached() else ModelError.RateLimited(HttpSupport.retryAfterMs(retryAfter))
            status == 402 -> ModelError.QuotaExceeded()
            status == 400 && (body.contains("context_length") || body.contains("maximum context")) -> ModelError.ContextTooLong()
            status == 404 -> ModelError.ModelNotFound(model, ModelAccessErrors.jsonDetail(body))
            status in 500..599 -> ModelError.Server(status)
            else -> ModelError.BadResponse("HTTP $status ${body.take(200)}")
        }

    private companion object {
        const val ENCRYPTED_REASONING = "reasoning.encrypted_content"
        const val IMAGE_OMITTED = "[screenshot omitted: the current model cannot see images]"
    }
}

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

/**
 * Read the event stream of Responses: `response.output_text.delta` is a piece of text,
 * `response.output_item.done` is a completed item (message, tool call, thinking), `response.completed` /
 * `response.incomplete` is the end, `response.failed` / `error` is a failure.
 */
private class ResponsesStream(
    private val onText: (String) -> Unit,
    private val errorOf: (String?, String, String?) -> ModelError?,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val visible = StringBuilder()
    private val items = mutableListOf<JsonObject>()
    private var final: JsonObject? = null
    private var incomplete = false

    fun accept(data: String) {
        if (data == "[DONE]") return
        val root = try { json.parseToJsonElement(data) as? JsonObject } catch (e: Exception) { null }
            ?: throw ModelError.BadResponse("流式响应不是合法 JSON")
        when (root.str("type")) {
            "response.output_text.delta" -> root.str("delta")?.takeIf { it.isNotEmpty() }?.let {
                visible.append(it)
                onText(visible.toString())
            }
            "response.output_item.done" -> (root["item"] as? JsonObject)?.let { items += it }
            "response.completed" -> final = root["response"] as? JsonObject ?: JsonObject(emptyMap())
            "response.incomplete" -> {
                final = root["response"] as? JsonObject ?: JsonObject(emptyMap())
                incomplete = true
            }
            "response.failed" -> throw failure((root["response"] as? JsonObject)?.get("error") as? JsonObject)
            "error" -> throw failure(root["error"] as? JsonObject ?: root)
        }
    }

    private fun failure(e: JsonObject?): ModelError {
        val code = e?.str("code")
        val message = e?.str("message").orEmpty()
        return errorOf(code, message, e?.str("param")) ?: ModelError.BadResponse(listOfNotNull(code, message.take(200)).joinToString(" "))
    }

    /** The server did not respond by streaming, but directly gave the entire response object. */
    fun fromBody(text: String): ModelReply {
        val root = try { json.parseToJsonElement(text) as? JsonObject } catch (e: Exception) { null }
            ?: throw ModelError.BadResponse("返回内容不是合法 JSON")
        (root["error"] as? JsonObject)?.let { throw failure(it) }
        final = root
        incomplete = root.str("status") == "incomplete"
        return finish()
    }

    fun finish(): ModelReply {
        // Before response.completed / response.incomplete, the connection was disconnected midway.
        val response = final ?: throw ModelError.StreamCut()
        val output = items.ifEmpty { (response["output"] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty() }
        val reasoning = mutableListOf<JsonObject>()
        val thinking = StringBuilder()
        val text = StringBuilder()
        var refusal = false
        val calls = mutableListOf<Part.ToolCall>()
        output.forEach { item ->
            when (item.str("type")) {
                "reasoning" -> {
                    (item["summary"] as? JsonArray)?.forEach { s -> (s as? JsonObject)?.str("text")?.let(thinking::append) }
                    // For playback: remove numbers and statuses that are only meaningful on the server
                    // side, leaving behind encryption considerations
                    if (item["encrypted_content"] != null) reasoning += JsonObject(item - "id" - "status")
                }
                "message" -> (item["content"] as? JsonArray)?.forEach { c ->
                    val o = c as? JsonObject ?: return@forEach
                    when (o.str("type")) {
                        "output_text" -> o.str("text")?.let(text::append)
                        "refusal" -> refusal = true
                    }
                }
                "function_call" -> {
                    val id = item.str("call_id") ?: throw ModelError.BadResponse("工具调用缺少编号")
                    val name = item.str("name") ?: throw ModelError.BadResponse("工具调用缺少名称")
                    calls += Part.ToolCall(id, name, item.str("arguments")?.ifEmpty { null } ?: "{}")
                }
            }
        }
        if (text.isEmpty() && visible.isNotEmpty()) text.append(visible)
        val reason = (response["incomplete_details"] as? JsonObject)?.str("reason")
        if (refusal && text.isEmpty() && calls.isEmpty()) throw ModelError.ContentRefused()
        if (incomplete && reason == "content_filter" && text.isEmpty() && calls.isEmpty()) throw ModelError.ContentRefused()
        val withReasoning = if (reasoning.isNotEmpty() && calls.isNotEmpty()) {
            listOf(calls.first().copy(opaque = JsonArray(reasoning).toString())) + calls.drop(1)
        } else calls
        val parts = buildList {
            if (thinking.isNotEmpty()) add(Part.Thinking(thinking.toString()))
            if (text.isNotEmpty()) add(Part.Text(text.toString()))
            addAll(withReasoning)
        }
        val stop = when {
            calls.isNotEmpty() -> StopReason.ToolUse
            incomplete && reason == "max_output_tokens" -> StopReason.MaxTokens
            incomplete -> StopReason.Other
            else -> StopReason.EndTurn
        }
        return ModelReply(parts, stop, usageOf(response["usage"]))
    }

    private fun usageOf(el: JsonElement?): Usage? {
        val u = el as? JsonObject ?: return null
        fun int(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.intOrNull
        val input = int(u, "input_tokens") ?: return null
        val cached = int(u["input_tokens_details"] as? JsonObject, "cached_tokens") ?: 0
        val output = int(u, "output_tokens") ?: 0
        val reasoning = (int(u["output_tokens_details"] as? JsonObject, "reasoning_tokens") ?: 0).coerceIn(0, output)
        return Usage(input, output, cached.coerceAtMost(input), reasoning)
    }
}
