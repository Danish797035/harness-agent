package com.meenai.mina.core.testing

import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelError
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.StopReason
import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.core.model.Usage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A request received by the fake model (the message list is a copy of that time). */
data class FakeRequest(val system: String, val messages: List<Message>, val tools: List<ToolSpec>) {
    val toolNames: List<String> get() = tools.map { it.name }
}

/**
 * The model of replying by script: each time a request is made to get the next item in the script, a
 * reply is returned or [ModelError] is thrown; every request received is recorded in [requests]. An
 * assertion error is reported when the script is finished and is called (indicating that the loop asks
 * one more times than the script). Write scripts using [fakeModel].
 */
class FakeChatModel(
    override val name: String,
    override val capabilities: Capabilities,
    script: List<(FakeRequest) -> ModelReply>,
) : ChatModel {
    private val queue = ArrayDeque(script)
    val requests = mutableListOf<FakeRequest>()
    /** The number of items that have not been used in the script; should be 0 at the end of the scene. */
    val remaining: Int get() = queue.size

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
        val req = FakeRequest(system, messages.toList(), tools.toList())
        requests += req
        val next = queue.removeFirstOrNull() ?: throw AssertionError("$name 的剧本用完了:第 ${requests.size} 次请求没有准备回复")
        return next(req)
    }
}

fun fakeModel(
    name: String = "fake",
    contextTokens: Int = 128_000,
    vision: Boolean = false,
    toolCalls: Boolean = true,
    script: FakeScript.() -> Unit,
): FakeChatModel = FakeChatModel(
    name, Capabilities(toolCalls = toolCalls, vision = vision, contextTokens = contextTokens, reasoning = false),
    FakeScript(name).apply(script).items.toList(),
)

/** Script: One item corresponds to one request. */
class FakeScript internal constructor(private val prefix: String) {
    internal val items = mutableListOf<(FakeRequest) -> ModelReply>()
    private var seq = 0
    private fun nextId() = "${prefix}_${++seq}"

    /** One-time reply; when [stop] is not written, a tool is called ToolUse, otherwise EndTurn. */
    fun reply(stop: StopReason? = null, build: ReplyBuilder.() -> Unit) {
        items += { ReplyBuilder(::nextId).apply(build).build(stop) }
    }

    /** Respond immediately according to the request received (for example, use the snapshotId in the last result). */
    fun replyTo(stop: StopReason? = null, build: ReplyBuilder.(FakeRequest) -> Unit) {
        items += { req -> ReplyBuilder(::nextId).apply { build(req) }.build(stop) }
    }

    fun call(name: String, vararg args: Pair<String, Any?>) = reply { call(name, *args) }
    fun text(vararg chunks: String) = reply { text(*chunks) }
    /** Reply without call: The final answer given to the user. */
    fun answer(text: String) = text(text)
    fun reportFailure(message: String) = call("end_not_done", "message" to message)

    /** Output to the upper limit is truncated: only half text, stop reason MaxTokens. */
    fun truncated(partial: String) = reply(StopReason.MaxTokens) { text(partial) }

    /** The parameter is not valid JSON for the tool call. */
    fun malformedCall(name: String, rawArgs: String = "{\"ref\":") = reply { callJson(name, rawArgs) }

    fun error(e: ModelError) { items += { throw e } }
    fun serverError(status: Int = 503) = error(ModelError.Server(status))
    fun rateLimited(retryAfterMs: Long? = 0) = error(ModelError.RateLimited(retryAfterMs))
    fun networkError() = error(ModelError.Network())
    fun contextOverflow() = error(ModelError.ContextTooLong())

    /** Supporting Role: Verification of Conclusions; [met] is a point-by-point criterion with on-screen citations. */
    fun verdict(verdict: String, reason: String, vararg met: Pair<String, String?>) = text(buildJsonObject {
        put("checks", buildJsonArray {
            met.forEach { (c, q) -> add(buildJsonObject { put("criterion", c); put("status", if (verdict == "fail") "unmet" else "met"); put("quote", q) }) }
        })
        put("verdict", verdict); put("reason", reason)
    }.toString())

    fun repeat(times: Int, block: FakeScript.() -> Unit) { kotlin.repeat(times) { block() } }
}

class ReplyBuilder internal constructor(private val nextId: () -> String) {
    private val parts = mutableListOf<Part>()
    private var usage: Usage? = null

    /** Each block has a text fragment, simulating text arriving in blocks (spliced in sequence in a loop). */
    fun text(vararg chunks: String) { chunks.forEach { parts += Part.Text(it) } }
    fun thinking(text: String) { parts += Part.Thinking(text) }
    fun call(name: String, vararg args: Pair<String, Any?>) { callJson(name, JsonObject(args.associate { (k, v) -> k to json(v) }).toString()) }
    fun callJson(name: String, argsJson: String, opaque: String? = null) { parts += Part.ToolCall(nextId(), name, argsJson, opaque) }
    fun usage(input: Int, output: Int, cached: Int = 0) { usage = Usage(input, output, cached) }

    internal fun build(stop: StopReason?) =
        ModelReply(parts.toList(), stop ?: if (parts.any { it is Part.ToolCall }) StopReason.ToolUse else StopReason.EndTurn, usage)
}

private fun json(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is List<*> -> JsonArray(v.map(::json))
    is Map<*, *> -> JsonObject(v.entries.associate { (k, x) -> k.toString() to json(x) })
    else -> error("不支持的参数类型:${v::class}")
}
