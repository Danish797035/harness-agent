package com.meenai.mina.core.agent

import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import com.meenai.mina.core.model.StopReason
import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.replay.Events
import com.meenai.mina.core.replay.MemRoutes
import com.meenai.mina.core.replay.Notes
import com.meenai.mina.core.replay.PagedScreen
import com.meenai.mina.core.replay.RouteMatcher
import com.meenai.mina.core.replay.ui
import com.meenai.mina.core.verify.Verifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.meenai.mina.core.assembly.toolDispatcher

class AgentLoopReplayTest {
    private var seq = 0
    private fun call(name: String, args: String = "{}") = ModelReply(listOf(Part.ToolCall("m${++seq}", name, args)), StopReason.ToolUse, null)
    private fun text(s: String) = ModelReply(listOf(Part.Text(s)), StopReason.EndTurn, null)
    private val finish = text("写好了,屏上有新笔记")
    private val pick = """{"route": 1, "slots": ["今天天气不错"]}"""
    private val pass = """{"checks": [{"criterion": "t", "status": "met", "quote": "新笔记"}], "verdict": "pass", "reason": "屏上有"}"""
    private val goal = "在备忘录里写今天天气不错"

    private class Run(val sink: Events, val main: FakeModel, val store: MemRoutes, val screen: PagedScreen)

    private fun run(
        screen: PagedScreen = Notes.screen(), store: MemRoutes = MemRoutes(Notes.route()), main: FakeModel = FakeModel(finish),
        matcher: ChatModel = FakeModel(text(pick)), flags: SpeedFlags = SpeedFlags(replay = true), verifier: Verifier? = null,
        task: String = goal,
        /** The outcome is determined after the answer is handed over (in the product, the result is determined after the result is handed over to the user); the route is only recorded as successful when completed. */
        outcome: RunOutcome = RunOutcome.Done,
    ): Run {
        val sink = Events()
        runBlocking {
            val agent = AgentLoop(main, toolDispatcher(screen), "BASE", sink = sink, flags = flags, verifier = verifier,
                routes = RouteMatcher(matcher, store, null))
            agent.run(task)
            agent.noteOutcome(outcome)
        }
        return Run(sink, main, store, screen)
    }

    /** When returning, add all the text of the dialogue: description, and the result of the call to screen reading. */
    private fun firstPrompt(r: Run) = r.main.requests.first().drop(1).flatMap { it.parts }.joinToString("") {
        when (it) {
            is Part.Text -> it.text
            is Part.ToolResult -> it.content
            else -> ""
        }
    }

    @Test fun goalsThatNameTheWebAreNotReplayed() {
        val matcher = FakeModel(text(pick))
        val r = run(matcher = matcher, task = "用网页$goal")
        assertTrue(r.sink.all.filterIsInstance<RunEvent.Replay>().isEmpty())
        assertTrue(matcher.requests.isEmpty())
    }

    @Test fun divergedHandsBackWithSummaryAndFullScreen() {
        val s = Notes.screen()
        s.pages["home"] = listOf(ui("@e4", "text", "请先登录", listOf(0, 0, 999, 60), clickable = false))
        val r = run(screen = s)
        val msg = firstPrompt(r)
        assertTrue(msg, msg.contains("1 of 3 steps were run automatically, following a way that worked before"))
        assertTrue(msg, msg.contains("1. phone_launch_app com.notes"))
        assertTrue(msg, msg.contains("Step 2 expected"))
        assertTrue(msg, msg.contains("请先登录"))
        // With a full copy of the screen reading (number and snapshotId), the model can be created
        // directly
        assertTrue(msg, msg.contains("\"snapshotId\"") && msg.contains("\"ref\":\"@e4\""))
        // Screen reading is a pair of calls and results (the call of the assistant comes first, followed
        // closely by the results), and the pairing requirements of each interface are met.
        val h = r.main.requests.first().withoutRuntime()
        val callAt = h.indexOfFirst { m -> m.role == Role.Assistant && m.parts.any { it is Part.ToolCall && it.id == "replay_handback" && it.name == "phone_list_elements" } }
        assertTrue(callAt > 0)
        assertEquals("replay_handback", (h[callAt + 1].parts.single() as Part.ToolResult).callId)
        assertEquals(callAt + 2, h.size)
        assertEquals(listOf(true, false), r.sink.all.filterIsInstance<RunEvent.Replay>().map { it.matched })
        assertEquals(1, r.store.m["r1"]!!.consecutiveMisses)
        assertFalse(r.store.m["r1"]!!.disabled)
    }

    @Test fun executedButUnchangedStepToldToModel() {
        val s = Notes.screen().apply { links.clear() }
        val msg = firstPrompt(run(screen = s))
        assertTrue(msg, msg.contains("Step 2 was run, but the expected change did not show"))
    }

    @Test fun unreadableHandbackScreenStillContinues() {
        val s = Notes.screen().apply { failReads = true }
        val r = run(screen = s)
        val msg = firstPrompt(r)
        assertTrue(msg, msg.contains("The current screen could not be read"))
        assertEquals("succeeded", r.sink.all.filterIsInstance<RunEvent.TaskEnded>().single().outcome)
    }

    @Test fun completedStillRequiresAnAnswer() {
        // The model has to answer the questions by itself after the walk; only after the answers are
        // verified can the route be considered a success: the "new notes" cited in this answer have been
        // read (the literal verification is correct, no need to ask for another verification)
        val ok = run(main = FakeModel(text("写好了,在「新笔记」里")), verifier = Verifier(FakeModel(text(pass)), null))
        assertTrue(firstPrompt(ok).contains("All steps of the earlier way of doing this were run"))
        assertEquals(1, ok.main.requests.size)
        assertTrue(ok.sink.all.none { it is RunEvent.Verifier })
        assertEquals(1, ok.store.m["r1"]!!.successes)

        val gaveUp = run(main = FakeModel(call("end_not_done", """{"message":"没写上"}""")), verifier = Verifier(FakeModel(text(pass)), null))
        assertTrue(gaveUp.sink.all.none { it is RunEvent.Verifier })
        assertEquals(0, gaveUp.store.m["r1"]!!.successes)

        // There is no verifiable value in the answer, and no verification is requested: success will be
        // recorded if the judgment is completed (learning depends on the ending, not verification)
        assertEquals(1, run().store.m["r1"]!!.successes)
        // Checked but not correct (marked as unconfirmed after returning once): No success recorded
        val doubtful = run(main = FakeModel(text("写好了,在「旧草稿 42 号」里"), text("写好了,在「旧草稿 42 号」里")))
        assertEquals(0, doubtful.store.m["r1"]!!.successes)
    }

    @Test fun routeSuccessOnlyWhenJudgedDone() {
        // The answer has been verified, but only part of it was completed, it was not completed, or it
        // was not determined to be successful: it will not be recorded as a success.
        for (o in listOf(RunOutcome.Partial, RunOutcome.NotPossible, RunOutcome.Unknown)) {
            val r = run(main = FakeModel(text("写好了,在「新笔记」里")), verifier = Verifier(FakeModel(text(pass)), null), outcome = o)
            assertEquals(o.wire, 0, r.store.m["r1"]!!.successes)
        }
    }

    @Test fun twoMissesAtSameStepDisableRoute() {
        val store = MemRoutes(Notes.route())
        fun broken() = Notes.screen().apply { pages["home"] = listOf(ui("@e4", "text", "请先登录", listOf(0, 0, 999, 60), clickable = false)) }
        run(screen = broken(), store = store)
        assertFalse(store.m["r1"]!!.disabled)
        run(screen = broken(), store = store)
        assertTrue(store.m["r1"]!!.disabled)
        // No longer a candidate after deactivation: the matching model will not be called
        val m = FakeModel()
        run(store = store, matcher = m)
        assertTrue(m.requests.isEmpty())
    }

    @Test fun replayOffNoMatcherCall() {
        val m = FakeModel()
        val r = run(matcher = m, flags = SpeedFlags.OFF)
        assertTrue(m.requests.isEmpty())
        assertTrue(r.sink.all.none { it is RunEvent.Replay })
        assertTrue(r.screen.typed.isEmpty())
    }

    @Test fun noModelCallsDuringReplay() {
        val s = Notes.screen()
        var typedWhenFirstAsked: List<String>? = null
        // As soon as the main model is called, record the input received by the port at that time: the
        // main model will not be touched during the entire playback process.
        val main = object : ChatModel by FakeModel() {
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                if (typedWhenFirstAsked == null) typedWhenFirstAsked = s.typed.toList()
                return finish
            }
        }
        val sink = Events()
        val matcher = FakeModel(text(pick))
        runBlocking {
            AgentLoop(main, toolDispatcher(s), "BASE", sink = sink, flags = SpeedFlags(replay = true),
                routes = RouteMatcher(matcher, MemRoutes(Notes.route()), null)).run(goal)
        }
        assertEquals(listOf("今天天气不错"), typedWhenFirstAsked)
        assertEquals(1, matcher.requests.size)
        val firstReply = sink.all.indexOfFirst { it is RunEvent.ModelReply }
        assertTrue(sink.all.withIndex().filter { it.value is RunEvent.Replay }.all { it.index < firstReply })
    }
}
