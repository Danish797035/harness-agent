package com.meenai.mina.core.agent

import com.meenai.mina.core.knowledge.KnowledgeStore
import com.meenai.mina.core.knowledge.GuideCatalog
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import com.meenai.mina.core.cap.sandbox.FakeSandbox
import com.meenai.mina.core.uistate.ActionResult
import com.meenai.mina.core.testing.FakeChatModel
import com.meenai.mina.core.testing.InterruptibleStream
import com.meenai.mina.core.run.RunId
import com.meenai.mina.core.run.RunInbox
import com.meenai.mina.core.run.RunInput
import com.meenai.mina.core.run.RunSpec
import com.meenai.mina.core.testing.LoopSnapshot
import com.meenai.mina.core.testing.Screen
import com.meenai.mina.core.testing.ScriptedScreen
import com.meenai.mina.core.testing.button
import com.meenai.mina.core.testing.fakeModel
import com.meenai.mina.core.testing.field
import com.meenai.mina.core.testing.label
import com.meenai.mina.core.tool.FakeScreen
import com.meenai.mina.core.tool.ScriptWebPort
import com.meenai.mina.core.tool.ToolDispatcher
import com.meenai.mina.core.verify.Verifier
import com.meenai.mina.core.cap.web.AttachedPage
import com.meenai.mina.core.cap.web.FakeWebPort
import com.meenai.mina.core.cap.web.PageText
import com.meenai.mina.core.cap.web.ScriptRun
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset
import com.meenai.mina.core.testing.WEB_SCRIPT_TOOL
import com.meenai.mina.core.assembly.toolDispatcher

/**
 * Use the fake model to drive the real AgentLoop to run a typical scenario: assert the ending, compare
 * the message sequence sent to the model each time with the golden sample, and keep the model unchanged
 * before and after reconstruction (execution entry, tool registry, capability interface, main loop
 * slimming). Golden sample regeneration:./gradlew:core:test --tests '*LoopScenarioTest'
 * -PupdateSnapshots
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoopScenarioTest {
    private val bundled by lazy { KnowledgeStore(KnowledgeStore.loadBundled()) }
    private val catalog by lazy { GuideCatalog.loadBundled() }

    private fun loop(
        model: FakeChatModel, tools: ToolDispatcher, maxSteps: Int = 20,
        checker: FakeChatModel? = null,
        knowledge: KnowledgeStore? = null, skills: GuideCatalog? = null,
        installed: List<Pair<String, String>> = emptyList(), flags: SpeedFlags = SpeedFlags.OFF,
    ) = AgentLoop(
        model, tools, SystemPrompt.identity(), maxSteps, knowledge = knowledge, installedApps = { installed },
        verifier = checker?.let { Verifier(it, null) },
        flags = flags, clock = { CLOCK }, skills = skills, zone = ZoneOffset.UTC,
    )

    private fun snapshot(name: String, end: TaskState, vararg models: Pair<String, FakeChatModel>) {
        models.forEach { (role, m) -> assertEquals("$role 的剧本没有用完", 0, m.remaining) }
        LoopSnapshot.check(name, LoopSnapshot.render(end.toString(), models.toList()))
    }

    /** The full text of the last runtime status in this request. */
    private fun List<com.meenai.mina.core.model.Message>.runtimeState(): String =
        flatMap { it.parts }.filterIsInstance<Part.Text>().last { ContextBuilder.kindOf(it.text) == ContextKind.RuntimeState }.text

    private fun FakeChatModel.lastResult(i: Int): String =
        requests[i].messages.last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().first().content

    // (a) Mobile phone: watch the screen (with progress instructions) → click → watch the screen → text
    // answer; you have read the quotations in the answer, please do not check the model; the reference
    // materials are injected by the front-end App
    @Test fun simplePhoneTask() = runTest {
        val screen = ScriptedScreen(listOf(
            Screen(SETTINGS, listOf(label("@e1", "设置", 80), button("@e2", "蓝牙", 300), button("@e3", "WLAN", 400))),
            Screen(SETTINGS, listOf(label("@e1", "蓝牙", 80), label("@e2", "已开启", 300))),
        ))
        val main = fakeModel("main") {
            reply { thinking("先看屏"); text("看一下设置首页"); call("phone_observe") }
            call("phone_tap", "ref" to "@e2", "snapshotId" to "s1")
            call("phone_observe")
            answer("蓝牙已开启:蓝牙页显示「已开启」。")
        }
        val checker = fakeModel("checker") { }
        val end = loop(main, toolDispatcher(screen, skills = catalog), checker = checker,
            knowledge = bundled, skills = catalog, installed = listOf(SETTINGS to "设置")).run("看看蓝牙开没开")
        end as TaskState.Succeeded
        assertEquals("蓝牙已开启:蓝牙页显示「已开启」。", end.answer)
        assertTrue(end.verified)
        // The source is the screen that reads "Enabled" (step 3)
        assertEquals(listOf(3), end.sources.map { it.step })
        assertEquals(listOf("tap @e2 s1"), screen.calls)
        assertTrue(checker.requests.isEmpty())
        snapshot("a_simple_phone", end, "main" to main)
    }

    // (b) Web page: After opening the site, inject site knowledge by domain name; the sandbox is
    @Test fun webTaskWithSiteKnowledge() = runTest {
        val web = FakeWebPort().apply {
            title = "知乎"
            page = pageText("https://www.zhihu.com/hot", "知乎", "热榜\n1. 今日话题")
        }
        val main = fakeModel("main") {
            call("web_navigate", "url" to "https://www.zhihu.com/hot")
            call("web_read_page")
            answer("知乎热榜第一是「今日话题」")
        }
        val tools = toolDispatcher(FakeScreen(), skills = catalog, web = web, sandbox = FakeSandbox())
        val end = loop(main, tools, knowledge = bundled, skills = catalog).run("用网页看一下知乎热榜")
        assertTrue("$end", end is TaskState.Succeeded)
        val refs = main.requests[1].messages.contextBodies(ContextKind.SiteKnowledge)
        assertEquals(1, refs.size)
        snapshot("b_web_site_knowledge", end, "main" to main)
    }

    // (c) Empty reply: only reminded once; the final answer arrives in chunks and is put together in
    // order
    @Test fun emptyReplyIsNudged() = runTest {
        val main = fakeModel("main") {
            reply { thinking("想想") }
            call("phone_observe")
            reply { text("找到了,", "首页有「搜索」按钮") }
        }
        val end = loop(main, toolDispatcher(FakeScreen())).run("找搜索按钮")
        assertEquals("找到了,首页有「搜索」按钮", (end as TaskState.Succeeded).answer)
        assertEquals(ContextKind.Notice, ContextBuilder.kindOf(main.requests[1].messages.withoutRuntime().last()))
        snapshot("c_empty_reply_nudge", end, "main" to main)
    }

    // (d) Number expired: The port reports ref_expired, re-view the model and click again.
    @Test fun staleRefThenReobserve() = runTest {
        val screen = ScriptedScreen(
            listOf(Screen(APP, listOf(button("@e1", "搜索", 100))), Screen(APP, listOf(label("@e1", "搜索结果", 100)))),
            results = listOf(ActionResult.Failed("ref_expired")),
        )
        val main = fakeModel("main") {
            call("phone_observe")
            call("phone_tap", "ref" to "@e1", "snapshotId" to "s1")
            call("phone_observe")
            call("phone_tap", "ref" to "@e1", "snapshotId" to "s2")
            answer("打开了搜索")
        }
        val end = loop(main, toolDispatcher(screen)).run("点搜索")
        assertTrue("$end", end is TaskState.Succeeded)
        assertTrue("ref_expired" in main.lastResult(2))
        snapshot("d_ref_expired", end, "main" to main)
    }

    // (e) Irreversible operation: blocked → application confirmation → user does not agree → give up
    // truthfully
    @Test fun irreversibleDeclined() = runTest {
        // When asking, the only step to spend money is to ask first: Submitting the order was blocked,
        // the application was approved, and the user did not agree.
        val screen = ScriptedScreen(listOf(
            Screen(APP, listOf(label("@e1", "耳机店", 40), label("@e2", "合计 ¥25", 800), button("@e3", "提交订单", 950))),
        ))
        val main = fakeModel("main") {
            call("phone_observe")
            call("phone_tap", "ref" to "@e3", "snapshotId" to "s1")
            call("request_confirmation", "action" to "order", "target" to "耳机店", "content" to "一副耳机", "amount" to "¥25", "app" to APP)
            reportFailure("用户没有同意下单")
        }
        val l = loop(main, toolDispatcher(screen))
        val run = async { l.run("买一副耳机") }
        advanceUntilIdle()
        assertTrue("${l.state.value}", l.state.value is TaskState.WaitingConfirmation)
        l.answerConfirmation(false)
        val end = run.await()
        assertEquals(TaskState.Failed("用户没有同意下单", FailKind.GaveUp), end)
        assertFalse(screen.calls.any { it.startsWith("tap @e3") })
        assertTrue("confirmation_required" in main.lastResult(2))
        assertTrue("confirmation_declined" in main.lastResult(3))
        snapshot("e_irreversible_declined", end, "main" to main)
    }

    // (f) Multiple steps at a time (do_steps): the entire batch is counted as one step, and the
    // sub-steps are executed one by one
    @Test fun batchSteps() = runTest {
        val screen = ScriptedScreen(listOf(
            Screen(APP, listOf(field("@e1", "搜索商品", 100), button("@e2", "搜索", 200))),
            Screen(APP, listOf(field("@e1", "咖啡", 100, hint = false), button("@e2", "搜索", 200))),
            Screen(APP, listOf(label("@e1", "咖啡豆 ¥89", 300))),
        ))
        val steps = listOf(
            mapOf("tool" to "phone_type_text", "args" to mapOf("text" to "咖啡", "ref" to "@e1", "snapshotId" to "s1")),
            mapOf("tool" to "phone_tap", "args" to mapOf("ref" to "@e2", "snapshotId" to "s1")),
        )
        val main = fakeModel("main") {
            call("phone_observe")
            call(DO_STEPS_TOOL, "steps" to steps)
            // After the batch is finished, you will not be brought back to this screen (model without
            // looking at the picture): you can only tell the price after reading it.
            call("phone_observe")
            answer("咖啡豆 ¥89")
        }
        val end = loop(main, toolDispatcher(screen), flags = SpeedFlags(batchSteps = true)).run("搜咖啡看价格")
        assertTrue("$end", end is TaskState.Succeeded)
        assertEquals(listOf("type @e1 咖啡", "tap @e2 s1"), screen.calls)
        snapshot("f_batch_steps", end, "main" to main)
    }

    // (g) Page script: ordinary sub-steps are executed, irreversible sub-steps are blocked and are not
    // executed.
    @Test fun browserScriptWithGatedSubAction() = runTest {
        val web = FakeWebPort().apply {
            elements = listOf(button("@e1", "搜索", 100).copy(resourceId = "#go"), button("@e2", "提交订单", 300).copy(resourceId = "#order"))
        }
        val port = ScriptWebPort(web) { b ->
            b.call("snapshot", JsonObject(emptyMap()))
            b.call("tap", buildJsonObject { put("ref", "@e1") })
            b.call("snapshot", JsonObject(emptyMap()))
            b.call("tap", buildJsonObject { put("ref", "@e2") })
            ScriptRun.Done("\"unreachable\"")
        }
        val main = fakeModel("main") {
            call("web_navigate", "url" to "https://shop.example.com/")
            call(WEB_SCRIPT_TOOL, "code" to "await mina.tap('@e1'); await mina.tap('@e2')")
            reportFailure("下单需要用户确认,脚本没有执行")
        }
        val end = loop(main, toolDispatcher(FakeScreen(), web = port)).run("搜一下再下单")
        assertTrue("$end", end is TaskState.Failed)
        assertEquals(1, web.calls.count { it.startsWith("tapRef") })
        assertTrue("confirmation_required" in main.lastResult(2))
        snapshot("g_browser_script_gated", end, "main" to main)
    }

    // (h) Service error: current limit, network, and 5xx are all temporary failures. Re-send the same
    // request (the content seen by the model remains unchanged), and then complete the task.
    @Test fun providerErrors() = runTest {
        val main = fakeModel("main") {
            rateLimited(retryAfterMs = 500)
            networkError()
            call("phone_observe")
            serverError(503)
            answer("找到了")
        }
        val end = loop(main, toolDispatcher(FakeScreen())).run("找搜索按钮")
        com.meenai.mina.core.testing.assertAnswered("找到了", end)
        assertEquals(5, main.requests.size)
        // The same request is reissued
        assertEquals(main.requests[3], main.requests[4])
        snapshot("h_provider_errors", end, "main" to main)
    }

    // (i) The context is too long: compress it hard and resend it, and continue with the same step.
    @Test fun contextOverflowCompressesAndContinues() = runTest {
        val main = fakeModel("main") {
            for (i in 1..8) call("wait", "seconds" to 1 + i / 10.0)
            contextOverflow()
            answer("等完了")
        }
        val end = loop(main, toolDispatcher(FakeScreen())).run("等一会儿")
        assertTrue("$end", end is TaskState.Succeeded)
        assertTrue(main.requests[9].messages.size < main.requests[8].messages.size)
        snapshot("i_context_overflow", end, "main" to main)
    }

    // (j) When the number of steps is exhausted: only end_not_done will be given in the last round, and
    // you will answer in text when you are done.
    @Test fun stepBudgetExhausted() = runTest {
        val main = fakeModel("main") {
            for (i in 1..3) call("wait", "seconds" to 1 + i / 10.0)
            reportFailure("查过首页和菜单,没找到")
        }
        val end = loop(main, toolDispatcher(FakeScreen()), maxSteps = 3).run("找夜间模式")
        assertEquals(TaskState.Failed("查过首页和菜单,没找到", FailKind.GaveUp), end)
        assertEquals(listOf("end_not_done"), main.requests.last().toolNames)
        snapshot("j_step_budget", end, "main" to main)
    }

    // (k) The output is truncated (only half text, not an answer, a reminder) and the parameters are not
    // legal JSON calls
    @Test fun truncatedAndMalformed() = runTest {
        val main = fakeModel("main") {
            truncated("我先打开")
            malformedCall("phone_tap")
            answer("找到了")
        }
        val end = loop(main, toolDispatcher(FakeScreen())).run("找搜索按钮")
        assertTrue("$end", end is TaskState.Succeeded)
        assertTrue("invalid_arguments" in main.lastResult(2))
        snapshot("k_truncated_malformed", end, "main" to main)
    }

    // (l) I haven’t read the price in the answer: Please check the model → Return it once and name the
    // part → If it still doesn’t match, hand it over and mark it as unconfirmed
    @Test fun ungroundedValueIsNudgedOnceThenLabelled() = runTest {
        val screen = ScriptedScreen(listOf(Screen(APP, listOf(label("@e1", "咖啡豆 ¥89", 300)))))
        val main = fakeModel("main") {
            call("phone_observe")
            answer("咖啡豆 ¥79")
            answer("咖啡豆 ¥79(页面上看到的)")
        }
        val checker = fakeModel("checker") {
            verdict("fail", "屏上写的是 ¥89", "看咖啡豆的价格" to null)
            verdict("fail", "屏上写的是 ¥89", "看咖啡豆的价格" to null)
        }
        val end = loop(main, toolDispatcher(screen), checker = checker).run("看咖啡豆的价格")
        end as TaskState.Succeeded
        assertFalse(end.verified)
        assertEquals(listOf("79", "屏上写的是 ¥89"), end.unconfirmed)
        assertEquals(ContextKind.Notice, ContextBuilder.kindOf(main.requests[2].messages.withoutRuntime().last()))
        snapshot("l_unconfirmed_labelled", end, "main" to main, "verifier" to checker)
    }

    // (m) Irreversible operation: blocked → application confirmation → user consent → send → read "sent"
    // → answer; if you have done external things, please check the model when answering. It does not
    // cost money to send: do not ask first, send directly, read and verify after sending.
    @Test fun irreversibleDoneDirectlyIsVerified() = runTest {
        val screen = ScriptedScreen(listOf(
            Screen(CHAT, listOf(label("@e1", "张三", 40), field("@e2", "发消息"), button("@e3", "+", 950))),
            Screen(CHAT, listOf(label("@e1", "张三", 40), field("@e2", "明天见", hint = false), button("@e3", "发送", 950))),
            Screen(CHAT, listOf(label("@e1", "张三", 40), label("@e4", "明天见", 600), label("@e5", "已发送", 640), field("@e2", "发消息"))),
        ))
        val main = fakeModel("main") {
            call("phone_observe")
            call("phone_type_text", "text" to "明天见", "ref" to "@e2", "snapshotId" to "s1")
            call("phone_tap", "ref" to "@e3", "snapshotId" to "s1")
            call("phone_observe")
            answer("已给张三发了「明天见」,聊天里显示「已发送」。")
        }
        val checker = fakeModel("checker") { verdict("pass", "聊天里显示已发送", "给张三发明天见" to "已发送") }
        val end = loop(main, toolDispatcher(screen), checker = checker).run("给张三发明天见")
        assertTrue("$end", end is TaskState.Succeeded && end.verified)
        assertTrue(screen.calls.any { it.startsWith("tap @e3") })
        snapshot("m_irreversible_verified", end, "main" to main, "verifier" to checker)
    }

    // (n) English questions and answers are bleached into Chinese: return it once before handing it
    // over, so that the entire model can be rewritten into the user's language; the rewritten English
    // answers are handed over as usual
    @Test fun answerInOtherLanguageIsNudgedOnce() = runTest {
        val screen = ScriptedScreen(listOf(Screen(SETTINGS, listOf(label("@e1", "关于手机", 80), label("@e2", "Android 版本 14", 300),
            label("@e3", "已使用 250.53 GB / 512 GB", 400)))))
        val main = fakeModel("main") {
            call("phone_observe")
            answer(CHINESE_ANSWER)
            answer("Android version: 14 (Android 版本). Storage: 250.53 GB of 512 GB is used (已使用), as shown on About phone (关于手机).")
        }
        val end = loop(main, toolDispatcher(screen)).run("What Android version is this phone on, and how much storage is used?")
        end as TaskState.Succeeded
        assertTrue(end.answer, end.answer.startsWith("Android version: 14"))
        val notice = main.requests[2].messages.withoutRuntime().last()
        assertEquals(ContextKind.Notice, ContextBuilder.kindOf(notice))
        assertTrue("translation (original)" in (notice.parts.single() as Part.Text).text)
        snapshot("n_answer_language_nudge", end, "main" to main)
    }

    // After rewriting, it is still in another language: no longer returned, but still handed over
    @Test fun answerLanguageIsNudgedOnlyOnce() = runTest {
        val screen = ScriptedScreen(listOf(Screen(SETTINGS, listOf(label("@e1", "关于手机", 80), label("@e2", "Android 版本 14", 300),
            label("@e3", "已使用 250.53 GB / 512 GB", 400)))))
        val main = fakeModel("main") {
            call("phone_observe")
            answer(CHINESE_ANSWER)
            answer(CHINESE_ANSWER)
        }
        val end = loop(main, toolDispatcher(screen)).run("What Android version is this phone on, and how much storage is used?")
        assertEquals(CHINESE_ANSWER, (end as TaskState.Succeeded).answer)
        assertEquals(0, main.remaining)
        assertEquals(3, main.requests.size)
    }

    // Question on the browser page: The sentence starts with the page description (fixed in English).
    // The language prompt and the language check of the answer only look at the words typed by the user.
    // Chinese answers will not be returned.
    @Test fun browserPageAnswerFollowsTheUserWords() = runTest {
        val attached = AttachedPage.Page("https://shop.example.com/s?k=降噪耳机", "降噪耳机 - 搜索结果")
        val web = FakeWebPort().apply {
            title = attached.title
            page = pageText(attached.url, attached.title, "入耳式降噪耳机 B2 Pro 649 元\n头戴式降噪耳机 C3 Max 899 元")
        }
        val answer = "这页里 700 元以内的只有入耳式降噪耳机 B2 Pro,价格 649 元;头戴式的 C3 Max 要 899 元,超出了预算,所以选 B2 Pro。"
        val main = fakeModel("main") {
            call("web_read_page")
            answer(answer)
        }
        val end = loop(main, toolDispatcher(FakeScreen(), web = web)).run(AttachedPage.compose(attached, "这页里 700 以内的挑一个"))
        assertEquals(answer, (end as TaskState.Succeeded).answer)
        assertEquals(2, main.requests.size)
        val hint = main.requests[0].messages.runtimeState()
        assertTrue(hint, "latest message: \"这页里 700 以内的挑一个\"" in hint)
    }

    // The interjection on the browser page contains the newly selected words: the language prompt refers
    // to the words typed in the interjection, and the Chinese answer will not be returned.
    @Test fun browserPageSteerFollowsTheUserWords() = runTest {
        browserPageSteer("就看选中的这个", quoted = "就看选中的这个")
    }

    // I only selected words and did not type when I interrupted: the language prompt follows the opening
    // sentence, and the Chinese answer will not be returned.
    @Test fun selectionOnlySteerKeepsTheOpeningWords() = runTest {
        browserPageSteer("", quoted = "这页里 700 以内的挑一个")
    }

    /** Start in Chinese on the page with English title, insert the newly selected word and [words]; the model directly answers in Chinese. */
    private suspend fun browserPageSteer(words: String, quoted: String) {
        val attached = AttachedPage.Page("https://shop.example.com/s?k=headphones", "Noise Cancelling Headphones - Search Results")
        val web = FakeWebPort().apply {
            title = attached.title
            page = pageText(attached.url, attached.title, "入耳式降噪耳机 B2 Pro 649 元\n头戴式降噪耳机 C3 Max 899 元")
        }
        val answer = "选中的是入耳式降噪耳机 B2 Pro,价格 649 元,在 700 元以内;头戴式的 C3 Max 要 899 元,超出了预算,所以选 B2 Pro。"
        val main = fakeModel("main") { answer(answer) }
        val inbox = RunInbox().apply { send(RunInput.Steer("m1", AttachedPage.compose(attached.copy(selection = "B2 Pro 649"), words))) }
        val end = loop(main, toolDispatcher(FakeScreen(), web = web))
            .run(RunSpec(RunId("r1"), AttachedPage.compose(attached, "这页里 700 以内的挑一个"), "thread-1"), inbox)
        assertEquals(answer, (end as TaskState.Succeeded).answer)
        assertEquals(1, main.requests.size)
        val hint = main.requests[0].messages.runtimeState()
        assertTrue(hint, "latest message: \"$quoted\"" in hint)
    }

    // Quoting Mina's previous English answer and typing a short Chinese sentence: if the whole sentence
    // is counted, it will be judged as a language change; the quoted paragraph is not counted as the
    // user's words, the language prompts the typed words, and the Chinese answer will not be returned
    @Test fun quotedReplyFollowsTheUserWords() = runTest {
        val goal = QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.Mina, ENGLISH_REPLY), "那哪个更值")
        assertNotNull(ReplyLanguage.mismatch(goal, HEADPHONES_ANSWER))
        val main = fakeModel("main") { answer(HEADPHONES_ANSWER) }
        val end = loop(main, toolDispatcher(FakeScreen())).run(goal)
        assertEquals(HEADPHONES_ANSWER, (end as TaskState.Succeeded).answer)
        assertEquals(1, main.requests.size)
        val hint = main.requests[0].messages.runtimeState()
        assertTrue(hint, "latest message: \"那哪个更值\"" in hint)
    }

    // The English answer was quoted in the interjection: Just look at the words typed in the
    // interjection
    @Test fun quotedSteerFollowsTheUserWords() = runTest {
        val steer = QuoteMessage.compose(QuoteMessage.Quote(QuoteMessage.From.Mina, ENGLISH_REPLY), "那哪个更值")
        assertNotNull(ReplyLanguage.mismatch(steer, HEADPHONES_ANSWER))
        val main = fakeModel("main") { answer(HEADPHONES_ANSWER) }
        val inbox = RunInbox().apply { send(RunInput.Steer("m1", steer)) }
        val end = loop(main, toolDispatcher(FakeScreen())).run(RunSpec(RunId("r1"), "帮我比较一下这两款降噪耳机", "thread-1"), inbox)
        assertEquals(HEADPHONES_ANSWER, (end as TaskState.Succeeded).answer)
        assertEquals(1, main.requests.size)
        val hint = main.requests[0].messages.runtimeState()
        assertTrue(hint, "latest message: \"那哪个更值\"" in hint)
    }

    // If the sentence is very short and the attachment name is long in English: if the whole sentence is
    // counted, it will be judged as a language change; the few lines in the attachment are not the
    // user's words, and the Chinese answer will not be returned.
    @Test fun attachedFileFollowsTheUserWords() = runTest {
        val goal = AttachmentLines.compose("总结一下", listOf("/workspace/inbox/Quarterly_Financial_Report_Final_v2.pdf" to "2 KB"))
        assertNotNull(ReplyLanguage.mismatch(goal, REPORT_ANSWER))
        val main = fakeModel("main") { answer(REPORT_ANSWER) }
        val end = loop(main, toolDispatcher(FakeScreen())).run(goal)
        assertEquals(REPORT_ANSWER, (end as TaskState.Succeeded).answer)
        assertEquals(1, main.requests.size)
        val hint = main.requests[0].messages.runtimeState()
        assertTrue(hint, "latest message: \"总结一下\"" in hint)
    }

    // (o) The tool call was leaked into text (the server did not parse it into a call): inappropriate
    // answer, returned once; the model was completed as usual after being re-called through the tool
    // interface
    @Test fun toolCallLeakedAsTextIsNudgedOnce() = runTest {
        val screen = ScriptedScreen(listOf(Screen(SETTINGS, listOf(label("@e1", "关于手机", 80), label("@e2", "Android 版本 14", 300)))))
        val main = fakeModel("main") {
            answer(LEAKED_OBSERVE)
            call("phone_observe")
            answer(ANDROID_14)
        }
        val end = loop(main, toolDispatcher(screen)).run("这台手机是安卓几?")
        assertEquals(ANDROID_14, (end as TaskState.Succeeded).answer)
        val notice = main.requests[1].messages.withoutRuntime().last()
        assertEquals(ContextKind.Notice, ContextBuilder.kindOf(notice))
        assertTrue("`phone_observe`" in (notice.parts.single() as Part.Text).text)
        snapshot("o_tool_call_as_text_nudge", end, "main" to main)
    }

    // After being returned, it is leaked into text: End, and the original mark is not handed over to the
    // user.
    @Test fun toolCallLeakedTwiceEndsTheTask() = runTest {
        val screen = ScriptedScreen(listOf(Screen(SETTINGS, listOf(label("@e1", "关于手机", 80)))))
        val main = fakeModel("main") {
            answer(LEAKED_OBSERVE)
            answer(LEAKED_OBSERVE)
        }
        val end = loop(main, toolDispatcher(screen)).run("这台手机是安卓几?")
        end as TaskState.Failed
        assertEquals(FailKind.Model, end.kind)
        assertFalse(end.reason, "<tool_call>" in end.reason)
        assertEquals(2, main.requests.size)
    }

    // After adjusting the tool normally, it leaked again: it still returned once every time (occasional
    // server-side problems, more than once in long tasks)
    @Test fun toolCallLeakAfterARealCallIsNudgedAgain() = runTest {
        val screen = ScriptedScreen(listOf(Screen(SETTINGS, listOf(label("@e1", "关于手机", 80), label("@e2", "Android 版本 14", 300)))))
        val main = fakeModel("main") {
            answer(LEAKED_OBSERVE)
            call("phone_observe")
            answer(LEAKED_OBSERVE)
            answer(ANDROID_14)
        }
        val end = loop(main, toolDispatcher(screen)).run("这台手机是安卓几?")
        assertEquals(ANDROID_14, (end as TaskState.Succeeded).answer)
        assertEquals(0, main.remaining)
    }

    // The answer talks about this format, but does not mention any tools this time: normal handover
    @Test fun answerAboutToolCallMarkupIsDelivered() = runTest {
        val screen = ScriptedScreen(listOf(Screen(SETTINGS, listOf(label("@e1", "设置", 80)))))
        val explanation = "有的模型写工具调用时用 <tool_call> 包起来,里面是 <function=工具名> 和若干 <parameter=参数名>。"
        val main = fakeModel("main") { answer(explanation) }
        val end = loop(main, toolDispatcher(screen)).run("模型的工具调用格式是什么样的?")
        assertEquals(explanation, (end as TaskState.Succeeded).answer)
        assertEquals(1, main.requests.size)
    }

    // (p) Interrupting the reply that is being streamed: the paragraph that the user sees is recorded in
    // history as a text-only reply, the interruption is followed by it, and the model takes both of them
    // and asks again
    @Test fun steerKeepsTheInterruptedReply() = runTest {
        val screen = ScriptedScreen(listOf(Screen(SETTINGS, listOf(label("@e1", "蓝牙", 80), label("@e2", "已开启", 300)))))
        val main = fakeModel("main") {
            call("phone_observe")
            answer("蓝牙已开启:蓝牙页显示「已开启」。")
        }
        val model = InterruptibleStream(main, listOf("我先看看", "我先看看 WLAN 开没开"))
        val inbox = RunInbox()
        val running = async {
            AgentLoop(model, toolDispatcher(screen), SystemPrompt.identity(), clock = { CLOCK }, zone = ZoneOffset.UTC, streamReplies = true)
                .run(RunSpec(RunId("r1"), "看看 WLAN 开没开", "thread-1"), inbox)
        }
        model.entered.await()
        inbox.send(RunInput.Steer("m1", "不是 WLAN,是蓝牙"))
        val end = running.await()
        assertTrue(model.cancelled)
        assertEquals("蓝牙已开启:蓝牙页显示「已开启」。", (end as TaskState.Succeeded).answer)
        val seen = main.requests[0].messages
        val kept = seen.indexOfFirst { it.role == Role.Assistant }
        assertEquals(listOf(Part.Text("我先看看 WLAN 开没开")), seen[kept].parts)
        assertTrue((seen[kept + 1].parts.first() as Part.Text).text.startsWith(AgentLoop.STEER_MARKER))
        // The language prompt refers to the words typed by the user in the interrupt, without the
        // interrupt mark.
        val hint = seen.runtimeState()
        assertTrue(hint, "latest message: \"不是 WLAN,是蓝牙\"" in hint)
        snapshot("p_steer_keeps_interrupted_reply", end, "main" to main)
    }

    private companion object {
        const val LEAKED_OBSERVE = "<tool_call>\n<function=phone_observe>\n</function>\n</tool_call>"
        const val ANDROID_14 = "这台手机的安卓版本是 14,是在关于手机页面上看到的。"
        const val ENGLISH_REPLY = "The first pair costs 399 dollars and has the best noise cancelling. " +
            "The second pair costs 429 dollars and is more comfortable on long flights."
        const val HEADPHONES_ANSWER = "看你更在意什么:第一副便宜 30 美元,降噪更好;第二副戴久了更舒服,适合长途飞行。只看性价比的话选第一副。"
        const val REPORT_ANSWER = "这份季度报告的要点有三条:收入比上个季度略有增长,成本控制得不错,下个季度的重点是扩大海外市场。"
        const val CHINESE_ANSWER = "我打开了关于手机页面看过了：这台手机的安卓版本是 14，存储已使用 250.53 GB，总共 512 GB，两个数字都是页面上显示的。"
        const val CLOCK = 1_761_300_000_000L
        const val SETTINGS = "com.android.settings"
        const val CHAT = "com.chat"
        const val APP = "com.x"
        fun pageText(url: String, title: String, text: String) =
            PageText(url, title, text, emptyList(), emptyList(), false, null)
    }
}
