package com.meenai.mina.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap

/**
 * Anthropic Messages API.
 * - - System messages in history are merged into system text (Anthropic does not have system role
 * messages).
 * - - When thinking intensity is selected, adaptive thinking (`thinking.type = adaptive`) is turned on
 * and the gear is set to `output_config.effort`; when `none` is selected, it is turned off.
 * The list indicates which ways of thinking ([thinkingTypes]) are accepted by the model, and those that
 * are not accepted will not be sent (nothing will be sent, as per the server's default). The `signature`
 * of the thinking block is stored in [Part.Thinking.opaque] together with the address from which it was
 * received ([SignedThinking]). The round with the tool call is returned to the same address as it is (if
 * it is not returned, it will be rejected by the server), and the order of the blocks remains unchanged
 * as received. The signature is also bound to the system prompt words, tools, and previous messages when
 * it was generated; after these changes (expanding a group of tools, compressing history, etc.), the
 * model starting from Claude 5.1 may reject the returned thinking block ([STALE_THINKING]). In this
 * case, remove the thinking block and send it again according to the recovery method of the document
 * (Mina will only use adaptive thinking when starting thinking on these models. The document says that
 * adaptive thinking does not require the assistant turn to start with a thinking block; here, remove
 * everything brought this time, see [dropStaleThinking]), and subsequent requests will no longer carry
 * them ([staleThinking]).
 * - - Merge adjacent messages of the same role (Anthropic requires user/assistant alternation).
 */
class AnthropicModel(
    baseUrl: String = "https://api.anthropic.com",
    private val apiKey: String,
    private val model: String,
    override val capabilities: Capabilities,
    private val http: OkHttpClient,
    /** Reply upper limit (`max_tokens`, required); null By default: [THINKING_MAX_TOKENS] is enabled, otherwise [DEFAULT_MAX_TOKENS]. */
    maxTokens: Int? = null,
    /** Each request carries a header (see [ClientHeaders]); the Key header is added by the adapter itself. */
    private val headers: Map<String, String> = emptyMap(),
    /** The thinking intensity selected by the user; null is not sent, `none` turns off thinking, and the rest is sent as `output_config.effort`. */
    private val reasoningEffort: String? = null,
    /**
     * The list indicates the way of thinking accepted by this model (see [ListedModel.thinkingTypes]);
     * gears that do not include `adaptive` will not be issued, and gears that do not include `disabled`
     * will not issue `none`. null It’s not mentioned in the list (the gears come from other lists), so
     * it’s sent according to the gears.
     */
    private val thinkingTypes: List<String>? = null,
) : StreamingChatModel {
    override val name: String get() = model
    private val endpoint = baseUrl.trimEnd('/') + "/v1/messages"
    /** The actual sent `thinking.type`:`adaptive`, `disabled`, or null (not sent). */
    private val thinkingType: String? = when (reasoningEffort) {
        null -> null
        "none" -> "disabled"
        else -> "adaptive"
    }?.takeIf { thinkingTypes == null || it in thinkingTypes }
    private val maxTokens = maxTokens ?: if (thinkingType == "adaptive") THINKING_MAX_TOKENS else DEFAULT_MAX_TOKENS
    private val json = Json { ignoreUnknownKeys = true }
    private val ephemeral = buildJsonObject { put("type", "ephemeral") }
    /**
     * The thinking block ([Part.Thinking.opaque]) that was included in the rejected request is still
     * there in the history, and subsequent requests will not be included. Each task builds a model, and
     * subtasks share it, so there may be several coroutines used at the same time.
     */
    private val staleThinking: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        val (status, retryAfter, text) = dropStaleThinking(messages) {
            HttpSupport.execute(http, request(system, messages, tools, stream = false))
        }
        if (status !in 200..299) throw mapError(status, retryAfter, text)
        return parse(text)
    }

    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply {
        val stream = AnthropicStream(endpoint, onText, ::parseUsage)
        // There was no event when it was rejected (HttpSupport.stream does not adjust accept when it is
        // not 2xx), resend and use this stream
        val (status, retryAfter, text) = dropStaleThinking(messages) {
            HttpSupport.stream(http, request(system, messages, tools, stream = true), stream::accept)
        }
        if (status !in 200..299) throw mapError(status, retryAfter, text)
        if (text.isNotEmpty()) return parse(text).also { reply ->
            reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.takeIf(String::isNotEmpty)?.let(onText)
        }
        return stream.finish()
    }

    /**
     * Send once; when the server says that the prefix of the returned thinking block does not match
     * ([STALE_THINKING]), it will record all the thinking blocks brought this time into [staleThinking]
     * and send it again (only once). The method given in the document is to remove the part where the
     * error is reported and the part after it; remove them all here, there is no need to press
     * `messages.N` to find the location, and the cost is that earlier effective thinking is no longer
     * required. This time, if there is no thinking block, it will not be reissued and an error will be
     * reported as usual.
     */
    private suspend fun dropStaleThinking(messages: List<Message>, send: suspend () -> RawResponse): RawResponse {
        val sent = messages.filter { it.role == Role.Assistant }.flatMap { it.parts }.filterIsInstance<Part.Thinking>()
            .filter(::replays).mapNotNull { it.opaque }
        val first = send()
        if (first.status != 400 || STALE_THINKING !in first.body || !staleThinking.addAll(sent)) return first
        return send()
    }

    /** This block will not be returned: it will not be sent when thinking is turned off; it will not be sent when it is received from other addresses (the signature can only be recognized by the service that sent it); it will not be sent when the request was rejected ([staleThinking]). */
    private fun replays(p: Part.Thinking): Boolean {
        val opaque = p.opaque ?: return false
        return thinkingType != "disabled" && opaque !in staleThinking && SignedThinking.block(p, endpoint) != null
    }

    private suspend fun request(system: String, messages: List<Message>, tools: List<ToolSpec>, stream: Boolean): Request {
        val systemText = (listOf(system) + messages.filter { it.role == Role.System }
            .map { m -> m.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text } })
            .filter { it.isNotEmpty() }.joinToString("\n\n")
        val payload = buildJsonObject {
            put("model", model)
            put("max_tokens", maxTokens)
            if (stream) put("stream", true)
            // Anthropic rejects empty text blocks, and the entire field is omitted when system is empty.
            if (systemText.isNotBlank()) put("system", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text"); put("text", systemText); put("cache_control", ephemeral)
                })
            })
            if (tools.isNotEmpty()) put("tools", buildJsonArray {
                tools.forEachIndexed { i, t -> add(toolJson(t, last = i == tools.lastIndex)) }
            })
            put("messages", buildMessages(messages))
            when (thinkingType) {
                "disabled" -> putJsonObject("thinking") { put("type", "disabled") }
                "adaptive" -> {
                    putJsonObject("thinking") {
                        put("type", "adaptive")
                        put("display", "summarized")
                    }
                    putJsonObject("output_config") { put("effort", reasoningEffort) }
                }
            }
        }
        return Request.Builder().url(endpoint)
            .withHeaders(headers)
            .header("x-api-key", apiKey)
            .header("anthropic-version", "2023-06-01")
            .postInference(InferenceProtocol.Anthropic, payload)
            .build()
    }

    private fun mapError(status: Int, retryAfter: String?, body: String): ModelError = ModelAccessErrors.of(status, body, model) ?: when {
        status == 401 || status == 403 -> ModelError.Auth()
        status == 402 || body.contains("credit balance is too low") -> ModelError.QuotaExceeded()
        status == 429 -> ModelError.RateLimited(HttpSupport.retryAfterMs(retryAfter))
        status == 413 || body.contains("prompt is too long") -> ModelError.ContextTooLong()
        status == 404 -> ModelError.ModelNotFound(model, ModelAccessErrors.jsonDetail(body))
        status == 529 || body.contains("overloaded_error") -> ModelError.Server(529)
        status in 500..599 -> ModelError.Server(status)
        else -> ModelError.BadResponse("HTTP $status ${body.take(200)}")
    }

    private fun toolJson(t: ToolSpec, last: Boolean): JsonObject = buildJsonObject {
        put("name", t.name)
        put("description", t.description)
        put("input_schema", try { json.parseToJsonElement(t.parametersJsonSchema) } catch (e: Exception) {
            throw IllegalArgumentException("工具 ${t.name} 的参数 schema 不是合法 JSON", e)
        })
        if (last) put("cache_control", ephemeral)
    }

    private fun buildMessages(messages: List<Message>): JsonArray {
        // (role, blocks) Merge according to adjacent and same roles first, and then output
        val merged = mutableListOf<Pair<String, MutableList<JsonObject>>>()
        for (m in messages) {
            if (m.role == Role.System) continue
            val role = if (m.role == Role.Assistant) "assistant" else "user"
            val blocks = if (role == "assistant") assistantBlocks(m) else userBlocks(m)
            if (blocks.isEmpty()) continue
            if (merged.lastOrNull()?.first == role) merged.last().second += blocks else merged += role to blocks.toMutableList()
        }
        // The conversation is cached until the last user message, and the next step is fetched from the
        // cache if the beginning is the same as it; the assistant message may end with a think block and
        // is not marked
        val lastUser = merged.indexOfLast { it.first == "user" }
        return buildJsonArray {
            merged.forEachIndexed { i, (role, blocks) ->
                val content = if (i == lastUser) blocks.dropLast(1) + JsonObject(blocks.last() + ("cache_control" to ephemeral)) else blocks
                add(buildJsonObject { put("role", role); put("content", JsonArray(content)) })
            }
        }
    }

    // tool_result must come before other blocks of the same user message
    private fun userBlocks(m: Message): List<JsonObject> {
        val results = m.parts.filterIsInstance<Part.ToolResult>().map { r ->
            buildJsonObject {
                put("type", "tool_result"); put("tool_use_id", r.callId)
                put("content", r.content); put("is_error", r.isError)
            }
        }
        val rest = m.parts.mapNotNull { p ->
            when {
                p is Part.Text -> buildJsonObject { put("type", "text"); put("text", p.text) }
                p is Part.Image && capabilities.vision -> buildJsonObject {
                    put("type", "image")
                    putJsonObject("source") {
                        put("type", "base64"); put("media_type", p.mime); put("data", p.pngBase64)
                    }
                }
                p is Part.Image -> buildJsonObject {
                    put("type", "text"); put("text", "[screenshot omitted: the current model cannot see images]")
                }
                else -> null
            }
        }
        return results + rest
    }

    // When starting thinking, the round with the tool call must bring back the thinking block of the
    // previous assistant message along with the signature as it is. The order of the blocks is as
    // received: the progress description written before the tool call for the new model is a separate
    // thinking block, arranged after the text; if it is moved to the front, the server will regard the
    // previous content as changed. It is not included when explicitly turning off thinking, so as not to
    // conflict with the thinking configuration of the current request; it is also not included in
    // requests received and rejected from other addresses ([replays]).
    private fun assistantBlocks(m: Message): List<JsonObject> = m.parts.mapNotNull { p ->
        when (p) {
            is Part.Thinking -> if (replays(p)) SignedThinking.block(p, endpoint) else null
            is Part.Text -> if (p.text.isEmpty()) null else buildJsonObject { put("type", "text"); put("text", p.text) }
            is Part.ToolCall -> buildJsonObject {
                put("type", "tool_use"); put("id", p.id); put("name", p.name)
                put("input", try { json.parseToJsonElement(p.argsJson).jsonObject } catch (e: Exception) { JsonObject(emptyMap()) })
            }
            else -> null
        }
    }

    private fun parse(text: String): ModelReply {
        try {
            val root = json.parseToJsonElement(text).jsonObject
            val content = root["content"]?.jsonArray ?: throw ModelError.BadResponse("缺少 content")
            val parts = mutableListOf<Part>()
            content.forEach { el ->
                val b = el.jsonObject
                when (b["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> b["text"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { parts += Part.Text(it) }
                    "thinking" -> {
                        val text = (b["thinking"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                        val signature = (b["signature"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                        if (text.isNotEmpty() || signature != null) parts += Part.Thinking(text, signature?.let { SignedThinking.signed(endpoint, it) })
                    }
                    "redacted_thinking" -> (b["data"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                        ?.let { parts += Part.Thinking("", SignedThinking.redacted(endpoint, it)) }
                    "tool_use" -> parts += Part.ToolCall(
                        b["id"]!!.jsonPrimitive.content,
                        b["name"]!!.jsonPrimitive.content,
                        (b["input"] ?: JsonObject(emptyMap())).toString(),
                    )
                }
            }
            val stop = when ((root["stop_reason"] as? JsonPrimitive)?.contentOrNull) {
                "end_turn", "stop_sequence" -> StopReason.EndTurn
                "tool_use" -> StopReason.ToolUse
                "max_tokens" -> StopReason.MaxTokens
                else -> StopReason.Other
            }
            return ModelReply(parts, stop, parseUsage(root["usage"]))
        } catch (e: ModelError) {
            throw e
        } catch (e: Exception) {
            throw ModelError.BadResponse(e.message.orEmpty().take(200), e)
        }
    }

    private fun parseUsage(el: JsonElement?): Usage? {
        val u = el as? JsonObject ?: return null
        fun n(k: String) = (u[k] as? JsonPrimitive)?.intOrNull
        val inT = n("input_tokens") ?: return null
        val cached = n("cache_read_input_tokens") ?: 0
        // Anthropic's input_tokens does not contain the cache part; it is unified into the total input
        // caliber. output_tokens includes thinking but does not report separately, reasoningTokens is
        // recorded as 0
        return Usage(inT + cached + (n("cache_creation_input_tokens") ?: 0), n("output_tokens") ?: 0, cached)
    }
}

private class AnthropicStream(
    private val endpoint: String,
    private val onText: (String) -> Unit,
    private val usageOf: (JsonElement?) -> Usage?,
) {
    private data class Block(
        val type: String, val id: String = "", val name: String = "",
        val text: StringBuilder = StringBuilder(), val input: StringBuilder = StringBuilder(), val initialInput: String = "{}",
        val signature: StringBuilder = StringBuilder(), val data: String = "",
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val blocks = sortedMapOf<Int, Block>()
    private val visible = StringBuilder()
    private var usage: Usage? = null
    private var reason: String? = null
    private var stopped = false

    fun accept(data: String) {
        val root = try { json.parseToJsonElement(data).jsonObject }
        catch (e: Exception) { throw ModelError.BadResponse("流式响应不是合法 JSON", e) }
        when ((root["type"] as? JsonPrimitive)?.contentOrNull) {
            "message_start" -> usage = usageOf((root["message"] as? JsonObject)?.get("usage"))
            "content_block_start" -> {
                val index = (root["index"] as? JsonPrimitive)?.intOrNull ?: return
                val b = root["content_block"] as? JsonObject ?: return
                val type = (b["type"] as? JsonPrimitive)?.contentOrNull ?: return
                blocks[index] = Block(type,
                    id = (b["id"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    name = (b["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                    initialInput = b["input"]?.toString() ?: "{}",
                    data = (b["data"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
                ).also { block ->
                    (b["signature"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty)?.let(block.signature::append)
                    (b["text"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty)?.let {
                        block.text.append(it); visible.append(it); onText(visible.toString())
                    }
                }
            }
            "content_block_delta" -> {
                val index = (root["index"] as? JsonPrimitive)?.intOrNull ?: return
                val block = blocks[index] ?: return
                val delta = root["delta"] as? JsonObject ?: return
                when ((delta["type"] as? JsonPrimitive)?.contentOrNull) {
                    "text_delta" -> (delta["text"] as? JsonPrimitive)?.contentOrNull?.let {
                        block.text.append(it); visible.append(it); onText(visible.toString())
                    }
                    "thinking_delta" -> (delta["thinking"] as? JsonPrimitive)?.contentOrNull?.let { block.text.append(it) }
                    "signature_delta" -> (delta["signature"] as? JsonPrimitive)?.contentOrNull?.let { block.signature.append(it) }
                    "input_json_delta" -> (delta["partial_json"] as? JsonPrimitive)?.contentOrNull?.let { block.input.append(it) }
                }
            }
            "message_delta" -> {
                reason = ((root["delta"] as? JsonObject)?.get("stop_reason") as? JsonPrimitive)?.contentOrNull ?: reason
                val output = ((root["usage"] as? JsonObject)?.get("output_tokens") as? JsonPrimitive)?.intOrNull
                if (output != null) usage = usage?.copy(outputTokens = output)
            }
            "message_stop" -> stopped = true
            "error" -> throw ModelError.BadResponse(root["error"].toString().take(200))
        }
    }

    fun finish(): ModelReply {
        // When message_delta brings stop_reason, all the content blocks are collected, and only
        // message_stop is missing. It is considered complete; neither of them means that the connection
        // was disconnected midway.
        if (!stopped && reason == null) throw ModelError.StreamCut()
        val parts = blocks.values.mapNotNull { block ->
            when (block.type) {
                "text" -> block.text.toString().takeIf(String::isNotEmpty)?.let(Part::Text)
                "thinking" -> {
                    val text = block.text.toString()
                    val signature = block.signature.toString().takeIf(String::isNotEmpty)
                    if (text.isNotEmpty() || signature != null) Part.Thinking(text, signature?.let { SignedThinking.signed(endpoint, it) }) else null
                }
                "redacted_thinking" -> block.data.takeIf(String::isNotEmpty)?.let { Part.Thinking("", SignedThinking.redacted(endpoint, it)) }
                "tool_use" -> {
                    if (block.id.isEmpty() || block.name.isEmpty()) throw ModelError.BadResponse("工具调用流缺少名称或编号")
                    Part.ToolCall(block.id, block.name, block.input.toString().ifEmpty { block.initialInput })
                }
                else -> null
            }
        }
        val stop = when (reason) {
            "end_turn", "stop_sequence" -> StopReason.EndTurn
            "tool_use" -> StopReason.ToolUse
            "max_tokens" -> StopReason.MaxTokens
            else -> StopReason.Other
        }
        return ModelReply(parts, stop, usage)
    }
}

/** The default reply limit; when thinking is turned on, thinking also accounts for this quota. 4K is often used up before the answer is given, giving [THINKING_MAX_TOKENS]. */
private const val DEFAULT_MAX_TOKENS = 4096
private const val THINKING_MAX_TOKENS = 16_000

/**
 * The returned thinking block prefix does not match the sentence in the error message (original text of
 * the Preserved thinking section of the Anthropic documentation). There is only the first half of the
 * sentence "Invalid `signature` in `thinking` block". Without this sentence, the signature itself is
 * broken. It will not be resent and an error will be reported as usual.
 */
private const val STALE_THINKING = "bound to a different conversation"

/**
 * Anthropic thinking blocks recorded in [Part.Thinking.opaque]: signed thinking is
 * `{"endpoint":…,"signature":…}`, redacted_thinking is `{"endpoint":…,"redacted":…}`. The signature is
 * only recognized by the service that sent it, and will only be recognized by one address during
 * playback; other addresses (another Anthropic compatible service) and other adapters will not be
 * returned.
 */
internal object SignedThinking {
    fun signed(endpoint: String, signature: String): String =
        buildJsonObject { put("endpoint", endpoint); put("signature", signature) }.toString()

    fun redacted(endpoint: String, data: String): String =
        buildJsonObject { put("endpoint", endpoint); put("redacted", data) }.toString()

    /** [p] is the one used for playback when the thinking block is received from [endpoint]; the one that comes from elsewhere and cannot be recognized is null. */
    fun block(p: Part.Thinking, endpoint: String): JsonObject? {
        val o = p.opaque?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return null
        if ((o["endpoint"] as? JsonPrimitive)?.contentOrNull != endpoint) return null
        (o["redacted"] as? JsonPrimitive)?.contentOrNull?.let { data ->
            return buildJsonObject { put("type", "redacted_thinking"); put("data", data) }
        }
        val signature = (o["signature"] as? JsonPrimitive)?.contentOrNull ?: return null
        return buildJsonObject { put("type", "thinking"); put("thinking", p.text); put("signature", signature) }
    }
}
