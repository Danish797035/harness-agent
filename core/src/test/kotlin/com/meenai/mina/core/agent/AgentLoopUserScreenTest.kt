package com.meenai.mina.core.agent

import com.meenai.mina.core.assembly.toolDispatcher
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.StopReason
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.record.RunSink
import com.meenai.mina.core.run.RunId
import com.meenai.mina.core.run.RunSpec
import com.meenai.mina.core.testing.answerReply
import com.meenai.mina.core.tool.FakeScreen
import com.meenai.mina.core.userscreen.ScreenLine
import com.meenai.mina.core.userscreen.UserScreen
import com.meenai.mina.core.userscreen.UserScreenMessage
import com.meenai.mina.core.userscreen.UserScreenSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/** When the user sends the current screen, this is what the model sees: the sentence, the screenshot, and the details; there is only one line left from the previous rounds; when only the screen is sent, the intention judgment is not asked. */
class AgentLoopUserScreenTest {
    private val at = 1_761_624_120_000L // 2025-10-28 04:02 UTC
    private val chat = UserScreen("s1", at, "com.example.chat", "聊天", lines = listOf(
        ScreenLine("周六那个聚会你还去吗", 300, 200), ScreenLine("应该去吧,怎么了", 700, 260),
    ))
    private val bank = UserScreen("s2", at, "com.example.bank", "银行", blocked = true)
    private val png = byteArrayOf(1, 2, 3)
    private val screenAnswer = "第二句「应该去吧,怎么了」是对方说的:他在回答你问的周六聚会还去不去,意思是大概会去,又问你有什么事。"

    private val source = object : UserScreenSource {
        override suspend fun screen(id: String) = listOf(chat, bank).firstOrNull { it.id == id }
        override suspend fun image(id: String) = png.takeIf { id == "s1" }
    }

    private fun loop(model: FakeModel, goal: String, vision: Boolean = false, prior: List<Turn> = emptyList(), judge: FakeModel? = null,
        events: MutableList<RunEvent> = mutableListOf()): AgentLoop {
        val spec = RunSpec(RunId("r"), goal, "c", prior)
        val tools = toolDispatcher(FakeScreen(), visionEnabled = vision, runSpec = spec, userScreens = source)
        return AgentLoop(model, tools, "B", zone = ZoneOffset.UTC, userScreens = source, intentJudge = judge,
            sink = object : RunSink { override fun event(e: RunEvent) { events += e } })
    }

    @Test fun screenWithoutWordsBecomesSentenceImageAndDetail() = runBlocking {
        val model = FakeModel(answerReply("好"))
        val goal = UserScreenMessage.compose("s1", "")
        loop(model, goal, vision = true).run(goal)
        val msgs = model.requests.first().withoutRuntime()
        val said = msgs.first { it.author == com.meenai.mina.core.model.Author.User }
        assertEquals("The user just captured the screen they are looking at (聊天, Oct 28 04:02), with no words.", (said.parts.first() as Part.Text).text)
        assertEquals(1, said.parts.count { it is Part.Image })
        val detail = msgs[msgs.indexOf(said) + 1]
        assertEquals(ContextKind.UserScreen, ContextBuilder.kindOf(detail))
        val body = ContextBuilder.bodyOf(detail)!!
        assertTrue(body.contains("Screen id: s1"))
        assertTrue(body.contains("(300,200) 周六那个聚会你还去吗"))
        assertTrue(body.contains("<untrusted>"))
        assertTrue("没有附言时带上按屏幕猜意图的规矩", body.contains("without words"))
    }

    @Test fun screenWithWordsHasNoExtraRuleAndNoImageWhenMainModelIsBlind() = runBlocking {
        val model = FakeModel(answerReply("好"))
        val goal = UserScreenMessage.compose("s1", "第二句是谁说的")
        loop(model, goal, vision = false).run(goal)
        val msgs = model.requests.first().withoutRuntime()
        val said = msgs.first { it.author == com.meenai.mina.core.model.Author.User }
        assertEquals("The user just captured the screen they are looking at (聊天, Oct 28 04:02), with these words: 第二句是谁说的", (said.parts.single() as Part.Text).text)
        val body = ContextBuilder.bodyOf(msgs[msgs.indexOf(said) + 1])!!
        assertFalse(body.contains("without words"))
        assertTrue(model.offeredTools.first().contains("view_user_screen"))
    }

    // The sentence that the model sees is a fixed English description plus a postscript: the language
    // prompts and the language check of the answer only look at the postscript, and Chinese answers will
    // not be returned.
    @Test fun languageChecksSeeTheCaptionOnly() = runBlocking {
        val goal = UserScreenMessage.compose("s1", "第二句是谁说的")
        val model = FakeModel(answerReply(screenAnswer), repeatLast = true)
        loop(model, goal).run(goal)
        assertEquals(1, model.requests.size)
        val hint = model.requests[0].runtimeHint()
        assertTrue(hint, "latest message: \"第二句是谁说的\"" in hint)
    }

    // Only the screen is sent, no typing: there is no language to take pictures of, no language prompts
    // are given, and no checking is done.
    @Test fun screenWithoutWordsIsNotLanguageChecked() = runBlocking {
        val goal = UserScreenMessage.compose("s1", "")
        val model = FakeModel(answerReply(screenAnswer), repeatLast = true)
        loop(model, goal).run(goal)
        assertEquals(1, model.requests.size)
        val hint = model.requests[0].runtimeHint()
        assertFalse(hint, "latest message" in hint)
    }

    private fun List<com.meenai.mina.core.model.Message>.runtimeHint(): String =
        flatMap { it.parts }.filterIsInstance<Part.Text>().last { ContextBuilder.kindOf(it.text) == ContextKind.RuntimeState }.text

    @Test fun blockedScreenSaysItCouldNotBeCaptured() = runBlocking {
        val model = FakeModel(answerReply("好"))
        val goal = UserScreenMessage.compose("s2", "")
        loop(model, goal, vision = true).run(goal)
        val msgs = model.requests.first().withoutRuntime()
        val said = msgs.first { it.author == com.meenai.mina.core.model.Author.User }
        assertTrue((said.parts.single() as Part.Text).text.contains("does not allow screenshots"))
        assertTrue(ContextBuilder.bodyOf(msgs[msgs.indexOf(said) + 1])!!.contains("did not allow screenshots"))
    }

    @Test fun earlierScreensCollapseToOneLine() = runBlocking {
        val model = FakeModel(answerReply("好"))
        val prior = listOf(Turn(UserScreenMessage.compose("s1", "看看这个"), "她在提醒你", "succeeded"))
        loop(model, "那我怎么回", prior = prior).run("那我怎么回", prior)
        val first = model.requests.first().withoutRuntime().first().plainText()
        assertTrue(first.startsWith(Conversation.HEADER))
        assertTrue(first.contains("(Screen s1 the user sent earlier: 聊天, Oct 28 04:02; the screen begins with 「周六那个聚会你还去吗 应该去吧,怎么了」) 看看这个"))
        assertFalse(first.contains("[[screen:"))
        // There are screens in previous rounds: you can look back by number
        assertTrue(model.offeredTools.first().contains("view_user_screen"))
    }

    @Test fun noScreenNoTool() = runBlocking {
        val model = FakeModel(answerReply("好"))
        loop(model, "看看天气").run("看看天气")
        assertFalse(model.offeredTools.first().contains("view_user_screen"))
    }

    // Only the screen is sent: there is nothing to judge, no intention judgment is asked, the proposed
    // call is done as usual
    @Test fun screenWithoutWordsSkipsIntentGate() = runBlocking {
        val main = FakeModel(ModelReply(listOf(Part.ToolCall("c1", "phone_list_apps", "{}")), StopReason.ToolUse, null), answerReply("好"))
        val judge = FakeModel(answerReply("DISCUSS"))
        val events = mutableListOf<RunEvent>()
        val goal = UserScreenMessage.compose("s1", "")
        loop(main, goal, judge = judge, events = events).run(goal)
        assertTrue(judge.requests.isEmpty())
        assertTrue(events.any { it is RunEvent.ToolResult && it.tool == "phone_list_apps" })
    }

    @Test fun viewUserScreenReturnsTextAndImage() = runBlocking {
        val main = FakeModel(ModelReply(listOf(Part.ToolCall("c1", "view_user_screen", """{"id":"s1"}""")), StopReason.ToolUse, null), answerReply("好"))
        val goal = UserScreenMessage.compose("s1", "")
        loop(main, goal, vision = true).run(goal)
        val result = main.requests[1].last { it.parts.any { p -> p is Part.ToolResult } }.parts.filterIsInstance<Part.ToolResult>().single()
        assertTrue(result.content.contains("周六那个聚会你还去吗"))
        assertTrue(result.content.contains("\"imageAttached\":true"))
    }

    @Test fun circleToAskWithEmptyOcrSwitchesNonVisionRouterToVisionAndAvoidsNoTextFraming() = runBlocking {
        val noOcrScreen = UserScreen("s_circle", at, "com.example.gallery", "Gallery", lines = emptyList())
        val circleSource = object : UserScreenSource {
            override suspend fun screen(id: String) = noOcrScreen.takeIf { id == "s_circle" }
            override suspend fun image(id: String) = png.takeIf { id == "s_circle" }
        }
        val blindCalls = mutableListOf<List<com.meenai.mina.core.model.Message>>()
        val visionCalls = mutableListOf<List<com.meenai.mina.core.model.Message>>()
        val blindModel = object : com.meenai.mina.core.model.ChatModel {
            override val name = "blind-light"
            override val capabilities = com.meenai.mina.core.model.Capabilities(
                toolCalls = true, vision = false, contextTokens = 32_000, reasoning = false,
            )
            override suspend fun complete(
                system: String,
                messages: List<com.meenai.mina.core.model.Message>,
                tools: List<com.meenai.mina.core.model.ToolSpec>,
            ): ModelReply {
                blindCalls += messages
                return answerReply("hi")
            }
        }
        val visionModel = object : com.meenai.mina.core.model.ChatModel {
            override val name = "vision-capable"
            override val capabilities = com.meenai.mina.core.model.Capabilities(
                toolCalls = true, vision = true, contextTokens = 64_000, reasoning = false,
            )
            override suspend fun complete(
                system: String,
                messages: List<com.meenai.mina.core.model.Message>,
                tools: List<com.meenai.mina.core.model.ToolSpec>,
            ): ModelReply {
                visionCalls += messages
                return answerReply("It is a mountain landscape.")
            }
        }
        val router = com.meenai.mina.core.router.FreeKeyRouter(
            listOf(
                com.meenai.mina.core.router.RouterCandidate(
                    "blind-light", "p1", blindModel, curatedRank = 10,
                    roles = setOf(com.meenai.mina.core.router.picker.ModelRole.Main, com.meenai.mina.core.router.picker.ModelRole.Light),
                ),
                com.meenai.mina.core.router.RouterCandidate(
                    "vision-capable", "p2", visionModel, curatedRank = 5,
                    roles = setOf(com.meenai.mina.core.router.picker.ModelRole.Main, com.meenai.mina.core.router.picker.ModelRole.Vision),
                ),
            ),
            delayMs = {},
        )
        // First turn: plain text "hi" leaves router on blind-light
        router.startTask("hi", hasImage = false)
        assertEquals("blind-light", router.activeCandidate?.id)

        // Second turn: Circle-to-Ask with empty OCR lines
        val goal = UserScreenMessage.compose("s_circle", "What is in this circled area?")
        val spec = RunSpec(RunId("r_circle"), goal, "c_circle", emptyList())
        val tools = toolDispatcher(
            FakeScreen(),
            visionEnabled = router.capabilities.vision,
            runSpec = spec,
            userScreens = circleSource,
            visionCheck = { router.capabilities.vision },
        )
        AgentLoop(
            router,
            tools,
            "B",
            zone = ZoneOffset.UTC,
            userScreens = circleSource,
            freeKeyRouter = router,
        ).run(goal)

        assertEquals("vision-capable", router.activeCandidate?.id)
        assertEquals(0, blindCalls.size)
        assertEquals(1, visionCalls.size)
        val req = visionCalls.single().withoutRuntime()
        assertTrue("Part.Image must be attached to user message", req.any { m -> m.parts.any { it is Part.Image } })
        val screenDetail = req.firstOrNull { ContextBuilder.kindOf(it) == ContextKind.UserScreen }?.let { ContextBuilder.bodyOf(it) }.orEmpty()
        assertFalse("Must not inject 'No text was read on the screen' when image is attached", screenDetail.contains("No text was read on the screen"))
        assertFalse("Must not tell vision model it cannot see the image", screenDetail.contains("You cannot see this image"))
    }
}
