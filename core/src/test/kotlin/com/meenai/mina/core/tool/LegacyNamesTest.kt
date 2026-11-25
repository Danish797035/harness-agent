package com.meenai.mina.core.tool

import com.meenai.mina.core.assembly.knownTools
import com.meenai.mina.core.assembly.legacyNames
import com.meenai.mina.core.assembly.toolDispatcher
import com.meenai.mina.core.cap.web.FakeWebPort
import com.meenai.mina.core.knowledge.GuideCatalog
import com.meenai.mina.core.memory.MemoryContext
import com.meenai.mina.core.memory.MemoryItem
import com.meenai.mina.core.memory.MemoryKind
import com.meenai.mina.core.memory.MemorySource
import com.meenai.mina.core.memory.MemoryStatus
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.replay.Anchors
import com.meenai.mina.core.replay.Route
import com.meenai.mina.core.replay.RouteStep
import com.meenai.mina.core.replay.withCurrentToolNames
import com.meenai.mina.core.skills.InMemoryPersonalSkills
import com.meenai.mina.core.skills.PersonalSkill
import com.meenai.mina.core.skills.SkillStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the thing before the name change is read in, it will be replaced with the current name according to the old name comparison table; calling the old name will only prompt, not execute for the model. */
class LegacyNamesTest {
    @Test fun unknownLegacyCallNamesTheReplacementAndDoesNothing() = runTest {
        val screen = FakeScreen()
        val d = toolDispatcher(screen, web = FakeWebPort())
        val r = d.execute(Part.ToolCall("c", "tap", """{"x":1,"y":1}""")).content
        assertEquals("unknown_tool", r["error"]!!.jsonPrimitive.content)
        val m = r["message"]!!.jsonPrimitive.content
        assertTrue(m, "phone_tap or web_tap" in m && "renamed" in m)
        assertTrue(screen.calls.isEmpty())
        // When only a mobile phone is provided, only mention the mobile phone
        val phoneOnly = toolDispatcher(FakeScreen()).execute(Part.ToolCall("c", "browser_navigate", "{}")).content
        assertFalse(phoneOnly["message"]!!.jsonPrimitive.content.contains("renamed"))
        val launch = toolDispatcher(FakeScreen()).execute(Part.ToolCall("c", "launch_app", "{}")).content["message"]!!.jsonPrimitive.content
        assertTrue(launch, "phone_launch_app" in launch)
    }

    // Removed finish: The saved skills and the place where it is called in the practice are replaced
    // with the current practice; when the model adjusts it, explain how it will end now.
    @Test fun retiredFinishIsRewrittenAndExplained() = runTest {
        val legacy = legacyNames()
        val zh = legacy.rewrite("查完后调用 finish 交结果")
        assertFalse(zh, "finish" in zh)
        assertTrue(zh, "reply to the user with your answer as text" in zh)
        val en = legacy.rewrite("When done, call `finish` with the price. The page may finish loading slowly.")
        assertTrue(en, "When done, reply to the user with your answer as text" in en)
        // It’s not that the tool’s finish (verb in English) doesn’t move.
        assertTrue(en, "may finish loading" in en)
        val r = toolDispatcher(FakeScreen()).execute(Part.ToolCall("c", "finish", """{"answer":"x"}""")).content
        val m = r["message"]!!.jsonPrimitive.content
        assertTrue(m, "no tool 'finish'" in m && "without calling any tool" in m)
    }

    // Removed phone_ask_app_ai: The place where it was called in the old records and old skills was
    // replaced by the current method. When the model adjusts it, it is clearly stated that it is no
    // longer there.
    @Test fun retiredAskAppAiIsRewrittenAndExplained() = runTest {
        val zh = legacyNames().rewrite("先 `phone_ask_app_ai` 问一下")
        assertFalse(zh, "phone_ask_app_ai" in zh)
        assertTrue(zh, "read the app's own screens yourself" in zh)
        val m = toolDispatcher(FakeScreen()).execute(Part.ToolCall("c", "phone_ask_app_ai", "{}")).content["message"]!!.jsonPrimitive.content
        assertTrue(m, "no tool 'phone_ask_app_ai'" in m)
    }

    @Test fun storedRoutesGetTheRoutableName() {
        val none = Anchors(null, emptyList())
        val r = Route("r", "t", 0, listOf("com.a"), emptyMap(), listOf(
            RouteStep("launch_app", "{}", null, none, none), RouteStep("tap", "{}", null, none, none), RouteStep("phone_back", "{}", null, none, none),
        ), lastUsedMs = 1)
        assertEquals(listOf("phone_launch_app", "phone_tap", "phone_back"), r.withCurrentToolNames(legacyNames(), knownTools()).steps.map { it.tool })
    }

    @Test fun personalSkillsAndProceduresAreRewrittenWhenLoaded() {
        val md = "---\nname: notes\ndescription: d\n---\n## 步骤\n1. 用 `launch_app` 打开,`tap` 新建"
        val catalog = GuideCatalog.withPersonal(GuideCatalog.loadBundled(), InMemoryPersonalSkills(PersonalSkill("notes", md, SkillStatus.active, null, null, 0, emptyList())), legacyNames()::rewrite)
        val text = catalog.fullText(catalog.items.first { it.name == "notes" })!!
        assertTrue(text, "`phone_launch_app`" in text && "`phone_tap` / `web_tap`" in text)
        val item = MemoryItem("m1", MemoryKind.procedure, "打开备忘录先 launch_app 再搜索", listOf("备忘录"), MemorySource("manual", atMs = 0), MemoryStatus.active, pinned = true, createdAt = 0, updatedAt = 0)
        val block = MemoryContext.build(listOf(item), "打开备忘录", null, legacyNames()::rewrite)
        assertTrue(block.message.orEmpty(), "phone_launch_app" in block.message.orEmpty())
    }

    // The second name change: When the old name has been completely changed, the new name will be
    // prompted when called, and the words in the text will be replaced by the new name.
    @Test fun movedNamesAreHintedAndRewritten() = runTest {
        val d = toolDispatcher(FakeScreen(), web = FakeWebPort())
        fun hint(name: String) = d.legacy.replacements(name)
        assertEquals(listOf("end_not_done"), hint("report_failure"))
        assertEquals(listOf("hand_to_user"), hint("user_takeover"))
        assertEquals(listOf("ask_user"), hint("request_input"))
        assertEquals(listOf("handle_obstacle"), hint("report_barrier"))
        assertEquals(listOf("view_past_screens"), hint("recall_screenshot"))
        assertEquals(listOf("run_bundled_script"), hint("run_script"))
        assertEquals(listOf("phone_list_elements"), hint("phone_dump_elements"))
        assertEquals(listOf("web_diagnose"), hint("web_check_screen"))
        // The names shared by several screens in the earliest generation are directly related to the
        // current ones.
        assertEquals(setOf("phone_find_visually", "web_find_visually"), hint("locate_on_screen").toSet())
        assertEquals(setOf("phone_describe", "web_describe"), hint("screen_description").toSet())
        val m = d.execute(Part.ToolCall("c", "report_failure", """{"message":"x"}""")).content["message"]!!.jsonPrimitive.content
        assertTrue(m, "end_not_done" in m && "renamed" in m)
        val text = legacyNames().rewrite("先 phone_dump_elements,不行就调用 `user_takeover`,再 phone_check_screen")
        assertEquals("先 phone_list_elements,不行就调用 `hand_to_user`,再 phone_diagnose", text)
    }

    // The old name is divided into two parts: knowledge and skill: select the corresponding one by kind
    // or name prefix, mention both if you can't figure it out.
    @Test fun splitNamesPickByKind() = runTest {
        val d = toolDispatcher(FakeScreen(), skills = GuideCatalog.loadBundled())
        suspend fun message(name: String, args: String) = d.execute(Part.ToolCall("c", name, args)).content["message"]!!.jsonPrimitive.content
        val app = message("find_skills", """{"query":"外卖","kind":"app"}""")
        assertTrue(app, "knowledge_search" in app && "skill_search" !in app)
        val skill = message("find_skills", """{"query":"文档","kind":"skill"}""")
        assertTrue(skill, "skill_search" in skill && "knowledge_search" !in skill)
        val either = message("find_skills", """{"query":"外卖"}""")
        assertTrue(either, "knowledge_search or skill_search" in either)
        val site = message("skill_view", """{"name":"site:jd.com"}""")
        assertTrue(site, "knowledge_read" in site && "skill_read" !in site)
        val personal = message("skill_view", """{"name":"personal:my-trip"}""")
        assertTrue(personal, "skill_read" in personal && "knowledge_read" !in personal)
        val bare = message("skill_view", """{"name":"meituan"}""")
        assertTrue(bare, "knowledge_read or skill_read" in bare)
        // I can’t tell which one it is from the saved text, so I write both.
        assertEquals("用 `knowledge_read` / `skill_read` 看全文", legacyNames().rewrite("用 `skill_view` 看全文"))
    }

    // The running records recorded before the name change: the old name is recognized as the current
    // tool
    @Test fun recordedOldNamesResolve() {
        val tools = com.meenai.mina.core.assembly.recordedTools()
        assertEquals(Verb.EndNotDone, tools.verb("report_failure"))
        assertEquals(Verb.ListElements, tools.verb("phone_dump_elements"))
        assertEquals(Verb.ListElements, tools.verb("dump_elements"))
        assertEquals(Verb.HandToUser, tools.verb("user_takeover"))
    }
}
