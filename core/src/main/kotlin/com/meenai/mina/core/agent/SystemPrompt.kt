package com.meenai.mina.core.agent

import com.meenai.mina.core.capability.PromptEnv
import com.meenai.mina.core.capability.PromptSections
import com.meenai.mina.core.prompt.Prompts

/**
 * System prompt words: P0 identity (prompts/system/identity.md), P1 general criteria
 * (system/principles.md, not naming tools), connect to the general rules of general tools
 * (capabilities/general.md, general tools are always there), and then connect the paragraphs given by
 * the core self and each ability. The paragraph only exists if that set of tools appears in the tool
 * list sent to the model for this task: the instructions and tools come from the same judgment. Spell it
 * once at the beginning of the task, and keep every word in the task unchanged.
 */
object SystemPrompt {
    fun identity(): String = listOf(Prompts.system("identity"), Prompts.system("principles"), read(GENERAL)).joinToString("\n\n")

    fun assemble(identity: String, sections: List<String>): String = (listOf(identity) + sections).joinToString("\n\n")

    private const val GENERAL = "general"

    /** A description of your abilities. */
    internal fun read(name: String): String = Prompts.capability(name)
}

/**
 * The core paragraphs are arranged before each ability: which entrance should be used to expand the
 * groups (groups, only the entrances given by this task are listed; keep them after expansion, and the
 * system prompt words will change less); how to choose when there are more than two screens, and how to
 * cooperate between the two screens (channels); after the screen is expanded, it is given to the user
 * and checked before the order is sent (handover); how to read the action result when it has its own
 * screen (inline-screen); multiple steps at a time (batch).
 */
internal object CoreSections : PromptSections {
    override fun sections(env: PromptEnv): List<String> = listOfNotNull(
        env.gate(SystemPrompt.read("groups")).takeIf { it.lines().size > 1 },
        SystemPrompt.read("channels").takeIf { env.screens >= 2 },
        SystemPrompt.read("handover").takeIf { env.openScreens >= 1 },
        SystemPrompt.read("inline-screen").takeIf { env.inlineScreen && env.openScreens >= 1 },
        SystemPrompt.read("batch").takeIf { DO_STEPS_TOOL in env.offered },
    ).map(env::gate)
}
