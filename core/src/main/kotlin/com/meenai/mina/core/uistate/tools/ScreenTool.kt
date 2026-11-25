package com.meenai.mina.core.uistate.tools

import com.meenai.mina.core.uistate.ScreenFamily
import com.meenai.mina.core.prompt.Prompts
import com.meenai.mina.core.uistate.PressKeys
import com.meenai.mina.core.tool.AgentTool
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.tool.Verb
import com.meenai.mina.core.uistate.sessions

/**
 * One of a set of screen tools: the name is `<screen>_<verb>`, and it only works on the screen [family](the
 * distributor notes it before calling it). The instructions use the shared template
 * prompts/tools/<verb>.md (use <screen>_<verb>.md when this screen has its own copy), and the tools named in the
 * template are replaced with the names of this set.
 */
internal abstract class ScreenTool(val family: ScreenFamily, val base: String) : AgentTool {
    final override val name: String = family.tool(base)
    override val capability: String get() = family.id
    /** The text results in the mobile phone set can be adjusted from the sandbox; the picture results (picture results require visual models) are not open. */
    override val sandboxCallable: Boolean get() = family.sandboxCallable && verb !in IMAGE_VERBS
    override val promptFile: String get() = if (base in family.ownPrompts) name else base

    /** This screen is only provided after it is connected to the port; [usable] is an additional condition for each tool. */
    final override fun available(ctx: ToolContext): Boolean = family.id in ctx.sessions && usable(ctx)

    protected open fun usable(ctx: ToolContext): Boolean = true

    /** Full description: {{verb}} in the template is replaced by the tool name in this set. {{keys}} is the key that can be pressed on this screen. */
    protected fun describe(vararg vars: Pair<String, Any?>): String =
        Prompts.tool(promptFile, *(MIRRORED.map { it to family.tool(it) } + ("keys" to keyNames(family)) + vars).toTypedArray())

    override fun toString() = name

    companion object {
        private val IMAGE_VERBS = setOf(Verb.Screenshot, Verb.Describe, Verb.FindVisually)

        /** Each screen has verbs in the order they are sent to the model. */
        val MIRRORED = listOf(
            "observe", "list_elements", "screenshot", "describe", "find_visually",
            "tap", "scroll", "swipe", "type_text", "clear_text", "back", "press_key", "diagnose",
        )

        /** The name of the key that press_key can press on a screen. */
        fun keyNames(f: ScreenFamily): String =
            PressKeys.BY_NAME.filterValues { f.keys == null || it in f.keys }.keys.joinToString()

        /** The set of screen tools for one screen is in the order of [MIRRORED]. */
        fun of(f: ScreenFamily): List<AgentTool> = listOf(
            Observe(f), ListElements(f), Screenshot(f), Describe(f), FindVisually(f),
            Tap(f), Scroll(f), Swipe(f), TypeText(f), ClearText(f), Back(f), PressKey(f), Diagnose(f),
        ).also { tools -> check(tools.map { it.base } == MIRRORED) }
    }
}
