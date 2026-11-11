package com.meenai.mina.core.router

import com.meenai.mina.core.model.Author
import com.meenai.mina.core.model.Capabilities
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

class FreeKeyRouterHA005Test {

    private val userMsg = listOf(Message(Role.User, listOf(Part.Text("run tool")), Author.User))
    private val toolCallReply = ModelReply(
        listOf(Part.ToolCall("call_123", "execute_command", "{\"cmd\":\"ls\"}")),
        StopReason.ToolUse,
        null,
    )
    private val textReply = ModelReply(listOf(Part.Text("text reply")), StopReason.EndTurn, null)
    private val emptyReply = ModelReply(emptyList(), StopReason.EndTurn, null)

    private fun mockStreamingModel(
        name: String,
        responder: suspend (
            system: String,
            messages: List<Message>,
            tools: List<ToolSpec>,
            onText: (String) -> Unit,
            onActivity: () -> Unit,
        ) -> ModelReply,
    ) = object : StreamingChatModel {
        override val name: String = name
        override val capabilities: Capabilities = Capabilities(
            toolCalls = true,
            vision = true,
            contextTokens = 64_000,
            reasoning = false,
            promptCaching = PromptCaching.Implicit,
        )
        var calls = 0

        override suspend fun complete(
            system: String,
            messages: List<Message>,
            tools: List<ToolSpec>,
        ): ModelReply = completeStreaming(system, messages, tools, {}, {})

        override suspend fun completeStreaming(
            system: String,
            messages: List<Message>,
            tools: List<ToolSpec>,
            onText: (String) -> Unit,
        ): ModelReply = completeStreaming(system, messages, tools, onText, {})

        override suspend fun completeStreaming(
            system: String,
            messages: List<Message>,
            tools: List<ToolSpec>,
            onText: (String) -> Unit,
            onActivity: () -> Unit,
        ): ModelReply {
            calls++
            return responder(system, messages, tools, onText, onActivity)
        }
    }

    // Scenario 1: Normal text response arrives immediately.
    @Test
    fun scenario1_normalTextResponseArrivesImmediately() = runTest {
        val model = mockStreamingModel("m1") { _, _, _, onText, _ ->
            onText("Immediate text token")
            textReply
        }
        val candidate = RouterCandidate("m1", "p1", model, curatedRank = 10)
        val router = FreeKeyRouter(listOf(candidate), firstTokenTimeoutMs = 15_000L, delayMs = {})

        var receivedText = ""
        val reply = router.completeStreaming("sys", userMsg, emptyList(), onText = { receivedText = it })

        assertEquals("Immediate text token", receivedText)
        assertEquals(textReply, reply)
        assertEquals("m1", router.activeCandidate?.id)
        assertEquals(1, model.calls)
    }

    // Scenario 2: Tool call arrives before any text.
    @Test
    fun scenario2_toolCallArrivesBeforeAnyText() = runTest {
        val model = mockStreamingModel("m1") { _, _, _, _, onActivity ->
            // Tool call arrives without any onText() calls
            onActivity()
            toolCallReply
        }
        val candidate = RouterCandidate("m1", "p1", model, curatedRank = 10)
        val router = FreeKeyRouter(listOf(candidate), firstTokenTimeoutMs = 15_000L, delayMs = {})

        var receivedText = ""
        var activityCount = 0
        val reply = router.completeStreaming(
            system = "sys",
            messages = userMsg,
            tools = emptyList(),
            onText = { receivedText = it },
            onActivity = { activityCount++ },
        )

        assertEquals("", receivedText)
        assertEquals(toolCallReply, reply)
        assertEquals("m1", router.activeCandidate?.id)
        assertEquals(1, model.calls)
        assertTrue(activityCount > 0)
    }

    // Scenario 3: Empty text chunks arrive, followed by a tool call.
    @Test
    fun scenario3_emptyTextChunksArriveFollowedByToolCall() = runTest {
        val model = mockStreamingModel("m1") { _, _, _, onText, onActivity ->
            onText("")
            onActivity()
            onText("")
            toolCallReply
        }
        val candidate = RouterCandidate("m1", "p1", model, curatedRank = 10)
        val router = FreeKeyRouter(listOf(candidate), firstTokenTimeoutMs = 15_000L, delayMs = {})

        var receivedText = ""
        val reply = router.completeStreaming("sys", userMsg, emptyList(), onText = { receivedText = it })

        assertEquals("", receivedText)
        assertEquals(toolCallReply, reply)
        assertEquals("m1", router.activeCandidate?.id)
        assertEquals(1, model.calls)
    }

    // Scenario 4: Tool call arrives after 15 seconds but the stream has been active.
    @Test
    fun scenario4_toolCallArrivesAfter15SecondsWithActiveStream() = runTest {
        val model = mockStreamingModel("m1") { _, _, _, _, onActivity ->
            // Stream emits activity early (e.g. at 2 seconds)
            delay(2_000L)
            onActivity()
            // Takes total 18 seconds (> 15s timeout threshold) to produce the complete tool call
            delay(16_000L)
            toolCallReply
        }
        val candidate = RouterCandidate("m1", "p1", model, curatedRank = 10)
        val router = FreeKeyRouter(listOf(candidate), firstTokenTimeoutMs = 15_000L, delayMs = {})

        val reply = router.completeStreaming("sys", userMsg, emptyList(), onText = {})

        assertEquals(toolCallReply, reply)
        assertEquals("m1", router.activeCandidate?.id)
        assertEquals(1, model.calls)
        assertEquals(18_000L, testScheduler.currentTime)
    }

    // Scenario 5: Model genuinely produces no activity and should time out.
    @Test
    fun scenario5_modelGenuinelyProducesNoActivityAndTimesOut() = runTest {
        var clockTime = 1_000_000L
        val slowModel = mockStreamingModel("m1_silent") { _, _, _, _, _ ->
            // Genuinely inactive: no onText, no onActivity
            delay(30_000L)
            textReply
        }
        val fastModel = mockStreamingModel("m2_backup") { _, _, _, onText, _ ->
            onText("backup response")
            textReply
        }

        val candidate1 = RouterCandidate("m1_silent", "p1", slowModel, curatedRank = 10)
        val candidate2 = RouterCandidate("m2_backup", "p2", fastModel, curatedRank = 5)

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
        val reply = router.completeStreaming("sys", userMsg, emptyList(), onText = { textEmitted = it })

        assertEquals(textReply, reply)
        assertEquals("m2_backup", router.activeCandidate?.id)
        assertEquals("m1_silent", switchedOld)
        assertEquals("m2_backup", switchedNew)
        assertEquals("Model slow tha, dusra try kar raha hoon", switchReason)
        assertEquals(1, slowModel.calls)
        assertEquals(1, fastModel.calls)
        assertEquals("backup response", textEmitted)
        assertTrue(router.cooldownStore.getRemainingCooldownMs("m1_silent", clockTime) > 0L)
    }

    // Scenario 6: Stream throws an exception.
    @Test
    fun scenario6_streamThrowsException() = runTest {
        val failingModel = mockStreamingModel("m_fail") { _, _, _, _, _ ->
            throw ModelError.BadResponse("500 Internal Server Error")
        }
        val fallbackModel = mockStreamingModel("m_fallback") { _, _, _, onText, _ ->
            onText("recovered text")
            textReply
        }

        val candidate1 = RouterCandidate("m_fail", "p1", failingModel, curatedRank = 10)
        val candidate2 = RouterCandidate("m_fallback", "p2", fallbackModel, curatedRank = 5)

        val router = FreeKeyRouter(
            candidates = listOf(candidate1, candidate2),
            firstTokenTimeoutMs = 15_000L,
            delayMs = {},
        )

        var textEmitted = ""
        val reply = router.completeStreaming("sys", userMsg, emptyList(), onText = { textEmitted = it })

        assertEquals(textReply, reply)
        assertEquals("m_fallback", router.activeCandidate?.id)
        assertEquals(1, failingModel.calls)
        assertEquals(1, fallbackModel.calls)
        assertEquals("recovered text", textEmitted)
    }

    // Scenario 7: Stream completes normally without text or tool calls.
    @Test
    fun scenario7_streamCompletesNormallyWithoutTextOrToolCalls() = runTest {
        val emptyModel = mockStreamingModel("m_empty") { _, _, _, _, _ ->
            // Completes immediately without onText or onActivity
            emptyReply
        }
        val candidate = RouterCandidate("m_empty", "p1", emptyModel, curatedRank = 10)
        val router = FreeKeyRouter(
            candidates = listOf(candidate),
            firstTokenTimeoutMs = 15_000L,
            delayMs = {},
        )

        var textEmitted = ""
        val reply = router.completeStreaming("sys", userMsg, emptyList(), onText = { textEmitted = it })

        assertEquals(emptyReply, reply)
        assertEquals("m_empty", router.activeCandidate?.id)
        assertEquals("", textEmitted)
        assertEquals(1, emptyModel.calls)
        // Watchdog was cancelled cleanly upon completion, candidate was not marked in cooldown
        assertEquals(0L, router.cooldownStore.getRemainingCooldownMs("m_empty"))
    }
}
