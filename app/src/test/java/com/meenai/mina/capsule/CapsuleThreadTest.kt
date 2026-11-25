package com.meenai.mina.capsule

import org.junit.Rule
import com.meenai.mina.i18n.EnglishTexts
import com.meenai.mina.core.agent.FailKind
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.agent.Turn
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.task.SteerNote
import com.meenai.mina.task.SteerState
import com.meenai.mina.ui.Exchange
import com.meenai.mina.ui.ExchangeStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CapsuleThreadTest {
    @get:Rule val texts = EnglishTexts()

    private fun turn(user: String, outcome: String, status: String = "succeeded", runId: String? = null, steers: List<String> = emptyList()) =
        Turn(user, outcome, status, runId = runId, steers = steers)

    private fun live(key: String, state: TaskState, kind: CapsuleKind, runId: String? = null, draft: String? = null, line: String = "") = CardRun(
        CapsuleRun(key, "c1", state, goal = "问题$key", runId = runId,
            exchange = Exchange("问题$key", emptyList(), ExchangeStatus.Working, live = true, draft = draft)),
        kind, line,
    )

    private fun texts(t: ChatThread) = t.items.map {
        when (it) {
            is ThreadItem.User -> "你:${it.text}"
            is ThreadItem.Note -> "注:${it.text}"
            is ThreadItem.Answer -> if (it.cut) "断:${it.text}" else "答:${it.text}"
        }
    }

    // ---------- How to arrange a round ----------

    @Test fun chatTurnIsBubbleAndFullAnswer() {
        // Leave the answer as it is in Markdown and format it in the card.
        val t = CapsuleThreads.build(listOf(PastTurn(turn("在吗", "# 在\n\n**有事**请说\n\n| 项 | 值 |\n|---|---|\n| a | 1 |\n"))), 10, emptyList())
        assertEquals(listOf("你:在吗", "答:# 在\n\n**有事**请说\n\n| 项 | 值 |\n|---|---|\n| a | 1 |"), texts(t))
        assertEquals("在吗", t.title)
    }

    @Test fun appTurnCollapsesToOneLineThenItsAnswer() {
        val past = listOf(
            PastTurn(turn("买牙膏", "下单了，共 ¥19.9", runId = "r1"), places = listOf("购物")),
            PastTurn(turn("查车票", "没有余票", status = "failed", runId = "r2"), places = listOf("12306", "浏览器", "地图")),
            PastTurn(turn("发消息", "已取消", status = "cancelled"), places = listOf("聊天")),
        )
        assertEquals(listOf(
            "你:买牙膏", "注:✓ 购物 · Done", "答:下单了，共 ¥19.9",
            "你:查车票", "注:12306, 浏览器 and more · Didn’t work out", "答:没有余票",
            "你:发消息", "注:聊天 · Stopped",
        ), texts(CapsuleThreads.build(past, 10, emptyList())))
    }

    @Test fun plainChatFailuresSayItAndSteersAreBubbles() {
        val past = listOf(
            PastTurn(turn("算一下", "模型没回", status = "failed")),
            PastTurn(turn("写首诗", "已取消", status = "cancelled", steers = listOf("短一点"))),
        )
        assertEquals(listOf("你:算一下", "注:Didn’t work out", "答:模型没回", "你:写首诗", "你:短一点", "注:Stopped"), texts(CapsuleThreads.build(past, 10, emptyList())))
    }

    // The stopped round: below the line "stopped" is the explanation; while the explanation is still
    // being written, it is "organizing what has been done..."
    @Test fun stoppedTurnShowsItsSummaryUnderTheNote() {
        val past = listOf(
            PastTurn(turn("看设置", "停止前看完了网络、蓝牙;显示、声音还没看。", status = "cancelled", runId = "r1"), places = listOf("设置")),
            PastTurn(turn("写首诗", "已取消", status = "cancelled", runId = "r2")),
        )
        assertEquals(listOf(
            "你:看设置", "注:设置 · Stopped", "答:停止前看完了网络、蓝牙;显示、声音还没看。",
            "你:写首诗", "注:Stopped", "注:Wrapping up what was done…",
        ), texts(CapsuleThreads.build(past, 10, emptyList(), summarizing = setOf("r2"))))
        // Unable to write: just leave that line
        assertEquals(listOf("你:写首诗", "注:Stopped"), texts(CapsuleThreads.build(past.drop(1), 10, emptyList())))
        // The same goes for the one that hasn't been recorded in the dialogue yet.
        val r = live("a", TaskState.Cancelled, CapsuleKind.Cancelled, runId = "r3")
        assertEquals(listOf("你:问题a", "注:Stopped", "注:Wrapping up what was done…"),
            texts(CapsuleThreads.build(emptyList(), 10, listOf(r), summarizing = setOf("r3"))))
    }

    @Test fun oldEvidenceSectionIsDropped() {
        val t = CapsuleThreads.build(listOf(PastTurn(turn("几点开门", "九点开门\n依据：官网"))), 10, emptyList())
        assertEquals(listOf("你:几点开门", "答:九点开门"), texts(t))
    }

    @Test fun placesAreAppsAndSitesInOrder() {
        fun act(target: String?) = RunEvent.Activity(0, 1, "act", "tap", target)
        val acts = listOf(act("com.shop.app"), act("Site example.com"), act("com.shop.app"), act("ls -l"), act(null), act("/workspace/a.txt"))
        assertEquals(listOf("购物", "example.com"), CapsuleThreads.places(acts) { if (it == "com.shop.app") "购物" else it })
    }

    // ---------- The one in progress ----------

    @Test fun streamingAnswerGrowsAfterTheQuestion() {
        val r = live("a", TaskState.Running(1, "n"), CapsuleKind.Running, runId = "ra", draft = "**今天**晴")
        val t = CapsuleThreads.build(listOf(PastTurn(turn("早", "早上好", runId = "r0"))), 10, listOf(r))
        assertEquals(listOf("你:早", "答:早上好", "你:问题a", "答:**今天**晴"), texts(t))
        assertTrue((t.items.last() as ThreadItem.Answer).streaming)
        // I haven’t written the words yet: tell me what I’m doing at the moment
        val thinking = live("a", TaskState.Running(1, "n"), CapsuleKind.Running, line = "在想下一步")
        assertEquals(listOf("你:问题a", "注:在想下一步"), texts(CapsuleThreads.build(emptyList(), 10, listOf(thinking))))
    }

    @Test fun liveWaitsShowTheQuestionAndAppLine() {
        val ask = live("a", TaskState.WaitingInput("要大的还是小的？"), CapsuleKind.NeedAnswer)
        assertEquals(listOf("你:问题a", "答:要大的还是小的？"), texts(CapsuleThreads.build(emptyList(), 10, listOf(ask))))
        val inApp = live("b", TaskState.Running(2, "n"), CapsuleKind.Running, draft = "在看")
        assertEquals(listOf("你:问题b", "注:购物 · Working", "答:在看"),
            texts(CapsuleThreads.build(emptyList(), 10, listOf(inApp)) { listOf("购物") }))
        val done = live("c", TaskState.Succeeded("好了"), CapsuleKind.Done)
        assertEquals(listOf("你:问题c", "注:✓ 购物 · Done", "答:好了"),
            texts(CapsuleThreads.build(emptyList(), 10, listOf(done)) { listOf("购物") }))
    }

    @Test fun liveTailSkipsWhatIsAlreadyRecorded() {
        val recorded = live("a", TaskState.Succeeded("好了"), CapsuleKind.Done, runId = "r1")
        val notYet = live("b", TaskState.Failed("x", FailKind.Model), CapsuleKind.Failed, runId = "r2")
        val queuedCancel = live("c", TaskState.Cancelled, CapsuleKind.Cancelled)
        val going = live("d", TaskState.Running(1, "n"), CapsuleKind.Running)
        val other = live("e", TaskState.Running(1, "n"), CapsuleKind.Running).let { it.copy(run = it.run.copy(conversationId = "c2")) }
        // The card that ended first is in the front row; the card that ended first is at the top, and
        // the card that is in progress is last.
        val tail = CapsuleThreads.liveTail(listOf(going, other, notYet, recorded, queuedCancel), "c1", setOf("r1"))
        assertEquals(listOf("b", "d"), tail.map { it.run.key })
    }

    @Test fun keysStayWhenTheLiveTurnGetsRecorded() {
        val r = live("a", TaskState.Succeeded("好了"), CapsuleKind.Done, runId = "r9")
        val before = CapsuleThreads.build(emptyList(), 10, listOf(r)).items.map { it.key }
        val after = CapsuleThreads.build(listOf(PastTurn(turn("问题a", "好了", runId = "r9"))), 10, emptyList()).items.map { it.key }
        assertEquals(before, after)
    }

    // ---------- Where to stop when paging and opening ----------

    @Test fun latestTenTurnsFirstThenEarlier() {
        val past = (1..25).map { PastTurn(turn("问$it", "答$it", runId = "r$it")) }
        val first = CapsuleThreads.build(past, CapsuleThreads.PAGE, emptyList())
        assertTrue(first.hasEarlier)
        assertEquals("你:问16", texts(first).first())
        assertEquals("问1", first.title)
        val more = CapsuleThreads.build(past, CapsuleThreads.PAGE * 2, emptyList())
        assertEquals("你:问6", texts(more).first())
        // The rounds added to the front are all at the front: the keys of the original items remain
        // unchanged, and the list stops at the same place.
        assertEquals(first.items.map { it.key }, more.items.takeLast(first.items.size).map { it.key })
        val all = CapsuleThreads.build(past, CapsuleThreads.PAGE * 3, emptyList())
        assertTrue(!all.hasEarlier)
        assertEquals(50, all.items.size)
    }

    @Test fun anchorIsTheLatestQuestion() {
        val past = listOf(PastTurn(turn("一", "甲")), PastTurn(turn("二", "乙\n很长的回答")))
        val t = CapsuleThreads.build(past, 10, emptyList())
        assertEquals(2, t.anchor)
        assertEquals(ThreadItem.User("p1-u0", "二"), t.items[t.anchor])
        // The ongoing question is at the end: read from it
        val going = CapsuleThreads.build(past, 10, listOf(live("a", TaskState.Running(1, "n"), CapsuleKind.Running, draft = "在写")))
        assertEquals("你:问题a", texts(going)[going.anchor])
        assertEquals(0, ChatThread(emptyList()).anchor)
    }

    // The interjection in progress is followed by the original sentence, and the destination is written
    // below (the same statement as the dialogue page); the latest sentence is the landing point when
    // opening.
    @Test fun liveSteersFollowTheGoalWithWhereTheyWent() {
        val base = live("a", TaskState.Running(1, "n"), CapsuleKind.Running, draft = "在写")
        val run = base.copy(run = base.run.copy(steers = listOf(
            SteerNote("s1", "改成后天", SteerState.Joined),
            SteerNote("s2", "顺便订午饭", SteerState.Received),
        )))
        val t = CapsuleThreads.build(emptyList(), 10, listOf(run))
        val users = t.items.filterIsInstance<ThreadItem.User>()
        assertEquals(listOf("问题a", "改成后天", "顺便订午饭"), users.map { it.text })
        assertEquals(listOf(null, "Added to the current task", "Got it — next step"), users.map { it.note })
        assertEquals("你:顺便订午饭", texts(t)[t.anchor])
        assertEquals(t.items.size, t.items.map { it.key }.toSet().size)
    }

    @Test fun withdrawnSteersAreNotShown() {
        val base = live("a", TaskState.Running(1, "n"), CapsuleKind.Running)
        val run = base.copy(run = base.run.copy(steers = listOf(
            SteerNote("s1", "已撤回的话", SteerState.Withdrawn),
            SteerNote("s2", "留下的话", SteerState.Received),
        )))
        val t = CapsuleThreads.build(emptyList(), 10, listOf(run))
        assertEquals(listOf("问题a", "留下的话"), t.items.filterIsInstance<ThreadItem.User>().map { it.text })
    }

    // The reply interrupted by the interruption is ranked before the interruption (same as the
    // conversation page); the recorded round is ranked in the same order as the ongoing one, and the key
    // is not repeated.
    @Test fun interruptedReplySitsAboveItsSteer() {
        val past = listOf(PastTurn(Turn("查天气", "明天多云", "succeeded", runId = "r1",
            steers = listOf("改成查明天", "顺便看看后天"), interruptedReplies = listOf("今天晴,最高", ""))))
        assertEquals(listOf("你:查天气", "断:今天晴,最高", "你:改成查明天", "你:顺便看看后天", "答:明天多云"),
            texts(CapsuleThreads.build(past, 10, emptyList())))

        val base = live("a", TaskState.Running(2, "n"), CapsuleKind.Running, draft = "明天")
        val run = base.copy(run = base.run.copy(steers = listOf(SteerNote("s1", "改成查明天", SteerState.Joined, interrupted = "今天晴,最高"))))
        val t = CapsuleThreads.build(emptyList(), 10, listOf(run))
        assertEquals(listOf("你:问题a", "断:今天晴,最高", "你:改成查明天", "答:明天"), texts(t))
        assertEquals(t.items.size, t.items.map { it.key }.toSet().size)
        assertEquals("你:改成查明天", texts(t)[t.anchor])
    }

    // Replies that have been finished before the interruption are sorted before the interruption, as
    // answered; interruptions that have not yet been given to the model are sorted after the answer
    // being written.
    @Test fun finishedReplyAndWaitingSteerOrder() {
        val past = listOf(PastTurn(Turn("查天气", "明天多云", "succeeded", runId = "r1",
            steers = listOf("改成查明天"), finishedReplies = listOf("今天晴,最高 25 度。"))))
        assertEquals(listOf("你:查天气", "答:今天晴,最高 25 度。", "你:改成查明天", "答:明天多云"),
            texts(CapsuleThreads.build(past, 10, emptyList())))

        val base = live("a", TaskState.Running(2, "n"), CapsuleKind.Running, draft = "明天多")
        val run = base.copy(run = base.run.copy(steers = listOf(
            SteerNote("s1", "改成查明天", SteerState.Joined, finished = "今天晴"),
            SteerNote("s2", "简短点", SteerState.Received),
        )))
        val t = CapsuleThreads.build(emptyList(), 10, listOf(run))
        assertEquals(listOf("你:问题a", "答:今天晴", "你:改成查明天", "答:明天多", "你:简短点"), texts(t))
        assertEquals(t.items.size, t.items.map { it.key }.toSet().size)
        assertEquals("你:简短点", texts(t)[t.anchor])
    }
}
