package com.meenai.mina.core.router

import com.meenai.mina.core.action.ActionOutcome
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
import com.meenai.mina.core.router.picker.AutoModelPicker
import com.meenai.mina.core.router.picker.DifficultyRouter
import com.meenai.mina.core.router.picker.ModelCacheStore
import com.meenai.mina.core.router.picker.ModelRole
import com.meenai.mina.core.router.picker.OpenRouterApiClient
import com.meenai.mina.core.router.picker.TaskDifficulty
import com.meenai.mina.core.router.preset.ConcurrencyLimitedChatModel
import com.meenai.mina.core.router.preset.HttpModelScopeApiClient
import com.meenai.mina.core.router.preset.ModelScopePreset
import com.meenai.mina.core.router.preset.ZaiPreset
import com.meenai.mina.core.tool.ToolDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.system.measureTimeMillis

class FreeKeyRouterWireTest {

    private fun mockModel(
        name: String,
        vision: Boolean = false,
        context: Int = 64_000,
        responder: suspend (system: String, messages: List<Message>, tools: List<ToolSpec>, onText: (String) -> Unit) -> ModelReply = { _, _, _, _ -> simpleReply },
    ): MockChatModel = MockChatModel(
        name = name,
        capabilities = Capabilities(
            toolCalls = true,
            vision = vision,
            contextTokens = context,
            reasoning = false,
            promptCaching = PromptCaching.Implicit,
        ),
        responder = responder,
    )

    private val userMsg = listOf(Message(Role.User, listOf(Part.Text("do task")), Author.User))
    private val simpleReply = ModelReply(listOf(Part.Text("done")), StopReason.EndTurn, null)

    @Test
    fun switchSirfBoundaryParAndReplaysStep() = runTest {
        var m1Calls = 0
        val model1 = mockModel("m1") { _, _, _, onText ->
            m1Calls++
            onText("partial streaming token from m1...")
            throw ModelError.StreamCut()
        }
        val model2 = mockModel("m2") { _, _, _, onText ->
            onText("clean reply from m2")
            simpleReply
        }

        val router = FreeKeyRouter(
            candidates = listOf(
                RouterCandidate("m1", "p1", model1, curatedRank = 10),
                RouterCandidate("m2", "p2", model2, curatedRank = 5),
            ),
            delayMs = {},
        )

        val streamTokens = mutableListOf<String>()
        val reply = router.completeStreaming("sys", userMsg, emptyList()) { streamTokens.add(it) }

        // Partial output from failed m1 must be discarded with onText("")
        assertTrue(streamTokens.contains(""))
        assertEquals("clean reply from m2", streamTokens.last())
        assertEquals("m2", router.activeCandidate?.id)
        assertEquals(1, m1Calls)
        assertEquals(1, model2.calls)
        assertEquals(1, router.switchCountInTask)

        // Verify RouterStatus stateflow recorded switch event
        val status = router.status.value
        assertEquals("m2", status.currentModel)
        assertEquals("p2", status.provider)
        assertEquals(1, status.switchEvents.size)
        assertEquals("m1", status.switchEvents[0].oldModel)
        assertEquals("m2", status.switchEvents[0].newModel)
    }

    @Test
    fun toolDoubleExecuteNahi() = runTest {
        var toolExecutionCount = 0
        val toolExecutedList = mutableListOf<String>()

        var m1Step2Attempts = 0
        val model1 = mockModel("m1") { _, messages, _, onText ->
            val stepCount = messages.count { it.role == Role.Assistant }
            if (stepCount == 0) {
                // Step 1: succeed with tool call
                ModelReply(listOf(Part.ToolCall("c1", "search", """{"q":"mina"}""")), StopReason.ToolUse, null)
            } else {
                // Step 2: fail mid-stream on m1
                m1Step2Attempts++
                onText("broken partial...")
                throw ModelError.RateLimited(60_000L)
            }
        }

        val model2 = mockModel("m2") { _, messages, _, onText ->
            // Step 2 replay on m2: succeeds
            onText("m2 tool call")
            ModelReply(listOf(Part.ToolCall("c2", "read", """{"id":"doc1"}""")), StopReason.ToolUse, null)
        }

        val router = FreeKeyRouter(
            candidates = listOf(
                RouterCandidate("m1", "p1", model1, curatedRank = 10),
                RouterCandidate("m2", "p2", model2, curatedRank = 5),
            ),
            delayMs = {},
        )

        // Simulated Agent Execution Loop:
        // Step 1
        router.startTask("analyze docs")
        router.prepareStep(1, hasImages = false)
        val replyStep1 = router.completeStreaming("sys", userMsg, emptyList()) {}
        val toolCall1 = replyStep1.parts.filterIsInstance<Part.ToolCall>().first()
        // Execute tool for step 1
        toolExecutionCount++
        toolExecutedList.add(toolCall1.name)
        router.recordStepAction("${toolCall1.name}:${toolCall1.argsJson}")

        // Build history with step 1 results
        val historyStep2 = userMsg +
            Message(Role.Assistant, replyStep1.parts, Author.Mina) +
            Message(Role.Tool, listOf(Part.ToolResult(toolCall1.id, "search results", false)), Author.Mina)

        // Step 2: m1 will fail and router will switch to m2 and replay
        router.prepareStep(2, hasImages = false)
        val replyStep2 = router.completeStreaming("sys", historyStep2, emptyList()) {}
        val toolCall2 = replyStep2.parts.filterIsInstance<Part.ToolCall>().first()
        // Execute tool for step 2
        toolExecutionCount++
        toolExecutedList.add(toolCall2.name)

        // Assertions:
        assertEquals(2, toolExecutionCount)
        assertEquals(listOf("search", "read"), toolExecutedList)
        assertEquals(1, m1Step2Attempts) // m1 failed once on step 2
        assertEquals(1, model2.calls) // m2 executed once on step 2
        // Tool "search" from step 1 was never re-executed!
        assertEquals(1, toolExecutedList.count { it == "search" })
    }

    @Test
    fun sixSwitchLimitThrowsExhaustedMessage() = runTest {
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
            assertEquals(6, router.switchCountInTask)
            assertTrue(router.status.value.reason!!.contains("Sab free limits khatam"))
        }
    }

    @Test
    fun pinnedNoRouting() = runTest {
        val weakPinned = mockModel("pinned_model") { _, _, _, _ -> simpleReply }
        val strongModel = mockModel("strong_model") { _, _, _, _ -> simpleReply }

        val candidates = listOf(
            RouterCandidate("pinned_model", "p_weak", weakPinned, curatedRank = 1),
            RouterCandidate("strong_model", "p_strong", strongModel, curatedRank = 100),
        )

        val picker = AutoModelPicker(
            apiClient = OpenRouterApiClient { """{"data":[]}""" },
            cacheStore = com.meenai.mina.core.router.picker.InMemoryModelCacheStore(),
        )

        val diffRouter = DifficultyRouter(
            picker = picker,
            pinnedModelId = "pinned_model",
        )

        val router = FreeKeyRouter(
            candidates = candidates,
            difficultyRouter = diffRouter,
            delayMs = {},
        )

        // Heavy task that would normally require strong_model
        router.startTask("phone screen control open app and scroll", hasImage = true)
        router.prepareStep(1, hasImages = true)

        val reply = router.completeStreaming("sys", userMsg, emptyList()) {}
        assertEquals("pinned_model", router.activeCandidate?.id)
        assertEquals(1, weakPinned.calls)
        assertEquals(0, strongModel.calls)

        // Escalation should not change pinned model
        diffRouter.recordFailure()
        diffRouter.recordFailure()
        assertTrue(diffRouter.isEscalated)
        router.prepareStep(2, hasImages = true)
        assertEquals("pinned_model", router.activeCandidate?.id)
    }

    @Test
    fun quotaCheckBinaNetwork() {
        val cooldownStore = ModelCooldownStore()
        val candidate = RouterCandidate("m1", "p1", mockModel("m1"))
        val router = FreeKeyRouter(listOf(candidate), cooldownStore = cooldownStore)

        // Benchmark in-memory check without network: 1000 iterations must take < 50ms
        val duration = measureTimeMillis {
            repeat(1000) {
                val ok = router.checkCachedQuota(candidate, isNewTask = false)
                assertTrue(ok)
            }
        }
        assertTrue("Expected < 50ms, took ${duration}ms", duration < 50)

        // Cooldown providers must be skipped
        cooldownStore.markCooldown("m1", 60_000L)
        assertFalse(router.checkCachedQuota(candidate, isNewTask = false))

        // Bad key providers must be skipped
        val candidate2 = RouterCandidate("m2", "bad_provider", mockModel("m2"))
        cooldownStore.markBadKey("bad_provider")
        assertFalse(router.checkCachedQuota(candidate2, isNewTask = false))
    }

    @Test
    fun modelScopeSirfLastFallback() = runTest {
        MagicubeLedger.recordDailyLogin()
        val zaiModel = mockModel("zai_flash") { _, _, _, _ -> simpleReply }
        val openrouterModel = mockModel("or_free") { _, _, _, _ -> simpleReply }
        val modelscopeModel = mockModel("ms_qwen") { _, _, _, _ -> simpleReply }

        val zaiCandidate = RouterCandidate("zai_flash", "zai", zaiModel, curatedRank = 80, isBackup = false)
        val orCandidate = RouterCandidate("or_free", "openrouter", openrouterModel, curatedRank = 70, isBackup = false)
        val msCandidate = RouterCandidate("ms_qwen", "modelscope", modelscopeModel, curatedRank = 95, isBackup = true)

        val cooldownStore = ModelCooldownStore()
        val router = FreeKeyRouter(
            candidates = listOf(zaiCandidate, orCandidate, msCandidate),
            cooldownStore = cooldownStore,
            delayMs = {},
        )

        try {
            // 1. When primary models are available, ModelScope is NOT chosen even if it has higher curated rank!
            val picked1 = router.pickBestCandidate(ModelRole.Main)
            assertNotNull(picked1)
            assertEquals("zai_flash", picked1!!.id)
            assertFalse(picked1.isBackup)

            // 2. Put zai into cooldown -> next primary is or_free (still not ModelScope)
            cooldownStore.markCooldown("zai_flash", 60_000L)
            val picked2 = router.pickBestCandidate(ModelRole.Main)
            assertNotNull(picked2)
            assertEquals("or_free", picked2!!.id)
            assertFalse(picked2.isBackup)

            // 3. Put all primary models into cooldown -> ONLY THEN ModelScope is used as last fallback!
            cooldownStore.markCooldown("or_free", 60_000L)
            val picked3 = router.pickBestCandidate(ModelRole.Main)
            assertNotNull(picked3)
            assertEquals("ms_qwen", picked3!!.id)
            assertTrue(picked3.isBackup)
            assertEquals("modelscope", picked3.provider)
        } finally {
            MagicubeLedger.clear()
        }
    }

    @Test
    fun zaiPresetEnforcesConcurrency1() = runTest {
        var activeConcurrentCalls = 0
        var maxObservedConcurrency = 0

        val slowModel = mockModel("glm-4.7-flash") { _, _, _, _ ->
            activeConcurrentCalls++
            if (activeConcurrentCalls > maxObservedConcurrency) {
                maxObservedConcurrency = activeConcurrentCalls
            }
            delay(50)
            activeConcurrentCalls--
            simpleReply
        }

        val sharedSemaphore = Semaphore(1)
        val limitedModel = ConcurrencyLimitedChatModel(slowModel, semaphore = sharedSemaphore)

        // Launch two concurrent requests to the same model
        val job1 = async { limitedModel.complete("sys", userMsg, emptyList()) }
        val job2 = async { limitedModel.complete("sys", userMsg, emptyList()) }

        job1.await()
        job2.await()

        assertEquals(1, maxObservedConcurrency)
        assertEquals(2, slowModel.calls)
    }

    @Test
    fun dynamicModelScopeModelListParsing() {
        val sampleJson = """
            {
              "object": "list",
              "data": [
                {
                  "id": "Qwen/Qwen2.5-72B-Instruct",
                  "object": "model",
                  "created": 1726713600,
                  "owned_by": "modelscope"
                },
                {
                  "id": "Qwen/Qwen2.5-VL-7B-Instruct",
                  "object": "model",
                  "created": 1726713700,
                  "owned_by": "modelscope"
                }
              ]
            }
        """.trimIndent()

        val client = HttpModelScopeApiClient()
        val candidates = client.parseModelsJson(sampleJson)

        assertEquals(2, candidates.size)
        val qwen72b = candidates[0]
        assertEquals("Qwen/Qwen2.5-72B-Instruct", qwen72b.id)
        assertEquals("modelscope", qwen72b.provider)
        assertEquals(72.0, qwen72b.parameterSizeB, 0.1)
        assertFalse(qwen72b.supportsImages)

        val qwenVl = candidates[1]
        assertEquals("Qwen/Qwen2.5-VL-7B-Instruct", qwenVl.id)
        assertTrue(qwenVl.supportsImages)
    }
}
