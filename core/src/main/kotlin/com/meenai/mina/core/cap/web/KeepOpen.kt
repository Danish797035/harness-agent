package com.meenai.mina.core.cap.web

import com.meenai.mina.core.prompt.Feedback
import com.meenai.mina.core.prompt.Prompts
import com.meenai.mina.core.tool.Args
import com.meenai.mina.core.tool.Risk
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.tool.ToolDef
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.Verb
import com.meenai.mina.core.tool.argStr
import com.meenai.mina.core.tool.failCode
import com.meenai.mina.core.tool.ok
import com.meenai.mina.core.tool.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Why was the page left closed? [wire] was recorded in the conversation. When the time is up, the memory
 * is insufficient and the system reclaims it, the rendering process crashes, the user closes it, leaves
 * another page, and Mina exits.
 */
enum class KeptPageClose(val wire: String) {
    Expired("expired"), Memory("memory"), Crashed("crashed"), User("user"), Replaced("replaced"), AppExit("app_exit");

    companion object {
        fun of(wire: String?): KeptPageClose? = entries.firstOrNull { it.wire == wire }
    }
}

/** The page left after the task is completed will be kept for a few hours at most (it will be automatically closed at that time). */
const val KEEP_OPEN_HOURS = 2

/**
 * When the task ends, all web pages of this task will be closed by default; when you want the page to
 * continue running after answering (then play, hang, and wait for itself to finish), use this to leave
 * the current page. Just write down "Keep when finished", and nothing will be moved at this moment; only
 * one page will be left globally, and keeping the new one will close the old one; use keep=false to
 * undo.
 */
internal object KeepOpen : WebTool {
    override val verb = Verb.KeepOpen
    override val label = "Keep page open"
    override val risk = Risk.Write
    override val logged = true
    override val def = ToolDef("web_keep_open",
        Prompts.tool("web_keep_open", "hours" to KEEP_OPEN_HOURS),
        mapOf(
            "reason" to str("Why this page must keep running after the task, in a few words (e.g. the user wants the video to keep playing)."),
            "keep" to buildJsonObject { put("type", "boolean"); put("description", "false takes back an earlier keep in this task (default true).") },
        ), listOf("reason"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val reason = a.str("reason", max = 200)!!.trim()
        val keep = a.bool("keep", true)
        val r = try { ctx.web!!.keepOpen(keep, reason) } catch (e: WebTabCrashed) { return failCode("tab_crashed") }
            catch (e: WebTabIsUsers) { return failCode("users_page") }
            catch (e: UnsupportedOperationException) { return failCode("keep_open_unavailable") }
            ?: return failCode("no_tab_open")
        return ok {
            put("keep", r.keep)
            r.tabId?.let { put("tabId", it) }
            r.host?.let { put("site", it) }
            r.title?.let { put("title", it) }
            if (r.keep) {
                put("hours", r.hours)
                r.replaces?.let { put("replaces", it) }
                put("hint", if (r.replaces != null) Feedback.format("keep_open.replaces", "site" to r.replaces, "hours" to r.hours.toString())
                    else Feedback.format("keep_open.kept", "hours" to r.hours.toString()))
            } else put("hint", Feedback["keep_open.taken_back"])
        }
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = o.argStr("reason")
}
