package com.meenai.mina.core.agent

import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.StopReason
import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.record.RunSink
import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.uistate.UiElement
import com.meenai.mina.core.tool.FakeScreen
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.meenai.mina.core.assembly.toolDispatcher

private class Named(override val name: String, private val inner: FakeModel) : ChatModel by inner {
    val requests get() = inner.requests
    override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply = inner.complete(system, messages, tools)
}

private class Events : RunSink {
    val list = mutableListOf<RunEvent>()
    override fun event(e: RunEvent) { list += e }
}

private var seq = 0
private fun call(name: String, args: String = "{}") = Part.ToolCall("l${++seq}", name, args)
private fun tools(vararg calls: Part.ToolCall) = ModelReply(calls.toList(), StopReason.ToolUse, null)
private fun finish() = com.meenai.mina.core.testing.answerReply("done")

/** There is only a "Send" button on the screen. */
private class SendScreen(private val label: String = "发送", private val inner: FakeScreen = FakeScreen()) : com.meenai.mina.core.uistate.ScreenPort by inner {
    override suspend fun snapshot() = ScreenSnapshot("s1", "com.x",
        listOf(UiElement("@e1", "button", label, 500, 500, listOf(400, 450, 600, 550), null, true, false, false)), 1080, 2400)
}

class AgentLoopLightTest {
    private val on = SpeedFlags(lightSteps = true)

    private fun loop(main: ChatModel, light: ChatModel, sink: RunSink = RunSink.NONE, screen: com.meenai.mina.core.uistate.ScreenPort = FakeScreen()) =
        AgentLoop(main, toolDispatcher(screen), "BASE", sink = sink, flags = on, router = ModelRouter(main, light))

    @Test fun cleanStepsGoToLightAndAreRecorded() = runTest {
        // Step 1: Main model; Step 2: The front-end App has only been seen once, the main model; From
        // Step 3 onwards, the same App is used in both steps, no errors → Lightweight
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val light = Named("light", FakeModel(tools(call("phone_observe")), finish()))
        val sink = Events()
        val r = loop(main, light, sink).run("t")
        assertTrue(r is TaskState.Succeeded)
        // Step 4 If the lightweight model wants to submit results, ask the main model instead
        assertEquals(2, light.requests.size)
        assertEquals(3, main.requests.size)
        val replies = sink.list.filterIsInstance<RunEvent.ModelReply>()
        assertEquals(listOf("main", "main", "light", "light_rejected", "main"), replies.map { it.role })
        assertEquals(listOf("main", "main", "light", "light", "main"), replies.map { it.model })
    }

    // The reply without calling is the final answer and is only given by the main model: the lightweight
    // model will ask the main model instead.
    @Test fun lightAnswerIsAskedAgainOfMain() = runTest {
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val light = Named("light", FakeModel(com.meenai.mina.core.testing.answerReply("LIGHT_ANSWER")))
        val sink = Events()
        val r = loop(main, light, sink).run("t")
        assertTrue(r is TaskState.Succeeded)
        assertEquals(1, light.requests.size)
        assertEquals(3, main.requests.size)
        assertTrue(sink.list.any { it is RunEvent.State && it.name == "light_to_main" && it.detail == "no_tool" })
        assertEquals(listOf("main", "main", "light_rejected", "main"), sink.list.filterIsInstance<RunEvent.ModelReply>().map { it.role })
        // After the reassignment, the history seen by the main model does not have the unexecuted reply.
        val asked = main.requests.last()
        assertTrue(asked.flatMap { it.parts }.filterIsInstance<Part.Text>().none { "LIGHT_ANSWER" in it.text })
        assertEquals(asked.size, light.requests.single().size)
    }

    @Test fun lightCoordinateAndLocatedTapsGoToMain() = runTest {
        for (args in listOf("""{"x":500,"y":500}""", """{"ref":"@v1"}""", """{"x":900,"y":900}""")) {
            val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), tools(call("end_not_done", """{"message":"不点"}"""))))
            val light = Named("light", FakeModel(tools(call("phone_tap", args))))
            val sink = Events()
            loop(main, light, sink, SendScreen()).run("t")
            assertTrue(args, sink.list.any { it is RunEvent.State && it.name == "light_to_main" && it.detail == "phone_tap" })
            assertTrue(args, sink.list.filterIsInstance<RunEvent.ToolResult>().none { it.tool == "phone_tap" })
        }
    }

    @Test fun confirmationBlockedStepForcesMain() = runTest {
        // In step 2, the main model click "Submit order" was blocked by the confirmation level: after
        // two steps, the main model, and in step 5 it was the turn of the lightweight model.
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_tap", """{"ref":"@e1","snapshotId":"s1"}""")),
            tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val light = Named("light", FakeModel(tools(call("phone_observe")), finish()))
        val sink = Events()
        loop(main, light, sink, SendScreen("提交订单")).run("t")
        assertEquals(listOf("main", "main", "main", "main", "light", "light_rejected", "main"),
            sink.list.filterIsInstance<RunEvent.ModelReply>().map { it.role })
        assertTrue((sink.list.filterIsInstance<RunEvent.ToolResult>().first { it.tool == "phone_tap" }).resultJson.contains("confirmation_required"))
    }

    @Test fun permanentLightErrorTurnsRoutingOff() = runTest {
        val main = Named("main", FakeModel(*Array(6) { tools(call("phone_observe")) }, finish()))
        val light = Named("light", FakeModel(com.meenai.mina.core.model.ModelError.Auth()))
        val sink = Events()
        val r = loop(main, light, sink).run("t")
        assertTrue(r is TaskState.Succeeded)
        assertEquals(1, light.requests.size)
        assertEquals(1, sink.list.filterIsInstance<RunEvent.ModelReply>().count { it.role == "light_rejected" })
    }

    @Test fun otherModelsThinkingAndOpaqueAreNotSentAcross() = runTest {
        val mine = Part.ToolCall("m1", "phone_observe", "{}", opaque = "sigM")
        val theirs = Part.ToolCall("x1", "phone_observe", "{}", opaque = "sigL")
        val main = Named("main", FakeModel(ModelReply(listOf(Part.Thinking("tm"), mine), StopReason.ToolUse, null),
            tools(call("phone_observe")), tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val light = Named("light", FakeModel(ModelReply(listOf(Part.Thinking("tl"), theirs), StopReason.ToolUse, null), finish()))
        loop(main, light).run("t")
        // Step 3: Lightweight model; Step 4: It has to hand over the results and ask the main model
        // instead: Remove the thinking and private data for the lightweight model, and the main model
        // remains as it is
        val toMain = main.requests[2].filter { it.role == com.meenai.mina.core.model.Role.Assistant }.flatMap { it.parts }
        assertTrue(mine in toMain && Part.Thinking("tm") in toMain)
        assertTrue(theirs.copy(opaque = null) in toMain && Part.Thinking("tl") !in toMain)
        val toLight = light.requests.first().filter { it.role == com.meenai.mina.core.model.Role.Assistant }.flatMap { it.parts }
        assertTrue(mine.copy(opaque = null) in toLight && toLight.none { it is Part.Thinking })
    }

    @Test fun lightIrreversibleTapIsAskedAgainOfMain() = runTest {
        val tapSend = """{"ref":"@e1","snapshotId":"s1"}"""
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), tools(call("end_not_done", """{"message":"不发"}"""))))
        val light = Named("light", FakeModel(tools(call("phone_tap", tapSend))))
        val sink = Events()
        loop(main, light, sink, SendScreen()).run("t")
        assertEquals(1, light.requests.size)
        assertTrue(sink.list.any { it is RunEvent.State && it.name == "light_to_main" && it.detail == "phone_tap" })
        assertTrue(sink.list.filterIsInstance<RunEvent.ToolResult>().none { it.tool == "phone_tap" })
    }

    @Test fun screenshotsNeverGoToTextOnlyLight() = runTest {
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val light = Named("light", FakeModel())
        // Pictures of the main model: observe Attached screenshots, lightweight models will not be
        // connected unless you look at the pictures
        val seeing = object : ChatModel by main {
            override val capabilities = main.capabilities.copy(vision = true)
        }
        AgentLoop(seeing, toolDispatcher(FakeScreen(), visionEnabled = true), "BASE", flags = on,
            router = ModelRouter(seeing, light)).run("t")
        assertEquals(0, light.requests.size)
    }

    @Test fun lightErrorFallsBackToMain() = runTest {
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val light = Named("light", FakeModel(com.meenai.mina.core.model.ModelError.Network()))
        val r = loop(main, light).run("t")
        assertTrue(r is TaskState.Succeeded)
        // If an error occurs in the lightweight model, there will be no retry. In this step, the main
        // model will be asked directly.
        assertEquals(1, light.requests.size)
        assertEquals(3, main.requests.size)
    }

    @Test fun light503FallsBackImmediately() = runTest {
        val main = Named("main", FakeModel(tools(call("phone_observe")), tools(call("phone_observe")), finish()))
        val light = Named("light", FakeModel(com.meenai.mina.core.model.ModelError.Server(503)))
        val r = loop(main, light).run("t")
        assertTrue(r is TaskState.Succeeded)
        // 503 is not resent to the lightweight model. This step is taken over by the main model.
        assertEquals(1, light.requests.size)
        assertEquals(3, main.requests.size)
    }
}
