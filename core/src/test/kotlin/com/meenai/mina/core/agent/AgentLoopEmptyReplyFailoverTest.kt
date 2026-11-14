package com.meenai.mina.core.agent

import com.meenai.mina.core.assembly.toolDispatcher
import com.meenai.mina.core.agent.FailKind
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.StopReason
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.record.RunSink
import com.meenai.mina.core.router.FreeKeyRouter
import com.meenai.mina.core.router.RouterCandidate
import com.meenai.mina.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopEmptyReplyFailoverTest {

    private class Events : RunSink {
        val list = mutableListOf<RunEvent>()
        override fun event(e: RunEvent) { list += e }
    }

    private fun finish(text: String = "done") = com.meenai.mina.core.testing.answerReply(text)

    @Test
    fun emptyReplyAfterNudgeSwitchesToNextCandidateAndSucceeds() = runTest {
        val model1 = FakeModel(
            ModelReply(emptyList(), StopReason.EndTurn, null), // 1st turn: empty
            ModelReply(emptyList(), StopReason.EndTurn, null), // 2nd turn: empty after nudge
        )
        val model2 = FakeModel(
            finish("Successfully answered by model 2")
        )

        val candidate1 = RouterCandidate(
            id = "test-model-1",
            provider = "test-provider-1",
            model = model1,
            curatedRank = 1,
        )
        val candidate2 = RouterCandidate(
            id = "test-model-2",
            provider = "test-provider-2",
            model = model2,
            curatedRank = 2,
        )

        val router = FreeKeyRouter(
            candidates = listOf(candidate1, candidate2),
        )
        val sink = Events()
        val loop = AgentLoop(
            model = router,
            tools = toolDispatcher(FakeScreen()),
            basePrompt = "BASE",
            sink = sink,
        )

        val result = loop.run("test task")
        assertTrue("Task should succeed via candidate 2, but was $result", result is TaskState.Succeeded)
        assertEquals("Successfully answered by model 2", (result as TaskState.Succeeded).answer)

        // Model 1 was placed into cooldown
        assertTrue(router.cooldownStore.getRemainingCooldownMs("test-model-1") > 0)
        // Model 2 became active candidate
        assertEquals("test-model-2", router.activeCandidate?.id)

        // Check that model_switched event was emitted
        val switchEvents = sink.list.filterIsInstance<RunEvent.State>().filter { it.name == "model_switched" }
        assertEquals(1, switchEvents.size)
        assertTrue(switchEvents[0].detail.contains("Switched to test-model-2"))
    }

    @Test
    fun emptyReplyFailsWhenMaxSwitchesExceeded() = runTest {
        // 3 models that all return empty replies repeatedly
        val model1 = FakeModel(
            ModelReply(emptyList(), StopReason.EndTurn, null),
            ModelReply(emptyList(), StopReason.EndTurn, null),
        )
        val model2 = FakeModel(
            ModelReply(emptyList(), StopReason.EndTurn, null),
            ModelReply(emptyList(), StopReason.EndTurn, null),
        )
        val model3 = FakeModel(
            ModelReply(emptyList(), StopReason.EndTurn, null),
            ModelReply(emptyList(), StopReason.EndTurn, null),
        )

        val c1 = RouterCandidate(id = "m1", provider = "p1", model = model1, curatedRank = 1)
        val c2 = RouterCandidate(id = "m2", provider = "p2", model = model2, curatedRank = 2)
        val c3 = RouterCandidate(id = "m3", provider = "p3", model = model3, curatedRank = 3)

        val router = FreeKeyRouter(candidates = listOf(c1, c2, c3))
        val loop = AgentLoop(
            model = router,
            tools = toolDispatcher(FakeScreen()),
            basePrompt = "BASE",
        )

        val result = loop.run("test task")
        // After 2 switches (m1 -> m2, m2 -> m3), when m3 also returns empty twice, it cannot switch a 3rd time (max 2 switches per task)
        assertTrue("Task should end with no answer after exceeding switch limit, but was $result", result is TaskState.Failed)
        assertEquals(FailKind.Model, (result as TaskState.Failed).kind)
    }
}
