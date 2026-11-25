package com.meenai.mina.core.testing

import com.meenai.mina.core.assembly.knownTools
import com.meenai.mina.core.assembly.recordedTools
import com.meenai.mina.core.cap.sandbox.SANDBOX
import com.meenai.mina.core.cap.web.WEB
import com.meenai.mina.core.tool.ToolIndex
import com.meenai.mina.core.tool.Verb

/** For testing: Declaration of all Mina tools. */
internal val TOOLS: ToolIndex get() = knownTools()

/** For testing: read the tool statement of the running record (the old name before the name change is also recognized). */
internal val RECORDED_TOOLS: ToolIndex get() = recordedTools()

/** For testing: press the tool name to check the actions it performs. */
internal fun verb(name: String): Verb? = TOOLS.verb(name)

/** For testing: Tool names that can be put into do_steps, in the order in the definition. */
internal val BATCHABLE: List<String> get() = TOOLS.names.filter { TOOLS[it]!!.batchable }

/** For testing: the tool name of a set of web pages and sandboxes. */
internal val WEB_TOOLS: Set<String> get() = TOOLS.names.filter { TOOLS[it]!!.capability == WEB }.toSet()
internal val SANDBOX_TOOLS: Set<String> get() = TOOLS.names.filter { TOOLS[it]!!.capability == SANDBOX }.toSet()

internal const val WEB_SCRIPT_TOOL = "web_script"
internal const val INTERACT_TOOL = "web_interact"

/** For testing: the reply without call is the final answer given to the user. */
internal fun answerReply(text: String) =
    com.meenai.mina.core.model.ModelReply(listOf(com.meenai.mina.core.model.Part.Text(text)), com.meenai.mina.core.model.StopReason.EndTurn, null)

/** For testing: the task is completed, the answer is [answer], there is no verification problem (it has been verified, or there is nothing that can be verified; the source will be tested separately). */
internal fun assertAnswered(answer: String, end: com.meenai.mina.core.agent.TaskState) {
    val s = end as? com.meenai.mina.core.agent.TaskState.Succeeded ?: throw AssertionError("not succeeded: $end")
    org.junit.Assert.assertEquals(answer, s.answer)
    org.junit.Assert.assertTrue("$end", !s.doubtful && s.unconfirmed.isEmpty())
}
