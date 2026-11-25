package com.meenai.mina.core.action

import com.meenai.mina.core.gate.ConfirmationGate
import com.meenai.mina.core.guard.OperationGuards
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.record.RunSink
import com.meenai.mina.core.uistate.UiElement
import com.meenai.mina.core.tool.FakeScreen
import com.meenai.mina.core.cap.web.SCRIPT_MAX_READS
import com.meenai.mina.core.tool.ScriptWebPort
import com.meenai.mina.core.tool.execute
import com.meenai.mina.core.cap.web.FakeWebPort
import com.meenai.mina.core.cap.web.ScriptRun
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.meenai.mina.core.testing.WEB_SCRIPT_TOOL
import com.meenai.mina.core.assembly.toolDispatcher

/** The page script polls an unchanged page: reads are only counted in this script call, and the maximum reminder is that the task will not be stopped, and it will not be carried to subsequent reads of the model. */
class ScriptReadsTest {
    @Test fun pollingAnUnchangedPageNeitherStopsTheTaskNorLeaks() = runTest {
        val web = FakeWebPort().apply { elements = listOf(UiElement("@e1", "button", "搜索", 540, 120, listOf(0, 0, 999, 100), "#go", true, false, false)) }
        val port = ScriptWebPort(web) { b ->
            repeat(SCRIPT_MAX_READS) { b.call("snapshot", JsonObject(emptyMap())) }
            ScriptRun.Done("\"done\"")
        }
        val tools = toolDispatcher(FakeScreen(), web = port)
        tools.execute(Part.ToolCall("n", "web_navigate", """{"url":"https://shop.example.com/"}"""))
        val guards = OperationGuards()
        val executor = ActionExecutor(tools, guards, ConfirmationGate(), gate = {}, recorder = ActionRecorder(RunSink.NONE))

        val script = executor.run(Part.ToolCall("s", WEB_SCRIPT_TOOL, """{"code":"poll"}"""), Origin.Model(1))
        assertTrue("$script", script is ActionOutcome.Ran)
        assertNull(guards.stop)
        val reads = (script as ActionOutcome.Ran).after.filter { it.code == "read_loop" }
        assertTrue("脚本里的重读要提醒", reads.isNotEmpty())
        // Repeated reads (read_loop, repeating_action) only remind
        assertTrue(script.verdicts.all { it.severity == com.meenai.mina.core.guard.Severity.Warn })

        // After that, the model reads the same page by itself: counting from the beginning according to
        // the count of tasks, and not reminding the first few times.
        val later = (2..5).map { executor.run(Part.ToolCall("m$it", "phone_list_elements", "{}"), Origin.Model(it)) as ActionOutcome.Ran }
        assertEquals(emptyList<String>(), later.flatMap { o -> o.verdicts.map { it.code } }.filter { it == "read_loop" })
        assertNull(guards.stop)
    }
}
