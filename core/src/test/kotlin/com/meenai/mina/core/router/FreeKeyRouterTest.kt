package com.meenai.mina.core.router

import com.meenai.mina.core.model.Author
import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelError
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.PromptCaching
import com.meenai.mina.core.model.Role
import com.meenai.mina.core.model.StopReason
import com.meenai.mina.core.model.StreamingChatModel
import com.meenai.mina.core.model.ToolSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Random

class FreeKeyRouterTest {

    private fun mockModel(
        name: String,
        context: Int = 64_000,
        responder: suspend (system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit) -> ModelReply,
    ) = MockChatModel(name, Capabilities(toolCalls = true, vision = true, contextTokens = context, reasoning = false, promptCaching = PromptCaching.Implicit), responder)

    private val userMsg = listOf(Message(Role.User, listOf(Part.Text("do task")), Author.User))
    private val simpleReply = ModelReply(listOf(Part.Text("done")), StopReason.EndTurn, null)

    @Test
    fun maintainsStickyModelAcrossSuccessfulCalls() = runTest {
        val model1 = mockModel("m1") { _, _, _, onText -> onText("m1-reply"); simpleReply }
        val model2 = mockModel("m2") { _, _, _, onText -> onText("m2-reply"); simpleReply }

        val candidate1 = RouterCandidate("m1", "p1", model1, curatedRank = 10)
        val candidate2 = RouterCandidate("m2", "p2", model2, curatedRank = 5)

        val router = FreeKeyRouter(listOf(candidate1, candidate2), delayMs = {})

        val reply1 = router.completeStreaming("sys", userMsg, emptyList()) {}
        assertEquals("m1", router.activeCandidate?.id)
        assertEquals(1, model1.calls)
        assertEquals(0, model2.calls)

        val reply2 = router.completeStreaming("sys", userMsg, emptyList()) {}
        assertEquals("m1", router.activeCandidate?.id)
        assertEquals(2, model1.calls)
        assertEquals(0, model2.calls)
    }

    @Test
    fun switchesSeamlesslyOn429RateLimit() = runTest {
        var m1Called = 0
        val model1 = mockModel("m1") { _, _, _, _ ->
            m1Called++
            throw ModelError.RateLimited(60_000L)
        }
        val model2 = mockModel("m2") { _, _, _, onText ->
            onText("success from m2")
            simpleReply
        }

        val router = FreeKeyRouter(
            listOf(
                RouterCandidate("m1", "p1", model1, curatedRank = 10),
                RouterCandidate("m2", "p2", model2, curatedRank = 5),
            ),
            delayMs = {},
        )

        var textEmitted = ""
        val reply = router.completeStreaming("sys", userMsg, emptyList()) { textEmitted = it }

        assertEquals("m2", router.activeCandidate?.id)
        assertEquals(1, m1Called)
        assertEquals(1, model2.calls)
        assertEquals("success from m2", textEmitted)
    }

    @Test
    fun discardsPartialTextOnMidStreamFailureAndReplaysCleanly() = runTest {
        var m1Streams = 0
        val model1 = mockModel("m1") { _, _, _, onText ->
            m1Streams++
            onText("partial broken token...")
            throw ModelError.StreamCut()
        }
        val model2 = mockModel("m2") { _, _, _, onText ->
            onText("clean full reply from m2")
            simpleReply
        }

        val router = FreeKeyRouter(
            listOf(
                RouterCandidate("m1", "p1", model1, curatedRank = 10),
                RouterCandidate("m2", "p2", model2, curatedRank = 5),
            ),
            delayMs = {},
        )

        val textHistory = mutableListOf<String>()
        val reply = router.completeStreaming("sys", userMsg, emptyList()) { textHistory.add(it) }

        // Mid-stream error must invoke onText("") to discard broken partial tokens
        assertTrue(textHistory.contains(""))
        assertEquals("clean full reply from m2", textHistory.last())
        assertEquals("m2", router.activeCandidate?.id)
    }

    @Test
    fun retriesWithSanitizedSchemaOn400BeforeSwitching() = runTest {
        var m1Attempts = 0
        val model1 = mockModel("m1") { _, _, tools, _ ->
            m1Attempts++
            if (m1Attempts == 1) {
                // First attempt has unsanitized schema
                throw ModelError.BadResponse("additionalProperties not allowed in parameters schema")
            }
            // Second attempt receives sanitized schema and succeeds!
            simpleReply
        }

        val tool = ToolSpec("test_tool", "desc", """{"type":"object","additionalProperties":false,"properties":{"k":{"type":"string"}}}""")

        val router = FreeKeyRouter(listOf(RouterCandidate("m1", "p1", model1)), delayMs = {})
        val reply = router.completeStreaming("sys", userMsg, listOf(tool)) {}

        assertEquals(2, m1Attempts)
        assertEquals("m1", router.activeCandidate?.id)
        val finalSchema = model1.lastTools?.first()?.parametersJsonSchema.orEmpty()
        assertFalse(finalSchema.contains("additionalProperties"))
    }

    @Test
    fun skipsProviderCompletelyOn401AuthError() = runTest {
        val model1 = mockModel("m1_p1") { _, _, _, _ -> throw ModelError.Auth() }
        val model2 = mockModel("m2_p1") { _, _, _, _ -> throw AssertionError("Should not call second model from bad provider") }
        val model3 = mockModel("m3_p2") { _, _, _, onText -> onText("p2 ok"); simpleReply }

        val router = FreeKeyRouter(
            listOf(
                RouterCandidate("m1_p1", "bad_provider", model1, curatedRank = 10),
                RouterCandidate("m2_p1", "bad_provider", model2, curatedRank = 9),
                RouterCandidate("m3_p2", "good_provider", model3, curatedRank = 5),
            ),
            delayMs = {},
        )

        router.completeStreaming("sys", userMsg, emptyList()) {}
        assertEquals("m3_p2", router.activeCandidate?.id)
        assertTrue(router.cooldownStore.isKeyBad("bad_provider"))
        assertEquals(1, model1.calls)
        assertEquals(0, model2.calls)
        assertEquals(1, model3.calls)
    }

    @Test
    fun doesNotSwitchOnContentRefusal() = runTest {
        val model1 = mockModel("m1") { _, _, _, _ -> throw ModelError.ContentRefused() }
        val model2 = mockModel("m2") { _, _, _, _ -> throw AssertionError("Should not switch on safety refusal") }

        val router = FreeKeyRouter(
            listOf(
                RouterCandidate("m1", "p1", model1, curatedRank = 10),
                RouterCandidate("m2", "p2", model2, curatedRank = 5),
            ),
            delayMs = {},
        )

        try {
            router.completeStreaming("sys", userMsg, emptyList()) {}
            fail("Expected ContentRefused")
        } catch (e: ModelError.ContentRefused) {
            // Expected
        }
    }

    @Test
    fun compactsHistoryWhenSwitchingToSmallerContextModel() = runTest {
        val model1 = mockModel("big_model", context = 128_000) { _, _, _, _ ->
            throw ModelError.RateLimited(60_000L)
        }
        val model2 = mockModel("small_model", context = 500) { _, messages, _, _ ->
            simpleReply
        }

        val router = FreeKeyRouter(
            listOf(
                RouterCandidate("big_model", "p1", model1, curatedRank = 10),
                RouterCandidate("small_model", "p2", model2, curatedRank = 5),
            ),
            delayMs = {},
        )

        // Create 20 turns of conversation
        val history = mutableListOf<Message>()
        history.add(Message(Role.User, listOf(Part.Text("Initial Goal: analyze data")), Author.User))
        for (i in 1..10) {
            history.add(Message(Role.Assistant, listOf(Part.ToolCall("c$i", "search", """{"q":"step $i"}""")), Author.Mina))
            history.add(Message(Role.Tool, listOf(Part.ToolResult("c$i", "Lots of detailed result text for step $i ".repeat(10), false)), Author.Mina))
        }

        router.completeStreaming("sys", history, emptyList()) {}

        val receivedMessages = model2.lastMessages!!
        assertTrue(receivedMessages.size < history.size)
        // Check that initial goal and summary exist
        assertEquals("Initial Goal: analyze data", (receivedMessages[0].parts[0] as Part.Text).text)
        assertTrue(receivedMessages.any { m -> m.parts.any { it is Part.Text && it.text.contains("Summary of previous") } })
    }

    @Test
    fun throwsAllLimitsExhaustedWhenReachingMax6Switches() = runTest {
        val candidates = (1..8).map { i ->
            val m = mockModel("m$i") { _, _, _, _ -> throw ModelError.RateLimited(60_000L) }
            RouterCandidate("m$i", "p$i", m, curatedRank = 10 - i)
        }

        val router = FreeKeyRouter(candidates, maxSwitchesPerTask = 6, delayMs = {})

        try {
            router.completeStreaming("sys", userMsg, emptyList()) {}
            fail("Expected AllLimitsExhaustedException")
        } catch (e: AllLimitsExhaustedException) {
            assertTrue(e.message!!.contains("Sab free limits khatam"))
            assertTrue(e.message!!.contains("minute baad try karo"))
            assertEquals(1, e.waitMinutes)
        }
    }

    @Test
    fun chaosTestWith5FailingProvidersCompletes20StepTask() = runTest {
        val random = Random(42)

        // 5 mock providers with variable failures (rate limits, 500s, stream cuts)
        val candidates = (1..5).map { i ->
            val m = mockModel("chaos_m$i") { _, _, _, onText ->
                val roll = random.nextInt(100)
                when {
                    roll < 20 -> throw ModelError.RateLimited(1_000L)
                    roll < 35 -> throw ModelError.Server(503)
                    roll < 45 -> {
                        onText("broken...")
                        throw ModelError.StreamCut()
                    }
                    else -> {
                        onText("step ok")
                        ModelReply(listOf(Part.ToolCall("c_step", "step_tool", "{}")), StopReason.ToolUse, null)
                    }
                }
            }
            RouterCandidate("chaos_m$i", "chaos_p$i", m, curatedRank = 5 - i)
        }

        var clock = 0L
        val router = FreeKeyRouter(
            candidates,
            maxSwitchesPerTask = 15,
            now = { clock },
            delayMs = { clock += it },
            maxInlineWaitMs = 120_000L,
        )

        val conversation = mutableListOf<Message>()
        conversation.add(Message(Role.User, listOf(Part.Text("Execute complex 20-step workflow")), Author.User))

        for (step in 1..20) {
            router.resetTask() // har step ko naya switch budget
            val reply = router.completeStreaming("system prompt", conversation, emptyList()) {}
            assertNotNull(reply)
            conversation.add(Message(Role.Assistant, reply.parts, Author.Mina))
            conversation.add(Message(Role.Tool, listOf(Part.ToolResult("c_step", "result $step", false)), Author.Mina))
        }

        assertEquals(41, conversation.size) // 1 initial user + 20 * (1 assistant + 1 tool)
    }

    @Test
    fun slowModelTimesOutAt15sAndSwitchesToNextCandidate() = runTest {
        var clockTime = 0L
        val m1Slow = mockModel("m1_slow") { _, _, _, _ ->
            // Simulates 60s slow model: delays past 15s timeout
            delay(60_000L)
            simpleReply
        }
        val m2Fast = mockModel("m2_fast") { _, _, _, onText ->
            onText("fast reply from m2")
            simpleReply
        }

        val candidate1 = RouterCandidate("m1_slow", "p1", m1Slow, curatedRank = 10)
        val candidate2 = RouterCandidate("m2_fast", "p2", m2Fast, curatedRank = 5)

        var switchedOld: String? = null
        var switchedNew: String? = null
        var switchReason: String? = null

        val router = FreeKeyRouter(
            candidates = listOf(candidate1, candidate2),
            firstTokenTimeoutMs = 15_000L,
            now = { clockTime },
            delayMs = { clockTime += it },
            onModelSwitched = { old, new, reason ->
                switchedOld = old
                switchedNew = new
                switchReason = reason
            },
        )

        var textEmitted = ""
        val reply = router.completeStreaming("sys", userMsg, emptyList()) { textEmitted = it }

        assertEquals("fast reply from m2", textEmitted)
        assertEquals("m2_fast", router.activeCandidate?.id)
        assertEquals("m1_slow", switchedOld)
        assertEquals("m2_fast", switchedNew)
        assertEquals("Model slow tha, dusra try kar raha hoon", switchReason)
        assertTrue(router.cooldownStore.getRemainingCooldownMs("m1_slow", clockTime) > 0L)
        assertTrue(testScheduler.currentTime in 15_000L..20_000L)
    }

    @Test
    fun pinnedModelDoesNotSwitchOnTimeout() = runTest {
        val m1Slow = mockModel("m1_pinned") { _, _, _, _ ->
            delay(60_000L)
            simpleReply
        }
        val m2Fast = mockModel("m2_candidate") { _, _, _, onText ->
            onText("reply from m2")
            simpleReply
        }

        val candidate1 = RouterCandidate("m1_pinned", "p1", m1Slow, curatedRank = 10)
        val candidate2 = RouterCandidate("m2_candidate", "p2", m2Fast, curatedRank = 5)

        val router = FreeKeyRouter(
            candidates = listOf(candidate1, candidate2),
            firstTokenTimeoutMs = 15_000L,
            delayMs = {},
        )
        router.pinModel("m1_pinned")

        try {
            router.completeStreaming("sys", userMsg, emptyList()) {}
            fail("Expected FirstTokenTimeoutException")
        } catch (e: ModelError.FirstTokenTimeout) {
            // Expected
        }

        // Must NOT switch to m2
        assertEquals("m1_pinned", router.activeCandidate?.id)
        assertEquals("m1_pinned", router.pinnedModelId)
        assertEquals(0, m2Fast.calls)
        assertEquals("Model slow tha, dobara try karo", router.status.value.reason)
    }
}

class MockChatModel(
    override val name: String,
    override val capabilities: Capabilities = Capabilities(toolCalls = true, vision = true, contextTokens = 64_000, reasoning = false, promptCaching = PromptCaching.Implicit),
    var responder: suspend (system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit) -> ModelReply,
) : StreamingChatModel {
    var calls = 0
    var lastMessages: List<Message>? = null
    var lastTools: List<ToolSpec>? = null

    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
        completeStreaming(system, messages, tools, {})

    override suspend fun completeStreaming(
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
        onText: (String) -> Unit
    ): ModelReply {
        calls++
        lastMessages = messages
        lastTools = tools
        return responder(system, messages, tools, onText)
    }
}
