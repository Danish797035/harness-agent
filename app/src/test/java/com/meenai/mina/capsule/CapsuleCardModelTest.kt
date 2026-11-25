package com.meenai.mina.capsule

import org.junit.Rule
import com.meenai.mina.i18n.EnglishTexts
import com.meenai.mina.cap.web.KeptPage
import com.meenai.mina.confirm.ConfirmationNotifier
import com.meenai.mina.core.agent.FailKind
import com.meenai.mina.core.agent.GateReason
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.gate.ConfirmationRequest
import com.meenai.mina.core.risk.IrrevAction
import com.meenai.mina.ui.Exchange
import com.meenai.mina.ui.ExchangeStatus
import com.meenai.mina.ui.StepRow
import com.meenai.mina.ui.StepState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleCardModelTest {
    @get:Rule val texts = EnglishTexts()

    private fun card(
        key: String, state: TaskState, kind: CapsuleKind = CapsuleKind.Running, line: String = "", ahead: Int = 0, behind: Int = 0,
        exchange: Exchange? = null, onScreen: Boolean = true,
    ) = CardRun(CapsuleRun(key, "c-$key", state, goal = "目标$key", exchange = exchange, onScreen = onScreen), kind, line, ahead = ahead, behind = behind)

    /** Which thing the card is about (task card or conversation card). */
    private fun keyOf(v: CardView?): String? = when (v) {
        is CardView.Detail -> v.run.run.key
        is CardView.Chat -> v.run?.run?.key
        else -> null
    }

    private val running = TaskState.Running(2, "note", "tap")

    // ---------- Just say one thing ----------

    @Test fun keptPageCardWhenNothingElse() {
        val kept = KeptPage(3, "c-k", "r-k", "播放课程", "course.example.com", null, null, 0, 7_200_000)
        val v = CapsuleCards.of(emptyList(), null, idle = CardView.Chat("c-x"), kept = kept)
        assertEquals(CardView.Kept(kept), v)
        // There are no input boxes and no stops on the card; "Open Conversation" goes to the
        // conversation it came from
        assertNull(CapsuleCards.input(v!!))
        assertNull(CapsuleCards.stopKey(v))
        assertEquals("c-k", CapsuleCards.conversationOf(v))
        // When there is a task (in progress or just finished), it is still said to be a task
        assertEquals("a", keyOf(CapsuleCards.of(listOf(card("a", running)), null, kept = kept)))
    }

    @Test fun oneRunOpensItsDetailDirectly() {
        val v = CapsuleCards.of(listOf(card("a", running)), null) as CardView.Detail
        assertEquals("a", v.run.run.key)
    }

    @Test fun severalRunsShowOnlyTheFirstInImportanceOrder() {
        // According to the order of cardRuns (you need to process, in progress, just finished), only the
        // first item is taken, there is no list
        val need = card("n", TaskState.WaitingInput("几点？"), CapsuleKind.NeedAnswer)
        val done = card("d", TaskState.Succeeded("好了"), CapsuleKind.Done)
        assertEquals("n", keyOf(CapsuleCards.of(listOf(need, card("a", running), done))))
        assertEquals("a", keyOf(CapsuleCards.of(listOf(card("a", running), done))))
        // Only the ones that just ended are left: also one piece, not a list
        assertEquals("d", keyOf(CapsuleCards.of(listOf(done, card("e", TaskState.Cancelled, CapsuleKind.Cancelled)))))
        assertNull(CapsuleCards.of(emptyList(), "c-a"))
    }

    // ---------- Task cards or dialogue cards ----------

    @Test fun taskCardOnlyWhileOperatingAScreenNow() {
        val waits = listOf(
            running to CapsuleKind.Running,
            TaskState.Held(GateReason("q")) to CapsuleKind.Held,
            TaskState.WaitingTakeover("login", "登录") to CapsuleKind.NeedTakeover,
            TaskState.WaitingInput("几点？") to CapsuleKind.NeedAnswer,
            TaskState.WaitingConfirmation(ConfirmationRequest(IrrevAction.send, "张三", "明天见", null, "com.chat")) to CapsuleKind.NeedConfirm,
        )
        for ((s, k) in waits) {
            assertTrue("$s on screen", CapsuleCards.of(listOf(card("a", s, k)), null) is CardView.Detail)
            // Pure chat (no virtual screen, no webpage open), the same status is a conversation card
            val chat = CapsuleCards.of(listOf(card("a", s, k, onScreen = false)), null) as CardView.Chat
            assertEquals("c-a", chat.target)
            assertEquals(CapsuleCards.content(card("a", s, k)), chat.content)
        }
    }

    @Test fun finishedAppTaskIsAChatCard() {
        // After operating the App and replying after completing the task: According to the current
        // status, it is a conversation card (the same applies even if the screen is not released)
        for ((s, k) in listOf(
            TaskState.Succeeded("好了") to CapsuleKind.Done,
            TaskState.Failed("没找到", FailKind.GaveUp) to CapsuleKind.Failed,
            TaskState.Cancelled to CapsuleKind.Cancelled,
        )) {
            assertTrue("$s", CapsuleCards.of(listOf(card("a", s, k, onScreen = true)), null) is CardView.Chat)
            assertTrue(!CapsuleCards.taskCard(card("a", s, k, onScreen = true)))
        }
        // The thing you are operating becomes a pure chat (the screen is released): it will be changed
        // to a conversation card immediately
        assertTrue(CapsuleCards.taskCard(card("a", running)))
        assertTrue(!CapsuleCards.taskCard(card("a", running, onScreen = false)))
    }

    @Test fun stopKeyWhileStillGoing() {
        assertEquals("a", CapsuleCards.stopKey(CapsuleCards.of(listOf(card("a", running)), null)!!))
        assertEquals("a", CapsuleCards.stopKey(CapsuleCards.of(listOf(card("a", running, onScreen = false)), null)!!))
        assertEquals("a", CapsuleCards.stopKey(CapsuleCards.of(listOf(card("a", TaskState.WaitingInput("几点？"), CapsuleKind.NeedAnswer, onScreen = false)), null)!!))
        assertNull(CapsuleCards.stopKey(CapsuleCards.of(listOf(card("a", TaskState.Succeeded("好了"), CapsuleKind.Done)), null)!!))
        assertNull(CapsuleCards.stopKey(CapsuleCards.of(listOf(card("a", TaskState.WaitingTakeover("login", "登录"), CapsuleKind.NeedTakeover)), null)!!))
        assertNull(CapsuleCards.stopKey(CardView.Chat("c1")))
    }

    // ---------- What to put in each state ----------

    @Test fun workingListsAllStepsEndingWithTheCurrentLine() {
        val ex = Exchange("g", listOf("打开购物", "点搜索框", "输入牙膏", "看结果").map { StepRow(1, it, "", StepState.Ok) }, ExchangeStatus.Working, live = true)
        assertEquals(listOf("打开购物", "点搜索框", "输入牙膏", "看结果", "挑销量高的"), CapsuleCards.steps(ex, "挑销量高的"))
        // The sentence at this moment is the last step: don’t repeat it
        assertEquals(listOf("打开购物", "点搜索框", "输入牙膏", "看结果"), CapsuleCards.steps(ex, "看结果"))
        assertEquals(listOf("在想下一步"), CapsuleCards.steps(null, "在想下一步"))
        assertEquals(CardContent.Working(listOf("在想")), CapsuleCards.content(card("a", running, line = "在想")))
    }

    @Test fun takeoverSaysWhyWithPaymentAndQueue() {
        val s = TaskState.WaitingTakeover("payment", "请输入支付密码", item = "牙膏", amount = "¥19.9", address = "家")
        val first = CapsuleCards.content(card("a", s, CapsuleKind.NeedTakeover, "需要你：付款", behind = 2)) as CardContent.Takeover
        assertEquals("需要你：付款", first.need)
        assertEquals("请输入支付密码\nItem: 牙膏\nAmount: ¥19.9\nAddress: 家", first.body)
        assertEquals("After this, 2 more are waiting for you", first.queueLine)
        assertTrue(first.canTakeOver)
        val queued = CapsuleCards.content(card("b", s, CapsuleKind.NeedTakeover, ahead = 1)) as CardContent.Takeover
        assertEquals(CapsuleCards.QUEUED_AHEAD, queued.queueLine)
        assertTrue(!queued.canTakeOver)
        assertNull((CapsuleCards.content(card("c", s, CapsuleKind.NeedTakeover)) as CardContent.Takeover).queueLine)
    }

    @Test fun confirmUsesTheChatCardCopy() {
        val s = TaskState.WaitingConfirmation(ConfirmationRequest(IrrevAction.order, "店", "两支牙膏", "¥19.9", "com.shop"))
        val ex = Exchange("g", emptyList(), ExchangeStatus.NeedsConfirmation("要你同意:下单", "在 购物\n金额:¥19.9", 42), live = true)
        assertEquals(CardContent.Confirm("要你同意:下单", "在 购物\n金额:¥19.9", 42), CapsuleCards.content(card("a", s, CapsuleKind.NeedConfirm, exchange = ex)))
        // Even if you haven’t answered this question yet, I can agree: the number is the same as the one
        // used for conversation pages and notifications.
        val raw = CapsuleCards.content(card("a", s, CapsuleKind.NeedConfirm)) as CardContent.Confirm
        assertEquals(ConfirmationNotifier.requestKey(s), raw.key)
        assertTrue(raw.body.contains("Amount: ¥19.9"))
    }

    @Test fun questionHeldAndResults() {
        assertEquals(CardContent.Question("要哪个？", listOf("大", "小")), CapsuleCards.content(card("a", TaskState.WaitingInput(" 要哪个？ ", listOf("大", "", "小")))))
        assertEquals(CardContent.Question("几点？", emptyList()), CapsuleCards.content(card("a", TaskState.WaitingInput("几点？"))))
        assertEquals(CardContent.Held("排队中", true), CapsuleCards.content(card("a", TaskState.Held(GateReason("q", "排队中", resumable = true)))))
        assertEquals(CardContent.Held("On hold", false), CapsuleCards.content(card("a", TaskState.Held(GateReason("q")))))
        assertEquals(CardContent.Result(CapsuleKind.Done), CapsuleCards.content(card("a", TaskState.Succeeded("下单了"))))
        assertEquals(CardContent.Result(CapsuleKind.Failed), CapsuleCards.content(card("a", TaskState.Failed("没找到", FailKind.GaveUp))))
        assertEquals(CardContent.Result(CapsuleKind.Cancelled), CapsuleCards.content(card("a", TaskState.Cancelled)))
    }

    @Test fun workingStepsAreCappedKeepingTheNewest() {
        val ex = Exchange("g", (1..80).map { StepRow(1, "第 $it 步", "", StepState.Ok) }, ExchangeStatus.Working, live = true)
        val steps = CapsuleCards.steps(ex, "在想下一步")
        assertEquals(CapsuleCards.STEPS, steps.size)
        assertEquals("第 32 步", steps.first())
        assertEquals("在想下一步", steps.last())
    }

    @Test fun screenOnlyBesideWorkTakeoverAndHeld() {
        assertTrue(CapsuleCards.showsScreen(CardContent.Working(emptyList())))
        assertTrue(CapsuleCards.showsScreen(CardContent.Takeover("", "", null, true)))
        assertTrue(CapsuleCards.showsScreen(CardContent.Held("", false)))
        assertTrue(!CapsuleCards.showsScreen(CardContent.Question("", emptyList())))
        assertTrue(!CapsuleCards.showsScreen(CardContent.Result(CapsuleKind.Done)))
    }

    // ---------- Input box ----------

    @Test fun inputGoesToTheRightPlace() {
        fun inputOf(state: TaskState, kind: CapsuleKind) = CapsuleCards.input(CapsuleCards.of(listOf(card("a", state, kind)), null)!!)?.kind
        assertEquals(CardInputKind.Steer, inputOf(running, CapsuleKind.Running))
        assertEquals(CardInputKind.Steer, inputOf(TaskState.Held(GateReason("q")), CapsuleKind.Held))
        assertEquals(CardInputKind.Answer, inputOf(TaskState.WaitingInput("几点？", listOf("8 点")), CapsuleKind.NeedAnswer))
        assertEquals(CardInputKind.Continue, inputOf(TaskState.Succeeded("好了"), CapsuleKind.Done))
        assertEquals(CardInputKind.Continue, inputOf(TaskState.Failed("x", FailKind.Model), CapsuleKind.Failed))
        // When I want you to take over or agree to it, handle that matter first without giving an input
        // box.
        assertNull(inputOf(TaskState.WaitingTakeover("login", "登录"), CapsuleKind.NeedTakeover))
        val confirm = TaskState.WaitingConfirmation(ConfirmationRequest(IrrevAction.send, "张三", "明天见", null, "com.chat"))
        assertNull(inputOf(confirm, CapsuleKind.NeedConfirm))
        assertEquals(CardInput(CapsuleCards.HINT_START, CardInputKind.Start), CapsuleCards.input(CardView.Chat(null)))
        assertEquals(CardInput(CapsuleCards.HINT_CONTINUE, CardInputKind.Continue), CapsuleCards.input(CapsuleCards.of(listOf(card("a", TaskState.Succeeded("好了"), CapsuleKind.Done)), null)!!))
        // In pure chat, you can also interrupt when answering; if you want to agree, deal with that
        // matter first
        assertEquals(CardInputKind.Steer, CapsuleCards.input(CapsuleCards.of(listOf(card("a", running, onScreen = false)), null)!!)?.kind)
        assertNull(CapsuleCards.input(CapsuleCards.of(listOf(card("a", confirm, CapsuleKind.NeedConfirm, onScreen = false)), null)!!))
    }

    @Test fun afterSendingTheCardFollowsThatConversation() {
        val done = card("a", TaskState.Succeeded("好了"), CapsuleKind.Done)
        val other = card("b", running)
        // After sending the message, the new task has not started yet: the following conversation only
        // contains the one that ended
        assertEquals("a", keyOf(CapsuleCards.of(listOf(other, done), follow = "c-a")))
        // New tasks are up: ongoing priority
        val again = card("a2", running).let { it.copy(run = it.run.copy(conversationId = "c-a")) }
        assertEquals("a2", keyOf(CapsuleCards.of(listOf(other, done, again), follow = "c-a")))
        // There are no tasks in the following dialogue: take the first one in order
        assertEquals("b", keyOf(CapsuleCards.of(listOf(other, done), follow = "c-gone")))
    }

    @Test fun idleCardWhenNoRuns() {
        val idle = CardView.Chat("c1", title = "买牙膏")
        assertEquals(idle, CapsuleCards.of(emptyList(), null, idle = idle))
        assertEquals("c1", idle.target)
        assertNull(idle.copy(fresh = true).target)
        // Do not display idle cards when there are tasks
        assertTrue(CapsuleCards.of(listOf(card("a", running)), null, idle = idle) is CardView.Detail)
    }

    /**
     * The conversation that the user is currently in takes priority over other conversations that have
     * ended and are still pending: otherwise, after opening a new conversation on the conversation page,
     * clicking on the capsule will still be the result card of the old conversation. The tasks that have
     * just ended in the same conversation still count, and the tasks in progress still take priority.
     */
    @Test fun idleCardOfTheConversationOnScreenBeatsAnEndedRunFromAnother() {
        val ended = card("a", TaskState.Succeeded("好了"), CapsuleKind.Done)
        val fresh = CardView.Chat(null, fresh = true, current = true)
        assertEquals(fresh, CapsuleCards.of(listOf(ended), null, idle = fresh))
        // The new dialogue has been sent and is running: the task card is still the same
        assertTrue(CapsuleCards.of(listOf(card("r", running)), null, idle = fresh) is CardView.Detail)
        // The item that just ended in the same conversation: display it (with results), do not replace
        // it with an empty card
        assertEquals("a", keyOf(CapsuleCards.of(listOf(ended), null, idle = CardView.Chat("c-a", current = true))))
        // Don’t grab the “Recent Conversations” at the bottom of the pocket: it’s not what the user is
        // currently in
        assertEquals("a", keyOf(CapsuleCards.of(listOf(ended), null, idle = CardView.Chat("c-z", title = "别的"))))
        // There are no tasks and there is still one page in the background: the cards on that page are
        // still there and will not be pushed out by idle cards.
        val kept = KeptPage(3, "c-k", "r-k", "播放课程", "course.example.com", null, null, 0, 7_200_000)
        assertEquals(CardView.Kept(kept), CapsuleCards.of(emptyList(), null, idle = fresh, kept = kept))
    }
}
