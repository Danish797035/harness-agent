package com.meenai.mina.core.eval

import com.meenai.mina.core.assembly.legacyNames
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.testing.RECORDED_TOOLS
import com.meenai.mina.core.testing.TOOLS
import com.meenai.mina.core.verify.RecordedVerifications
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The runs written down before the name was changed (such as the tuning team's record that night) are
 * read according to the comparison table under the old name: the facts taken from the records and the
 * evidence at the time of review are exactly the same as when the same run was recorded under the
 * current name, and the re-scoring results remain unchanged.
 */
class RecordedRunNamesTest {
    private var t = 0L
    private fun screen(step: Int, app: String?, vararg names: String) = com.meenai.mina.core.testing.legacyScreenText(++t, step, app, names.toList())
    private fun reply(step: Int, tool: String, args: String = "{}") = RunEvent.ModelReply(++t, step, "", tool, args, null, null, null)
    private fun result(step: Int, tool: String, json: String) = RunEvent.ToolResult(++t, step, tool, json, null, null)

    /** Once the mobile phone is added to the web page, the operation of handing in the paper after logging in and taking over is recorded with the current name. */
    private val current: List<RunEvent> = listOf(
        reply(1, "phone_launch_app", """{"package":"com.example.notes"}"""), result(1, "phone_launch_app", """{"ok":true}"""),
        screen(2, "com.example.notes", "新建", "搜索"), reply(2, "phone_type_text", """{"text":"买牛奶"}"""), result(2, "phone_type_text", """{"ok":true}"""),
        reply(3, "web_navigate", """{"url":"https://weather.example.com/"}"""), result(3, "web_navigate", """{"ok":true,"url":"https://weather.example.com/","title":"天气"}"""),
        reply(4, "web_read_page"), result(4, "web_read_page", """{"ok":true,"text":"北京 晴 25℃"}"""),
        screen(5, "网站 weather.example.com", "欢迎登录", "请输入密码", "登录"), reply(5, "handle_obstacle", """{"kind":"login"}"""),
        RunEvent.Barrier(++t, "login"), reply(6, "hand_to_user", """{"reason":"login","message":"请登录"}"""),
        RunEvent.State(++t, "waiting_takeover", "请登录"),
        screen(7, "网站 weather.example.com", "北京 晴 25℃"), reply(8, "finish", """{"answer":"北京晴 25℃\n依据:网页正文"}"""),
        RunEvent.Verifier(++t, "pass", "{}", step = 8),
    )

    /** Here's what the same run looked like before renaming: Each renamed tool reverts to its old name. */
    private val old: List<RunEvent> = run {
        val legacy = legacyNames()
        val former = legacy.names.flatMap { o -> legacy.replacements(o).map { it to o } }.toMap()
        fun back(n: String) = former[n] ?: n
        current.map { e ->
            when (e) {
                is RunEvent.ModelReply -> e.copy(tool = e.tool?.let(::back))
                is RunEvent.ToolResult -> e.copy(tool = back(e.tool))
                else -> e
            }
        }
    }

    private fun facts(events: List<RunEvent>, tools: com.meenai.mina.core.tool.ToolIndex) = listOf(
        RunFacts.ledger(events, tools).all, RunFacts.appsSeen(events, tools), RunFacts.appsLaunched(events, tools),
        RunFacts.appsWrittenIn(events, tools), RunFacts.takeoverBarrier(events, tools),
    )

    @Test fun oldNamesRescoreLikeTheCurrentOnes() {
        assertNotEquals("the fixture must really use old names", current.map { (it as? RunEvent.ModelReply)?.tool }, old.map { (it as? RunEvent.ModelReply)?.tool })
        assertEquals(facts(current, TOOLS), facts(old, RECORDED_TOOLS))
        // If you don’t read according to the comparison table, the facts obtained from the old records
        // will be different (this test can be held)
        assertNotEquals(facts(current, TOOLS), facts(old, TOOLS))
        // The same goes for checking the evidence at the time.
        val (v, f) = RecordedVerifications.pairs(current).single()
        val answer = RecordedVerifications.answer(current, f).orEmpty()
        assertEquals(RecordedVerifications.ledgerSources(current, v, f, "", answer), RecordedVerifications.ledgerSources(old, v, f, "", answer))
    }
}
