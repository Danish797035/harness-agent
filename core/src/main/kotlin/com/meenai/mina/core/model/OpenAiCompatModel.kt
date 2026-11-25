package com.meenai.mina.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * OpenAI Chat Completions compatible endpoints (DeepSeek/Qwen/Kimi/GLM/OpenRouter/local services, etc.).
 * The difference is only placed in [capabilities].
 *
 * Each company places the thinking content in different places: `reasoning_content`, `reasoning`, or
 * `content` is a block array, and thinking is in the `thinking` block. Note down which address and
 * location the received thoughts come from ([ReasoningVia], exists [Part.Thinking.opaque]), and put the
 * request with the tool back to its original place: Some service providers require the thoughts before
 * and after the tool call to be sent back, and if not sent back, they will be rejected. Only thoughts
 * received from this address will be returned, and thoughts sent by others will not be sent (service
 * providers that do not recognize this field will not receive it). [reasoningField] is the thinking
 * field specified in the model list (`reasoning_content` or `reasoning`): if it is specified, the
 * thinking of unknown origin (history saved in the old version) will also be returned according to it.
 * When thinking intensity is selected, `reasoning_effort` will be sent separately; if the service
 * provider does not write the level in the list, the value will not be received here.
 */
class OpenAiCompatModel(
    baseUrl: String,
    private val apiKey: String?,
    private val model: String,
    override val capabilities: Capabilities,
    private val http: OkHttpClient,
    reasoningField: String? = null,
    /** Each request carries headers (client identification, session number required by the service provider, etc., see [ClientHeaders]); the Key header is added by the adapter itself. */
    private val headers: Map<String, String> = emptyMap(),
    /** The thinking intensity selected by the user (`reasoning_effort`); null is not sent. */
    private val reasoningEffort: String? = null,
) : StreamingChatModel {
    override val name: String get() = model
    private val endpoint = baseUrl.trimEnd('/') + "/chat/completions"
    private val declaredVia = ReasoningVia.ofField(reasoningField)
    private val json = Json { ignoreUnknownKeys = true }

    private companion object {
        val MARKABLE_ROLES = setOf("system", "user", "tool")
        val EPHEMERAL = buildJsonObject { put("type", "ephemeral") }
        /** The last thing about SSE: it’s not finished until you receive it. */
        const val STREAM_DONE = "[DONE]"
    }

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        val response = HttpSupport.execute(http, request(system, messages, tools, stream = false))
        val (status, retryAfter, text) = response
        if (status !in 200..299) throw mapError(status, retryAfter, text)
        // Some compatible endpoints do not adhere to OpenAI's default values: they still return to SSE
        // even if streaming is not enabled (common for self-built services on local and local area
        // networks). Read it according to the stream and then spell it. Don't regard `data:` as bad
        // JSON.
        if (!looksLikeEvents(text)) return parse(text)
        val stream = OpenAiStream({}, ::parseUsage, ::thinking)
        response.replay(eventsOf(text), stream::accept)
        return stream.finish()
    }

    override suspend fun completeStreaming(
        system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit,
    ): ModelReply {
        var stream = OpenAiStream(onText, ::parseUsage, ::thinking)
        var response = HttpSupport.stream(http, request(system, messages, tools, stream = true), stream::accept)
        if (response.status == 400 && response.body.contains("stream_options")) {
            // Some compatible endpoints can be streamed but have no usage options.
            stream = OpenAiStream(onText, ::parseUsage, ::thinking)
            response = HttpSupport.stream(http, request(system, messages, tools, stream = true, includeUsage = false), stream::accept)
        }
        val (status, retryAfter, text) = response
        if (status !in 200..299) {
            // Compatible endpoints that do not support streaming can still complete the task according
            // to the original interface.
            if (status == 400 && text.contains("stream", ignoreCase = true)) return complete(system, messages, tools).also {
                it.parts.filterIsInstance<Part.Text>().joinToString("") { part -> part.text }.takeIf(String::isNotEmpty)?.let(onText)
            }
            throw mapError(status, retryAfter, text)
        }
        if (text.isNotEmpty()) return parse(text).also { reply ->
            reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.takeIf(String::isNotEmpty)?.let(onText)
        }
        return stream.finish()
    }

    private suspend fun request(system: String, messages: List<Message>, tools: List<ToolSpec>, stream: Boolean, includeUsage: Boolean = true): Request {
        val payload = buildJsonObject {
            put("model", model)
            reasoningEffort?.let { put("reasoning_effort", it) }
            // Thinking is only returned in requests with tools (you need to continue thinking when tool
            // calls are made step by step); bypass calls without tools are not required
            val replayReasoning = tools.isNotEmpty()
            val wire = listOf(buildJsonObject { put("role", "system"); put("content", system) }) +
                messages.flatMap { serialize(it, replayReasoning) }
            put("messages", JsonArray(if (capabilities.promptCaching == PromptCaching.Explicit) withCacheMarks(wire) else wire))
            if (tools.isNotEmpty()) putJsonArray("tools") { tools.forEach { add(toolJson(it)) } }
            if (stream) {
                put("stream", true)
                if (includeUsage) putJsonObject("stream_options") { put("include_usage", true) }
            } else {
                // Specify not to stream: When omitting this item, some compatible endpoints will return
                // by SSE by default.
                put("stream", false)
            }
        }
        return Request.Builder().url(endpoint)
            .withHeaders(headers)
            .header("Accept", if (stream) "text/event-stream" else "application/json")
            .apply { if (!apiKey.isNullOrEmpty()) header("Authorization", "Bearer $apiKey") }
            .postInference(InferenceProtocol.Chat, payload)
            .build()
    }

    /**
     * Explicit caching: Mark on the system prompt word (the same every time), and then mark on the last
     * message that can be marked (the entire conversation up to this time is cached, and the next
     * request will be fetched from the cache when the beginning of the request is the same). The server
     * looks back from the mark to the same beginning, so earlier messages cannot be overwritten. If the
     * content is a string, replace it with a text block and then mark it; assistant messages (may only
     * be tool calls) are not marked.
     */
    private fun withCacheMarks(wire: List<JsonObject>): List<JsonObject> {
        val last = wire.indexOfLast { (it["role"] as? JsonPrimitive)?.contentOrNull in MARKABLE_ROLES && it["content"] !is JsonNull }
        return wire.mapIndexed { i, m -> if (i == 0 || i == last) marked(m) else m }
    }

    private fun marked(m: JsonObject): JsonObject {
        val blocks = when (val c = m["content"]) {
            is JsonPrimitive -> listOf(buildJsonObject { put("type", "text"); put("text", c.content) })
            is JsonArray -> c.map { it.jsonObject }
            else -> return m
        }
        if (blocks.isEmpty()) return m
        val withMark = blocks.dropLast(1) + JsonObject(blocks.last() + ("cache_control" to EPHEMERAL))
        return JsonObject(m + ("content" to JsonArray(withMark)))
    }

    private fun mapError(status: Int, retryAfter: String?, body: String): ModelError = ModelHttp.openAiError(status, retryAfter, body, model)

    private fun toolJson(t: ToolSpec): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", t.name)
            put("description", t.description)
            put("parameters", try { json.parseToJsonElement(t.parametersJsonSchema) } catch (e: Exception) {
                throw IllegalArgumentException("工具 ${t.name} 的参数 schema 不是合法 JSON", e)
            })
        }
    }

    /** An internal message may be expanded into multiple online messages (tool results account for one each, and pictures can only contain user messages). */
    private fun serialize(m: Message, replayReasoning: Boolean): List<JsonObject> {
        if (m.role == Role.Assistant) return listOf(assistantJson(m, replayReasoning))
        val out = mutableListOf<JsonObject>()
        m.parts.filterIsInstance<Part.ToolResult>().forEach { r ->
            out += buildJsonObject {
                put("role", "tool"); put("tool_call_id", r.callId); put("content", r.content)
            }
        }
        val rest = m.parts.filter { it is Part.Text || it is Part.Image }
        if (rest.isNotEmpty()) {
            val role = if (m.role == Role.System) "system" else "user"
            val hasImage = rest.any { it is Part.Image } && capabilities.vision
            out += buildJsonObject {
                put("role", role)
                if (!hasImage) {
                    put("content", rest.joinToString("\n") { textOf(it) })
                } else {
                    putJsonArray("content") {
                        rest.forEach { p ->
                            add(buildJsonObject {
                                if (p is Part.Image) {
                                    put("type", "image_url")
                                    putJsonObject("image_url") { put("url", "data:${p.mime};base64,${p.pngBase64}") }
                                } else {
                                    put("type", "text"); put("text", textOf(p))
                                }
                            })
                        }
                    }
                }
            }
        }
        return out
    }

    private fun textOf(p: Part): String = when (p) {
        is Part.Text -> p.text
        is Part.Image -> "[screenshot omitted: the current model cannot see images]"
        else -> ""
    }

    /** A thought I received: write down this address and the location where you received it, and recognize it when you play it back. */
    private fun thinking(text: String, via: ReasoningVia) = Part.Thinking(text, via.tag(endpoint))

    /** Where can this assistant message be sent back to this address and put back: the received position from here; the field specified in the list for unknown origin; none of them are null. */
    private fun replayable(m: Message): Pair<String, ReasoningVia>? {
        val parts = m.parts.filterIsInstance<Part.Thinking>()
        val own = parts.mapNotNull { p -> ReasoningVia.of(p.opaque, endpoint)?.let { p.text to it } }
        if (own.isNotEmpty()) return own.joinToString("") { it.first } to own.first().second
        val declared = declaredVia ?: return null
        val untagged = parts.filter { it.opaque == null }.joinToString("") { it.text }
        return if (untagged.isEmpty()) null else untagged to declared
    }

    private fun assistantJson(m: Message, replayReasoning: Boolean): JsonObject {
        val text = m.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
        val calls = m.parts.filterIsInstance<Part.ToolCall>()
        val replay = if (replayReasoning) replayable(m) else null
        return buildJsonObject {
            put("role", "assistant")
            when {
                // The house that separated the blocks should put them back as they are: the thinking
                // block in front and the text block in the back.
                replay?.second == ReasoningVia.ThinkingChunks -> putJsonArray("content") {
                    addJsonObject {
                        put("type", "thinking")
                        putJsonArray("thinking") { addJsonObject { put("type", "text"); put("text", replay.first) } }
                    }
                    if (text.isNotEmpty()) addJsonObject { put("type", "text"); put("text", text) }
                }
                text.isEmpty() && calls.isNotEmpty() -> put("content", JsonNull)
                else -> put("content", text)
            }
            replay?.second?.field?.let { put(it, replay.first) }
            if (calls.isNotEmpty()) putJsonArray("tool_calls") {
                calls.forEach { c ->
                    add(buildJsonObject {
                        put("id", c.id); put("type", "function")
                        putJsonObject("function") { put("name", c.name); put("arguments", c.argsJson) }
                    })
                }
            }
        }
    }

    /** Is the entire reply SSE (`data: …` one by one) or JSON. */
    private fun looksLikeEvents(body: String): Boolean {
        val head = body.trimStart()
        return head.startsWith("data:") || head.startsWith("event:")
    }

    /** Split the entire SSE reply into individual events; the content has been collected, and if there is a missing end mark, add one and treat it as normal. */
    private fun eventsOf(body: String): List<String> {
        val out = mutableListOf<String>()
        val data = mutableListOf<String>()
        fun dispatch() {
            if (data.isNotEmpty()) {
                out += data.joinToString("\n")
                data.clear()
            }
        }
        body.lineSequence().forEach { line ->
            when {
                line.isEmpty() -> dispatch()
                line.startsWith("data:") -> data += line.removePrefix("data:").removePrefix(" ")
            }
        }
        dispatch()
        if (out.lastOrNull() != STREAM_DONE) out += STREAM_DONE
        return out
    }

    private fun parse(text: String): ModelReply {
        try {
            val root = json.parseToJsonElement(text).jsonObject
            val choice = root["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw ModelError.BadResponse("缺少 choices")
            val msg = choice["message"]?.jsonObject ?: throw ModelError.BadResponse("缺少 message")
            val parts = mutableListOf<Part>()
            ReasoningVia.fields.firstNotNullOfOrNull { via ->
                (msg[via.field!!] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }?.let { it to via }
            }?.let { (t, via) -> parts += thinking(t, via) }
            when (val content = msg["content"]) {
                is JsonArray -> {
                    val chunks = ContentChunks.of(content)
                    if (chunks.thinking.isNotEmpty() && parts.isEmpty()) parts += thinking(chunks.thinking, ReasoningVia.ThinkingChunks)
                    if (chunks.text.isNotEmpty()) parts += Part.Text(chunks.text)
                }
                is JsonPrimitive -> content.contentOrNull?.takeIf { it.isNotEmpty() }?.let { parts += Part.Text(it) }
                else -> {}
            }
            (msg["tool_calls"] as? JsonArray)?.forEach { el ->
                val c = el.jsonObject
                val fn = c["function"]!!.jsonObject
                parts += Part.ToolCall(
                    c["id"]!!.jsonPrimitive.content,
                    fn["name"]!!.jsonPrimitive.content,
                    // Some adapters give an empty string to tools without parameters: treat it as `{}`,
                    // otherwise the caller will fail to parse it according to JSON.
                    fn["arguments"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "{}",
                )
            }
            val stop = when ((choice["finish_reason"] as? JsonPrimitive)?.contentOrNull) {
                "stop" -> StopReason.EndTurn
                "tool_calls", "function_call" -> StopReason.ToolUse
                "length" -> StopReason.MaxTokens
                else -> if (parts.any { it is Part.ToolCall }) StopReason.ToolUse else StopReason.Other
            }
            // Some models will spit the tool call mark directly into the text: first restore it to the
            // call, and then peel it off if it cannot be restored. Don't leave garbled code to the
            // interface (see LeakedToolMarkup)
            return LeakedToolMarkup.repair(ModelReply(parts, stop, parseUsage(root["usage"])))
        } catch (e: ModelError) {
            throw e
        } catch (e: Exception) {
            throw ModelError.BadResponse(e.message.orEmpty().take(200), e)
        }
    }

    private fun parseUsage(el: JsonElement?): Usage? {
        val u = el as? JsonObject ?: return null
        fun int(o: JsonObject?, k: String) = (o?.get(k) as? JsonPrimitive)?.intOrNull
        // The compatible interface has two hit field names; depending on whether the field exists or
        // not, the value is
        val hit = int(u, "prompt_cache_hit_tokens")
        val miss = int(u, "prompt_cache_miss_tokens")
        val inT = int(u, "prompt_tokens") ?: if (hit != null && miss != null) hit + miss else return null
        val outT = int(u, "completion_tokens") ?: 0
        val cached = maxOf(int(u["prompt_tokens_details"] as? JsonObject, "cached_tokens") ?: 0, hit ?: 0).coerceAtMost(inT)
        // completion_tokens includes thoughts; some service providers report it separately in
        // completion_tokens_details.reasoning_tokens
        val reasoning = (int(u["completion_tokens_details"] as? JsonObject, "reasoning_tokens") ?: 0).coerceIn(0, outT)
        return Usage(inT, outT, cached, reasoning)
    }
}

/** Chat Completions' delta can split the name and parameters of the same tool into multiple frames. */
private class OpenAiStream(
    private val onText: (String) -> Unit,
    private val usageOf: (JsonElement?) -> Usage?,
    private val thinkingOf: (String, ReasoningVia) -> Part.Thinking,
) {
    private data class Call(val id: StringBuilder = StringBuilder(), val name: StringBuilder = StringBuilder(),
        val args: StringBuilder = StringBuilder())

    private val json = Json { ignoreUnknownKeys = true }
    private val visible = StringBuilder()
    private val thinking = StringBuilder()
    private var thinkingVia: ReasoningVia? = null
    private val calls = sortedMapOf<Int, Call>()
    private var usage: Usage? = null
    private var reason: String? = null
    private var done = false
    private var chunks = 0

    fun accept(data: String) {
        if (data == "[DONE]") { done = true; return }
        val root = try { json.parseToJsonElement(data).jsonObject }
        catch (e: Exception) { throw ModelError.BadResponse("流式响应不是合法 JSON", e) }
        if (root["error"] != null) throw ModelError.BadResponse(root["error"].toString().take(200))
        chunks++
        usageOf(root["usage"])?.let { usage = it }
        val choice = (root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return
        (choice["finish_reason"] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty)?.let { reason = it }
        val delta = choice["delta"] as? JsonObject ?: return
        ReasoningVia.fields.forEach { via ->
            (delta[via.field!!] as? JsonPrimitive)?.contentOrNull?.let { appendThinking(it, via) }
        }
        when (val content = delta["content"]) {
            is JsonArray -> {
                val chunks = ContentChunks.of(content)
                if (chunks.thinking.isNotEmpty()) appendThinking(chunks.thinking, ReasoningVia.ThinkingChunks)
                appendVisible(chunks.text)
            }
            is JsonPrimitive -> content.contentOrNull?.let(::appendVisible)
            else -> {}
        }
        (delta["tool_calls"] as? JsonArray)?.forEach { item ->
            val obj = item as? JsonObject ?: return@forEach
            val index = (obj["index"] as? JsonPrimitive)?.intOrNull ?: return@forEach
            val call = calls.getOrPut(index, ::Call)
            (obj["id"] as? JsonPrimitive)?.contentOrNull?.let { call.id.append(it) }
            val fn = obj["function"] as? JsonObject
            (fn?.get("name") as? JsonPrimitive)?.contentOrNull?.let { call.name.append(it) }
            (fn?.get("arguments") as? JsonPrimitive)?.contentOrNull?.let { call.args.append(it) }
        }
    }

    /** When thinking about a reply, only the location received first will be recorded (the same company will not change locations; when both fields are given, only one copy will be recorded). */
    private fun appendThinking(t: String, via: ReasoningVia) {
        if (t.isEmpty()) return
        if (thinkingVia == null) thinkingVia = via
        if (via == thinkingVia) thinking.append(t)
    }

    private fun appendVisible(t: String) {
        if (t.isEmpty()) return
        visible.append(t)
        // Also peel the preview first: when the model spits the tool call mark into the text, don’t let
        // the user watch the garbled code flow by.
        onText(LeakedToolMarkup.stripMarkup(visible.toString()))
    }

    fun finish(): ModelReply {
        // Even if the reply is complete after receiving finish_reason: some compatible endpoints do not
        // send [DONE]. Neither is possible. The connection was disconnected midway.
        if (!done && reason == null) throw ModelError.StreamCut()
        if (chunks == 0) throw ModelError.BadResponse("流式响应没有内容")
        val parts = buildList<Part> {
            thinkingVia?.takeIf { thinking.isNotEmpty() }?.let { add(thinkingOf(thinking.toString(), it)) }
            if (visible.isNotEmpty()) add(Part.Text(visible.toString()))
            calls.values.forEach { c ->
                if (c.name.isEmpty() || c.id.isEmpty()) throw ModelError.BadResponse("工具调用流缺少名称或编号")
                add(Part.ToolCall(c.id.toString(), c.name.toString(), c.args.toString().ifEmpty { "{}" }))
            }
        }
        val stop = when (reason) {
            "stop" -> StopReason.EndTurn
            "tool_calls", "function_call" -> StopReason.ToolUse
            "length" -> StopReason.MaxTokens
            else -> if (calls.isNotEmpty()) StopReason.ToolUse else StopReason.Other
        }
        return LeakedToolMarkup.repair(ModelReply(parts, stop, usage))
    }
}

/**
 * The compatible interface places the thoughts: [field] is the field name on the assistant message;
 * [ThinkingChunks] is `content` as the block array, and the thoughts are in the `thinking` block. Noted
 * in [Part.Thinking.opaque] is `{"endpoint":…,"via":…}`.
 */
internal enum class ReasoningVia(val wire: String, val field: String?) {
    ReasoningContent("reasoning_content", "reasoning_content"),
    Reasoning("reasoning", "reasoning"),
    ThinkingChunks("thinking_chunks", null);

    fun tag(endpoint: String): String = buildJsonObject { put("endpoint", endpoint); put("via", wire) }.toString()

    companion object {
        /** The several types placed on the message field are recognized in this order (if both are given, take `reasoning_content`). */
        val fields = listOf(ReasoningContent, Reasoning)

        fun ofField(name: String?): ReasoningVia? = fields.firstOrNull { it.field == name }

        /** [opaque] is the position given when the thought is received from [endpoint]; if it comes from elsewhere and cannot be recognized, it is null. */
        fun of(opaque: String?, endpoint: String): ReasoningVia? {
            val o = opaque?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: return null
            if ((o["endpoint"] as? JsonPrimitive)?.contentOrNull != endpoint) return null
            val via = (o["via"] as? JsonPrimitive)?.contentOrNull
            return entries.firstOrNull { it.wire == via }
        }
    }
}

/** When `content` is a block array: `text` blocks are combined into answers, and `thinking` blocks (with `text` blocks inside) are combined into thoughts. I don’t recognize other blocks. */
private class ContentChunks(val text: String, val thinking: String) {
    companion object {
        fun of(content: JsonArray): ContentChunks {
            val text = StringBuilder()
            val thinking = StringBuilder()
            content.forEach { el ->
                val c = el as? JsonObject ?: return@forEach
                when ((c["type"] as? JsonPrimitive)?.contentOrNull) {
                    "text" -> (c["text"] as? JsonPrimitive)?.contentOrNull?.let(text::append)
                    "thinking" -> when (val t = c["thinking"]) {
                        is JsonArray -> t.forEach { inner ->
                            ((inner as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull?.let(thinking::append)
                        }
                        is JsonPrimitive -> t.contentOrNull?.let(thinking::append)
                        else -> {}
                    }
                }
            }
            return ContentChunks(text.toString(), thinking.toString())
        }
    }
}
