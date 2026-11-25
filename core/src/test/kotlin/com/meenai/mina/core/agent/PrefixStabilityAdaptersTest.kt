package com.meenai.mina.core.agent

import com.meenai.mina.core.assembly.toolDispatcher
import com.meenai.mina.core.cap.sandbox.CommandResult
import com.meenai.mina.core.cap.sandbox.FakeSandbox
import com.meenai.mina.core.model.AnthropicModel
import com.meenai.mina.core.model.BearerToken
import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.GeminiModel
import com.meenai.mina.core.model.OpenAiCompatModel
import com.meenai.mina.core.model.PromptCaching
import com.meenai.mina.core.model.ResponsesModel
import com.meenai.mina.core.testing.Screen
import com.meenai.mina.core.testing.ScriptedScreen
import com.meenai.mina.core.testing.label
import com.meenai.mina.core.uistate.ScreenPort
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The same fake task of [PrefixStabilityTest] was replaced with adapters of several other interfaces
 * (Responses, Anthropic, Gemini, and OpenAI-compatible interfaces with explicit cache tags), and the
 * same items were checked by online bytes: Area 0 (system prompt words, tools) only changes when a group
 * is expanded, and the tools ranked first do not change word by word when expanded; The history is only
 * appended, only the compressed ones are rewritten; the playback thoughts (encrypted thoughts, signed
 * thoughts, thoughtSignature) are brought together at every step; area 0 and the top-level fields other
 * than the message (thinking gear, output upper limit, etc.) are the same every time. The cache tag
 * (cache_control) is moved to the last one each time. The tag itself does not enter the cache key and is
 * removed before comparison; Anthropic requests no more than 4 tags at a time.
 */
class PrefixStabilityAdaptersTest {
    private val servers = mutableListOf<MockWebServer>()
    private val pad = "第几项内容".repeat(30)

    @After fun tearDown() = servers.forEach { it.shutdown() }

    /** An interface: how to build an adapter, how to split requests into areas 0 and messages one by one, how to return tool calls and the final answer. */
    private interface Api {
        fun model(url: String, vision: Boolean, contextTokens: Int): ChatModel
        /** Area 0 is the top-level field occupied by each message; the remaining top-level fields should be the same for each request. */
        val keys: Set<String>
        /** The prefix of the playback value of each step in the fake reply (the nth step is the prefix plus n); the interface without playback is null. */
        val replay: String?
        /** Request to rewrite prefix (compression) under 80K window, see [PrefixStabilityTest.compaction]. */
        val compactions: List<Int>
        fun zone0(body: JsonObject): String
        /** Message (entry) by message, the cache mark is removed. */
        fun messages(body: JsonObject): List<String>
        /** The raw bytes and names of each tool. */
        fun tools(body: JsonObject): List<Pair<String, String>>
        fun call(step: Int, name: String, args: JsonObject, promptTokens: Int): MockResponse
        fun answer(promptTokens: Int): MockResponse
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun sse(vararg data: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(data.joinToString("") { "data: $it\n\n" })

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    private fun strip(e: JsonElement): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.filterKeys { it != "cache_control" }.mapValues { strip(it.value) })
        is JsonArray -> JsonArray(e.map(::strip))
        else -> e
    }

    private val responses = object : Api {
        override fun model(url: String, vision: Boolean, contextTokens: Int) = ResponsesModel(
            "$url/v1", BearerToken.fixed("k"), "m", Capabilities(true, vision, contextTokens, true), OkHttpClient(), reasoningEffort = "medium",
        )
        override val keys = setOf("instructions", "tools", "input")
        override val replay = "ENC"
        override val compactions = listOf(12, 21, 30)
        override fun zone0(body: JsonObject) = body["instructions"].toString() + body["tools"]
        override fun messages(body: JsonObject) = body["input"]!!.jsonArray.map { it.toString() }
        override fun tools(body: JsonObject) = body["tools"]!!.jsonArray.map { it.toString() to it.jsonObject.str("name")!! }
        override fun call(step: Int, name: String, args: JsonObject, promptTokens: Int): MockResponse {
            val call = buildJsonObject {
                put("type", "function_call"); put("id", "fc_$step"); put("call_id", "call_$step"); put("name", name); put("arguments", args.toString())
            }
            return sse(
                """{"type":"response.output_item.done","item":{"type":"reasoning","id":"rs_$step","summary":[],"encrypted_content":"ENC$step"}}""",
                """{"type":"response.output_item.done","item":$call}""",
                completed(promptTokens),
            )
        }
        override fun answer(promptTokens: Int) = sse(
            """{"type":"response.output_item.done","item":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"看完了"}]}}""",
            completed(promptTokens),
        )
        private fun completed(promptTokens: Int) =
            """{"type":"response.completed","response":{"status":"completed","usage":{"input_tokens":$promptTokens,"output_tokens":10}}}"""
    }

    private val anthropic = object : Api {
        override fun model(url: String, vision: Boolean, contextTokens: Int) =
            AnthropicModel(url, "k", "m", Capabilities(true, vision, contextTokens, true), OkHttpClient(), reasoningEffort = "medium")
        override val keys = setOf("system", "tools", "messages")
        override val replay = "SIG"
        override val compactions = listOf(12, 21, 30)
        override fun zone0(body: JsonObject) = strip(body["system"]!!).toString() + strip(body["tools"]!!)
        override fun messages(body: JsonObject) = body["messages"]!!.jsonArray.map { strip(it).toString() }
        override fun tools(body: JsonObject) = body["tools"]!!.jsonArray.map { strip(it).toString() to it.jsonObject.str("name")!! }
        override fun call(step: Int, name: String, args: JsonObject, promptTokens: Int) = json(
            """{"content":[{"type":"thinking","thinking":"想第${step}步","signature":"SIG$step"},""" +
                """{"type":"tool_use","id":"tu_$step","name":"$name","input":$args}],""" +
                """"stop_reason":"tool_use","usage":{"input_tokens":$promptTokens,"output_tokens":10}}""",
        )
        override fun answer(promptTokens: Int) = json(
            """{"content":[{"type":"text","text":"看完了"}],"stop_reason":"end_turn","usage":{"input_tokens":$promptTokens,"output_tokens":10}}""",
        )
    }

    private val gemini = object : Api {
        override fun model(url: String, vision: Boolean, contextTokens: Int) =
            GeminiModel(url, "k", "m", Capabilities(true, vision, contextTokens, true), OkHttpClient(), reasoningEffort = "medium")
        override val keys = setOf("systemInstruction", "tools", "contents")
        override val replay = "TS"
        // The request bytes are slightly less than the other three, and are just one step away from the
        // trigger point: once the length of the prompt word or tool description changes, the step beyond
        // the trigger point may be one step away from the other three (it used to be 13, 24); now it is
        // crossed over at the same step as the other three
        override val compactions = listOf(12, 21, 30)
        override fun zone0(body: JsonObject) = body["systemInstruction"].toString() + body["tools"]
        override fun messages(body: JsonObject) = body["contents"]!!.jsonArray.map { it.toString() }
        override fun tools(body: JsonObject) = body["tools"]!!.jsonArray.flatMap { t ->
            t.jsonObject["functionDeclarations"]!!.jsonArray.map { it.toString() to it.jsonObject.str("name")!! }
        }
        override fun call(step: Int, name: String, args: JsonObject, promptTokens: Int) =
            reply("""{"functionCall":{"name":"$name","args":$args},"thoughtSignature":"TS$step"}""", promptTokens)
        override fun answer(promptTokens: Int) = reply("""{"text":"看完了"}""", promptTokens)
        private fun reply(part: String, promptTokens: Int) = json(
            """{"candidates":[{"content":{"role":"model","parts":[$part]},"finishReason":"STOP"}],""" +
                """"usageMetadata":{"promptTokenCount":$promptTokens,"candidatesTokenCount":10}}""",
        )
    }

    /** The OpenAI compatible interface needs to be marked as cached (the compatible interfaces of some service providers are like this): move the mark to the last one each time. */
    private val explicitCompat = object : Api {
        override fun model(url: String, vision: Boolean, contextTokens: Int) = OpenAiCompatModel(
            "$url/v1", "k", "m", Capabilities(true, vision, contextTokens, false, promptCaching = PromptCaching.Explicit), OkHttpClient(),
        )
        override val keys = setOf("messages", "tools")
        override val replay = null
        override val compactions = listOf(12, 21, 30)
        override fun zone0(body: JsonObject) = strip(body["messages"]!!.jsonArray.first()).toString() + body["tools"]
        override fun messages(body: JsonObject) = body["messages"]!!.jsonArray.drop(1).map { plain(strip(it).jsonObject).toString() }
        override fun tools(body: JsonObject) =
            body["tools"]!!.jsonArray.map { it.toString() to it.jsonObject["function"]!!.jsonObject.str("name")!! }

        /**
         * When marking, the string content is replaced by a text block. When it is no longer the last
         * one, it is a string again: it is treated as the same one when comparing. The basis is an
         * example of the context cache document of a service provider's compatible interface (checked on
         * 2026-11-01): the first time a system message is sent with an array tag, and the second time
         * the same content is sent as a string, it still hits; the document only cites the system
         * message, and other roles are processed similarly, which is an inference.
         */
        private fun plain(m: JsonObject): JsonObject {
            val block = (m["content"] as? JsonArray)?.singleOrNull() as? JsonObject ?: return m
            if (block.keys != setOf("type", "text") || block.str("type") != "text") return m
            return JsonObject(m + ("content" to block["text"]!!))
        }
        override fun call(step: Int, name: String, args: JsonObject, promptTokens: Int) = json(
            """{"choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[{"id":"call_$step","type":"function",""" +
                """"function":{"name":"$name","arguments":${JsonPrimitive(args.toString())}}}]}}],""" +
                """"usage":{"prompt_tokens":$promptTokens,"completion_tokens":10}}""",
        )
        override fun answer(promptTokens: Int) =
            json("""{"choices":[{"finish_reason":"stop","message":{"content":"看完了"}}],"usage":{"prompt_tokens":$promptTokens,"completion_tokens":10}}""")
    }

    /** Run the fake task of [PrefixStabilityTest] and return the request received by the server. */
    private fun run(api: Api, vision: Boolean = false, contextTokens: Int = 1_000_000): List<JsonObject> {
        val bodies = CopyOnWriteArrayList<JsonObject>()
        val server = MockWebServer().also { servers += it }.apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val raw = request.body.readUtf8()
                    val body = Json.parseToJsonElement(raw).jsonObject
                    bodies += body
                    val msgs = api.messages(body)
                    val step = STEP.findAll(msgs.last()).last().groupValues[1].toInt()
                    val tokens = TokenEstimate.of(raw)
                    if (step > STEPS) return api.answer(tokens)
                    val (name, args) = when {
                        step % 3 == 1 -> "phone_observe" to buildJsonObject {}
                        step % 3 == 2 -> lastRead(msgs.joinToString("")).let { (ref, snap) ->
                            "phone_tap" to buildJsonObject { put("ref", ref); put("snapshotId", snap) }
                        }
                        else -> "run_command" to buildJsonObject { put("command", "cat part$step.txt") }
                    }
                    return api.call(step, name, args, tokens)
                }
            }
            start()
        }
        val model = api.model(server.url("/").toString().trimEnd('/'), vision, contextTokens)
        val screens = (1..40).map { n -> Screen("com.x", (1..12).map { label("@e$it", "第${n}屏第${it}项$pad", 100 + it * 50) }) }
        val scripted = ScriptedScreen(screens)
        val port: ScreenPort = if (!vision) scripted else object : ScreenPort by scripted {
            override suspend fun screenshotPng() = byteArrayOf(1, 2, 3)
        }
        val sb = FakeSandbox().apply { next = { r -> CommandResult(0, false, false, 10, "输出${r.argv}" + "行".repeat(6000), 0, false) } }
        runTest {
            AgentLoop(model, toolDispatcher(port, sandbox = sb, workspace = { "w" }, visionEnabled = vision),
                SystemPrompt.identity(), STEPS + 5, clock = { 0L }, zone = ZoneOffset.UTC,
            ).run("把每一屏都看一遍").also { assertTrue("$it", it is TaskState.Succeeded) }
        }
        return bodies.toList()
    }

    /** The number of the first element in the latest screen reading and the screen reading number; as a result, the number of escape layers in each online format is different, and you can search without limit by escaping. */
    private fun lastRead(text: String): Pair<String, String> {
        val at = ELEMENTS.findAll(text).last().range.first
        return REF.find(text, at)!!.groupValues[1] to SNAPSHOT.findAll(text.substring(0, at)).last().groupValues[1]
    }

    private val entries = setOf(
        "phone_launch_app", "phone_list_apps", "phone_set_alarm", "phone_set_timer", "phone_add_calendar_event", "web_navigate", "web_read_page", "run_command",
    )
    private val markers = listOf(
        HistoryPruner.OMITTED, HistoryPruner.OMITTED_READ, HistoryPruner.olderImage(0).substringBefore(";"), HistoryPruner.OMITTED_IMAGE, ContextCompressor.HEADER,
    )

    /**
     * The first difference between the two messages is that when the content is a list of blocks (or
     * parts), the details are down to the block. Interfaces (Anthropic, Gemini) that merge consecutive
     * messages of the same role into one. Merge the summary into the first user message, which also
     * contains the deployment context; it is not until the block is detailed that it is clear whether
     * the summary or the deployment context is changed.
     */
    private fun firstDiff(old: String, new: String): Pair<String, String> {
        val (a, b) = Json.parseToJsonElement(old).jsonObject to Json.parseToJsonElement(new).jsonObject
        val key = listOf("content", "parts").firstOrNull { a[it] is JsonArray && b[it] is JsonArray } ?: return old to new
        val (x, y) = a[key]!!.jsonArray to b[key]!!.jsonArray
        val same = x.zip(y).takeWhile { (p, q) -> p == q }.size
        return x.getOrNull(same).toString() to y.getOrNull(same).toString()
    }

    private class Result(val changed: List<Int>, val share: Double, val opened: List<Int>)

    /** Same as [PrefixStabilityTest]; [appendOnly]: The trigger point has not been crossed and should not be rewritten even once. */
    private fun check(api: Api, bodies: List<JsonObject>, label: String, appendOnly: Boolean = true): Result {
        val zone0 = bodies.map(api::zone0)
        val msgs = bodies.map(api::messages)
        val opened = (1 until bodies.size).filter { zone0[it] != zone0[it - 1] }
        assertTrue("$label:区 0 变了 $opened 次", opened.size <= 2)
        for (i in opened) {
            val (a, b) = api.tools(bodies[i - 1]) to api.tools(bodies[i])
            val same = a.zip(b).takeWhile { (x, y) -> x.first == y.first }.size
            assertTrue("$label 第 ${i + 1} 次请求:展开时改了一组前面的工具 ${a.getOrNull(same)?.second}", same == a.size || a[same].second in entries)
            assertTrue("$label 第 ${i + 1} 次请求:展开只加不减", b.size > a.size)
        }
        val changed = mutableListOf<Int>()
        for (i in 1 until bodies.size) {
            val (a, b) = msgs[i - 1] to msgs[i]
            val same = a.zip(b).takeWhile { (x, y) -> x == y }.size
            if (same == a.size) continue
            changed += i
            // The first thing to be rewritten is the deleted article, or the abstract itself (the
            // abstract will be replaced with a new one when you press it again)
            val (old, new) = firstDiff(a[same], b[same])
            assertTrue("$label 第 ${i + 1} 次请求第 $same 条改写不是压缩:${new.take(300)}",
                markers.any { it in new && it !in old } || ContextCompressor.HEADER in new)
        }
        if (appendOnly) assertEquals("$label:没越过触发点却改写了前缀", emptyList<Int>(), changed)
        val rest = bodies.map { b -> JsonObject(b.filterKeys { it !in api.keys }) }.distinct()
        assertEquals("$label:区 0 和消息以外的字段变了", 1, rest.size)
        val full = bodies.indices.map { zone0[it] + msgs[it].joinToString("") }
        val pairs = full.zipWithNext().withIndex().filter { (i, _) -> i + 1 !in opened }.map { it.value }
        val share = pairs.sumOf { (a, b) -> a.commonPrefixWith(b).length.toLong() }.toDouble() / pairs.sumOf { (_, b) -> b.length.toLong() }
        return Result(changed, share, opened)
    }

    /** The last request brought all 30 steps of playback: it was not brought twice before and after, and the above items cannot be found. */
    private fun replaysAll(api: Api, bodies: List<JsonObject>, label: String) {
        val prefix = api.replay ?: return
        val last = bodies.last().toString()
        val missing = (1..STEPS).filter { "\"$prefix$it\"" !in last }
        assertEquals("$label:最后一次请求缺了这几步的回放", emptyList<Int>(), missing)
    }

    // Screen reading, large results (and screen): do not rewrite once in 30 steps, area 0 is only
    // expanded when using the phone for the first time or using the sandbox for the first time
    private fun appendOnly(api: Api, label: String) {
        for (vision in listOf(false, true)) {
            val l = if (vision) "$label vision" else label
            val bodies = run(api, vision = vision)
            val r = check(api, bodies, l)
            assertEquals(l, listOf(1, 3), r.opened)
            assertTrue("$l ${r.share}", r.share > 0.95)
            replaysAll(api, bodies, l)
        }
    }

    // 80K window: only rewritten a few times during compression, the sequence number is the same as the
    // algorithm of [PrefixStabilityTest.compaction] (Gemini, see its compactions)
    private fun compaction(api: Api, label: String) {
        val bodies = run(api, contextTokens = 80_000)
        val r = check(api, bodies, "$label compaction", appendOnly = false)
        assertTrue(bodies.any { ContextCompressor.HEADER in it.toString() })
        assertEquals(api.compactions, r.changed)
        assertTrue("${r.share}", r.share > 0.85)
    }

    @Test fun responsesAppendOnly() = appendOnly(responses, "responses")
    @Test fun responsesCompaction() = compaction(responses, "responses")
    @Test fun anthropicAppendOnly() = appendOnly(anthropic, "anthropic")
    @Test fun anthropicCompaction() = compaction(anthropic, "anthropic")
    @Test fun geminiAppendOnly() = appendOnly(gemini, "gemini")
    @Test fun geminiCompaction() = compaction(gemini, "gemini")
    @Test fun explicitCompatAppendOnly() = appendOnly(explicitCompat, "compat explicit")
    @Test fun explicitCompatCompaction() = compaction(explicitCompat, "compat explicit")

    // One system prompt word, one for the last tool, and one for the last user message; Anthropic can
    // request up to 4 at a time, if more, the entire request will be rejected (400)
    @Test fun anthropicCacheMarksStayWithinLimit() {
        for (body in run(anthropic, contextTokens = 80_000)) {
            assertEquals("缓存标记个数", 3, CACHE_MARK.findAll(body.toString()).count())
        }
    }

    private companion object {
        const val STEPS = 30
        val STEP = Regex("Step: (\\d+) of at most")
        val ELEMENTS = Regex("""elements\\*":\[\{""")
        val REF = Regex("""ref\\*":\\*"([^"\\]+)""")
        val SNAPSHOT = Regex("""snapshotId\\*":\\*"([^"\\]+)""")
        val CACHE_MARK = Regex("\"cache_control\"")
    }
}
