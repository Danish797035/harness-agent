package com.meenai.mina.ui

import com.meenai.mina.i18n.EnglishTexts
import com.meenai.mina.task.TaskGate

import com.meenai.mina.chat.ConversationStore
import com.meenai.mina.chat.CurrentChat
import com.meenai.mina.confirm.ConfirmationNotifier
import com.meenai.mina.core.agent.FailKind
import com.meenai.mina.core.agent.GateReason
import com.meenai.mina.core.agent.QuoteMessage
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.core.gate.ConfirmationRequest
import com.meenai.mina.core.risk.IrrevAction
import com.meenai.mina.core.agent.Turn
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.settings.AccessibilityState
import com.meenai.mina.settings.Prerequisites
import com.meenai.mina.settings.Problem
import com.meenai.mina.core.shell.BuiltinShellStatus
import com.meenai.mina.settings.SystemStatus
import com.meenai.mina.takeover.Takeover
import com.meenai.mina.task.SteerNote
import com.meenai.mina.task.SteerState
import com.meenai.mina.ui.PipPhysics.Corner
import com.meenai.mina.core.cap.web.AttachedPage
import com.meenai.mina.core.gemini.GeminiUsage
import com.meenai.mina.core.gemini.GeminiUsageWindow
import com.meenai.mina.core.router.RouterUsageTracker
import com.meenai.mina.core.userscreen.UserScreenMessage
import com.meenai.mina.ui.router.ActiveModel
import com.meenai.mina.ui.router.ConnectionStatus
import com.meenai.mina.ui.router.ConnectionWarmupResult
import com.meenai.mina.ui.router.FakeRouterStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    @get:Rule val texts = EnglishTexts()

    @get:Rule val tmp = TemporaryFolder()

    private val state = MutableStateFlow<TaskState>(TaskState.Idle)
    private val stage = MutableStateFlow(Takeover.Stage.None)
    private val calls = mutableListOf<String>()
    private val acts = MutableStateFlow<List<RunEvent.Activity>>(emptyList())
    private val steerFlow = MutableStateFlow<List<SteerNote>>(emptyList())
    private val steerIds = mutableListOf<String>()
    private var canWithdraw = true
    private val actions = object : TaskActions {
        override fun start(goal: String, conversationId: String) { calls += "start:$goal" }
        override fun steer(conversationId: String, messageId: String, text: String) { calls += "steer:$text"; steerIds += messageId }
        override fun withdrawSteer(conversationId: String, messageId: String): Boolean {
            calls += "withdraw:$messageId"
            return canWithdraw
        }
        override fun cancel() { calls += "cancel" }
        override fun resume() { calls += "resume" }
        override fun takeOver() { calls += "takeOver" }
        override fun handBack() { calls += "handBack" }
        override fun finishedMyself() { calls += "finishedMyself" }
        override fun skip() { calls += "skip" }
        override fun answer(text: String) { calls += "answer:$text" }
        override fun approve(key: Int) { calls += "approve:$key" }
        override fun decline(key: Int) { calls += "decline:$key" }
        override fun show(conversationId: String) { calls += "show:$conversationId" }
        override fun cancelIn(conversationId: String) { calls += "cancel@$conversationId" }
        override fun resumeIn(conversationId: String) { calls += "resume@$conversationId" }
        override fun answerIn(conversationId: String, text: String) { calls += "answer@$conversationId:$text" }
        override fun approveIn(conversationId: String, key: Int) { calls += "approve@$conversationId:$key" }
    }

    private val ready = SystemStatus(34, BuiltinShellStatus.Running, AccessibilityState.On, notificationsAllowed = true)
    private val ok = Prerequisites.check(ready, hasMainModel = true)

    private fun TestScope.vm(store: ConversationStore = ConversationStore(tmp.newFolder())) =
        ChatViewModel(backgroundScope, state, stage, actions, store, appLabel = { if (it == "com.chat") "聊天" else it },
            activities = acts, steers = steerFlow).also { runCurrent() }

    /** The same as [vm], but you can specify whether the screen is turned on or off. */
    private fun TestScope.vmWithScreen(open: MutableStateFlow<Boolean>) =
        ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            appLabel = { if (it == "com.chat") "聊天" else it }, activities = acts, steers = steerFlow,
            screenOpen = open).also { runCurrent() }

    private fun TestScope.emit(s: TaskState) {
        state.value = s
        runCurrent()
    }

    /** There is one more thing actually done in the running event. */
    private fun TestScope.did(step: Int, kind: String, tool: String, target: String?, effect: String = "completed", title: String? = null, failed: Boolean = false) {
        acts.value = acts.value + RunEvent.Activity(0, step, kind, tool, target, effect, title = title, failed = failed)
        runCurrent()
    }

    @Test fun blocksWhenPrerequisiteMissing() = runTest {
        val vm = vm()
        val check = Prerequisites.check(ready.copy(accessibility = AccessibilityState.Off), hasMainModel = false)
        assertFalse(vm.send("查天气", check))
        assertTrue(calls.isEmpty())
        // Accessibility is only required when operating the mobile app and does not block tasks; only
        // the model is blocked.
        assertEquals(listOf(Problem.NoModel), vm.ui.value.blocked)
        assertTrue(vm.ui.value.exchanges.isEmpty())

        // If the notification is not turned on, it does not count as blocking the road.
        assertTrue(vm.send("查天气", Prerequisites.check(ready.copy(notificationsAllowed = false), hasMainModel = true)))
        assertNull(vm.ui.value.blocked)
        assertEquals(listOf("start:查天气"), calls)
    }

    @Test fun showsStepsFromState() = runTest {
        val vm = vm()
        vm.send("打开设置看电量", ok)
        emit(TaskState.Running(0, "正在准备"))
        assertTrue(vm.ui.value.exchanges.single().steps.isEmpty())

        emit(TaskState.Running(1, ""))
        var steps = vm.ui.value.exchanges.single().steps
        assertTrue(steps.isEmpty())
        assertEquals("Working", liveLine(vm.ui.value.exchanges.single()))

        // If you have selected a tool but haven’t done it yet: don’t list the tool in a row until you
        // actually do it.
        emit(TaskState.Running(1, "我先打开设置", "phone_launch_app"))
        assertTrue(vm.ui.value.exchanges.single().steps.isEmpty())
        did(1, "act", "phone_launch_app", "com.chat", "place_changed")
        steps = vm.ui.value.exchanges.single().steps
        assertEquals(StepRow(1, "Open app · 聊天", "", StepState.Run), steps.single())
        assertEquals("Open app · 聊天", liveLine(vm.ui.value.exchanges.single()))

        // Reading: "Look at the screen" does not appear; consecutive readings of the same place are
        // combined into one line; I don't know where I am reading.
        emit(TaskState.Running(2, "我先打开设置"))
        emit(TaskState.Running(2, "phone_observe", "phone_observe"))
        did(2, "observe", "phone_observe", "com.chat", title = "会话列表")
        did(2, "observe", "phone_diagnose", "com.chat", title = "会话列表")
        did(2, "observe", "phone_observe", null)
        steps = vm.ui.value.exchanges.single().steps
        assertEquals(listOf("Open app · 聊天", "Viewed 会话列表 in 聊天"), steps.map { it.text })
        assertEquals(listOf(StepState.Ok, StepState.Run), steps.map { it.state })

        emit(TaskState.WaitingTakeover("captcha", "出现滑块验证码"))
        val ex = vm.ui.value.exchanges.single()
        assertEquals(StepState.Wait, ex.steps.last().state)
        assertEquals("Needs you", liveLine(ex))
        assertEquals(ExchangeStatus.NeedsTakeover("captcha", "出现滑块验证码"), ex.status)
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)

        vm.takeOver()
        assertEquals("takeOver", calls.last())
    }

    @Test fun paymentTakeoverCarriesDetailsAndCanBeSkipped() = runTest {
        val vm = vm()
        vm.send("买一杯咖啡", ok)
        emit(TaskState.WaitingTakeover("payment", "请付款", item = "拿铁 大杯", amount = "¥18", address = "公司前台"))
        assertEquals(
            ExchangeStatus.NeedsTakeover("payment", "请付款", "拿铁 大杯", "¥18", "公司前台"),
            vm.ui.value.exchanges.single().status,
        )
        vm.skip()
        assertEquals("skip", calls.last())
    }

    // The answer is displayed as it is; the source is not separated from the answer, it is given by the
    // code written down in the running record.
    @Test fun finalAnswerShownAsIs() = runTest {
        val vm = vm()
        vm.send("电量多少", ok)
        emit(TaskState.Running(1, "phone_observe", "phone_observe"))
        did(1, "observe", "phone_observe", "com.chat")
        emit(TaskState.Running(2, "phone_observe")) // The next step is still waiting for the model to end: this line is not left
        emit(TaskState.Succeeded("电量 86%,正在充电。来源:设置 › 电池"))
        val ex = vm.ui.value.exchanges.single()
        assertEquals(ExchangeStatus.Done("电量 86%,正在充电。来源:设置 › 电池", null), ex.status)
        assertEquals(listOf(StepRow(1, "Viewed 聊天", "", StepState.Ok)), ex.steps)
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)

        assertEquals("没有依据段落" to null, splitEvidence("没有依据段落"))
        // The writing method in the old record (conversation file): The last single line starts with
        // "Based on:", and it is still separated out
        assertEquals("已把亮度调到 50%。\n自动亮度已关闭。" to "设置 › 显示 页面的亮度滑块显示 50%,自动亮度开关为关",
            splitEvidence("已把亮度调到 50%。\n自动亮度已关闭。\n依据: 设置 › 显示 页面的亮度滑块显示 50%,自动亮度开关为关"))
        assertEquals("结论。" to "来自搜索结果第一条", splitEvidence("结论。证据：来自搜索结果第一条"))
    }

    /** Each step has its actual action: click on this step to see which tool was used, what was used, and what the result was. */
    @Test fun eachStepCarriesWhatItActuallyDid() = runTest {
        val vm = vm()
        vm.send("打开设置看电量", ok)
        emit(TaskState.Running(0, "正在准备"))
        did(1, "act", "phone_tap", "com.chat", "changed")
        did(1, "act", "phone_type_text", "com.other", "unknown_code", failed = true)
        val ex = vm.ui.value.exchanges.single()
        assertEquals(
            listOf(
                // Use the interface term of the tool without the tool name.
                StepAction("Tap", "聊天", "changed", false),
                StepAction("Type text", "com.other", "unknown_code", true),
            ),
            ex.stepActions.getValue(1),
        )
        // Only hang on the step where there is action
        assertEquals(setOf(1), ex.stepActions.keys)
        // After clicking, each thing has one line: what was done, what was done, and the result; the
        // error code is not visible to others, and it says "unsuccessful"
        val lines = ex.stepActions.getValue(1).map(ChatCopy::actionLine)
        assertEquals("Tap · 聊天 · ${tr(R.string.result_effective)}", lines[0])
        assertEquals("Type text · com.other · ${tr(R.string.step_failed)}", lines[1])
        lines.forEach { assertFalse(it, it.contains("phone_") || it.contains("unknown_code")) }
    }

    @Test fun unverifiedIsMarkedOnTheResult() = runTest {
        val vm = vm()
        vm.send("查余额", ok)
        emit(TaskState.Running(1, "phone_observe", "phone_observe"))
        emit(TaskState.Succeeded("余额 10 元", verified = false, note = "结果页受系统防截屏保护"))
        val ex = vm.ui.value.exchanges.single()
        assertEquals(ExchangeStatus.Done("余额 10 元", null, verified = false, note = "结果页受系统防截屏保护"), ex.status)
        assertEquals("Done, but couldn’t verify: 结果页受系统防截屏保护", (ex.status as ExchangeStatus.Done).unverifiedLine)
        assertEquals(PipMode.Done, vm.ui.value.pip.mode)

        vm.send("再查", ok)
        emit(TaskState.Running(1, "phone_observe", "phone_observe"))
        emit(TaskState.Succeeded("余额 10 元"))
        assertNull((vm.ui.value.exchanges.last().status as ExchangeStatus.Done).unverifiedLine)
    }

    @Test fun liveLineUsesTheModelsReasonAndCollapsesToASummary() = runTest {
        var now = 1_000L
        val screen = MutableStateFlow(false)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = screen, clock = { now },
            activities = acts).also { runCurrent() }
        vm.send("查上海到杭州的车次", ok)
        emit(TaskState.Running(1, "", "phone_launch_app", reason = "打开 12306"))
        did(1, "act", "phone_launch_app", "cn.railway")
        screen.value = true
        runCurrent()
        var ex = vm.ui.value.exchanges.single()
        assertEquals(StepRow(1, "Open app · cn.railway", "", StepState.Run, "打开 12306"), ex.steps.single())
        assertEquals("打开 12306", liveLine(ex))
        assertTrue(vm.ui.value.operatingPhone)

        // Model requests show a neutral state while in transit and do not invent new steps.
        emit(TaskState.Running(2, ""))
        ex = vm.ui.value.exchanges.single()
        assertEquals(1, ex.steps.size)
        assertEquals("Working", liveLine(ex))

        emit(TaskState.Running(2, "", "phone_tap", reason = "正在 12306 搜索上海到杭州的车次"))
        assertEquals("正在 12306 搜索上海到杭州的车次", liveLine(vm.ui.value.exchanges.single()))
        did(2, "act", "phone_tap", "cn.railway", "changed")

        // Waiting for unlocking does not count as background operations
        emit(TaskState.Held(TaskGate.LOCKED))
        assertFalse(vm.ui.value.operatingPhone)

        emit(TaskState.Running(3, "正在核实结果", reason = "正在核实结果"))
        did(3, "observe", "phone_observe", "cn.railway", title = "查询结果")
        now = 81_000L
        emit(TaskState.Succeeded("有 3 趟\n依据:12306 查询结果页"))
        ex = vm.ui.value.exchanges.single()
        assertEquals(80_000L, ex.elapsedMs)
        assertEquals("3 steps · 1 min 20 s", stepsSummary(ex))
        assertFalse(vm.ui.value.operatingPhone)
        // If you don’t know the time, just tell me the number of steps
        assertEquals("3 steps", stepsSummary(ex.copy(elapsedMs = null)))
    }

    /** The subtitle of the top bar "Operating...": The App where the most recent object is located (replace the package name with the App name). */
    @Test fun livePlaceIsTheLatestTargetedApp() = runTest {
        val vm = vm()
        vm.send("看聊天", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        assertNull(vm.livePlace(vm.ui.value.exchanges.single()))
        did(1, "act", "phone_launch_app", "com.chat")
        did(2, "act", "phone_wait", null)
        val ex = vm.ui.value.exchanges.single()
        assertEquals("聊天", vm.livePlace(ex))
        assertEquals("Working in 聊天" to ChatCopy.Tone.Accent, ChatCopy.subtitle(ex, vm.livePlace(ex)))
    }

    // The progress description written with the call takes precedence over the intention in the call.
    @Test fun liveLinePrefersNarration() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder())).also { runCurrent() }
        vm.send("查车次", ok)
        emit(TaskState.Running(1, "先打开 12306 看看", "phone_launch_app", reason = "打开 12306", narration = "先打开 12306 看看"))
        assertEquals("先打开 12306 看看", liveLine(vm.ui.value.exchanges.single()))
        emit(TaskState.Running(2, "", "phone_tap", reason = "点搜索"))
        assertEquals("点搜索", liveLine(vm.ui.value.exchanges.single()))
    }

    @Test fun streamedTextIsVisibleDuringRequestAndClearedAtCompletion() = runTest {
        val vm = vm()
        vm.send("解释这个概念", ok)
        emit(TaskState.Running(1, "", preview = "第一段"))
        var ex = vm.ui.value.exchanges.single()
        assertEquals("第一段", ex.draft)
        assertTrue(ex.steps.isEmpty())
        assertEquals("Working", liveLine(ex))

        emit(TaskState.Running(1, "", preview = "第一段和第二段"))
        assertEquals("第一段和第二段", vm.ui.value.exchanges.single().draft)

        emit(TaskState.Succeeded("完整答复"))
        ex = vm.ui.value.exchanges.single()
        assertNull(ex.draft)
        assertEquals(ExchangeStatus.Done("完整答复", null), ex.status)
    }

    // A few things that are still not correct after returning them once: List them one by one on the
    // result card without putting a green check.
    @Test fun unconfirmedItemsAreListedOnTheCard() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder())).also { runCurrent() }
        vm.send("看价格", ok)
        emit(TaskState.Running(1, "", "phone_observe"))
        emit(TaskState.Succeeded("咖啡豆 ¥79", verified = false, note = "未确认:79", unconfirmed = listOf("79")))
        val done = vm.ui.value.exchanges.single().status as ExchangeStatus.Done
        assertEquals("咖啡豆 ¥79", done.answer)
        assertNull(done.evidence)
        assertEquals("Couldn’t confirm: 79", done.unverifiedLine)
    }

    @Test fun phoneHintOnlyWhileTheScreenIsOpen() = runTest {
        val screen = MutableStateFlow(false)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = screen).also { runCurrent() }
        vm.send("算一下 1+1", ok)
        emit(TaskState.Running(1, "", "run_command"))
        // Never opened the screen (documents, web pages, pure conversation tasks): not to mention
        // operating the phone in the background
        assertFalse(vm.ui.value.operatingPhone)
        screen.value = true
        runCurrent()
        assertTrue(vm.ui.value.operatingPhone)
        emit(TaskState.Succeeded("2"))
        assertFalse(vm.ui.value.operatingPhone)
    }

    @Test fun answerPartsPrefersAStructuredEvidence() {
        assertEquals("电量 86%" to "设置 › 电池", answerParts("电量 86%\n依据:设置 › 电池"))
        assertEquals("电量 86%" to "电池页", answerParts("电量 86% ", evidence = " 电池页 "))
        assertEquals("电量 86%\n依据:设置 › 电池" to "电池页", answerParts("电量 86%\n依据:设置 › 电池", evidence = "电池页"))
        // If the answer is empty, it will be removed from the answer as usual.
        assertEquals("电量 86%" to "设置 › 电池", answerParts("电量 86%\n依据:设置 › 电池", evidence = " "))
        assertEquals("没有依据" to null, answerParts("没有依据"))
    }

    // New record: The source is found by the code from the evidence ledger (sources event), and listed
    // one by one. The picture shows the step of reading these
    @Test fun evidenceSourceUsesRecordedSources() {
        val events = listOf(
            RunEvent.Media(1, 1, 1, "com.chat"),
            RunEvent.Media(2, 2, 3, "Site www.12306.cn"),
            RunEvent.Sources(3, listOf(
                com.meenai.mina.core.verify.AnswerSource(1, "screen", "com.chat", null, 1, listOf(1)),
                com.meenai.mina.core.verify.AnswerSource(4, "web", "https://www.12306.cn/index", "车次查询", 3, emptyList()),
            )),
        )
        val source = evidenceSource(events) { if (it == "com.chat") "聊天" else it }
        assertEquals(EvidenceSource("www.12306.cn", listOf(1), listOf("Step 1 · 聊天", "Step 3 · www.12306.cn · 车次查询")), source)
        assertEquals("Source · www.12306.cn · 1 screenshot", sourceLabel(source))
    }

    @Test fun evidenceSourceIsTheLastSavedScreen() {
        val events = listOf(
            RunEvent.Media(1, 1, 1, "com.chat"),
            RunEvent.Media(2, 2, 3, "Site www.12306.cn"),
            RunEvent.Media(3, 3, 3, null),
        )
        val source = evidenceSource(events) { if (it == "com.chat") "聊天" else it }
        assertEquals(EvidenceSource("www.12306.cn", listOf(2, 3)), source)
        assertEquals("Source · www.12306.cn · 2 screenshots", sourceLabel(source))
        assertEquals(EvidenceSource("聊天", listOf(1)), evidenceSource(events.take(1)) { if (it == "com.chat") "聊天" else it })
        // There are no screenshots (files, dialogue-only tasks), just write "source" if you haven't
        // retrieved it yet.
        assertEquals(EvidenceSource(null, emptyList()), evidenceSource(emptyList()))
        assertEquals("Source", sourceLabel(EvidenceSource(null, emptyList())))
        assertEquals("Source", sourceLabel(null))
    }

    @Test fun livePreviewVisibleOnlyWhileRunning() = runTest {
        val screen = MutableStateFlow(false)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = screen).also { runCurrent() }
        assertFalse(vm.ui.value.pip.visible)
        vm.send("任务", ok)
        emit(TaskState.Running(1, "", null))
        screen.value = true
        runCurrent()
        assertTrue(vm.ui.value.pip.visible)
        assertEquals(PipMode.Running, vm.ui.value.pip.mode)

        emit(TaskState.Succeeded("好了"))
        assertTrue(vm.ui.value.pip.visible)
        assertEquals(PipMode.Done, vm.ui.value.pip.mode)
        advanceTimeBy(ChatViewModel.DONE_LINGER_MS + 1)
        runCurrent()
        assertFalse(vm.ui.value.pip.visible)

        screen.value = false
        runCurrent()
        vm.send("再来", ok)
        emit(TaskState.Running(1, "", null))
        screen.value = true
        runCurrent()
        assertTrue(vm.ui.value.pip.visible)
        emit(TaskState.Failed("出错了", FailKind.GaveUp))
        assertFalse(vm.ui.value.pip.visible)
    }

    @Test fun pipOnlyOnceTheTaskHasAScreen() = runTest {
        val screen = MutableStateFlow(false)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = screen).also { runCurrent() }
        vm.send("打开设置看安卓版本", ok)
        assertFalse(vm.ui.value.pip.visible)
        emit(TaskState.Running(1, "phone_observe", "phone_observe"))
        assertFalse(vm.ui.value.pip.visible)
        vm.expandPip()
        assertFalse(vm.ui.value.pip.expanded)
        // Questions asked before opening the app are only in the chat card
        emit(TaskState.WaitingInput("哪个?", null))
        assertFalse(vm.ui.value.pip.visible)
        emit(TaskState.Running(2, "phone_launch_app", "phone_launch_app"))

        screen.value = true // The first time phone_launch_app built a screen
        runCurrent()
        assertTrue(vm.ui.value.pip.visible)
        assertEquals(PipMode.Running, vm.ui.value.pip.mode)
        // You will still have to handle the takeover after opening the screen.
        emit(TaskState.WaitingTakeover("login", "需要登录"))
        assertTrue(vm.ui.value.pip.visible)
        assertEquals(PipMode.NeedsYou, vm.ui.value.pip.mode)

        // At the end, remove the screen first and then announce the end: the few seconds after
        // completion will still be displayed.
        screen.value = false
        runCurrent()
        emit(TaskState.Succeeded("安卓 14\n依据:关于手机页"))
        assertTrue(vm.ui.value.pip.visible)
        assertEquals(PipMode.Done, vm.ui.value.pip.mode)
    }

    @Test fun chatOnlyTaskNeverShowsPip() = runTest {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = MutableStateFlow(false)).also { runCurrent() }
        vm.send("你好", ok)
        emit(TaskState.Running(1, "phone_observe", "phone_observe"))
        emit(TaskState.Running(2, ""))
        emit(TaskState.Succeeded("你好!", verified = false, reviewed = false))
        assertFalse(vm.ui.value.pip.visible)
        val done = vm.ui.value.exchanges.single().status as ExchangeStatus.Done
        assertEquals(ExchangeStatus.Done("你好!", null, verified = false, reviewed = false), done)
        // Chats with nothing checked: no marking
        assertNull(done.unverifiedLine)
    }

    @Test fun pipSnapsToNearestCorner() {
        val b = PipPhysics.Bounds(1080f, 2400f, 200f, 420f, margin = 30f, top = 300f, bottom = 250f)
        assertEquals(850f to 330f, b.position(Corner.TopEnd))
        assertEquals(30f to 1700f, b.position(Corner.BottomStart))
        // Stopped near the upper right, no speed: go back to the upper right
        assertEquals(Corner.TopEnd, PipPhysics.snapCorner(700f, 500f, 0f, 0f, b))
        // Throw downward at the same position: the projection point is on the lower half of the screen
        assertEquals(Corner.BottomEnd, PipPhysics.snapCorner(700f, 500f, 0f, 2500f, b))
        // Throw down to the left
        assertEquals(Corner.BottomStart, PipPhysics.snapCorner(700f, 500f, -2000f, 2500f, b))
        assertEquals(499f, PipPhysics.project(1000f), 0.01f)
        // The window is too short (for example, the keyboard pops up): instead of drilling under the top
        // bar, the lower corners retreat to the same level as the upper ones.
        val short = b.copy(height = 900f)
        assertEquals(30f to 330f, short.position(Corner.BottomStart))

        // Critical damping: converge to the target without exceeding
        val spring = PipPhysics.Spring()
        var m = PipPhysics.Motion(0f, 0f)
        var max = 0f
        repeat(120) {
            m = spring.step(m, 100f, 1f / 60)
            max = maxOf(max, m.position)
        }
        assertTrue(spring.settled(m, 100f))
        assertTrue("overshoot $max", max <= 100.5f)
    }

    @Test fun pipAutoExpandsWhenNeedsUser() = runTest {
        val screen = MutableStateFlow(false)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = screen).also { runCurrent() }
        vm.send("任务", ok)
        emit(TaskState.Running(1, "", null))
        screen.value = true
        runCurrent()
        assertFalse(vm.ui.value.pip.expanded)

        emit(TaskState.WaitingTakeover("login", "需要登录"))
        assertTrue(vm.ui.value.pip.expanded)
        assertEquals(PipMode.NeedsYou, vm.ui.value.pip.mode)

        vm.collapsePip()
        assertFalse(vm.ui.value.pip.expanded)
        stage.value = Takeover.Stage.HandedOver
        runCurrent()
        assertFalse(vm.ui.value.pip.expanded) // Do not bounce repeatedly during the same wait
        assertEquals(Takeover.Stage.HandedOver, vm.ui.value.takeoverStage)

        emit(TaskState.Running(2, "", null))
        assertFalse(vm.ui.value.pip.expanded)
        // Only takeovers automatically expand: keep input boxes and options in front of you when asking
        // questions
        emit(TaskState.WaitingInput("选哪个?", listOf("A", "B")))
        assertFalse(vm.ui.value.pip.expanded)
        assertEquals(PipMode.NeedsYou, vm.ui.value.pip.mode)
        assertEquals(ComposerMode.Answer, vm.ui.value.composer)
        assertTrue(vm.send("A", ok))
        assertEquals("answer:A", calls.last())
    }

    @Test fun startWaitsUntilServiceFinishedPreviousTask() = runTest {
        val busy = MutableStateFlow(true)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), busy)
        runCurrent()
        assertTrue(vm.send("下一个", ok))
        runCurrent()
        assertTrue(calls.isEmpty())
        busy.value = false
        runCurrent()
        assertEquals(listOf("start:下一个"), calls)
    }

    @Test fun stopWhileQueuedBehindServiceCancelsTheStart() = runTest {
        val busy = MutableStateFlow(true)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), busy)
        runCurrent()
        assertTrue(vm.send("下一个", ok))
        runCurrent()
        vm.cancel()
        busy.value = false
        runCurrent()
        assertTrue(calls.toString(), calls.isEmpty()) // It has not started, and it does not cancel the previous task that is being completed in the service.
        assertEquals(ExchangeStatus.Cancelled(), vm.ui.value.exchanges.single().status)
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)
        assertFalse(vm.ui.value.pip.visible)
    }

    // When waiting for the service to be free, the bottom of the message clearly states that it is
    // waiting for the previous task, which is not like being stuck; after handing it over to the
    // service, it is replaced by the progress of the task itself.
    @Test fun waitingMessageSaysItWaitsForThePreviousTask() = runTest {
        val busy = MutableStateFlow(true)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), busy)
        runCurrent()
        assertTrue(vm.send("下一个", ok))
        runCurrent()
        val waiting = vm.ui.value.exchanges.single()
        assertEquals(ExchangeStatus.Working, waiting.status)
        assertEquals("Starts after the previous task finishes", ChatCopy.progressLine(waiting))
        busy.value = false
        runCurrent()
        assertEquals(listOf("start:下一个"), calls)
        emit(TaskState.Running(1, "打开设置", reason = "打开设置"))
        assertEquals("打开设置", ChatCopy.progressLine(vm.ui.value.exchanges.single()))
    }

    // When the conversation page sees that the service is empty, it is handed over to it. Before handing
    // it over, another task (timing, computer) was started: the service queues this item behind it (not
    // lost), and it can be seen in the queue; after the task is completed, this task starts and is
    // completed as usual.
    private fun TestScope.raceVm(): ChatViewModel {
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), MutableStateFlow(false),
            shownTask = shown, activeConversations = active).also { runCurrent() }
        assertTrue(vm.send("订会议室", ok))
        assertEquals(listOf("start:订会议室"), calls)
        val mine = vm.ui.value.conversationId!!
        // Tasks elsewhere are started first, and it is displayed in the service: Do not receive this
        // conversation.
        active.value = listOf("remote")
        shown.value = "remote" to TaskState.Running(3, "查快递")
        emit(TaskState.Running(3, "查快递"))
        assertEquals("订会议室", vm.ui.value.exchanges.single().goal)
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.single().status)
        // This line is queued behind it, and the service displays its queuing status.
        val queued = TaskState.Held(GateReason.queued("排队中:前面还有 1 个任务"))
        active.value = listOf("remote", mine)
        shown.value = mine to queued
        emit(queued)
        return vm
    }

    @Test fun messageQueuedBehindATaskStartedElsewhereStartsAfterIt() = runTest {
        val vm = raceVm()
        val mine = vm.ui.value.conversationId!!
        assertEquals(ExchangeStatus.Queued("排队中:前面还有 1 个任务"), vm.ui.value.exchanges.single().status)
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)
        // When that task is finished, release this one and start running until it is completed.
        active.value = listOf(mine)
        shown.value = mine to TaskState.Running(1, "打开日历", reason = "打开日历")
        emit(TaskState.Running(1, "打开日历", reason = "打开日历"))
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.single().status)
        shown.value = mine to TaskState.Succeeded("订好了")
        emit(TaskState.Succeeded("订好了"))
        assertEquals("订好了", (vm.ui.value.exchanges.single().status as ExchangeStatus.Done).answer)
    }

    // Stop when queuing: only stop the conversation, and do not send an unnumbered stop (that will stop
    // the tasks displayed by the service and started elsewhere)
    @Test fun stopWhileQueuedInServiceCancelsOnlyThisMessage() = runTest {
        val vm = raceVm()
        val mine = vm.ui.value.conversationId!!
        vm.cancel()
        assertEquals("cancel@$mine", calls.last())
        assertFalse("cancel" in calls)
    }

    @Test fun historyLoadsWhenOpeningConversation() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        store.append(id, Turn("查天气", "晴\n依据:天气 App", "succeeded"))
        store.append(id, Turn("订票", "验证码过不去", "failed"))
        store.append(id, Turn("查余额", "余额 10 元\n依据:钱包页", "unverified", note = "防截屏"))
        val vm = vm(store)
        vm.openConversation(id)
        val ex = vm.ui.value.exchanges
        assertEquals(listOf("查天气", "订票", "查余额"), ex.map { it.goal })
        assertEquals(ExchangeStatus.Done("余额 10 元", "钱包页", verified = false, note = "防截屏"), ex[2].status)
        assertEquals(ExchangeStatus.Done("晴", "天气 App"), ex[0].status)
        assertEquals(ExchangeStatus.Failed("验证码过不去"), ex[1].status)

        vm.send("再查一次", ok)
        assertEquals("start:再查一次", calls.last())
        assertEquals(id, vm.ui.value.conversationId)
    }

    @Test fun confirmationCardShowsButtons() = runTest {
        val vm = vm()
        assertTrue(vm.send("给张三发明天见", ok))
        emit(TaskState.Running(3, "打字", "phone_type_text"))
        did(3, "act", "phone_type_text", "com.chat", "changed")
        val waiting = TaskState.WaitingConfirmation(ConfirmationRequest(IrrevAction.send, "张三", "明天见", null, "com.chat"))
        emit(waiting)
        val ex = vm.ui.value.exchanges.last()
        assertEquals(ExchangeStatus.NeedsConfirmation("Needs your OK: Send", "In 聊天\nTo: 张三\nContent: 明天见", ConfirmationNotifier.requestKey(waiting)), ex.status)
        assertEquals(StepState.Wait, ex.steps.last().state)
        assertEquals(PipMode.NeedsYou, vm.ui.value.pip.mode)
        assertEquals("Waiting for your OK", liveLine(ex))
        assertFalse(vm.ui.value.operatingPhone)
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)
        // Return to run after answering
        emit(TaskState.Running(4, "", null))
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.last().status)
    }

    @Test fun approveCallsAction() = runTest {
        val vm = vm()
        // The card button carries the declaration it belongs to
        vm.approve(7)
        vm.decline(8)
        assertEquals(listOf("approve:7", "decline:8"), calls)
    }

    @Test fun endedTaskCarriesItsRunId() = runTest {
        val endedRunId = MutableStateFlow<String?>("r-before")
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        store.append(id, Turn("查天气", "晴", "succeeded", runId = "r-old"))
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, endedRunId = endedRunId)
        runCurrent()
        vm.openConversation(id)
        assertEquals("r-old", vm.ui.value.exchanges.single().runId)

        vm.send("查电量", ok)
        emit(TaskState.Running(1, "看看", "phone_observe"))
        assertNull(vm.ui.value.exchanges.last().runId) // Not giving entrance while still running
        // The service will first record the number this time and then report it to the end.
        endedRunId.value = "r-new"
        emit(TaskState.Succeeded("85%"))
        assertEquals("r-new", vm.ui.value.exchanges.last().runId)

        // The number of the next task arrives later: the one that has ended will not change accordingly.
        vm.send("再查", ok)
        emit(TaskState.Running(1, "看看", "phone_observe"))
        endedRunId.value = "r-next"
        runCurrent()
        assertEquals("r-new", vm.ui.value.exchanges[1].runId)
        emit(TaskState.Failed("没找到", FailKind.GaveUp))
        assertEquals("r-next", vm.ui.value.exchanges[2].runId)
        assertEquals("r-new", vm.ui.value.exchanges[1].runId)
    }

    // Stop: The scene has been stopped and the input box is empty; the explanation is still written with
    // the time mark "being sorted out". After writing it down, it will be entered into the dialogue and
    // become the answer for this round.
    @Test fun stoppedTurnShowsSummaryWhenItArrives() = runTest {
        val endedRunId = MutableStateFlow<String?>(null)
        val summarizing = MutableStateFlow<Set<String>>(emptySet())
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, endedRunId = endedRunId, summarizing = summarizing)
        runCurrent()
        vm.openConversation(id)
        vm.send("看四个设置页", ok)
        emit(TaskState.Running(1, "看看", "phone_launch_app"))
        vm.cancel()
        assertEquals("cancel", calls.last())
        // The service first registers as "organizing", writes down the number, then reports as
        // completed, and then writes down the "cancelled" round.
        summarizing.value = setOf("r1")
        endedRunId.value = "r1"
        emit(TaskState.Cancelled)
        store.append(id, Turn("看四个设置页", "已取消", "cancelled", runId = "r1"))
        runCurrent()
        assertEquals(ExchangeStatus.Cancelled(summarizing = true), vm.ui.value.exchanges.single().status)
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)

        store.updateTurn(id, "r1") { it.copy(outcome = "停止前看完了网络、蓝牙;显示、声音还没看。") }
        runCurrent()
        summarizing.value = emptySet()
        runCurrent()
        val ex = vm.ui.value.exchanges.single()
        assertEquals(ExchangeStatus.Cancelled("停止前看完了网络、蓝牙;显示、声音还没看。"), ex.status)
        assertEquals("r1", ex.runId)
        // Reopen the conversation: Read the explanation from the saved copy
        vm.newConversation()
        vm.openConversation(id)
        assertEquals(ExchangeStatus.Cancelled("停止前看完了网络、蓝牙;显示、声音还没看。"), vm.ui.value.exchanges.single().status)
    }

    // The explanation cannot be written (or there is nothing to explain): remove "organizing" and leave
    // only "stopped"
    @Test fun stoppedTurnWithoutSummaryDropsTheHint() = runTest {
        val endedRunId = MutableStateFlow<String?>(null)
        val summarizing = MutableStateFlow<Set<String>>(emptySet())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), endedRunId = endedRunId, summarizing = summarizing)
        runCurrent()
        vm.send("看设置", ok)
        emit(TaskState.Running(1, "看看", "phone_launch_app"))
        summarizing.value = setOf("r2")
        endedRunId.value = "r2"
        emit(TaskState.Cancelled)
        assertEquals(ExchangeStatus.Cancelled(summarizing = true), vm.ui.value.exchanges.single().status)
        summarizing.value = emptySet()
        runCurrent()
        assertEquals(ExchangeStatus.Cancelled(), vm.ui.value.exchanges.single().status)
    }

    @Test fun attachmentsGoWithTheNextMessage() = runTest {
        val sandbox = MutableStateFlow(false)
        val imported = mutableListOf<Pair<String, List<String>>>()
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = sandbox,
            importAttachments = { id, uris ->
                imported += id to uris
                com.meenai.mina.cap.sandbox.ImportResult(listOf("/workspace/inbox/报表 (1).csv" to 2048L), listOf("big.iso"))
            },
            describeAttachments = { uris -> uris.map { PendingAttachment(it, if (it.endsWith("1")) "报表.csv" else "big.iso", 10L) } },
        ).also { runCurrent() }
        // The sandbox is not ready: there is no attachment button and no collection
        assertFalse(vm.ui.value.attachEnabled)
        vm.attach(listOf("content://x/1"))
        runCurrent()
        assertTrue(vm.ui.value.attachments.isEmpty())

        sandbox.value = true
        runCurrent()
        assertTrue(vm.ui.value.attachEnabled)
        // When selected, it will only hang above the input box and will not be copied.
        vm.attach(listOf("content://x/1", "content://x/2"))
        runCurrent()
        assertEquals(listOf("content://x/1", "content://x/2"), vm.ui.value.attachments.map { it.uri })
        assertTrue(imported.isEmpty())

        // When sending, it is copied into the workspace and the path is written into the message.
        assertTrue(vm.send("汇总一下", ok))
        runCurrent()
        val id = vm.ui.value.conversationId!!
        assertEquals(listOf(id to listOf("content://x/1", "content://x/2")), imported)
        assertEquals(listOf("start:汇总一下\n附件:/workspace/inbox/报表 (1).csv(2 KB)"), calls)
        assertTrue(vm.ui.value.attachments.isEmpty())
        assertEquals(listOf("big.iso"), vm.ui.value.rejectedAttachments)
        assertFalse(vm.ui.value.importing)
        // Attachments are also collected while the task is running (and handed over to the current task
        // when interrupted)
        vm.attach(listOf("content://x/3"))
        runCurrent()
        assertEquals(listOf("content://x/3"), vm.ui.value.attachments.map { it.uri })
    }

    @Test fun attachmentsAloneCanBeSent() = runTest {
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true),
            importAttachments = { _, _ -> com.meenai.mina.cap.sandbox.ImportResult(listOf("/workspace/inbox/a.jpg" to 3L * 1024 * 1024), emptyList()) },
        ).also { runCurrent() }
        assertTrue(vm.toggleAttachment(PendingAttachment("content://m/1", "a.jpg", 1L)))
        assertTrue(vm.send("", ok))
        runCurrent()
        assertEquals(listOf("start:附件:/workspace/inbox/a.jpg(3.0 MB)"), calls)
    }

    @Test fun nothingIsSentWhenEveryAttachmentFailsAndThereAreNoWords() = runTest {
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true),
            importAttachments = { _, _ -> com.meenai.mina.cap.sandbox.ImportResult(emptyList(), listOf("huge.mov")) },
        ).also { runCurrent() }
        vm.toggleAttachment(PendingAttachment("content://m/1", "huge.mov", 1L))
        vm.send("", ok)
        runCurrent()
        assertTrue(calls.isEmpty())
        assertTrue(vm.ui.value.exchanges.isEmpty())
        assertEquals(listOf("huge.mov"), vm.ui.value.rejectedAttachments)
    }

    @Test fun pickingPhotosStopsAtTheLimit() = runTest {
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            sandboxReady = MutableStateFlow(true),
            describeAttachments = { uris -> uris.map { PendingAttachment(it, "p.jpg", 1L) } },
        ).also { runCurrent() }
        repeat(AttachTray.LIMIT) { assertTrue(vm.toggleAttachment(PendingAttachment("content://m/$it", "p.jpg", 1L))) }
        assertFalse(vm.toggleAttachment(PendingAttachment("content://m/x", "p.jpg", 1L)))
        // Click on the selected one again to cancel
        assertTrue(vm.toggleAttachment(PendingAttachment("content://m/0", "p.jpg", 1L)))
        assertEquals(AttachTray.LIMIT - 1, vm.ui.value.attachments.size)
        vm.attach(listOf("content://f/1", "content://f/2"))
        runCurrent()
        assertEquals(AttachTray.LIMIT, vm.ui.value.attachments.size)
        assertEquals(1, vm.ui.value.overLimit)
        vm.dismissRejected()
        assertEquals(0, vm.ui.value.overLimit)
    }

    @Test fun sharedFilesShowOnTheLiveExchangeAndAreRestored() = runTest {
        val files = MutableStateFlow<List<com.meenai.mina.chat.SharedFile>>(emptyList())
        val store = ConversationStore(tmp.newFolder())
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, sharedFiles = files).also { runCurrent() }
        vm.send("导出表格", ok)
        emit(TaskState.Running(1, "整理"))
        val f = com.meenai.mina.chat.SharedFile("表.xlsx", 5000, "Download/Harness/表.xlsx", "content://media/1")
        files.value = listOf(f)
        runCurrent()
        assertEquals(listOf(f), vm.ui.value.exchanges.last().files)
        emit(TaskState.Succeeded("好了\n依据: 已导出"))
        assertEquals(listOf(f), vm.ui.value.exchanges.last().files)

        val id = vm.ui.value.conversationId!!
        store.append(id, Turn("导出表格", "好了", "succeeded"), listOf(f))
        vm.openConversation(id)
        assertEquals(listOf(f), vm.ui.value.exchanges.single().files)
    }

    @Test fun sizeLabels() {
        assertEquals("12 B", ChatViewModel.sizeLabel(12))
        assertEquals("2 KB", ChatViewModel.sizeLabel(2048))
        assertEquals("1.5 MB", ChatViewModel.sizeLabel(1_572_864))
    }

    // ---- Parallel tasks (switch parallelTasks on)----
    private val shown = MutableStateFlow<Pair<String, TaskState>?>(null)
    private val active = MutableStateFlow<List<String>>(emptyList())

    private fun TestScope.parallelVm(
        store: ConversationStore,
        files: MutableStateFlow<List<com.meenai.mina.chat.SharedFile>> = MutableStateFlow(emptyList()),
    ) = ChatViewModel(
        backgroundScope, state, stage, actions, store, serviceBusy = MutableStateFlow(true),
        parallelFlag = { true }, shownTask = shown, activeConversations = active, runGoal = { if (it == "b") "查快递" else null },
        sharedFiles = files,
    ).also { runCurrent() }

    private fun TestScope.show(conversationId: String, s: TaskState) {
        shown.value = conversationId to s
        runCurrent()
    }

    /** Delete this conversation on the screen: replace it with a new empty conversation on the spot, no longer list it in the sidebar, and leave the deletion to the background. */
    @Test fun deletingTheOpenConversationShowsAFreshOne() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.append("b", Turn("订票", "好了", "succeeded"))
        val removed = mutableListOf<String>()
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, removeConversation = { removed += it; store.delete(it) }).also { runCurrent() }
        vm.openConversation("a")
        assertEquals(1, vm.ui.value.exchanges.size)

        vm.deleteConversation("a")
        runCurrent()
        assertEquals(listOf("a"), removed)
        assertEquals(setOf("a"), vm.deleted.value)
        assertNull(vm.ui.value.conversationId)
        assertTrue(vm.ui.value.exchanges.isEmpty())
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)

        // Delete other conversations: No movement on the screen
        vm.openConversation("b")
        vm.deleteConversation("x")
        runCurrent()
        assertEquals("b", vm.ui.value.conversationId)
        assertEquals(listOf("订票"), vm.ui.value.exchanges.map { it.goal })
    }

    /** Rename and pin to the top in the sidebar: save it, and rearrange the sidebar according to the saved one (the pinned items are in the "Top" section, and the most recently pinned items are in front); deleted conversations will no longer be changed. */
    @Test fun renameAndPinReorderTheDrawer() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded"))
        store.append("b", Turn("订票", "好了", "succeeded"))
        store.append("c", Turn("写周报", "好了", "succeeded"))
        var now = 1_000L
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, clock = { now }, removeConversation = { store.delete(it) }).also { runCurrent() }
        fun sections() = drawerSections(store.list(), CurrentConversation(null, false, "", ""), emptyList(), vm::runningGoal, vm.canSwitch(), 0L)

        vm.renameConversation("b", " 回家的票 ")
        vm.setPinned("c", true)
        now = 2_000L
        vm.setPinned("b", true)
        val s = sections()
        assertEquals(listOf("回家的票", "写周报"), s.pinned.map { it.title })
        assertEquals(listOf("a"), s.recent.map { it.id })

        vm.setPinned("b", false)
        assertEquals(listOf("c"), sections().pinned.map { it.id })
        assertEquals(setOf("a", "b"), sections().recent.mapNotNull { it.id }.toSet())

        vm.deleteConversation("c")
        runCurrent()
        vm.renameConversation("c", "不该写进去")
        vm.setPinned("c", true)
        assertNull(store.title("c"))
        assertNull(store.pinnedAt("c"))
    }

    /** Someone has saved the deleted conversation again (the suspension capsule then said in that number): Relist it in the side column. */
    @Test fun aDeletedConversationThatIsWrittenAgainIsListedAgain() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded"))
        val vm = ChatViewModel(backgroundScope, state, stage, actions, store, removeConversation = { store.delete(it) }).also { runCurrent() }
        vm.deleteConversation("a")
        runCurrent()
        assertEquals(setOf("a"), vm.deleted.value)
        store.append("a", Turn("再来", "好", "succeeded"))
        runCurrent()
        assertTrue(vm.deleted.value.isEmpty())
    }

    /** Delete the on-screen dialogue of the running task during parallel operation: no longer follow that task, and its subsequent status will not be included in the new dialogue. */
    @Test fun deletingARunningOpenConversationStopsFollowingIt() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val vm = parallelVm(store)
        vm.switchTo("a")
        assertTrue(vm.send("设置版本号", ok))
        active.value = listOf("a")
        show("a", TaskState.Running(1, "打开设置"))
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)

        vm.deleteConversation("a")
        runCurrent()
        val fresh = vm.ui.value.conversationId
        assertTrue(fresh != null && fresh != "a")
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)
        assertFalse(vm.ui.value.pip.visible)
        show("a", TaskState.Running(2, "看屏幕"))
        show("a", TaskState.Cancelled)
        assertTrue(vm.ui.value.exchanges.isEmpty())
        assertEquals(fresh, vm.ui.value.conversationId)
    }

    @Test fun otherRunningExcludesCurrent() = runTest {
        val vm = parallelVm(ConversationStore(tmp.newFolder()))
        vm.switchTo("a")
        active.value = listOf("a", "b", "c")
        runCurrent()
        assertEquals(listOf("b", "c"), vm.otherRunning.value)
        vm.switchTo("b")
        runCurrent()
        assertEquals(listOf("a", "c"), vm.otherRunning.value)
    }

    @Test fun switchToLoadsThatConversation() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("b", Turn("查天气", "晴\n依据:天气 App", "succeeded"))
        val vm = parallelVm(store)
        // The task of dialogue a is running
        vm.switchTo("a")
        assertTrue(vm.send("设置版本号", ok))
        show("a", TaskState.Running(1, "打开设置"))
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)

        // There is also a task running in dialogue b: switch to it and let the service display it first,
        // load the history and connect its current status
        active.value = listOf("a", "b")
        show("b", TaskState.Running(2, "看屏幕"))
        vm.switchTo("b")
        assertEquals("b", vm.ui.value.conversationId)
        assertTrue("show:b" in calls)
        assertEquals(listOf("查天气", "查快递"), vm.ui.value.exchanges.map { it.goal })
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.last().status)
        // The status of a does not enter the dialogue of b
        show("a", TaskState.Succeeded("done"))
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.last().status)

        // On the screen is b: Stop sending tasks only to b without changing the display first.
        calls.clear()
        vm.cancel()
        assertEquals(listOf("cancel@b"), calls)

        // New dialogue without tasks: New tasks can be started while other tasks are still running,
        // without waiting for the service to be free.
        vm.newConversation()
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)
        assertTrue(vm.send("新建闹钟", ok))
        assertEquals("start:新建闹钟", calls.last())
    }

    private val learnCalls = mutableListOf<String>()
    private val savedNotes = mutableListOf<com.meenai.mina.memory.RunNotes>()
    private val learning = object : LearningActions {
        override suspend fun undo(token: String): Boolean { learnCalls += "undo:$token"; return token != "expired" }
        override suspend fun approve(pendingId: String): Boolean { learnCalls += "approve:$pendingId"; return true }
        override suspend fun forget(memoryId: String): Boolean { learnCalls += "forget:$memoryId"; return true }
        override fun viewSkill(draftId: String) { learnCalls += "view:$draftId" }
        override suspend fun restoreSkill(name: String) { learnCalls += "restore:$name" }
        override suspend fun deleteSkill(name: String) { learnCalls += "delete:$name" }
        override suspend fun save(notes: com.meenai.mina.memory.RunNotes) { savedNotes += notes }
    }
    private val notesFlow = MutableStateFlow<Map<String, com.meenai.mina.memory.RunNotes>>(emptyMap())
    private fun item(id: String, content: String) = com.meenai.mina.core.memory.MemoryItem(
        id, com.meenai.mina.core.memory.MemoryKind.preference, content, emptyList(),
        com.meenai.mina.core.memory.MemorySource("manual", atMs = 0), com.meenai.mina.core.memory.MemoryStatus.active, createdAt = 0, updatedAt = 0,
    )
    private val items = MutableStateFlow(listOf(item("a", "用户点外卖不要香菜"), item("b", "用户喝咖啡不加糖"), item("p1", "点外卖先比价")))

    private fun TestScope.vmWithHistory(): ChatViewModel {
        val store = ConversationStore(tmp.newFolder())
        store.append("c1", Turn("点外卖", "已下单\n依据: 订单页", "succeeded", runId = "r1"))
        return ChatViewModel(backgroundScope, state, stage, actions, store, runNotes = notesFlow, learning = learning, memoryItems = items).also {
            runCurrent(); it.openConversation("c1"); runCurrent()
        }
    }

    private val remembered = com.meenai.mina.core.memory.MemoryNote(com.meenai.mina.core.memory.NoteKind.added, "a", "t1", null)
    private val procedure = com.meenai.mina.core.memory.MemoryNote(com.meenai.mina.core.memory.NoteKind.pending, "p1", "t2", "p1")

    @Test fun memoryNotesShownUnderResult() = runTest {
        val vm = vmWithHistory()
        notesFlow.value = mapOf("r1" to com.meenai.mina.memory.RunNotes("c1", "r1", listOf(remembered)))
        runCurrent()
        val note = vm.ui.value.exchanges.single().memoryNotes.single()
        assertEquals("记住了:用户点外卖不要香菜", vm.noteText(note))
        // The content is now retrieved: delete the item and write "a deleted memory"
        items.value = items.value.filter { it.id != "a" }
        assertEquals("记住了:一条已删除的记忆", vm.noteText(note))
        // When reopening the conversation, read it back from the saved round (only the type and number
        // are saved)
        val store = ConversationStore(tmp.newFolder())
        store.append("c2", Turn("点外卖", "ok", "succeeded", runId = "r2", memoryNotes = listOf(remembered), usedMemory = listOf("a")))
        val vm2 = ChatViewModel(backgroundScope, state, stage, actions, store).also { runCurrent(); it.openConversation("c2"); runCurrent() }
        assertEquals(listOf(remembered), vm2.ui.value.exchanges.single().memoryNotes)
    }

    @Test fun undoRemovesNote() = runTest {
        val vm = vmWithHistory()
        val expired = remembered.copy(undoToken = "expired")
        notesFlow.value = mapOf("r1" to com.meenai.mina.memory.RunNotes("c1", "r1", listOf(remembered, expired)))
        runCurrent()
        vm.undoNote("r1", remembered); runCurrent()
        assertEquals(listOf("undo:t1"), learnCalls)
        assertEquals(listOf(expired), vm.ui.value.exchanges.single().memoryNotes)
        // Token invalidation: Do not delete the prompt, only remove "Revoke"
        vm.undoNote("r1", expired); runCurrent()
        assertEquals(listOf(expired.copy(undoToken = null)), vm.ui.value.exchanges.single().memoryNotes)
        assertEquals(listOf(expired.copy(undoToken = null)), savedNotes.last().memoryNotes)
    }

    @Test fun pendingProcedureShowsYesNo() = runTest {
        val vm = vmWithHistory()
        val second = procedure.copy(undoToken = "expired", pendingId = "p2", memoryId = "p2")
        notesFlow.value = mapOf("r1" to com.meenai.mina.memory.RunNotes("c1", "r1", listOf(procedure, second)))
        runCurrent()
        assertEquals("要我以后都这样做吗?点外卖先比价", vm.noteText(procedure))
        vm.approveNote("r1", procedure); runCurrent()
        assertEquals(listOf("approve:p1"), learnCalls)
        // The token has expired when "don't": delete that method directly
        vm.rejectNote("r1", vm.ui.value.exchanges.single().memoryNotes.single()); runCurrent()
        assertEquals(listOf("approve:p1", "undo:expired", "forget:p2"), learnCalls)
        assertTrue(vm.ui.value.exchanges.single().memoryNotes.isEmpty())
    }

    @Test fun usedMemoryCountShown() = runTest {
        val vm = vmWithHistory()
        notesFlow.value = mapOf("r1" to com.meenai.mina.memory.RunNotes("c1", "r1", usedMemory = listOf("a", "b")))
        runCurrent()
        val ex = vm.ui.value.exchanges.single()
        assertEquals(listOf("用户点外卖不要香菜", "用户喝咖啡不加糖"), vm.usedMemoryTexts(ex.usedMemory))
        // After clearing all, it will no longer be listed (the interface will not display "n memories
        // used" accordingly)
        items.value = emptyList()
        assertTrue(vm.usedMemoryTexts(ex.usedMemory).isEmpty())
    }

    @Test fun suspendedSkillRestoreAndView() = runTest {
        val vm = vmWithHistory()
        val learned = com.meenai.mina.core.skills.SkillNote("Mina 学会了一项技能:发微信", draftId = "d1")
        val suspended = com.meenai.mina.core.skills.SkillNote("这项技能最近不灵,先停用了", suspended = "wechat-msg")
        notesFlow.value = mapOf("r1" to com.meenai.mina.memory.RunNotes("c1", "r1", skillNotes = listOf(learned, suspended)))
        runCurrent()
        vm.viewSkill(learned)
        vm.restoreSkill("r1", suspended); runCurrent()
        assertEquals(listOf("view:d1", "restore:wechat-msg"), learnCalls)
        assertEquals(listOf(learned), vm.ui.value.exchanges.single().skillNotes)
    }

    // Pressing the button in a dialogue without a task will never fall into the task of other dialogues;
    // when pressed, it will bring the dialogue on the screen at that moment.
    @Test fun buttonsInConversationWithoutRunGoNowhere() = runTest {
        val vm = parallelVm(ConversationStore(tmp.newFolder()))
        active.value = listOf("a")
        show("a", TaskState.WaitingInput("哪个?", null))
        vm.switchTo("b")
        calls.clear()
        vm.cancel(); vm.resume(); vm.answer("第一个"); vm.approve(7)
        assertTrue(calls.isEmpty())

        vm.switchTo("a")
        calls.clear()
        vm.resume()
        vm.switchTo("b")
        assertEquals(listOf("resume@a"), calls.filter { "@" in it })
    }

    // The takeover progress is only related to the task of the dialogue on the screen; it is cleared
    // when switching to the dialogue without the task.
    @Test fun takeoverStageFollowsScreenConversation() = runTest {
        val vm = parallelVm(ConversationStore(tmp.newFolder()))
        active.value = listOf("a")
        vm.switchTo("a")
        show("a", TaskState.WaitingTakeover("login", "请登录"))
        stage.value = Takeover.Stage.HandedOver
        runCurrent()
        assertEquals(Takeover.Stage.HandedOver, vm.ui.value.takeoverStage)
        vm.switchTo("b")
        assertEquals(Takeover.Stage.None, vm.ui.value.takeoverStage)
        stage.value = Takeover.Stage.Waiting
        runCurrent()
        assertEquals(Takeover.Stage.None, vm.ui.value.takeoverStage)
        vm.newConversation()
        assertEquals(Takeover.Stage.None, vm.ui.value.takeoverStage)
    }

    // The exported files and success criteria are unique to each task: when the service displays tasks
    // from other conversations, it will not be hung on the card of this conversation on the screen.
    @Test fun sharedFilesFollowScreenConversation() = runTest {
        val files = MutableStateFlow<List<com.meenai.mina.chat.SharedFile>>(emptyList())
        val vm = parallelVm(ConversationStore(tmp.newFolder()), files)
        active.value = listOf("a", "b")
        vm.switchTo("a")
        show("a", TaskState.Running(1, "整理"))
        val mine = com.meenai.mina.chat.SharedFile("a.csv", 1, "Download/Harness/a.csv", "content://media/1")
        files.value = listOf(mine)
        runCurrent()
        assertEquals(listOf(mine), vm.ui.value.exchanges.last().files)
        show("b", TaskState.Running(1, "查快递"))
        files.value = listOf(com.meenai.mina.chat.SharedFile("b.csv", 1, "Download/Harness/b.csv", "content://media/2"))
        runCurrent()
        assertEquals(listOf(mine), vm.ui.value.exchanges.last().files)
    }

    // Each conversation card has its own run number, regardless of which task the service is displaying
    // at the time.
    @Test fun endedCardUsesItsConversationsRunId() = runTest {
        val ended = MutableStateFlow<Map<String, String?>>(emptyMap())
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), serviceBusy = MutableStateFlow(true),
            endedRunId = MutableStateFlow("r-shown-other"), endedRunIds = ended,
            parallelFlag = { true }, shownTask = shown, activeConversations = active,
        ).also { runCurrent() }
        vm.switchTo("a")
        assertTrue(vm.send("设置版本号", ok))
        show("a", TaskState.Running(1, "打开设置"))
        // The other dialogue tasks are over and are only recorded in its own name.
        ended.value = mapOf("b" to "r-b")
        runCurrent()
        assertNull(vm.ui.value.exchanges.last().runId)
        ended.value = mapOf("b" to "r-b", "a" to "r-a")
        show("a", TaskState.Succeeded("12"))
        assertEquals("r-a", vm.ui.value.exchanges.last().runId)
    }

    // When running, just hang up the running number ("Running Process" can be entered on the spot),
    // click on the dialog where the card is located, do not use the one in the service display
    @Test fun runningCardUsesItsOwnConversationsLiveRunId() = runTest {
        val live = mutableMapOf<String, String>()
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), serviceBusy = MutableStateFlow(true),
            endedRunIds = MutableStateFlow(mapOf("a" to "r-a")), liveRunId = { live[it] },
            parallelFlag = { true }, shownTask = shown, activeConversations = active,
        ).also { runCurrent() }
        vm.switchTo("a")
        assertTrue(vm.send("设置版本号", ok))
        live["b"] = "r-b"
        show("a", TaskState.Running(0, "正在准备"))
        assertNull("这个对话的任务还没分到编号:不拿别的对话的", vm.ui.value.exchanges.last().runId)
        live["a"] = "r-a"
        show("a", TaskState.Running(1, "打开设置"))
        assertEquals("r-a", vm.ui.value.exchanges.last().runId)
        show("a", TaskState.Succeeded("12"))
        assertEquals("r-a", vm.ui.value.exchanges.last().runId)
    }

    // The switch is turned on after the interface is built: reread when starting the task without
    // rebuilding the interface.
    @Test fun parallelFlagReadWhenStarting() = runTest {
        var flag = false
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            parallelFlag = { flag }, shownTask = shown, activeConversations = active,
        ).also { runCurrent() }
        flag = true
        assertTrue(vm.send("设置版本号", ok))
        assertEquals("start:设置版本号", calls.last())
        val first = vm.ui.value.conversationId
        active.value = listOf(first!!)
        runCurrent()
        // A second conversation can be opened in a new conversation.
        vm.newConversation()
        assertTrue(vm.ui.value.conversationId != first)
        assertTrue(vm.send("新建闹钟", ok))
        assertEquals("start:新建闹钟", calls.last())
    }

    // Also reread switch when changing dialogues
    @Test fun parallelFlagReadWhenSwitching() = runTest {
        var flag = false
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            parallelFlag = { flag }, shownTask = shown, activeConversations = active,
        ).also { runCurrent() }
        active.value = listOf("b")
        runCurrent()
        flag = true
        vm.openConversation("b")
        // In parallel, switching to a conversation with a task running will cause the service to display
        // it
        assertTrue("show:b" in calls)
    }

    // Whether there is a screen or not is also unique to each task: when the task being displayed is
    // another dialogue task, this card will not be entered.
    @Test fun otherConversationsScreenStaysOut() = runTest {
        val screen = MutableStateFlow(true)
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), screenOpen = screen,
            parallelFlag = { true }, shownTask = shown, activeConversations = active,
        ).also { runCurrent() }
        vm.switchTo("a")
        // The screen for other dialogue tasks is open: the new task has no screen yet and no
        // picture-in-picture appears.
        assertTrue(vm.send("设置版本号", ok))
        assertFalse(vm.ui.value.pip.visible)
        show("b", TaskState.Running(1, "看屏幕"))
        runCurrent()
        show("a", TaskState.Running(1, "打开设置"))
        runCurrent()
    }

    // The interface thread on the real machine is scheduled immediately: as soon as the service changes
    // display, the status is sent in on the spot. When you switch away and then switch back to a
    // conversation that is still running, the original words of the questions and answers, the steps
    // (including those during the cut), and the files must be there, and you cannot start another empty
    // original sentence.
    @Test fun switchingBackRestoresTheRunningExchange() = runTest {
        val runStates = mapOf("a" to MutableStateFlow<TaskState>(TaskState.Running(0, "")), "b" to MutableStateFlow<TaskState>(TaskState.Running(0, "")))
        val fileA = com.meenai.mina.chat.SharedFile("a.csv", 1, "Download/Harness/a.csv", "content://media/1")
        val runFiles = mapOf("a" to listOf(fileA), "b" to emptyList())
        val goals = mapOf("a" to "第一个任务", "b" to "第二个任务")
        val files = MutableStateFlow<List<com.meenai.mina.chat.SharedFile>>(emptyList())
        // The same as the service: when changing the display, copy the file first, and then copy the
        // status of the conversation number.
        val immediate = object : TaskActions by actions {
            override fun show(conversationId: String) {
                files.value = runFiles.getValue(conversationId)
                shown.value = conversationId to runStates.getValue(conversationId).value
            }
        }
        fun publish(c: String, s: TaskState) {
            runStates.getValue(c).value = s
            if (shown.value?.first == c) shown.value = c to s
        }
        val ui = kotlinx.coroutines.CoroutineScope(backgroundScope.coroutineContext + kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))
        val vm = ChatViewModel(
            ui, state, stage, immediate, ConversationStore(tmp.newFolder()), serviceBusy = MutableStateFlow(true),
            parallelFlag = { true }, shownTask = shown, activeConversations = active, runGoal = goals::get, runState = runStates::get,
            sharedFiles = files,
        )
        vm.switchTo("a")
        assertTrue(vm.send("第一个任务", ok))
        active.value = listOf("a")
        immediate.show("a")
        publish("a", TaskState.Running(1, "打开设置", "phone_launch_app"))

        // Cut to b, which has no task, and start the second task: stop a's questions and answers, and a
        // continues to run.
        vm.switchTo("b")
        assertTrue(vm.send("第二个任务", ok))
        active.value = listOf("a", "b")
        immediate.show("b")
        publish("b", TaskState.Running(1, "看屏幕"))
        publish("a", TaskState.Running(2, "点版本号", "phone_tap", reason = "点版本号"))
        assertEquals("第二个任务", vm.ui.value.exchanges.last().goal)

        vm.switchTo("a")
        val ex = vm.ui.value.exchanges.last()
        assertEquals("第一个任务", ex.goal)
        assertEquals(listOf(fileA), ex.files)
        // The progress of a during the cut is still remembered
        assertEquals("点版本号", ex.line)
        assertEquals(ExchangeStatus.Working, ex.status)
        assertEquals(1, vm.ui.value.exchanges.size)
    }

    @Test fun withdrawRemovesOnlyTheUnprocessedMessage() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.send("撤回这句", ok)
        vm.send("留下这句", ok)
        val id = steerIds.first()
        assertTrue(vm.withdrawSteer(id))
        assertEquals(listOf(SteerState.Withdrawn, SteerState.Pending), vm.ui.value.exchanges.single().steers.map { it.state })
        assertFalse(vm.withdrawSteer(id))
        assertEquals(1, calls.count { it == "withdraw:$id" })
        assertEquals("留下这句", vm.ui.value.exchanges.single().steers.last().text)
    }

    @Test fun editingRestoresQuoteAndTextWithoutOverwritingAnotherQuote() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        val quote = QuoteMessage.Quote(QuoteMessage.From.Mina, "明天有雨")
        vm.quote(quote.from, quote.text)
        vm.send("改这句话", ok)
        val id = steerIds.single()
        vm.quote(QuoteMessage.From.You, "正在写的引用")
        assertNull(vm.editSteer(id))
        vm.clearQuote()
        assertEquals("改这句话", vm.editSteer(id))
        assertEquals(quote, vm.ui.value.quote)
        assertEquals(SteerState.Withdrawn, vm.ui.value.exchanges.single().steers.single().state)
        vm.send("改好的话", ok)
        assertEquals("steer:${QuoteMessage.compose(quote, "改好的话")}", calls.last())
    }

    @Test fun failedWithdrawalDoesNotPretendTheModelMissedTheMessage() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.send("模型刚取走", ok)
        val id = steerIds.single()
        canWithdraw = false
        assertNull(vm.editSteer(id))
        assertEquals(SteerState.Pending, vm.ui.value.exchanges.single().steers.single().state)
        assertNull(vm.ui.value.quote)
        steerFlow.value = listOf(SteerNote(id, "模型刚取走", SteerState.Joined))
        runCurrent()
        assertFalse(vm.withdrawSteer(id))
        assertEquals(SteerState.Joined, vm.ui.value.exchanges.single().steers.single().state)
    }

    // If added by computer, it will also be displayed in the conversation, but if you press and hold it
    // for a long time, you will not be able to withdraw or edit it. If you directly call it, you will
    // not be able to withdraw it. You will not ask for service.
    @Test fun remoteSteersOfferNoWithdrawOrEdit() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        vm.send("手机插话", ok)
        val local = steerIds.single()
        steerFlow.value = listOf(SteerNote(local, "手机插话", SteerState.Received), SteerNote("r", "电脑追加", SteerState.Received, remote = true))
        runCurrent()
        val exchanges = vm.ui.value.exchanges
        assertEquals(listOf(false, true), exchanges.single().steers.map { it.remote })
        assertNull(withdrawableSteer(exchanges, "m0-r"))
        assertEquals(local, withdrawableSteer(exchanges, "m0-$local")?.id)
        assertFalse(vm.withdrawSteer("r"))
        assertNull(vm.editSteer("r"))
        assertFalse(calls.any { it == "withdraw:r" })
        assertEquals(SteerState.Received, vm.ui.value.exchanges.single().steers.last().state)
    }

    // The floating capsule carries the screen and the browser page carries the page description. The
    // inserted words are also displayed in the dialog: retrieving the edit will put the marked original
    // text into the input box, which will only be withdrawn, not edited.
    @Test fun steersWithAScreenOrAPageCanOnlyBeWithdrawn() = runTest {
        val vm = vm()
        vm.send("当前任务", ok)
        emit(TaskState.Running(1, "在做"))
        val screen = UserScreenMessage.compose("s1", "这个按钮是干什么的")
        val page = AttachedPage.compose(AttachedPage.Page("https://example.com/a", "标题", "选中的一段"), "这段什么意思")
        steerFlow.value = listOf(SteerNote("s", screen, SteerState.Received), SteerNote("p", page, SteerState.Received))
        runCurrent()
        assertEquals("s", withdrawableSteer(vm.ui.value.exchanges, "m0-s")?.id)
        assertFalse(editableSteer(screen))
        assertFalse(editableSteer(page))
        assertNull(vm.editSteer("s"))
        assertNull(vm.editSteer("p"))
        assertFalse(calls.any { it.startsWith("withdraw:") })
        assertTrue(vm.withdrawSteer("p"))
        assertEquals(listOf(SteerState.Received, SteerState.Withdrawn), vm.ui.value.exchanges.single().steers.map { it.state })
        // Only words and quotes can still be edited.
        assertTrue(editableSteer("改这句话"))
        assertTrue(editableSteer(QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.Mina, "明天有雨"), "改这句话")))
    }

    @Test fun withdrawingBeforeTheTaskStartsPreventsLaterDelivery() = runTest {
        val busy = MutableStateFlow(true)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), serviceBusy = busy)
        runCurrent()
        vm.send("当前任务", ok)
        vm.send("别发这句", ok)
        val id = vm.ui.value.exchanges.single().steers.single().id
        assertTrue(vm.withdrawSteer(id))
        busy.value = false
        runCurrent()
        assertEquals(listOf("start:当前任务"), calls)
        assertEquals(SteerState.Withdrawn, vm.ui.value.exchanges.single().steers.single().state)
    }

    // Tasks can still be sent in the input box while running: if sent, it will be hung under this
    // question and answer and handed over to the current task; the destination will be reported back by
    // the service
    @Test fun messageWhileRunningSteersTheTask() = runTest {
        val vm = vm()
        vm.send("订明天的会议室", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)

        assertTrue(vm.send("改成后天", ok))
        assertEquals("steer:改成后天", calls.last())
        var ex = vm.ui.value.exchanges.single()
        assertEquals(listOf("改成后天"), ex.steers.map { it.text })
        assertNull(steerLabel(ex.steers.single().state))
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)

        // The model got this sentence before the next step
        steerFlow.value = listOf(SteerNote(steerIds.single(), "改成后天", SteerState.Joined))
        runCurrent()
        assertEquals("Added to the current task", steerLabel(vm.ui.value.exchanges.single().steers.single().state))

        // Say another sentence, and the task ends: This sentence is not handed over to the task, as the
        // next thing
        assertTrue(vm.send("顺便订午饭", ok))
        emit(TaskState.Succeeded("订好了"))
        ex = vm.ui.value.exchanges.single()
        assertEquals(listOf(SteerState.Joined, SteerState.Next), ex.steers.map { it.state })
        assertEquals("Queued as the next task", steerLabel(ex.steers.last().state))
        assertEquals(1, vm.ui.value.exchanges.size)
    }

    // The interruption interrupts the reply being written: the paragraph is reported back along with the
    // interruption and is hung on the interruption; the status will not be lost after the interruption.
    @Test fun interruptedReplyStaysWithItsSteer() = runTest {
        val vm = vm()
        vm.send("查今天的天气", ok)
        emit(TaskState.Running(1, "", preview = "今天晴,最高"))
        assertTrue(vm.send("改成查明天", ok))

        steerFlow.value = listOf(SteerNote(steerIds.single(), "改成查明天", SteerState.Joined, interrupted = "今天晴,最高"))
        emit(TaskState.Running(2, ""))
        val note = vm.ui.value.exchanges.single().steers.single()
        assertEquals("今天晴,最高", note.interrupted)
        assertEquals(SteerState.Joined, note.state)

        steerFlow.value = listOf(SteerNote(steerIds.single(), "改成查明天", SteerState.Joined))
        runCurrent()
        assertEquals("今天晴,最高", vm.ui.value.exchanges.single().steers.single().interrupted)
    }

    // As soon as the task is received in the inbox, there will be a response of "Received, next step";
    // when the task is completed, it will be treated as the next thing as it has not yet been sent.
    @Test fun receivedSteerIsShownAtOnceAndBecomesNextIfTheRunEnds() = runTest {
        val vm = vm()
        vm.send("订明天的会议室", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        assertTrue(vm.send("改成后天", ok))
        steerFlow.value = listOf(SteerNote(steerIds.single(), "改成后天", SteerState.Received))
        runCurrent()
        val note = vm.ui.value.exchanges.single().steers.single()
        assertEquals(SteerState.Received, note.state)
        assertEquals("Got it — next step", steerLabel(note.state))
        emit(TaskState.Succeeded("订好了"))
        assertEquals(SteerState.Next, vm.ui.value.exchanges.single().steers.single().state)
    }

    @Test fun stopCancelsReceivedSteersToo() = runTest {
        val vm = vm()
        vm.send("订明天的会议室", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        assertTrue(vm.send("改成后天", ok))
        steerFlow.value = listOf(SteerNote(steerIds.single(), "改成后天", SteerState.Received))
        runCurrent()
        vm.cancel()
        assertEquals(SteerState.Cancelled, vm.ui.value.exchanges.single().steers.single().state)
    }

    // After finishing the service, the service will be reported back to: Still changing to the same
    // question and answer
    @Test fun lateSteerOutcomeStillLandsOnItsExchange() = runTest {
        val vm = vm()
        vm.send("查快递", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        vm.send("只看顺丰的", ok)
        emit(TaskState.Failed("网络断了", FailKind.Environment))
        steerFlow.value = listOf(SteerNote(steerIds.single(), "只看顺丰的", SteerState.Next))
        runCurrent()
        assertEquals(SteerState.Next, vm.ui.value.exchanges.single().steers.single().state)
    }

    // If the action fails, it will only say that it was unsuccessful, and the error code will not be
    // shown to others.
    @Test fun failedActionHidesErrorCode() = runTest {
        val vm = vm()
        vm.send("发消息", ok)
        emit(TaskState.Running(1, "", "phone_tap"))
        did(1, "act", "phone_tap", "com.chat", "ref_expired", failed = true)
        val row = vm.ui.value.exchanges.single().steps.single()
        assertEquals("Tap · 聊天", row.title)
        assertEquals("Failed", row.subtitle)
        assertFalse(row.toString().contains("ref_expired"))
    }

    // The task of the original words is still waiting for the service to end. For the last interrupted
    // words: start the task of the original words first, and then hand over the interruption to it.
    @Test fun steerDuringPendingStartIsSentAfterTheStart() = runTest {
        val busy = MutableStateFlow(true)
        val vm = ChatViewModel(backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()), busy, steers = steerFlow)
        runCurrent()
        assertTrue(vm.send("订会议室", ok))
        assertTrue(vm.send("要大一点的", ok))
        runCurrent()
        assertTrue(calls.isEmpty())
        assertEquals(listOf("要大一点的"), vm.ui.value.exchanges.single().steers.map { it.text })
        busy.value = false
        runCurrent()
        assertEquals(listOf("start:订会议室", "steer:要大一点的"), calls)
    }

    // Interruptions that have not yet been handed over to the task when stopped will be invalidated and
    // marked as canceled; when stopped while waiting for service, the interruption will not be sent.
    @Test fun stopCancelsPendingSteers() = runTest {
        val vm = vm()
        vm.send("查快递", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        vm.send("只看顺丰的", ok)
        vm.cancel()
        assertEquals("cancel", calls.last())
        assertEquals(SteerState.Cancelled, vm.ui.value.exchanges.single().steers.single().state)
        assertEquals("Cancelled", steerLabel(SteerState.Cancelled))
        emit(TaskState.Cancelled)
        assertEquals(SteerState.Cancelled, vm.ui.value.exchanges.single().steers.single().state)

        val busy = MutableStateFlow(true)
        calls.clear()
        val vm2 = ChatViewModel(backgroundScope, MutableStateFlow(TaskState.Idle), stage, actions, ConversationStore(tmp.newFolder()), busy)
        runCurrent()
        vm2.send("订会议室", ok)
        vm2.send("要大一点的", ok)
        vm2.cancel()
        busy.value = false
        runCurrent()
        assertTrue(calls.isEmpty())
        assertEquals(SteerState.Cancelled, vm2.ui.value.exchanges.single().steers.single().state)
    }

    // Interjecting may be the next thing: passing the same check as opening a new task
    @Test fun steerIsCheckedLikeANewTask() = runTest {
        val vm = vm()
        vm.send("查快递", ok)
        emit(TaskState.Running(1, "", "phone_launch_app"))
        assertFalse(vm.send("只看顺丰的", Prerequisites.check(ready, hasMainModel = false)))
        assertEquals(listOf(Problem.NoModel), vm.ui.value.blocked)
        assertTrue(vm.ui.value.exchanges.single().steers.isEmpty())
    }

    // Quotes are sent with the next sentence: new tasks and interruptions are included, and cleared
    // after sending; only quotes are not sent when no words are written.
    @Test fun quoteGoesWithTheNextMessage() = runTest {
        val vm = vm()
        vm.quote(QuoteMessage.From.Mina, "  明天有小雨 ")
        assertEquals(QuoteMessage.Quote(QuoteMessage.From.Mina, "明天有小雨"), vm.ui.value.quote)
        assertFalse(vm.send("  ", ok))
        assertTrue(calls.isEmpty())

        assertTrue(vm.send("那要带伞吗", ok))
        val goal = "> [Quoting your earlier reply]\n> 明天有小雨\n\n那要带伞吗"
        assertEquals(listOf("start:$goal"), calls)
        assertEquals(goal, vm.ui.value.exchanges.single().goal)
        assertNull(vm.ui.value.quote)

        emit(TaskState.Running(1, "", "phone_launch_app"))
        vm.quote(QuoteMessage.From.You, "查天气")
        assertTrue(vm.send("改成后天", ok))
        val steer = "> [Quoting my earlier message]\n> 查天气\n\n改成后天"
        assertEquals("steer:$steer", calls.last())
        assertEquals(steer, vm.ui.value.exchanges.single().steers.single().text)
        assertNull(vm.ui.value.quote)

        // Remove quotes: Just send words as usual
        vm.quote(QuoteMessage.From.Mina, "x")
        vm.clearQuote()
        vm.send("再快一点", ok)
        assertEquals("steer:再快一点", calls.last())
    }

    // The reference only belongs to this dialogue on the screen: clear it when changing dialogues or
    // opening new dialogues.
    @Test fun quoteIsDroppedWhenTheConversationChanges() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        store.append(id, Turn("查天气", "晴", "succeeded"))
        val vm = vm(store)
        vm.quote(QuoteMessage.From.Mina, "晴")
        vm.openConversation(id)
        assertNull(vm.ui.value.quote)
        vm.quote(QuoteMessage.From.Mina, "晴")
        vm.newConversation()
        assertNull(vm.ui.value.quote)
    }

    // The saved words with quotes will be returned as they are when reopened (the interface will draw
    // the quote accordingly); the old words will not be quoted.
    @Test fun quotedTurnsSurviveReopening() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        val quoted = QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.Mina, "晴"), "那后天呢")
        store.append(id, Turn("查天气", "晴", "succeeded"))
        store.append(id, Turn(quoted, "多云", "succeeded", steers = listOf(QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.You, "查天气"), "顺便看风"))))
        val vm = vm(store)
        vm.openConversation(id)
        val (old, new) = vm.ui.value.exchanges
        assertNull(QuoteMessage.parse(old.goal).quote)
        assertEquals("查天气", QuoteMessage.parse(old.goal).words)
        assertEquals(QuoteMessage.Quote(QuoteMessage.From.Mina, "晴"), QuoteMessage.parse(new.goal).quote)
        assertEquals("那后天呢", QuoteMessage.parse(new.goal).words)
        assertEquals("顺便看风", QuoteMessage.parse(new.steers.single().text).words)
        assertEquals("查天气", ChatCopy.title(vm.ui.value.exchanges))
    }

    // The interruptions assigned to the task are saved with that round: the dialogue will still be
    // displayed under that round after restarting.
    @Test fun joinedSteersSurviveReopening() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        store.append(id, Turn("订会议室", "订好了", "succeeded", steers = listOf("要大一点的")))
        val vm = vm(store)
        vm.openConversation(id)
        val ex = vm.ui.value.exchanges.single()
        assertEquals(listOf("要大一点的"), ex.steers.map { it.text })
        assertEquals("Added to the current task", steerLabel(ex.steers.single().state))
    }

    // The reply interrupted by the interruption is also saved with that round, and will still be
    // attached to that interruption when it is restarted; the reply that was not interrupted is not.
    @Test fun interruptedRepliesSurviveReopening() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        store.append(id, Turn("查天气", "明天多云", "succeeded", steers = listOf("改成查明天", "顺便看看后天"), interruptedReplies = listOf("今天晴", "")))
        val vm = vm(store)
        vm.openConversation(id)
        assertEquals(listOf("今天晴", null), vm.ui.value.exchanges.single().steers.map { it.interrupted })
    }

    // Replies that have been finished before interrupting: report back with the direction of the
    // interrupt, and the status will not be lost after the interrupt is saved; after saving and
    // re-opening, the interrupt will still hang on
    @Test fun finishedReplyStaysWithItsSteer() = runTest {
        val vm = vm()
        vm.send("查今天的天气", ok)
        emit(TaskState.Running(1, "", preview = "今天晴,最高 25 度。"))
        assertTrue(vm.send("改成查明天", ok))
        steerFlow.value = listOf(SteerNote(steerIds.single(), "改成查明天", SteerState.Joined, finished = "今天晴,最高 25 度。"))
        emit(TaskState.Running(2, ""))
        steerFlow.value = listOf(SteerNote(steerIds.single(), "改成查明天", SteerState.Joined))
        runCurrent()
        assertEquals("今天晴,最高 25 度。", vm.ui.value.exchanges.single().steers.single().finished)
    }

    @Test fun finishedRepliesSurviveReopening() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val id = store.newConversation()
        store.append(id, Turn("查天气", "明天多云", "succeeded", steers = listOf("改成查明天", "顺便看看后天"), finishedReplies = listOf("今天晴", "")))
        val vm = vm(store)
        vm.openConversation(id)
        assertEquals(listOf("今天晴", null), vm.ui.value.exchanges.single().steers.map { it.finished })
    }

    // ---- Tasks started elsewhere (suspended capsules, timed tasks) and rounds saved ----

    /** The switch is off, and the service status carries the session number ([shown]). */
    private fun TestScope.taggedVm(store: ConversationStore, endedRunId: MutableStateFlow<String?> = MutableStateFlow(null)) = ChatViewModel(
        backgroundScope, state, stage, actions, store, endedRunId = endedRunId,
        shownTask = shown, activeConversations = active, runGoal = { if (it == "a") "胶囊里说的" else null }, steers = steerFlow, activities = acts,
    ).also { runCurrent() }

    /** The tasks displayed in the service have changed status: the numbered copy is written first, the same as in the service. */
    private fun TestScope.serve(conversationId: String, s: TaskState) {
        shown.value = conversationId to s
        state.value = s
        runCurrent()
    }

    // After running elsewhere, save the round of this dialogue on the screen: make it up on the spot,
    // only one
    @Test fun turnSavedElsewhereAppearsOnce() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
        val vm = vm(store)
        vm.openConversation("a")
        store.append("a", Turn("订会议室", "订好了", "succeeded", runId = "r2", steers = listOf("要大一点的")))
        runCurrent()
        assertEquals(listOf("查天气", "订会议室"), vm.ui.value.exchanges.map { it.goal })
        assertEquals(listOf("要大一点的"), vm.ui.value.exchanges.last().steers.map { it.text })
        // The round I revised later (added memory tips): reread, no more than one sentence
        store.updateTurn("a", "r2") { it.copy(outcome = "订好了 3 楼") }
        runCurrent()
        assertEquals(2, vm.ui.value.exchanges.size)
        assertEquals("订好了 3 楼", (vm.ui.value.exchanges.last().status as ExchangeStatus.Done).answer)
    }

    // The turn saved into another conversation will not keep this conversation on the screen.
    @Test fun turnSavedInAnotherConversationLeavesScreenAlone() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
        val vm = vm(store)
        vm.openConversation("a")
        val before = vm.ui.value
        store.append("b", Turn("订会议室", "订好了", "succeeded", runId = "r2"))
        runCurrent()
        assertEquals(before, vm.ui.value)
    }

    // The dialogue here about the end of the task and the round of saving and entering: regardless of
    // saving first or reporting the end first, there is only one step. Keep the steps here.
    @Test fun ownRunSavedIsNotDuplicated() = runTest {
        val ended = MutableStateFlow<String?>(null)
        for (savedFirst in listOf(false, true)) {
            val store = ConversationStore(tmp.newFolder())
            val vm = taggedVm(store, ended)
            assertTrue(vm.send("查快递", ok))
            val id = vm.ui.value.conversationId!!
            serve(id, TaskState.Running(1, "", "phone_launch_app"))
            did(1, "act", "phone_launch_app", "com.chat")
            val turn = Turn("查快递", "到了", "succeeded", runId = "r-$savedFirst")
            ended.value = "r-$savedFirst"
            if (savedFirst) { store.append(id, turn); runCurrent() }
            serve(id, TaskState.Succeeded("到了"))
            if (!savedFirst) { store.append(id, turn); runCurrent() }
            val ex = vm.ui.value.exchanges.single()
            assertEquals("r-$savedFirst", ex.runId)
            assertTrue(ex.live)
            assertEquals(1, ex.steps.size)
            assertEquals(ComposerMode.Idle, vm.ui.value.composer)
            acts.value = emptyList()
        }
    }

    // Tasks started elsewhere in this conversation on the screen: are then displayed. The original words
    // are the task's own, and the words inserted elsewhere are also hung up; after the end is saved,
    // there is only one
    @Test fun runStartedElsewhereInThisConversationIsFollowed() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
        val ended = MutableStateFlow<String?>(null)
        val vm = taggedVm(store, ended)
        vm.openConversation("a")
        active.value = listOf("a")
        serve("a", TaskState.Running(1, "打开设置"))
        assertEquals(listOf("查天气", "胶囊里说的"), vm.ui.value.exchanges.map { it.goal })
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.last().status)
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)
        steerFlow.value = listOf(SteerNote("m1", "只看顺丰的", SteerState.Joined))
        runCurrent()
        assertEquals(listOf("只看顺丰的"), vm.ui.value.exchanges.last().steers.map { it.text })
        ended.value = "r2"
        serve("a", TaskState.Succeeded("好了"))
        store.append("a", Turn("胶囊里说的", "好了", "succeeded", runId = "r2", steers = listOf("只看顺丰的")))
        runCurrent()
        assertEquals(listOf("查天气", "胶囊里说的"), vm.ui.value.exchanges.map { it.goal })
        assertEquals(listOf("只看顺丰的"), vm.ui.value.exchanges.last().steers.map { it.text })
    }

    // Open (or return to) a conversation that has a task running and is not being followed here: pick up
    // on the spot, without waiting for the next status change
    @Test fun openingOrRefreshingAttachesActiveRun() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val vm = taggedVm(store)
        active.value = listOf("a")
        shown.value = "a" to TaskState.Running(2, "看屏幕")
        vm.openConversation("a")
        assertTrue("show:a" in calls)
        assertEquals("胶囊里说的", vm.ui.value.exchanges.single().goal)
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.single().status)

        // When you return to the front desk, answer the call again: Already following, don’t start
        // another one.
        vm.refresh()
        assertEquals(1, vm.ui.value.exchanges.size)
    }

    // When the switch is turned off, the tasks of other conversations will not be connected to this
    // conversation on the screen; when the task being followed here is replaced and displayed, the
    // service display will return.
    @Test fun serialRunInAnotherConversationIsNotAttached() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
        val vm = taggedVm(store)
        vm.openConversation("a")
        active.value = listOf("b")
        serve("b", TaskState.Running(1, "打开设置"))
        assertEquals(listOf("查天气"), vm.ui.value.exchanges.map { it.goal })
        assertEquals(ComposerMode.Idle, vm.ui.value.composer)
        serve("b", TaskState.Succeeded("好了"))
        assertEquals(1, vm.ui.value.exchanges.size)

        assertTrue(vm.send("查快递", ok))
        active.value = listOf("a")
        serve("a", TaskState.Running(1, "", "phone_launch_app"))
        calls.clear()
        // The scheduled task is scheduled in dialog b, and the service displays it
        active.value = listOf("a", "b")
        serve("b", TaskState.Held(GateReason.queued("排队中:前面还有 1 个任务")))
        assertEquals(listOf("show:a"), calls)
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.last().status)
    }

    // Quests received when there is no dialogue: are displayed together with the saved rounds of that
    // dialogue, and those saved later will also match.
    @Test fun adoptingARunLoadsItsConversation() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
        val vm = taggedVm(store)
        active.value = listOf("a")
        serve("a", TaskState.Running(1, "打开设置"))
        assertEquals("a", vm.ui.value.conversationId)
        assertEquals(listOf("查天气", "胶囊里说的"), vm.ui.value.exchanges.map { it.goal })
    }

    // ---- Tasks started elsewhere are based on the actual order in the service: they are displayed
    // first (status is sent), and then registered in the task list ----

    private val goals = mutableMapOf<String, String>()
    private val endedRun = MutableStateFlow<String?>(null)

    private fun TestScope.registryVm(store: ConversationStore, parallel: Boolean) = ChatViewModel(
        backgroundScope, state, stage, actions, store, appLabel = { if (it == "com.chat") "聊天" else it }, endedRunId = endedRun, parallelFlag = { parallel },
        shownTask = shown, activeConversations = active, runGoal = goals::get, steers = steerFlow, activities = acts,
    ).also { runCurrent() }

    /** The service starts a task: it is first displayed (numbered status, status), and then registered in the task list (dialogue, original words). */
    private fun TestScope.startedElsewhere(conversationId: String, goal: String) {
        val s = TaskState.Running(0, "正在准备")
        shown.value = conversationId to s
        state.value = s
        runCurrent()
        goals[conversationId] = goal
        active.value = active.value + conversationId
        runCurrent()
    }

    // If the words posted elsewhere (suspended capsule) in this conversation on the screen are displayed
    // during the run, the words and steps are the same as those posted here.
    @Test fun runStartedElsewhereShowsMessageWhileRunning() = runTest {
        for (parallel in listOf(false, true)) {
            val store = ConversationStore(tmp.newFolder())
            store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
            val vm = registryVm(store, parallel)
            vm.openConversation("a")
            startedElsewhere("a", "胶囊里说的")
            assertEquals("parallel=$parallel", listOf("查天气", "胶囊里说的"), vm.ui.value.exchanges.map { it.goal })
            assertEquals(ComposerMode.Busy, vm.ui.value.composer)
            shown.value = "a" to TaskState.Running(1, "打开设置", "phone_launch_app")
            state.value = shown.value!!.second
            runCurrent()
            did(1, "act", "phone_launch_app", "com.chat")
            assertEquals(listOf("Open app · 聊天"), vm.ui.value.exchanges.last().steps.map { it.text })
            // Go back to the front desk and pick it up again: no new one
            vm.refresh()
            assertEquals(2, vm.ui.value.exchanges.size)
            assertEquals("胶囊里说的", vm.ui.value.exchanges.last().goal)
            endedRun.value = "r2-$parallel"
            serve("a", TaskState.Succeeded("好了"))
            store.append("a", Turn("胶囊里说的", "好了", "succeeded", runId = "r2-$parallel"))
            runCurrent()
            assertEquals(listOf("查天气", "胶囊里说的"), vm.ui.value.exchanges.map { it.goal })
            active.value = emptyList(); acts.value = emptyList(); goals.clear(); calls.clear()
        }
    }

    // Taking over a task started elsewhere when there is no dialogue on the screen: that sentence is
    // also there
    @Test fun adoptedRunShowsItsMessage() = runTest {
        val vm = registryVm(ConversationStore(tmp.newFolder()), parallel = false)
        startedElsewhere("a", "胶囊里说的")
        assertEquals("a", vm.ui.value.conversationId)
        assertEquals(listOf("胶囊里说的"), vm.ui.value.exchanges.map { it.goal })
    }

    // Tasks started elsewhere in a new conversation: the sidebar is listed as in progress; open it
    // (return to Mina) and you will see that sentence and the running status
    @Test fun runInNewConversationElsewhereOpensWithMessage() = runTest {
        for (parallel in listOf(false, true)) {
            val store = ConversationStore(tmp.newFolder())
            store.append("a", Turn("查天气", "晴", "succeeded", runId = "r1"))
            val vm = registryVm(store, parallel)
            vm.openConversation("a")
            startedElsewhere("n", "新对话里说的")
            assertEquals(listOf("查天气"), vm.ui.value.exchanges.map { it.goal })
            assertEquals(listOf("n"), vm.otherRunning.value)
            val sections = drawerSections(store.list(), CurrentConversation("a", false, "查天气", ""), vm.otherRunning.value, vm::runningGoal, vm.canSwitch(), 0L)
            assertEquals(listOf("新对话里说的"), sections.running.map { it.title })
            vm.openConversation("n")
            assertEquals("n", vm.ui.value.conversationId)
            assertEquals(listOf("新对话里说的"), vm.ui.value.exchanges.map { it.goal })
            assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.single().status)
            serve("n", TaskState.Succeeded("好了"))
            active.value = emptyList(); goals.clear(); calls.clear()
        }
    }

    // Answers that are growing are ranked after the comments that have been given to the model and
    // before the comments that have not been given to the model.
    @Test fun draftSitsBetweenJoinedAndWaitingSteers() {
        val joined = SteerNote("a", "改成明天", SteerState.Joined)
        val received = SteerNote("b", "简短点", SteerState.Received)
        val pending = SteerNote("c", "再简单点", SteerState.Pending)
        assertEquals(listOf(joined) to listOf(received, pending), steersAroundDraft(listOf(joined, received, pending)))
        assertEquals(listOf(joined) to emptyList<SteerNote>(), steersAroundDraft(listOf(joined)))
        assertEquals(emptyList<SteerNote>() to listOf(received), steersAroundDraft(listOf(received)))
    }
    /** When another task is released from the app, it is still working: if the progress line says who it is waiting for, it does not count as a pause or for you to deal with. */
    @Test fun waitingForAnotherTasksAppIsStillWorking() = runTest {
        val vm = vm()
        vm.send("给张三发消息", ok)
        emit(TaskState.Running(2, "打开聊天", tool = "phone_launch_app", progress = com.meenai.mina.core.agent.RunProgress.WaitingForApp("聊天")))
        val ex = vm.ui.value.exchanges.single()
        assertEquals(ExchangeStatus.Working, ex.status)
        assertEquals("Waiting for another task to finish with 聊天", ex.line)
        assertEquals(ComposerMode.Busy, vm.ui.value.composer)
    }

    /** The small screen pops up as soon as the screen is turned on; after the user closes it, only the small button is left, and the task is still running. */
    @Test fun smallWindowCanBeClosedForThisTask() = runTest {
        val open = MutableStateFlow(false)
        val vm = vmWithScreen(open)
        vm.send("打开设置看电量", ok)
        emit(TaskState.Running(0, "正在准备"))
        assertFalse("还没开屏时不浮出来", vm.ui.value.pip.visible)
        open.value = true
        runCurrent()
        assertTrue(vm.ui.value.pip.visible)
        assertFalse(vm.ui.value.pip.closed)

        vm.closePip()
        assertTrue("关掉后还有小按钮", vm.ui.value.pip.visible)
        assertTrue(vm.ui.value.pip.closed)
        assertEquals(ExchangeStatus.Working, vm.ui.value.exchanges.single().status)
        // The subsequent progress will not let it surface again on its own.
        emit(TaskState.Running(1, "点了设置"))
        assertTrue(vm.ui.value.pip.closed)

        vm.reopenPip()
        assertFalse("点小按钮再打开", vm.ui.value.pip.closed)
    }

    /** When the small screen is closed, you are asked to take over: it does not pop up by itself, but the small button becomes "requiring you to handle", and what you are asked to do is still in the dialogue card. */
    @Test fun closedSmallWindowStillSignalsWhenItNeedsYou() = runTest {
        val open = MutableStateFlow(false)
        val vm = vmWithScreen(open)
        vm.send("付款", ok)
        emit(TaskState.Running(0, "正在准备"))
        open.value = true
        runCurrent()
        vm.closePip()
        emit(TaskState.WaitingTakeover("payment", "要你付款"))
        val pip = vm.ui.value.pip
        assertTrue(pip.visible)
        assertTrue(pip.closed)
        assertFalse("关着时接管也不弹开", pip.expanded)
        assertEquals(PipMode.NeedsYou, pip.mode)
        assertTrue(vm.ui.value.exchanges.single().status is ExchangeStatus.NeedsTakeover)
    }

    /** Turn off just that task: the next task will still appear on the small screen. */
    @Test fun closingTheSmallWindowOnlyLastsForThatTask() = runTest {
        val open = MutableStateFlow(false)
        val vm = vmWithScreen(open)
        vm.send("打开设置看电量", ok)
        emit(TaskState.Running(0, "正在准备"))
        open.value = true
        runCurrent()
        vm.closePip()
        emit(TaskState.Succeeded("电量 80%"))
        open.value = false
        advanceTimeBy(ChatViewModel.DONE_LINGER_MS + 1)
        runCurrent()

        vm.send("再看看存储", ok)
        emit(TaskState.Running(0, "正在准备"))
        open.value = true
        runCurrent()
        assertTrue(vm.ui.value.pip.visible)
        assertFalse(vm.ui.value.pip.closed)
    }

    // ---------- Which dialogue the capsule follows ---------- The capsule does not look at who wrote
    // the dialogue file last, but looks at which dialogue the interface is in at the moment: each of
    // these three changes is told to it once.

    @Test fun newChatTellsTheCapsuleItIsAFreshOne() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val vm = vm(store)
        assertEquals(null, store.currentChat())
        vm.newConversation()
        assertEquals(CurrentChat.Fresh, store.currentChat())
    }

    @Test fun openingAConversationTellsTheCapsuleWhichOne() = runTest {
        val store = ConversationStore(tmp.newFolder())
        store.append("c-old", Turn("查天气", "晴", "succeeded"))
        val vm = vm(store)
        vm.openConversation("c-old")
        assertEquals(CurrentChat.Open("c-old"), store.currentChat())
    }

    /** The first sentence spoken in a new dialogue is numbered: this dialogue follows the capsule, not the previous one. */
    @Test fun theFirstMessageInANewChatTellsTheCapsuleItsId() = runTest {
        val store = ConversationStore(tmp.newFolder())
        val vm = vm(store)
        vm.newConversation()
        assertTrue(vm.send("查天气", ok))
        val id = vm.ui.value.conversationId
        assertTrue(id != null)
        assertEquals(CurrentChat.Open(id!!), store.currentChat())
        emit(TaskState.Succeeded("晴"))
        // It's still the same after it's over: the capsule doesn't jump back to the previous
        // conversation
        assertEquals(CurrentChat.Open(id), store.currentChat())
    }

    @Test fun activeExchangeTracksRunningModelAndClearsOnCompletion() = runTest {
        val runningModel = MutableStateFlow<String?>(null)
        val vm = ChatViewModel(
            backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
            runningModel = runningModel,
        ).also { runCurrent() }
        assertTrue(vm.send("Hello", ok))
        emit(TaskState.Running(1, ""))
        assertNull(vm.ui.value.exchanges.single().activeModel)

        runningModel.value = "google/gemini-2.0-flash-exp:free"
        runCurrent()
        assertEquals("google/gemini-2.0-flash-exp:free", vm.ui.value.exchanges.single().activeModel)

        // Switching model mid-task updates activeModel on the running exchange
        runningModel.value = "Qwen/Qwen2.5-72B-Instruct"
        runCurrent()
        assertEquals("Qwen/Qwen2.5-72B-Instruct", vm.ui.value.exchanges.single().activeModel)

        runningModel.value = null
        emit(TaskState.Succeeded("Done"))
        assertNull(vm.ui.value.exchanges.single().activeModel)
    }

    @Test fun liveThinkingAndMultiStepLiveActionUpdateCleanly() = runTest {
        val vm = vm()
        vm.send("search yo yo honey singh on youtube", ok)
        emit(TaskState.Running(1, "", thinking = "Let me open YouTube first."))
        var ex = vm.ui.value.exchanges.single()
        assertEquals("Let me open YouTube first.", ex.thinking)
        assertTrue(ex.waitingForModel)

        emit(TaskState.Running(1, "phone_launch_app", tool = "phone_launch_app", actionTarget = "com.google.android.youtube"))
        ex = vm.ui.value.exchanges.single()
        assertEquals("Opening YouTube…", liveLine(ex))

        did(1, "act", "phone_launch_app", "com.google.android.youtube", "place_changed")
        emit(TaskState.Running(2, "phone_type_text", tool = "phone_type_text"))
        ex = vm.ui.value.exchanges.single()
        assertEquals("Typing…", liveLine(ex))
    }

    @Test fun remainingQuotaPercentUpdatesInRealTimeOnLaunchAndAfterTurns() = runTest {
        RouterUsageTracker.clear()
        try {
            val routerStatus = FakeRouterStatus(
                initialModel = ActiveModel(
                    id = "gemini-4-hydrogen",
                    name = "Gemini 4 Hydrogen",
                    provider = "Google Gemini",
                    usedToday = 4,
                    limit = 100,
                    tierLabel = "Pro Plan",
                )
            )
            val vm = ChatViewModel(
                backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
                routerStatus = routerStatus,
                dailyBudget = { 100 },
                ioDispatcher = StandardTestDispatcher(testScheduler),
            )
            runCurrent()

            // Fallback daily budget calculation: 100 - 4 = 96% remaining
            assertEquals(96.0, vm.remainingQuotaPercent.value, 0.01)
            assertEquals("96.0%", vm.quotaSnapshot.value.formattedPercent)

            // Recording a call immediately updates remainingQuotaPercent via RouterUsageTracker.updates
            repeat(5) {
                RouterUsageTracker.recordCall("gemini-4-hydrogen", "gemini_web")
            }
            runCurrent()
            assertEquals(95.0, vm.remainingQuotaPercent.value, 0.01)

            // When server reports exact fractional quota (9.2% used => 90.8% left), show exact %
            routerStatus.setCurrentModel(
                ActiveModel(
                    id = "gemini-4-hydrogen",
                    name = "Gemini 4 Hydrogen",
                    provider = "Google Gemini",
                    usedToday = 5,
                    limit = 100,
                    remainingPercent = 90.8,
                    isExactQuota = true,
                    tierLabel = "Pro Plan",
                    windowLabel = "5h window",
                )
            )
            runCurrent()
            assertEquals(90.8, vm.remainingQuotaPercent.value, 0.01)
            assertEquals("90.8%", vm.quotaSnapshot.value.formattedPercent)

            // After completing a turn + recording another call, remaining % decrements immediately
            assertTrue(vm.send("check status", ok))
            RouterUsageTracker.recordCall("gemini-4-hydrogen", "gemini_web")
            emit(TaskState.Succeeded("All done"))
            runCurrent()
            assertEquals(89.8, vm.remainingQuotaPercent.value, 0.01)
        } finally {
            RouterUsageTracker.clear()
        }
    }

    @Test fun startupAutoConnectTransitionsFromConnectingToConnectedAndSignInExpired() = runTest {
        RouterUsageTracker.clear()
        try {
            val io = StandardTestDispatcher(testScheduler)
            val routerStatus = FakeRouterStatus(
                initialModel = ActiveModel(
                    id = "gemini-4-hydrogen",
                    name = "Gemini 4 Hydrogen",
                    provider = "Google Gemini",
                    tierLabel = "Pro Plan",
                )
            )
            var nextOutcome = ConnectionWarmupResult(
                status = ConnectionStatus.SignInRequired,
                detail = "Google Gemini session expired",
            )
            val vm = ChatViewModel(
                backgroundScope, state, stage, actions, ConversationStore(tmp.newFolder()),
                routerStatus = routerStatus,
                warmUpConnection = { nextOutcome },
                ioDispatcher = io,
            )

            // Before IO dispatcher runs, launch warm-up immediately signals Connecting (amber dot)
            assertEquals(ConnectionStatus.Connecting, vm.connectionStatus.value)
            assertEquals(ConnectionStatus.Connecting, routerStatus.connectionStatus.value)

            runCurrent()
            assertEquals(ConnectionStatus.SignInRequired, vm.connectionStatus.value)
            assertEquals("Google Gemini session expired", vm.connectionDetail.value)
            assertEquals(ConnectionStatus.SignInRequired, routerStatus.connectionStatus.value)

            // After re-signing in, warmUpIfNeeded reconnects, applies GeminiUsage %, and turns Connected
            nextOutcome = ConnectionWarmupResult(
                status = ConnectionStatus.Connected,
                geminiUsage = GeminiUsage(
                    daily = GeminiUsageWindow(
                        percentUsed = 9,
                        resetsAtEpochMs = 1_800_000_000_000L,
                        fractionUsed = 0.092,
                    )
                ),
            )
            vm.warmUpIfNeeded()
            assertEquals(ConnectionStatus.Connecting, vm.connectionStatus.value)
            runCurrent()

            assertEquals(ConnectionStatus.Connected, vm.connectionStatus.value)
            assertEquals(ConnectionStatus.Connected, routerStatus.connectionStatus.value)
            assertEquals(90.8, vm.remainingQuotaPercent.value, 0.01)
        } finally {
            RouterUsageTracker.clear()
        }
    }
}
