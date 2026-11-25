package com.meenai.mina.core.model

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAiCompatModelTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().also { it.start() } }
    @After fun tearDown() { server.shutdown() }

    private fun caps(vision: Boolean = false) = Capabilities(true, vision, 32000, false)
    private fun model(vision: Boolean = false, key: String? = "sk-x") =
        OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), key, "m", caps(vision), OkHttpClient())
    private val user = listOf(Message(Role.User, listOf(Part.Text("hi")), Author.Mina))
    private fun ok(body: String) = MockResponse().setResponseData(body)
    private fun MockResponse.setResponseData(b: String) = setHeader("Content-Type", "application/json").setBody(b)

    private fun body(): kotlinx.serialization.json.JsonObject =
        Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject

    @Test fun parsesToolCalls() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"phone_tap","arguments":"{\"ref\":\"a1\"}"}}]}}],"usage":{"prompt_tokens":10,"completion_tokens":3,"prompt_tokens_details":{"cached_tokens":4}}}"""))
        val r = model().complete("sys", user, listOf(ToolSpec("phone_tap", "d", """{"type":"object"}""")))
        assertTrue(r.parts.contains(Part.ToolCall("c1", "phone_tap", """{"ref":"a1"}""")))
        assertEquals(StopReason.ToolUse, r.stopReason)
        assertEquals(Usage(10, 3, 4), r.usage)
        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer sk-x", req.getHeader("Authorization"))
        val j = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("system", j["messages"]!!.jsonArray[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("function", j["tools"]!!.jsonArray[0].jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun omitsToolsAndAuthWhenEmpty() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"length","message":{"content":"x"}}]}"""))
        val r = model(key = null).complete("s", user, emptyList())
        assertEquals(StopReason.MaxTokens, r.stopReason)
        assertNull(r.usage)
        val req = server.takeRequest()
        assertNull(req.getHeader("Authorization"))
        assertFalse(req.body.readUtf8().contains("\"tools\""))
    }

    /**
     * Non-streaming requests state `stream:false` and require a JSON; streaming requests require SSE.
     * When you omit `stream`, some compatible endpoints (such as local and self-built services on the
     * LAN) still return to SSE. When the client parses according to JSON, it will only see `data: …`.
     */
    @Test fun nonStreamRequestAsksForJsonInsteadOfEvents() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        model().complete("s", user, emptyList())
        val req = server.takeRequest()
        assertTrue(req.body.readUtf8().contains("\"stream\":false"))
        assertEquals("application/json", req.getHeader("Accept"))
    }

    /** When the server does not listen to `stream:false` and still returns to SSE: read it according to the stream and then spell it, it should not become bad JSON. */
    @Test fun readsEventStreamReplyToANonStreamRequest() = runTest {
        val events = listOf(
            """{"choices":[{"delta":{"content":"先看"}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"phone_tap","arguments":"{\"ref\":\"a1\"}"}}]},"finish_reason":"tool_calls"}]}""",
            "[DONE]",
        )
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(events.joinToString("") { "data: $it\n\n" }),
        )
        val r = model().complete("s", user, tools)
        assertEquals(listOf(Part.Text("先看"), Part.ToolCall("c1", "phone_tap", """{"ref":"a1"}""")), r.parts)
        assertEquals(StopReason.ToolUse, r.stopReason)
    }

    /** The entire reply has been received, but it has not been sent. `[DONE]`: Finish as usual, it should not be regarded as the stream being cut off early. */
    @Test fun eventStreamWithoutDoneStillFinishes() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"好\"},\"finish_reason\":\"stop\"}]}\n\n"),
        )
        val r = model().complete("s", user, emptyList())
        assertEquals(listOf(Part.Text("好")), r.parts)
        assertEquals(StopReason.EndTurn, r.stopReason)
    }

    /** For tools without parameters, some adapters treat `""`: as `{}` to prevent the caller from failing to parse JSON. */
    @Test fun blankToolCallArgumentsBecomeEmptyObject() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"tool_calls","message":{"content":null,"tool_calls":[{"id":"c1","type":"function","function":{"name":"get_time","arguments":""}}]}}]}"""))
        val r = model().complete("s", user, tools)
        assertEquals(Part.ToolCall("c1", "get_time", "{}"), r.parts.filterIsInstance<Part.ToolCall>().single())
    }

    @Test fun readsReasoningContent() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok","reasoning_content":"想一想"}}]}"""))
        val r = model().complete("s", user, emptyList())
        assertEquals("想一想", r.parts.filterIsInstance<Part.Thinking>().single().text)
        assertTrue(r.parts.contains(Part.Text("ok")))
        assertEquals(StopReason.EndTurn, r.stopReason)
    }

    private val withImage = listOf(Message(Role.User, listOf(Part.Text("look"), Part.Image("QUJD")), Author.Mina))

    @Test fun sendsImagesOnlyWhenVision() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        model(vision = false).complete("s", withImage, emptyList())
        val off = server.takeRequest().body.readUtf8()
        assertFalse(off.contains("image_url"))
        assertTrue(off.contains("[screenshot omitted: the current model cannot see images]"))

        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        model(vision = true).complete("s", withImage, emptyList())
        assertTrue(server.takeRequest().body.readUtf8().contains("data:image/png;base64,QUJD"))
    }

    @Test fun sendsJpegMime() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        val jpeg = listOf(Message(Role.User, listOf(Part.Image("QUJD", mime = "image/jpeg")), Author.Mina))
        model(vision = true).complete("s", jpeg, emptyList())
        assertTrue(server.takeRequest().body.readUtf8().contains("data:image/jpeg;base64,QUJD"))
    }

    private suspend fun errorFor(resp: MockResponse): ModelError {
        server.enqueue(resp)
        return try { model().complete("s", user, emptyList()); throw AssertionError("no error") } catch (e: ModelError) { e }
    }

    @Test fun mapsErrors() = runTest {
        assertTrue(errorFor(MockResponse().setResponseCode(401)) is ModelError.Auth)
        assertTrue(errorFor(MockResponse().setResponseCode(402)) is ModelError.QuotaExceeded)
        assertTrue(errorFor(MockResponse().setResponseCode(403).setBody("""{"error":{"code":"insufficient_quota"}}""")) is ModelError.QuotaExceeded)
        val rl = errorFor(MockResponse().setResponseCode(429).setHeader("retry-after", "2")) as ModelError.RateLimited
        assertEquals(2000L, rl.retryAfterMs)
        assertTrue(errorFor(MockResponse().setResponseCode(429).setBody("""{"error":{"message":"Rate limit reached, limit: 60"}}""")) is ModelError.RateLimited)
        // This model's quota is zero (Gemini's OpenAI compatible entry returns the array): just change
        // the model.
        val zero = errorFor(MockResponse().setResponseCode(429).setBody("""[{"error":{"code":429,"message":"Quota exceeded for metric: free_tier_requests, limit: 0","status":"RESOURCE_EXHAUSTED"}}]"""))
        assertTrue(zero is ModelError.ModelNotAvailable)
        assertTrue(errorFor(MockResponse().setResponseCode(400).setBody("context_length_exceeded")) is ModelError.ContextTooLong)
        assertTrue(errorFor(MockResponse().setResponseCode(400).setBody("exceeds the maximum context")) is ModelError.ContextTooLong)
        assertEquals("m", (errorFor(MockResponse().setResponseCode(404)) as ModelError.ModelNotFound).model)
        // The 404 returned by the gateway does not necessarily mean that the model name is wrong: bring
        // its original words in JSON; the HTML page does not contain the original words.
        val gateway = errorFor(MockResponse().setResponseCode(404).setBody("""{"error":{"type":"not_found_error","message":"none of group/rpa's models is ready"}}""")) as ModelError.ModelNotFound
        assertEquals("none of group/rpa's models is ready", gateway.detail)
        assertEquals("", (errorFor(MockResponse().setResponseCode(404).setBody("<html><body>404 Not Found</body></html>")) as ModelError.ModelNotFound).detail)
        assertEquals(503, (errorFor(MockResponse().setResponseCode(503)) as ModelError.Server).status)
        // This account has not activated this model: it is not a key or request issue
        val na = errorFor(MockResponse().setResponseCode(400).setBody("""{"code":"InvalidParameter","message":"The product is not activated."}""")) as ModelError.ModelNotAvailable
        assertEquals("m", na.model)
        assertEquals("The product is not activated.", na.detail)
        assertTrue(errorFor(MockResponse().setResponseCode(403).setBody("""{"error":{"message":"not authorized to access this model"}}""")) is ModelError.ModelNotAvailable)
        assertTrue(errorFor(MockResponse().setResponseCode(400).setBody("""{"error":{"message":"m does not support tools"}}""")) is ModelError.ToolsUnsupported)
        assertTrue(errorFor(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)) is ModelError.Network)
    }

    @Test fun badResponses() = runTest {
        assertTrue(errorFor(ok("not json")) is ModelError.BadResponse)
        assertTrue(errorFor(ok("""{"choices":[]}""")) is ModelError.BadResponse)
        assertTrue(errorFor(ok("""{"foo":1}""")) is ModelError.BadResponse)
    }

    @Test fun toolResultRoundTrip() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        val hist = listOf(
            Message(Role.User, listOf(Part.Text("go")), Author.Mina),
            Message(Role.Assistant, listOf(Part.Thinking("secret"), Part.ToolCall("c1", "phone_tap", """{"ref":"a1"}""")), Author.Mina),
            Message(Role.Tool, listOf(Part.ToolResult("c1", "done", false)), Author.Mina),
        )
        model().complete("s", hist, emptyList())
        val raw = server.takeRequest().body.readUtf8()
        assertFalse(raw.contains("secret"))
        val msgs: JsonArray = Json.parseToJsonElement(raw).jsonObject["messages"]!!.jsonArray
        val asst = msgs[2].jsonObject
        assertEquals("assistant", asst["role"]!!.jsonPrimitive.content)
        assertEquals("c1", asst["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("""{"ref":"a1"}""", asst["tool_calls"]!!.jsonArray[0].jsonObject["function"]!!.jsonObject["arguments"]!!.jsonPrimitive.content)
        val tool = msgs[3].jsonObject
        assertEquals("tool", tool["role"]!!.jsonPrimitive.content)
        assertEquals("c1", tool["tool_call_id"]!!.jsonPrimitive.content)
        assertEquals("done", tool["content"]!!.jsonPrimitive.content)
    }

    private val tools = listOf(ToolSpec("phone_tap", "d", """{"type":"object"}"""))
    private val endpoint get() = server.url("/v1/chat/completions").toString()
    private fun texts(parts: List<Part>) = parts.map { if (it is Part.Thinking) it.text else it }

    /** Reasoning_content: requests with tools received by any address are returned to the same field as they are; thoughts from elsewhere are not sent; requests without tools are not sent back. */
    @Test fun reasoningContentReceivedHereIsReplayedWithTools() = runTest {
        val m = model()
        server.enqueue(ok("""{"choices":[{"finish_reason":"tool_calls","message":{"content":null,"reasoning_content":"先找入口","tool_calls":[{"id":"c1","type":"function","function":{"name":"phone_tap","arguments":"{}"}}]}}]}"""))
        val first = m.complete("s", user, tools)
        assertEquals(listOf<Any>("先找入口", Part.ToolCall("c1", "phone_tap", "{}")), texts(first.parts))
        server.takeRequest()

        val history = user + listOf(
            Message(Role.Assistant, first.parts, Author.Mina),
            Message(Role.Tool, listOf(Part.ToolResult("c1", "done", false)), Author.Mina),
            // Unknown sources (given by other adapters, saved from old versions) will not be sent.
            Message(Role.Assistant, listOf(Part.Thinking("别处想的"), Part.Text("已打开")), Author.Mina),
            Message(Role.User, listOf(Part.Text("再看一下")), Author.User),
        )
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        m.complete("s", history, tools)
        val messages = body()["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals("先找入口", messages[2]["reasoning_content"]!!.jsonPrimitive.content)
        assertFalse("reasoning" in messages[2])
        assertEquals(JsonNull, messages[2]["content"])
        assertEquals("c1", messages[2]["tool_calls"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertFalse("reasoning_content" in messages[4])

        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        m.complete("s", history, emptyList())
        assertFalse("reasoning_content" in body()["messages"]!!.jsonArray[2].jsonObject)
    }

    /** Thinking received in the `reasoning` field, puts back `reasoning` (without changing it to reasoning_content). */
    @Test fun reasoningFieldIsReplayedUnderItsOwnName() = runTest {
        val m = model()
        server.enqueue(ok("""{"choices":[{"finish_reason":"tool_calls","message":{"content":"","reasoning":"想想","tool_calls":[{"id":"c1","type":"function","function":{"name":"phone_tap","arguments":"{}"}}]}}]}"""))
        val first = m.complete("s", user, tools)
        assertEquals("想想", (first.parts.first() as Part.Thinking).text)
        server.takeRequest()
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        m.complete("s", user + Message(Role.Assistant, first.parts, Author.Mina) +
            Message(Role.Tool, listOf(Part.ToolResult("c1", "done", false)), Author.Mina), tools)
        val assistant = body()["messages"]!!.jsonArray[2].jsonObject
        assertEquals("想想", assistant["reasoning"]!!.jsonPrimitive.content)
        assertFalse("reasoning_content" in assistant)
    }

    /** Thoughts received at another address (changed service provider) are not sent to this one: it may not recognize this field. */
    @Test fun reasoningReceivedFromAnotherEndpointIsNotSent() = runTest {
        val elsewhere = Part.Thinking("那边想的", ReasoningVia.ReasoningContent.tag("https://other.example/v1/chat/completions"))
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        model().complete("s", user + Message(Role.Assistant, listOf(elsewhere, Part.Text("next")), Author.Mina), tools)
        val assistant = body()["messages"]!!.jsonArray[2].jsonObject
        assertFalse("reasoning_content" in assistant)
        assertFalse("reasoning" in assistant)
        // If you receive it at the same address, it will be sent.
        val here = Part.Thinking("这边想的", ReasoningVia.ReasoningContent.tag(endpoint))
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        model().complete("s", user + Message(Role.Assistant, listOf(here, Part.Text("next")), Author.Mina), tools)
        assertEquals("这边想的", body()["messages"]!!.jsonArray[2].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
    }

    /** The model list indicates the thought field, and thoughts of unknown origin (the history saved in the old version) are also returned according to it. */
    @Test fun modelsListedWithReasoningContentFieldReplayItWithTools() = runTest {
        val m = OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "k", "kimi-k3", caps(), OkHttpClient(), "reasoning_content")
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        val history = user + Message(Role.Assistant, listOf(Part.Thinking("先想"), Part.Text("next")), Author.Mina)
        m.complete("s", history, tools)
        assertEquals("先想", body()["messages"]!!.jsonArray[2].jsonObject["reasoning_content"]!!.jsonPrimitive.content)
    }

    /** `content` is a block array: the text block is the answer, and the thinking block is the thinking; during playback, the whole piece is put back into blocks, with the thinking block in front. */
    @Test fun chunkedContentIsParsedAndReplayedAsChunks() = runTest {
        val m = model()
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":[{"type":"thinking","thinking":[{"type":"text","text":"先想"},{"type":"text","text":"再想"}]},{"type":"text","text":"答案"}]}}]}"""))
        val first = m.complete("s", user, tools)
        assertEquals(listOf<Any>("先想再想", Part.Text("答案")), texts(first.parts))
        server.takeRequest()
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        m.complete("s", user + Message(Role.Assistant, first.parts, Author.Mina) + Message(Role.User, listOf(Part.Text("接着")), Author.User), tools)
        val content = body()["messages"]!!.jsonArray[2].jsonObject["content"]!!.jsonArray.map { it.jsonObject }
        assertEquals("thinking", content[0]["type"]!!.jsonPrimitive.content)
        assertEquals("先想再想", content[0]["thinking"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("text", content[1]["type"]!!.jsonPrimitive.content)
        assertEquals("答案", content[1]["text"]!!.jsonPrimitive.content)
    }

    @Test fun readsPromptCacheHitTokens() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"hi"}}],"usage":{"prompt_tokens":900,"completion_tokens":10,"prompt_cache_hit_tokens":640,"prompt_cache_miss_tokens":260}}"""))
        assertEquals(Usage(900, 10, 640), model().complete("sys", user, emptyList()).usage)
        // When prompt_tokens is missing, the sum of hits + misses is used.
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"hi"}}],"usage":{"completion_tokens":10,"prompt_cache_hit_tokens":640,"prompt_cache_miss_tokens":260}}"""))
        assertEquals(Usage(900, 10, 640), model().complete("sys", user, emptyList()).usage)
    }

    @Test fun readsCachedTokensDetails() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"hi"}}],"usage":{"prompt_tokens":500,"completion_tokens":7,"prompt_tokens_details":{"cached_tokens":128}}}"""))
        assertEquals(Usage(500, 7, 128), model().complete("sys", user, emptyList()).usage)
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"hi"}}],"usage":{"prompt_tokens":500,"completion_tokens":7}}"""))
        assertEquals(Usage(500, 7, 0), model().complete("sys", user, emptyList()).usage)
    }

    @Test fun readsReasoningTokensDetails() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"hi"}}],"usage":{"prompt_tokens":500,"completion_tokens":70,"completion_tokens_details":{"reasoning_tokens":64}}}"""))
        assertEquals(Usage(500, 70, 0, reasoningTokens = 64), model().complete("sys", user, emptyList()).usage)
        // If the report is more than the output, press the output button.
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"hi"}}],"usage":{"prompt_tokens":500,"completion_tokens":7,"completion_tokens_details":{"reasoning_tokens":64}}}"""))
        assertEquals(Usage(500, 7, 0, reasoningTokens = 7), model().complete("sys", user, emptyList()).usage)
    }

    @Test fun cachedTakesLargerFieldClampedToInput() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"hi"}}],"usage":{"prompt_tokens":900,"completion_tokens":1,"prompt_tokens_details":{"cached_tokens":100},"prompt_cache_hit_tokens":640}}"""))
        assertEquals(Usage(900, 1, 640), model().complete("sys", user, emptyList()).usage)
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"hi"}}],"usage":{"prompt_tokens":900,"completion_tokens":1,"prompt_tokens_details":{"cached_tokens":1000}}}"""))
        assertEquals(Usage(900, 1, 900), model().complete("sys", user, emptyList()).usage)
    }

    // Explicit caching: The system prompt word and the last message that can be marked are marked with
    // cache_control; the previous message is unchanged, and the beginning of the two requests is the
    // same word-for-word.
    @Test fun explicitCachingMarksSystemAndLastMessage() = runTest {
        val m = OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "k", "m",
            Capabilities(true, true, 32000, false, promptCaching = PromptCaching.Explicit), OkHttpClient())
        val history = listOf(
            Message(Role.User, listOf(Part.Text("任务")), Author.Mina),
            Message(Role.Assistant, listOf(Part.ToolCall("c1", "phone_observe", "{}")), Author.Mina),
            Message(Role.Tool, listOf(Part.ToolResult("c1", "屏幕", false)), Author.Mina),
        )
        val reply = """{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}],"usage":{"prompt_tokens":3000,"completion_tokens":5,"prompt_tokens_details":{"cached_tokens":2048,"cache_creation_input_tokens":900}}}"""
        server.enqueue(ok(reply)); server.enqueue(ok(reply))
        val r = m.complete("SYS", history, emptyList())
        assertEquals(Usage(3000, 5, 2048), r.usage)
        val first = body()["messages"]!!.jsonArray.map { it.jsonObject }
        fun marks(msgs: List<kotlinx.serialization.json.JsonObject>) = msgs.mapIndexedNotNull { i, msg ->
            (msg["content"] as? JsonArray)?.takeIf { c -> c.any { "cache_control" in it.jsonObject } }?.let { i }
        }
        assertEquals(listOf(0, 3), marks(first))
        assertEquals("ephemeral", first[0]["content"]!!.jsonArray.last().jsonObject["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("屏幕", first[3]["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("tool", first[3]["role"]!!.jsonPrimitive.content)

        // Next step: One more round; the mark is moved to the new last one, and the previous one is
        // restored to its original state (the server finds the beginning according to the content, and
        // the mark itself is not counted as content)
        m.complete("SYS", history + Message(Role.Assistant, listOf(Part.ToolCall("c2", "phone_tap", "{}")), Author.Mina) +
            Message(Role.Tool, listOf(Part.ToolResult("c2", "点了", false)), Author.Mina), emptyList())
        val second = body()["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf(0, 5), marks(second))
        assertEquals(first.subList(1, 3), second.subList(1, 3))
    }

    @Test fun implicitCachingSendsNoMarks() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""))
        model().complete("SYS", user, emptyList())
        assertFalse(body().toString().contains("cache_control"))
    }

    @Test fun sendsReasoningEffortOnlyWhenChosen() = runTest {
        val reply = """{"choices":[{"finish_reason":"stop","message":{"content":"ok"}}]}"""
        server.enqueue(ok(reply))
        OpenAiCompatModel(server.url("/v1").toString().trimEnd('/'), "sk-x", "m", caps(), OkHttpClient(), reasoningEffort = "high")
            .complete("s", user, emptyList())
        assertEquals("high", body()["reasoning_effort"]!!.jsonPrimitive.content)

        server.enqueue(ok(reply))
        model().complete("s", user, emptyList())
        assertFalse(body().containsKey("reasoning_effort"))
    }

    // After the interruption: the half reply is an assistant message with only text, without calling and
    // thinking fields; the next two user messages are sent as they are.
    @Test fun interruptedReplyIsAPlainAssistantMessageBeforeTheSteer() = runTest {
        server.enqueue(ok("""{"choices":[{"finish_reason":"stop","message":{"content":"好"}}]}"""))
        model().complete("s", InterruptedHistory.messages, emptyList())
        val msgs = body()["messages"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("system", "user", "assistant", "user", "user"), msgs.map { it["role"]!!.jsonPrimitive.content })
        assertEquals(setOf("role", "content"), msgs[2].keys)
        assertEquals(InterruptedHistory.PARTIAL, msgs[2]["content"]!!.jsonPrimitive.content)
        assertEquals(InterruptedHistory.STEER, msgs[3]["content"]!!.jsonPrimitive.content)
        assertEquals(InterruptedHistory.RUNTIME, msgs[4]["content"]!!.jsonPrimitive.content)
    }
}
