package com.meenai.mina.core.tool

import com.meenai.mina.core.agent.AgentLoop
import com.meenai.mina.core.agent.SystemPrompt
import com.meenai.mina.core.agent.Turn
import com.meenai.mina.core.assembly.toolDispatcher
import com.meenai.mina.core.cap.sandbox.FakeSandbox
import com.meenai.mina.core.cap.sandbox.SandboxStatus
import com.meenai.mina.core.cap.web.FakeWebPort
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.core.prompt.Prompts
import com.meenai.mina.core.run.Attendance
import com.meenai.mina.core.run.RunId
import com.meenai.mina.core.run.RunOrigin
import com.meenai.mina.core.run.RunProvenance
import com.meenai.mina.core.run.RunSpec
import com.meenai.mina.core.testing.fakeModel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tools are divided into two layers: the common tools that are always there plus the entrance of
 * each group (short description). When a group is used for the first time, the entire group (full text)
 * is expanded and left until the end of the task; the order is fixed, and the expansion only changes the
 * part starting from that group; at the beginning, the groups used in the previous round and the
 * workspace files brought are pre-expanded.
 */
class ToolGroupsTest {
    private var seq = 0
    private fun call(name: String, args: String = "{}") = Part.ToolCall("g${++seq}", name, args)
    private fun all() = toolDispatcher(FakeScreen(), web = FakeWebPort(), sandbox = FakeSandbox(), workspace = { "w" })
    private fun names(specs: List<ToolSpec>) = specs.map { it.name }

    /** Only sent to the model during chat: general tools (excluding taking over, reporting obstacles, and reviewing the screen) plus five entrances. */
    private val chatOnly = listOf(
        "wait", "ask_user", "request_confirmation", "end_not_done",
        "phone_list_apps", "phone_launch_app", "phone_set_alarm", "phone_set_timer", "phone_add_calendar_event",
        "web_navigate", "web_read_page", "run_command",
    )

    @Test fun chatOnlyGetsBaseAndEntries() {
        val d = all()
        assertEquals(chatOnly, names(d.offeredNow()))
        assertEquals(emptySet<String>(), d.expanded)
        // Use a short description when closing the entry, and the parameters are the same as the full
        // text.
        val now = d.offeredNow().associateBy { it.name }
        val full = d.definitions().associateBy { it.name }
        for (e in listOf("phone_launch_app", "phone_list_apps", "phone_set_alarm", "phone_set_timer", "phone_add_calendar_event", "web_navigate", "web_read_page", "run_command")) {
            assertEquals(Prompts.entry(e), now.getValue(e).description)
            assertEquals(full.getValue(e).parametersJsonSchema, now.getValue(e).parametersJsonSchema)
            assertTrue(e, now.getValue(e).description.length < full.getValue(e).description.length)
        }
        // All tools (expand each group) one at a time
        assertTrue(names(d.definitions()).containsAll(listOf("phone_tap", "web_tap", "read_file", "hand_to_user", "handle_obstacle", "view_past_screens")))
    }

    @Test fun entryCallExpandsItsGroup() = runTest {
        val d = all()
        val r = d.execute(call("phone_launch_app", """{"package":"com.x"}""")).content
        assertTrue("phone" in r["moreTools"]!!.jsonPrimitive.content)
        assertEquals(setOf("phone"), d.expanded)
        val now = d.offeredNow()
        // The entire set of common tools available only with a screen are here, and the entrance is
        // changed back to the full text
        assertTrue(names(now).containsAll(listOf("phone_observe", "phone_tap", "hand_to_user", "handle_obstacle", "view_past_screens")))
        assertEquals(d.definitions().single { it.name == "phone_launch_app" }, now.single { it.name == "phone_launch_app" })
        // The web page and sandbox are still stored
        assertFalse("web_tap" in names(now))
        assertEquals(Prompts.entry("web_navigate"), now.single { it.name == "web_navigate" }.description)
        // No more words for the second call
        assertNull(d.execute(call("phone_launch_app", """{"package":"com.x"}""")).content["moreTools"])
        assertEquals(setOf("phone"), d.used)
    }

    @Test fun readersAloneDoNotExpand() = runTest {
        val d = all()
        d.execute(call("phone_list_apps"))
        // Setting an alarm clock and timer alone is enough: do not unfold the mobile phone as a set
        d.execute(call("phone_set_alarm", """{"hour":9,"minute":40}"""))
        d.execute(call("phone_set_timer", """{"seconds":600}"""))
        d.execute(call("web_read_page", """{"url":"https://example.com"}"""))
        assertEquals(emptySet<String>(), d.expanded)
        assertEquals(emptySet<String>(), d.used)
        assertEquals(chatOnly, names(d.offeredNow()))
    }

    @Test fun collapsedToolCalledFromMemoryRunsAndExpands() = runTest {
        val d = all()
        // The model did not see phone_observe (one group of mobile phones is closed), so it was adjusted
        // from memory: execute as usual, one group of mobile phones is expanded
        val r = d.execute(call("phone_observe")).content
        assertEquals("true", r["ok"]!!.jsonPrimitive.content)
        assertTrue(r["moreTools"] != null)
        assertEquals(setOf("phone"), d.expanded)
        // Expand if the parameters are incorrect: change it next time when you can see the full text
        val w = d.execute(call("web_tap", """{"bogus":1}""")).content
        assertEquals("false", w["ok"]!!.jsonPrimitive.content)
        assertTrue(w["moreTools"] != null)
        assertEquals(setOf("phone", "web"), d.expanded)
    }

    @Test fun orderIsFixedAndExpandingOnlyChangesFromItsGroup() = runTest {
        val d = all()
        val collapsed = d.offeredNow()
        d.execute(call("web_navigate", """{"url":"https://example.com"}"""))
        val web = d.offeredNow()
        d.execute(call("phone_launch_app", """{"package":"com.x"}"""))
        val both = d.offeredNow()
        d.execute(call("run_command", """{"command":"ls"}"""))
        val everything = d.offeredNow()
        // The ones who have always been there are always at the front and remain unchanged word for
        // word.
        val base = collapsed.takeWhile { it.name !in setOf("phone_list_apps", "phone_launch_app") }
        for (l in listOf(web, both, everything)) assertEquals(base, l.take(base.size))
        // Open the web page first and then the mobile phone, it's the same as the other way around: the
        // order only looks at the groups, not the order of expansion.
        assertEquals(both, toolDispatcher(FakeScreen(), web = FakeWebPort(), sandbox = FakeSandbox(), workspace = { "w" }).also {
            it.expand("phone"); it.expand("web")
        }.offeredNow())
        // Expand the sandbox (ranked last) and only change at the end: the first two groups of mobile
        // phones and web pages remain unchanged word for word.
        val runCommandAt = both.indexOfFirst { it.name == "run_command" }
        assertEquals(both.take(runCommandAt), everything.take(runCommandAt))
        // Each group is expanded: all the tools (in order by group)
        assertEquals(d.definitions().map { it.name }.toSet(), names(everything).toSet())
        assertEquals(everything, d.offeredNow(all = true))
        // The group of general tools with screens is arranged behind the always-on, and the group of
        // mobile phones is in front.
        val i = names(everything)
        assertTrue(i.indexOf("end_not_done") < i.indexOf("hand_to_user") && i.indexOf("view_past_screens") < i.indexOf("phone_observe"))
    }

    @Test fun batchSpecGoesWithScreenGroups() {
        val d = all()
        val batch = ToolSpec("do_steps", "d", "{}")
        assertFalse("do_steps" in names(d.offeredNow(listOf(batch))))
        d.expand("sandbox")
        assertFalse("do_steps" in names(d.offeredNow(listOf(batch))))
        d.expand("web")
        val now = names(d.offeredNow(listOf(batch)))
        // Ranked at the end of the group of general tools with screens, followed by the group of mobile
        // phones (closed, only entrance)
        assertEquals(now.indexOf("handle_obstacle") + 1, now.indexOf("do_steps"))
        assertEquals("phone_list_apps", now[now.indexOf("do_steps") + 1])
    }

    @Test fun preloadRules() {
        // Groups used in the previous round
        assertEquals(listOf("phone", "web"), all().preload("再看一下", listOf("web", "phone")))
        // Bring the files in the workspace (the attachment line says the path under /workspace/)
        assertEquals(listOf("sandbox"), all().preload("总结一下\n附件:/workspace/inbox/a.pdf(12 KB)", emptyList()))
        // Asked from the browser page to a page: Which page is already the current tab?
        val page = com.meenai.mina.core.cap.web.AttachedPage.compose(com.meenai.mina.core.cap.web.AttachedPage.Page("https://shop.example.com/", "店"), "挑一个")
        assertEquals(listOf("web"), all().preload(page, emptyList()))
        // Device does not support sandbox: do not expand
        val none = toolDispatcher(FakeScreen(), sandbox = FakeSandbox(SandboxStatus.Unsupported("x86")), workspace = { "w" })
        assertEquals(emptyList<String>(), none.preload("附件:/workspace/inbox/a.pdf", listOf("sandbox")))
        // Groups that are not included in this task will not be expanded; if there is nothing, they will
        // be collected.
        assertEquals(emptyList<String>(), toolDispatcher(FakeScreen()).preload("看看", listOf("web")))
        assertEquals(emptyList<String>(), all().preload("1+1 等于几", emptyList()))
    }

    @Test fun loopPreloadsFromPreviousTurnAndRecordsUsedGroups() = runTest {
        val m = fakeModel("main") { call("phone_launch_app", "package" to "com.x"); answer("好了") }
        val loop = AgentLoop(m, all(), SystemPrompt.identity())
        loop.run("打开看看")
        assertEquals(setOf("phone"), loop.usedGroups)
        // The next round will bring "Used mobile phone in the previous round": the first request at the
        // beginning will have a whole set of mobile phone tools and a mobile phone section.
        val m2 = fakeModel("main") { answer("好") }
        AgentLoop(m2, all(), SystemPrompt.identity()).run("再看一下", listOf(Turn("打开看看", "好了", "succeeded", groups = loop.usedGroups.toList())))
        val first = m2.requests.first()
        assertTrue("phone_tap" in first.tools.map { it.name })
        assertFalse("web_tap" in first.tools.map { it.name })
        assertTrue(SystemPrompt.read("phone").lineSequence().first() in first.system)
    }

    @Test fun childAndScheduledRunsUseTheSameLayers() = runTest {
        // The subtasks only have web pages and (read-only) sandboxes: web pages only provide entrances
        // and will be expanded when used; read-only sandboxes have no entrances (not given when running
        // commands), and that group will be expanded at the beginning.
        val child = toolDispatcher(FakeScreen(), web = FakeWebPort(), sandbox = FakeSandbox(), workspace = { "w" },
            runSpec = RunSpec(RunId("c"), "查", "t", provenance = RunProvenance(RunOrigin.Child, Attendance.Unattended)))
        val start = names(child.offeredNow())
        assertEquals(listOf("web_navigate", "web_read_page"), start.filter { it.startsWith("web_") || it.startsWith("phone_") })
        assertTrue("read_file" in start && "search_files" in start && "run_command" !in start)
        assertEquals(setOf("sandbox"), child.expanded)
        child.execute(call("web_navigate", """{"url":"https://example.com"}"""))
        assertEquals(setOf("sandbox", "web"), child.expanded)
        assertTrue("web_observe" in names(child.offeredNow()))
        // Tasks that are triggered on a scheduled basis (no one is present): no one will be given to
        // take over, and the rest will be layered in the same way
        val scheduled = toolDispatcher(FakeScreen(), web = FakeWebPort(), runSpec = RunSpec(RunId("s"), "查", "t",
            provenance = RunProvenance(RunOrigin.Trigger, Attendance.Unattended)))
        assertFalse("phone_observe" in names(scheduled.offeredNow()))
        scheduled.execute(call("phone_launch_app", """{"package":"com.x"}"""))
        val now = names(scheduled.offeredNow())
        assertTrue("phone_observe" in now && "handle_obstacle" in now && "hand_to_user" !in now)
    }
}
