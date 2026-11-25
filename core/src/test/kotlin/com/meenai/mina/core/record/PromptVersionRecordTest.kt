package com.meenai.mina.core.record

import com.meenai.mina.core.agent.AgentLoop
import com.meenai.mina.core.agent.SystemPrompt
import com.meenai.mina.core.prompt.PromptVersion
import com.meenai.mina.core.testing.fakeModel
import com.meenai.mina.core.tool.FakeScreen
import com.meenai.mina.core.tool.ToolDispatcher
import com.meenai.mina.core.cap.web.FakeWebPort
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import com.meenai.mina.core.assembly.toolDispatcher

/**
 * Write down the version of the prompt word for this task in the running record: the system prompt word
 * plus a summary of the tool definition sent to the model when each group is expanded, has nothing to do
 * with which groups are actually expanded this time (when there is only a mobile phone, this is what you
 * see after expanding a group of mobile phones).
 */
class PromptVersionRecordTest {
    private suspend fun run(tools: ToolDispatcher): Pair<String, String> {
        val events = mutableListOf<RunEvent>()
        val m = fakeModel("main") { call("phone_observe"); answer("好了") }
        AgentLoop(m, tools, SystemPrompt.identity(), sink = object : RunSink {
            override fun event(e: RunEvent) { events += e }
        }).run("t")
        val r = m.requests.last()
        return events.filterIsInstance<RunEvent.PromptVersion>().single().hash to PromptVersion.of(r.system, r.tools)
    }

    @Test fun recordsHashOfWhatTheModelSaw() = runTest {
        val (phone, expected) = run(toolDispatcher(FakeScreen()))
        assertEquals(expected, phone)
        val (web, _) = run(toolDispatcher(FakeScreen(), web = FakeWebPort()))
        assertNotEquals(phone, web)
    }
}
