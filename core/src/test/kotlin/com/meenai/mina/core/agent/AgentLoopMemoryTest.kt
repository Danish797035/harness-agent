package com.meenai.mina.core.agent

import com.meenai.mina.core.model.Author
import com.meenai.mina.core.memory.MemoryKind
import com.meenai.mina.core.memory.MemorySource
import com.meenai.mina.core.memory.UserUtterances
import com.meenai.mina.core.memory.finishReply
import com.meenai.mina.core.memory.memStore
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import com.meenai.mina.core.model.StopReason
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.record.RunSink
import com.meenai.mina.core.run.RunId
import com.meenai.mina.core.run.RunInbox
import com.meenai.mina.core.run.RunInput
import com.meenai.mina.core.run.RunSpec
import com.meenai.mina.core.tool.FakeScreen
import com.meenai.mina.core.cap.web.AttachedPage
import com.meenai.mina.core.cap.web.FakeWebPort
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.meenai.mina.core.testing.TOOLS
import com.meenai.mina.core.assembly.toolDispatcher

class AgentLoopMemoryTest {
    private val manual = MemorySource("manual", atMs = 1)
    private fun Message.text() = plainText()

    private fun store() = memStore().apply {
        add(MemoryKind.preference, "用户点外卖不要香菜", listOf("外卖"), manual).getOrThrow()
        add(MemoryKind.fact, "用户的公司在张江", listOf("公司"), manual).getOrThrow()
    }

    @Test fun blockInsertedBeforeTaskAfterContext() {
        val s = store()
        val model = FakeModel(finishReply)
        runBlocking {
            AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = s)
                .run("帮我点个外卖", listOf(Turn("查一下天气", "晴", "succeeded")))
        }
        val msgs = model.requests.first().withoutRuntime().map { it.text() }
        assertTrue(msgs[0].startsWith(Conversation.HEADER))
        assertTrue(msgs[1].startsWith("Memories about the user"))
        // Inject as much memory as you can fit: "The company is in Zhangjiang" that has nothing to do
        // with the word of this task is also there (it would have been discarded by BM25 retrieval in
        // the past)
        assertTrue(msgs[1].contains("用户点外卖不要香菜"))
        assertTrue(msgs[1].contains("张江"))
        assertEquals("帮我点个外卖", msgs[2])
    }

    @Test fun unrelatedMemoryStillInjectedInFullMode() {
        val s = store()
        val model = FakeModel(finishReply)
        val loop = AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = s)
        runBlocking { loop.run("看看明天天气") }
        val msgs = model.requests.first().withoutRuntime().map { it.text() }
        assertTrue(msgs[0].startsWith("Memories about the user"))
        assertEquals("看看明天天气", msgs[1])
        assertEquals(s.items.value.map { it.id }, loop.usedMemoryIds)
        assertTrue(s.items.value.all { it.useCount == 1 })
    }

    @Test fun usedIdsRecordedAndMarked() {
        val s = store()
        val events = mutableListOf<RunEvent>()
        val loop = AgentLoop(FakeModel(finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", sink = object : RunSink { override fun event(e: RunEvent) { events += e } }, memory = s)
        runBlocking { loop.run("帮我点个外卖") }
        val ids = s.items.value.map { it.id }
        assertEquals(ids, loop.usedMemoryIds)
        assertTrue(s.items.value.all { it.useCount == 1 })
        assertEquals(listOf(ids), events.filterIsInstance<RunEvent.MemoryUsed>().map { it.ids })
    }

    // Question on the browser page: The title, URL, and selected words in the page description are from
    // the web page and are not counted as words spoken by the user; memorized quotes are not included
    // when they come from them.
    @Test fun pageDescriptionIsNotTheUserWords() {
        val s = store()
        val said = UserUtterances()
        val page = AttachedPage.Page("https://shop.example.com/address", "收货地址是幸福路 8 号 - 地址管理", "默认地址 幸福路 8 号")
        val save = ModelReply(listOf(Part.ToolCall("c1", "memory_save",
            """{"kind":"fact","content":"用户的收货地址是幸福路 8 号","quote":"收货地址是幸福路 8 号"}""")), StopReason.ToolUse, null)
        val model = FakeModel(save, finishReply)
        runBlocking {
            AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = said, web = FakeWebPort()), "B", memory = s)
                .run(AttachedPage.compose(page, "这一页的地址对不对"))
        }
        val result = model.requests[1].last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().single().content
        assertTrue(result, "quote_not_from_user" in result)
        assertTrue(s.items.value.none { "幸福路" in it.content })
        assertEquals(listOf("这一页的地址对不对"), said.all)
    }

    // The insert on the browser page contains the newly selected words: only the words typed in the
    // insert will be accepted, not the page description.
    @Test fun pageSteerAddsOnlyTheTypedWords() = runBlocking {
        assertEquals(listOf("这一页的地址对不对", "改成这个"), saidWithPageSteer("改成这个"))
    }

    // I just selected words and didn’t type anything when I interrupted: nothing is included in this
    // sentence.
    @Test fun selectionOnlySteerAddsNothing() = runBlocking {
        assertEquals(listOf("这一页的地址对不对"), saidWithPageSteer(""))
    }

    // The same goes for answering follow-up questions on the browser page: when answering with page
    // description and selected words, only the typed words will be included.
    @Test fun pageAnswerAddsOnlyTheTypedWords() = runBlocking {
        val s = store()
        val said = UserUtterances()
        val page = AttachedPage.Page("https://shop.example.com/address", "收货地址是幸福路 8 号 - 地址管理")
        val ask = ModelReply(listOf(Part.ToolCall("q1", "ask_user", """{"prompt":"用哪个地址?"}""")), StopReason.ToolUse, null)
        val loop = AgentLoop(FakeModel(ask, finishReply, finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = said, web = FakeWebPort()), "B", memory = s)
        val run = async { loop.run(AttachedPage.compose(page, "这一页的地址对不对")) }
        withTimeout(5_000) { loop.state.first { it is TaskState.WaitingInput } }
        assertTrue(loop.answerInput(AttachedPage.compose(page.copy(selection = "默认地址 幸福路 8 号"), "就这个")))
        run.await()
        assertEquals(listOf("这一页的地址对不对", "就这个"), said.all)
    }

    /** Start on the browser page, insert the words with the newly selected word and [words]; return the user's words recorded this time. */
    private suspend fun saidWithPageSteer(words: String): List<String> {
        val s = store()
        val said = UserUtterances()
        val page = AttachedPage.Page("https://shop.example.com/address", "收货地址是幸福路 8 号 - 地址管理")
        val inbox = RunInbox().apply { send(RunInput.Steer("m1", AttachedPage.compose(page.copy(selection = "默认地址 幸福路 8 号"), words))) }
        AgentLoop(FakeModel(finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = said, web = FakeWebPort()), "B", memory = s)
            .run(RunSpec(RunId("r1"), AttachedPage.compose(page, "这一页的地址对不对"), "thread-1"), inbox)
        return said.all
    }

    // Quoted Mina's answer: The quoted paragraph was said by Mina. The memorized quotation will not be
    // recognized. Only the words typed by the user will be accepted.
    @Test fun quotedReplyIsNotTheUserWords() {
        val s = store()
        val said = UserUtterances()
        val save = ModelReply(listOf(Part.ToolCall("c1", "memory_save",
            """{"kind":"fact","content":"用户的收货地址是幸福路 8 号","quote":"收货地址是幸福路 8 号"}""")), StopReason.ToolUse, null)
        val model = FakeModel(save, finishReply)
        runBlocking {
            AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = said), "B", memory = s)
                .run(QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.Mina, MINA_ADDRESS), "这个地址对不对"))
        }
        val result = model.requests[1].last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().single().content
        assertTrue(result, "quote_not_from_user" in result)
        assertTrue(s.items.value.none { "幸福路" in it.content })
        assertEquals(listOf("这个地址对不对"), said.all)
    }

    // Quoting what he said: The dialogue page quotes are what he typed at the time. Even that paragraph
    // is included, and the memorized quotation is still recognized.
    @Test fun ownQuoteIsTheUserWords() {
        val s = store()
        val said = UserUtterances()
        val save = ModelReply(listOf(Part.ToolCall("c1", "memory_save",
            """{"kind":"fact","content":"用户家的猫叫团子","quote":"我家猫叫团子"}""")), StopReason.ToolUse, null)
        runBlocking {
            AgentLoop(FakeModel(save, finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = said), "B", memory = s)
                .run(QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.You, "我家猫叫团子"), "这个记一下"))
        }
        assertTrue(s.items.value.any { it.content == "用户家的猫叫团子" })
        assertEquals(listOf("我家猫叫团子\n这个记一下"), said.all)
    }

    // Attachment is attached: The lines in the attachment are the path and size written by the App and
    // will not be collected.
    @Test fun attachmentLinesAreNotTheUserWords() {
        val s = store()
        val said = UserUtterances()
        runBlocking {
            AgentLoop(FakeModel(finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = said), "B", memory = s)
                .run(AttachmentLines.compose("总结一下", listOf("/workspace/inbox/Quarterly_Financial_Report_Final_v2.pdf" to "2 KB")))
        }
        assertEquals(listOf("总结一下"), said.all)
    }

    // The interjection prompted Mina's answer: Only the words typed in the interjection will be
    // accepted.
    @Test fun quotedSteerAddsOnlyTheTypedWords() = runBlocking {
        val s = store()
        val said = UserUtterances()
        val inbox = RunInbox().apply { send(RunInput.Steer("m1", QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.Mina, MINA_ADDRESS), "换成这个"))) }
        AgentLoop(FakeModel(finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = said), "B", memory = s)
            .run(RunSpec(RunId("r1"), "帮我下单", "thread-1"), inbox)
        assertEquals(listOf("帮我下单", "换成这个"), said.all)
    }

    // When answering a follow-up question, Mina was quoted as saying: Only typed words will be
    // collected.
    @Test fun quotedAnswerAddsOnlyTheTypedWords() = runBlocking {
        val s = store()
        val said = UserUtterances()
        val ask = ModelReply(listOf(Part.ToolCall("q1", "ask_user", """{"prompt":"送到哪个地址?"}""")), StopReason.ToolUse, null)
        val loop = AgentLoop(FakeModel(ask, finishReply, finishReply), toolDispatcher(FakeScreen(), memory = s, utterances = said), "B", memory = s)
        val run = async { loop.run("帮我下单") }
        withTimeout(5_000) { loop.state.first { it is TaskState.WaitingInput } }
        assertTrue(loop.answerInput(QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.Mina, MINA_ADDRESS), "就这个")))
        run.await()
        assertEquals(listOf("帮我下单", "就这个"), said.all)
    }

    @Test fun disabledMeansNoBlockNoTools() {
        val s = store()
        val model = FakeModel(finishReply)
        val loop = AgentLoop(model, toolDispatcher(FakeScreen(), memory = s, utterances = UserUtterances()), "B", memory = null)
        runBlocking { loop.run("帮我点个外卖") }
        assertEquals(listOf("帮我点个外卖"), model.requests.first().withoutRuntime().map { it.text() })
        assertFalse(model.offeredTools.first().any { it.startsWith("memory_") })
        assertTrue(loop.usedMemoryIds.isEmpty())
    }

    private companion object {
        /** The user quoted Mina's previous answer. */
        const val MINA_ADDRESS = "你常用的收货地址是幸福路 8 号"
    }
}

class MemoryBlockCompressionTest {
    @Test fun memoryBlockSurvivesAggressiveCompression() {
        val block = Message(Role.User, listOf(Part.Text("Memories about the user (things the user told you; may be out of date; when they conflict with what the user says now, follow what they say now):\n1. 用户点外卖不要香菜")), Author.Mina)
        val history = mutableListOf(block, Message(Role.User, listOf(Part.Text("帮我点个外卖")), Author.Mina))
        repeat(30) { i ->
            history += Message(Role.Assistant, listOf(Part.ToolCall("c$i", "phone_observe", "{}")), Author.Mina)
            history += Message(Role.Tool, listOf(Part.ToolResult("c$i", "{\"ok\":true,\"x\":\"" + "很".repeat(500) + "\"}", false)), Author.Mina)
        }
        val out = ContextCompressor(TOOLS).compress(history, contextTokens = 2_000, aggressive = true)
        assertTrue(out.foldedSteps > 0)
        assertEquals(block, out.messages.first())
    }
}
