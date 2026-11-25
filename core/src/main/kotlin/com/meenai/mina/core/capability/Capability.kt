package com.meenai.mina.core.capability

import com.meenai.mina.core.uistate.ScreenFamily
import com.meenai.mina.core.uistate.Surface
import com.meenai.mina.core.guard.OffScreenGuard
import com.meenai.mina.core.knowledge.BundledScripts
import com.meenai.mina.core.observation.Look
import com.meenai.mina.core.tool.AgentTool
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.Verb
import kotlinx.serialization.json.JsonObject

/** Can the ability be used at this moment? */
sealed interface CapabilityStatus {
    data object Available : CapabilityStatus
    /** It works, but there are still some shortcomings (the operating environment is being prepared, and some functions are not available). */
    data class Degraded(val reason: String) : CapabilityStatus
    data class Unavailable(val reason: String) : CapabilityStatus
}

/**
 * What can be seen in each group of paragraphs when spelling the system prompt words: the name and
 * switch of the tool sent to the model in this task; [screens] is the several screens in the tool sent
 * to the model in this task (mobile phone, web page..., the ones kept are only given to the entrance).
 */
class PromptEnv(
    val offered: Set<String>, val inlineScreen: Boolean, val screens: Int = 1,
    /** Expanded abilities (including those that are not closed); null means all are expanded. The ability to collect is not given to your own paragraphs. */
    val open: Set<String>? = null,
    /** The unfolded screens in [screens]. */
    val openScreens: Int = screens,
) {
    /** Capability [id] has been expanded (or not closed). */
    fun isOpen(id: String): Boolean = open == null || id in open

    /**
     * A line in the paragraph that is only valid when certain tools are provided. The first line of the
     * line is `<!-- needs: tool name,... -->`: Those tools are left in the tool list of this task (remove the
     * mark), otherwise the entire line is not needed. Unmarked rows are left as is.
     */
    fun gate(text: String): String = text.lines().mapNotNull { line ->
        val m = NEEDS.find(line) ?: return@mapNotNull line
        val tools = m.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (tools.all { it in offered }) m.groupValues[1] + m.groupValues[3] else null
    }.joinToString("\n")

    private companion object {
        val NEEDS = Regex("^(\\s*)<!-- needs: ([a-z0-9_, ]+) -->\\s?(.*)$")
    }
}

/** The system prompts a group of paragraphs in the words; one group is given for each ability, and the core is also included. */
internal fun interface PromptSections {
    fun sections(env: PromptEnv): List<String>
}

/**
 * An ability (mobile phone, web page, sandbox...): own tools, paragraphs in system prompt words, screens
 * and guards. The core only knows capabilities through this interface; one instance per task, and the
 * port is given at construction time.
 */
internal interface Capability : PromptSections {
    val id: String

    fun status(): CapabilityStatus

    /** This set of tools is expanded only when used (see [com.meenai.mina.core.tool.ToolGroups]): there are only entrance tools at the beginning; all the tools that are not collected are always there. */
    val folds: Boolean get() = false

    /** If you press this time ([task]), this group will be expanded at the beginning (must be used when needed); it will not be expanded by default. */
    fun preload(task: String): Boolean = false

    /** The user's sentence ([text]) is typed by the user himself after removing the description related to this ability (such as the page description on the browser page); if there is no such description, it is returned as it is. */
    fun userWords(text: String): String = text

    /** All tools that this capability may provide; whether this task can be used depends on the context of each tool. */
    fun tools(): List<AgentTool>

    /** The ability to have a screen gives the appearance of a set of screen tools (also when not connected to a port). */
    val family: ScreenFamily? get() = null

    /** Screen of the port connected; null if not. The set of screen tools for this ability only works on it. */
    val surface: Surface? get() = null

    /** It is given when the skill script can be run. */
    val scripts: BundledScripts? get() = null

    /** Start this task: Put the port and status that the tool will use into the tool context. */
    fun onTaskStart(ctx: ToolContext) {}

    /** The user answered the question in the task (answered the follow-up question and handed over the acceptance). */
    fun onUserAnswered(ctx: ToolContext) {}

    /**
     * An external action (send, submit, see [com.meenai.mina.core.action.OutwardPacing]) is required on
     * the screen of this capability; [site] is the website or App where it is located. After finishing,
     * be sure to follow [onOutwardEnd]([done]:It’s done).
     */
    fun onOutwardStart(ctx: ToolContext, site: String) {}

    fun onOutwardEnd(ctx: ToolContext, site: String, done: Boolean) {}

    /**
     * There is an action to change the screen ([verb]) on the screen of this ability: stop for a while
     * according to the rhythm of this ability (see [com.meenai.mina.core.action.ActionExecutor], each
     * step in single step and batch passes here). [outward]: This step is an external action (send,
     * submit). The default is non-stop.
     */
    suspend fun beforeWrite(ctx: ToolContext, verb: Verb?, outward: Boolean): Paced = Paced.NONE

    /** That action is completed ([result] is its result); [beforeWrite] stops and does not adjust. */
    fun afterWrite(ctx: ToolContext, verb: Verb?, result: JsonObject) {}

    /** Guard for tools that don't move the screen, one per task; null if not. */
    fun guard(): OffScreenGuard? = null

    /**
     * Before checking, take a look at what this ability looks like at the moment (without changing
     * anything in the model's hand); [withImage] is accompanied by a frame. null if there is nothing to
     * see.
     */
    suspend fun freshLook(ctx: ToolContext, withImage: Boolean): Look? = null
}

/**
 * The result of stopping according to the rhythm of the ability before changing the screen action:
 * [waitMs] (counting into the wait of this call) is stopped, and [idleScrolls] is lightly scrolled
 * during the stay (only the running record is entered); this action is not performed when [stop] is not
 * null, which is the result of this call (the upper limit of the number of screens has been reached, and
 * the user interrupted during the stay).
 */
internal class Paced(val waitMs: Long = 0, val idleScrolls: Int = 0, val stop: ToolExecution? = null) {
    companion object {
        val NONE = Paced()
    }
}

/** The ability to put keys into things in the tool context; the core doesn't know what's inside. */
class CapabilityKey<T : Any>(val name: String)
