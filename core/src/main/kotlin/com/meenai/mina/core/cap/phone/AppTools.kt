package com.meenai.mina.core.cap.phone

import com.meenai.mina.core.tool.Entry
import com.meenai.mina.core.observation.ObsKind
import com.meenai.mina.core.observation.Observation
import com.meenai.mina.core.prompt.Feedback
import com.meenai.mina.core.prompt.Prompts
import com.meenai.mina.core.tool.ArgError
import com.meenai.mina.core.tool.Args
import com.meenai.mina.core.tool.EvidenceLog
import com.meenai.mina.core.tool.Risk
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.tool.ToolDef
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.Verb
import com.meenai.mina.core.tool.argStr
import com.meenai.mina.core.tool.bool
import com.meenai.mina.core.tool.int
import com.meenai.mina.core.tool.failCode
import com.meenai.mina.core.tool.ok
import com.meenai.mina.core.tool.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import com.meenai.mina.core.tool.LinkPolicy
import com.meenai.mina.core.uistate.ActionResult
import com.meenai.mina.core.uistate.APP_IN_USE_BY_OTHER_TASK
import com.meenai.mina.core.uistate.AppWait
import com.meenai.mina.core.uistate.action
import com.meenai.mina.core.uistate.session

/** List and open apps on your phone: always works on your phone. */
internal object ListApps : PhoneTool {
    override val verb = Verb.ListApps
    override val label = "List installed apps"
    override val risk = Risk.Read
    override val entry = Entry.Alone
    override val def = ToolDef("phone_list_apps", Prompts.tool("phone_list_apps"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val apps = ctx.phone.screen.listApps()
        // It is also installed in another user copy such as clones and work materials: each copy has a
        // separate item and the user to use to open it.
        val copies = ctx.phone.screen.appCopies().groupBy { it.packageName }
        val access = ctx.phone.screen.appAccess.current()
        return ok {
            put("packages", JsonArray(apps.map { JsonPrimitive(it.first) }))
            put("apps", JsonArray(apps.flatMap { (p, l) ->
                listOf(buildJsonObject { put("package", p); put("label", l); put("blocked", p in access.blocked); put("screenShareAllowed", p in access.shareAllowed) }) +
                    copies[p].orEmpty().map { c -> buildJsonObject { put("package", p); put("label", c.label(l)); put("user", c.userId) } }
            }))
        }
    }

    /** The listed App names are not used as separate evidence. They are written down for later use when opening the App (the name of the other copy is not recorded, so as not to overwrite the original copy). */
    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        (r.content["apps"] as? JsonArray)?.forEach { a ->
            val o2 = a as? JsonObject ?: return@forEach
            if ("user" in o2) return@forEach
            val p = (o2["package"] as? JsonPrimitive)?.contentOrNull ?: return@forEach
            (o2["label"] as? JsonPrimitive)?.contentOrNull?.let { log.appLabels[p] = it }
        }
        return emptyList()
    }
}

internal object LaunchApp : PhoneTool {
    override val verb = Verb.LaunchApp
    override val batchable = true
    override val routable = true
    override val label = "Open app"
    override val risk = Risk.Write
    override val entry = Entry.Opens
    override val showsScreenAfter = true
    override val logged = true
    override val def = ToolDef("phone_launch_app", Prompts.tool("phone_launch_app"),
        mapOf(
            "package" to str("Exact package name, e.g. com.example.app."),
            "cold" to bool("Restart the app first (default false)."),
            "user" to int("Only to open another copy of the app (a clone or work-profile copy): the user value of that copy's phone_list_apps entry. Leave out for the main app.", min = 0, max = MAX_USER),
            "wait" to int("Only when another task is using this app: seconds to wait for it to be free, then open it (at most $MAX_WAIT_S). Leave out to get the in-use result at once.", min = 1, max = MAX_WAIT_S),
        ),
        listOf("package"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val p = a.str("package")!!
        if (!LinkPolicy.PACKAGE.matches(p)) throw ArgError("package", "must be an Android package name")
        val user = a.int("user", 0, MAX_USER)
        val cold = a.bool("cold", false)
        val wait = a.int("wait", 1, MAX_WAIT_S)
        // Only open the ones listed: those that are not available to another user (private space, other
        // people's users, not installed) will not be touched.
        if (user != null && ctx.phone.screen.appCopies().none { it.packageName == p && it.userId == user }) {
            return ctx.action(ActionResult.Failed(COPY_NOT_FOUND))
        }
        // After changing the app, the payment declaration that was rejected before is no longer the case
        // this time.
        ctx.refusedPay = null
        suspend fun open() = if (user != null) ctx.phone.screen.openAppCopy(p, user, cold) else ctx.phone.screen.openApp(p, cold)
        var opened = open()
        // Other tasks are in use, the model is required, wait: wait for it to be released and open it
        // again; wait for each other (no one can wait), wait until the time limit is still occupied,
        // tell the truth
        if (wait != null && opened is ActionResult.Failed && opened.reason == APP_IN_USE_BY_OTHER_TASK) {
            when (ctx.phone.screen.awaitAppFree(p, user, wait * 1_000L)) {
                AppWait.Free -> opened = open()
                AppWait.WouldDeadlock -> return failCode(WAIT_WOULD_DEADLOCK)
                AppWait.TimedOut -> return failCode(WAIT_TIMED_OUT)
                AppWait.Unsupported -> Unit
            }
        }
        val r = ctx.action(opened)
        if (r.content["ok"] != JsonPrimitive(true)) return r
        ctx.appOpened = true
        return unreadable(ctx, p, r)
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) =
        o.argStr("package")?.let { p -> (o["user"] as? JsonPrimitive)?.contentOrNull?.let { "$p (user $it)" } ?: p }

    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        return listOfNotNull(o.argStr("package")?.let { p ->
            val copy = (o["user"] as? JsonPrimitive)?.contentOrNull?.let { "(另一份,用户 $it)" }.orEmpty()
            Observation(ObsKind.Fact, "$name 打开了 $p$copy" + (log.appLabels[p]?.let { "(App 名字「$it」)" } ?: ""), source = PHONE)
        })
    }

    /** The upper limit of Android user number (within UserHandle.PER_USER_RANGE). */
    private const val MAX_USER = 99_999
    /** Let go of the app and wait for other tasks. Wait a few seconds at most: if it takes longer, it is better to do other things first, or tell the user. */
    const val MAX_WAIT_S = 180
    const val COPY_NOT_FOUND = "copy_not_found"
    const val WAIT_WOULD_DEADLOCK = "app_wait_would_deadlock"
    const val WAIT_TIMED_OUT = "app_wait_timed_out"
}

/**
 * Should the app remain or be closed at the end of the task? The default is to keep it (put it back in
 * the background, stop on the page where you finished it, and use it next time), and turn it off when it
 * is finished. Use it to mark it when the user wants it to continue playing, or when it is finished
 * (temporary login). Just write it down, nothing is moving at this moment; mark the same App again and
 * the last time will prevail.
 */
internal object AppAfterTask : PhoneTool {
    override val verb = Verb.AppAfterTask
    override val label = "Choose whether the app stays open afterwards"
    override val risk = Risk.Write
    override val logged = true
    override val def = ToolDef("phone_app_after_task", Prompts.tool("phone_app_after_task"),
        mapOf(
            "package" to str("Exact package name of the app, e.g. com.example.app."),
            "action" to str("keep: leave it running in the background after the task (even if it is playing sound). close: close it when the task ends.", listOf(KEEP, CLOSE)),
            "reason" to str("Why, in a few words (e.g. the user wants the music to keep playing; a one-time sign-in)."),
            "user" to int("Only for another copy of the app (a clone or work-profile copy): the user value of that copy's phone_list_apps entry. Leave out for the main app.", min = 0, max = MAX_USER),
        ),
        listOf("package", "action", "reason"))

    /** Just note it down, don't switch the screen you are working on. */
    override fun aimAt(o: JsonObject): String? = null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val p = a.str("package")!!
        if (!LinkPolicy.PACKAGE.matches(p)) throw ArgError("package", "must be an Android package name")
        val action = a.str("action")!!
        if (action != KEEP && action != CLOSE) throw ArgError("action", "must be keep or close")
        val reason = a.str("reason", max = 200)!!.trim()
        val user = a.int("user", 0, MAX_USER)
        val marks = ctx.phone.screen.afterTask ?: return failCode(AFTER_TASK_UNAVAILABLE)
        val previous = marks.mark(p, user, action == KEEP, reason)
        return ok {
            put("package", p)
            user?.let { put("user", it) }
            put("action", action)
            previous?.takeIf { it.keep != (action == KEEP) }?.let { put("replaces", if (it.keep) KEEP else CLOSE) }
            put("hint", Feedback[if (action == KEEP) "after_task.keep" else "after_task.close"])
        }
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) =
        listOfNotNull(o.argStr("package"), o.argStr("action")).joinToString(" ").ifEmpty { null }

    const val KEEP = "keep"
    const val CLOSE = "close"
    const val AFTER_TASK_UNAVAILABLE = "after_task_unavailable"
    private const val MAX_USER = 99_999
}

/**
 * Share a piece of text to an App (ACTION_SEND text/plain) and open its sharing page on Mina's screen:
 * the recipient and sender click on that page, but nothing is sent here. When you can't type words into
 * an interface that you can't read, share the words and bring them in.
 */
internal object ShareText : PhoneTool {
    override val verb = Verb.ShareText
    override val label = "Share text to app"
    override val risk = Risk.Write
    override val showsScreenAfter = true
    override val logged = true
    override val def = ToolDef("phone_share_text", Prompts.tool("phone_share_text"),
        mapOf(
            "package" to str("Exact package name of the app to share the text to, e.g. com.example.app."),
            "text" to buildJsonObject { put("type", "string"); put("description", "The text to share, 1-10000 characters."); put("minLength", 1); put("maxLength", 10000) },
            "reason" to str("Optional: why you share it, e.g. the message the user asked to send to a contact."),
        ),
        listOf("package", "text"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val p = a.str("package")!!
        if (!LinkPolicy.PACKAGE.matches(p)) throw ArgError("package", "must be an Android package name")
        val text = a.str("text", max = 10000)!!
        // After changing the app, the payment declaration that was rejected before is no longer the case
        // this time.
        ctx.refusedPay = null
        val r = ctx.action(ctx.phone.screen.shareText(p, text))
        if (r.content["ok"] != JsonPrimitive(true)) return r
        ctx.appOpened = true
        // When the system first asks which one to use (two copies are installed and there is more than
        // one sharing page), the prompt already tells you how to choose; when you get to the sharing
        // page, you can talk about the next step.
        val opened = if (r.content["chooser"] != null) r else withHint(r, Feedback["share.opened"])
        return unreadable(ctx, p, opened)
    }

    override fun logTarget(o: JsonObject, r: ToolExecution, ctx: ToolContext) = o.argStr("package")

    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        return listOfNotNull(o.argStr("package")?.let { p ->
            Observation(ObsKind.Fact, "$name 打开了 $p 的分享页(还没发出)" + (log.appLabels[p]?.let { "(App 名字「$it」)" } ?: ""), source = PHONE)
        })
    }
}

private fun withHint(r: ToolExecution, hint: String): ToolExecution {
    val all = listOfNotNull((r.content["hint"] as? JsonPrimitive)?.content, hint).joinToString(" ")
    return ToolExecution(JsonObject(r.content + ("hint" to JsonPrimitive(all))), changed = r.changed)
}

/** When you get to the front desk, you can't see the window (security window, startup screen...): tell the truth, lest the model thinks it is not open and starts it again. */
private suspend fun unreadable(ctx: ToolContext, p: String, r: ToolExecution): ToolExecution {
    val s = ctx.session.peek()
    if (s?.unreadableApp != p || s.elements.any { it.meaningful }) return r
    return withHint(ToolExecution(JsonObject(r.content + ("readable" to JsonPrimitive(false))), changed = r.changed), Feedback.format("unreadable_window", "app" to p))
}

