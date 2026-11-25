package com.meenai.mina.core.uistate

import com.meenai.mina.core.capability.CapabilityKey
import com.meenai.mina.core.prompt.Feedback
import com.meenai.mina.core.tool.FAILURE_MESSAGES
import com.meenai.mina.core.tool.HINT_NOT_SETTLED
import com.meenai.mina.core.tool.HINT_TAP_UNCHANGED
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.tool.ToolDispatcher
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.fail
import com.meenai.mina.core.tool.okObj
import com.meenai.mina.core.tool.switchHint
import com.meenai.mina.core.vision.LocateRegistry
import com.meenai.mina.core.vision.Locator
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Interface status in the tool context: [Interfaces.attach] is put in for the start, and tools with
 * interfaces (mobile phones, web pages, and the roll call screen in general tools) are taken from here.
 * The tool engine (core/tool) does not know these and only uses the interface side through
 * [com.meenai.mina.core.tool.SurfaceLayer].
 */

/** Interface state key in tool context. */
internal val INTERFACES = CapabilityKey<Interfaces>("interfaces")

/** The interface status of this task (the one put in at the beginning). */
internal val ToolContext.interfaces: Interfaces get() = this[INTERFACES] ?: error("interfaces not attached")

/** The interface status of the task of the dispatcher (used when executing entry and playback based on screen reading). */
internal val ToolDispatcher.interfaces: Interfaces get() = ctx.interfaces

/** The screen reading status of each screen is based on the ID of the ability. */
internal val ToolContext.sessions: Map<String, ScreenSession> get() = interfaces.sessions

/** Which screen was used recently. */
internal val ToolContext.focus: SurfaceFocus get() = interfaces.focus

/** The screen reading status of the screen being operated at the moment (the distributor records the screen to which the tool belongs before each call, see [focus]). */
internal val ToolContext.session: ScreenSession get() = sessions.getValue(focus.current.id)
internal val ToolContext.screen: ScreenPort get() = session.screen

/** The screen you are operating at the moment. */
internal val ToolContext.surface: Surface get() = focus.current
internal val ToolContext.lastSnapshot: ScreenSnapshot? get() = session.lastSnapshot

/** The point (@vN) found by find_visually on that screen at this moment; it will be cleared after any writing operation on that screen. */
internal val ToolContext.located: LocateRegistry get() = session.located
internal val ToolContext.locatedInfo: MutableMap<String, Located> get() = session.locatedInfo

/** I read it after the last writing operation. describe: The coordinate clicks during this period are treated as points obtained from the picture. */
internal var ToolContext.described: Boolean
    get() = session.described
    set(v) { session.described = v }
internal val ToolContext.locator: Locator? get() = session.locator

internal fun ToolContext.clearLocated() = session.clearLocated()

/** The action results of the port are converted into tool results: no change, being switched to another App, and the page not being quiet are all written with prompts; failure will be explained by error code. */
internal fun ToolContext.action(r: ActionResult): ToolExecution = when (r) {
    is ActionResult.Ok -> {
        // Switched to another App (not the payment page called up by the target App itself): the payment
        // declaration is invalid
        if (r.appSwitched != null && !r.switchedByTarget) refusedPay = null
        val hints = listOfNotNull(
            HINT_TAP_UNCHANGED.takeIf { r.changed == false && !reportsEffect && r.opened == null },
            // It is opened to the user's home screen and then moved back. It is explained clearly in the
            // following sentence, and it is no longer said to be cut away.
            r.appSwitched?.takeIf { r.opened == null }?.let { switchHint(it, r.switchedByTarget) },
            HINT_NOT_SETTLED.takeIf { r.settled == false },
            r.coveredBy?.let { Feedback.format("covered_by", "pkg" to it) },
            r.opened?.let { openedHint(it) },
            // The system asks on the user screen, leaving it to the user to choose: it doesn’t say how
            // to choose.
            r.chooser?.takeIf { r.opened?.picker != true }?.let { Feedback.format("launch.chooser", "pkg" to it.packageName) },
            // When what you want is another copy, there is no need to remind you when you order another
            // copy; if you order another copy if it is not the one you asked for, tell the truth.
            (r.opened?.inProfile ?: r.inProfile)?.takeIf { it == OpenedElsewhere.OTHER_PROFILE && r.requestedUser == null }?.let { Feedback["profile.other"] },
            r.requestedUser?.takeIf { r.profileUser != null && r.profileUser != it }?.let { wanted ->
                if (r.inProfile == OpenedElsewhere.MAIN_PROFILE) Feedback["profile.main_not_requested"]
                else Feedback.format("profile.copy_not_requested", "user" to "${r.profileUser}", "wanted" to "$wanted")
            },
            r.hint,
        )
        ToolExecution(okObj {
            r.changed?.let { put("changed", it) }
            r.appSwitched?.let { put("app_switched", it) }
            if (r.appSwitched != null && r.switchedByTarget) put("opened_by_target", true)
            if (r.relocated) put("relocated", true)
            r.relocatedTo?.takeIf { r.relocated }?.let { put("relocatedTo", it) }
            if (r.settled == false) put("settled", false)
            r.coveredBy?.let { put("coveredBy", it) }
            r.chooser?.let { put("chooser", it.toJson()) }
            r.opened?.let { put("opened", it.toJson()) }
            r.inProfile?.let { put("inProfile", it) }
            r.profileUser?.let { put("profileUser", it) }
            r.requestedUser?.let { wanted -> r.profileUser?.let { put("requestedCopy", it == wanted) } }
            if (hints.isNotEmpty()) put("hint", hints.joinToString(" "))
        }, changed = r.changed)
    }
    is ActionResult.Failed ->
        if (r.reason == "ref_expired") fail("ref_expired", Feedback["ref_expired"])
        else {
            val code = r.reason.takeIf { Regex("^[a-z][a-z0-9_]*$").matches(it) } ?: "action_failed"
            val message = FAILURE_MESSAGES[code] ?: Feedback.format("action_failed", "reason" to r.reason)
            fail(code, r.hint?.let { "$message $it" } ?: message)
        }
}

/**
 * When the action makes the app open on the user's home screen, the sentence given to the model: When it
 * is moved back, it is said to continue on Mina's screen and don't click again; the system asks which
 * one to open on the user's screen and says it is left to the user to choose, don't click again and
 * don't choose for the user. The next action will wait for the user to finish selecting; if the user
 * closes that question and handles it personally, tell the truth; Pop-up windows that require users to
 * handle by themselves (such as login verification) are left on the user screen and are told not to move
 * them back, and the user is asked to click there; pop-up windows that are left untouched on the user
 * screen (the user is currently using them) are told not to come back and are asked to be handled by the
 * user.
 */
internal fun openedHint(o: OpenedElsewhere): String {
    val app = o.app ?: o.packageName
    return when {
        o.closed -> Feedback["spill.picker_closed"]
        o.byUser -> Feedback.format("spill.user_done", "app" to app)
        o.picker && o.onUserScreen -> Feedback.format("spill.user_picking", "app" to app)
        o.needsUser && o.onUserScreen -> Feedback.format("spill.user_popup", "app" to app)
        o.onUserScreen -> Feedback.format("spill.in_use", "app" to app)
        else -> Feedback.format("spill.pulled_back", "app" to app)
    }
}

/**
 * When the stable numbering is on, the model number is first translated into the port number and then
 * handed over to [act]: The number has been retired and returned to ref_expired, which is not executed;
 * the numbering of the old screen is still executed as usual and marked as relocated in the latest
 * screen. Also record the port number in the running record.
 */
internal suspend fun ToolContext.withPortRef(ref: Pair<String, String>?, act: suspend (Pair<String, String>?) -> ToolExecution): ToolExecution {
    val st = session.stabilizer
    if (st == null || ref == null) return act(ref)
    val r = st.resolve(ref.first, ref.second) as? Resolved.Current ?: return action(ActionResult.Failed("ref_expired"))
    val e = act(r.portRef to r.portSnapshotId)
    val content = if (r.relocated && e.content["ok"] == JsonPrimitive(true)) JsonObject(e.content + ("relocated" to JsonPrimitive(true))) else e.content
    return ToolExecution(content, changed = e.changed, outcome = e.outcome, frame = e.frame, images = e.images,
        record = JsonObject(e.record + buildJsonObject { put("portRef", r.portRef); put("portSnapshotId", r.portSnapshotId) }))
}

/** One screen used for coordinate conversion: the most recent screen reading, and the one that has not yet been read. */
internal suspend fun ToolContext.lastOrSnapshot(): ScreenSnapshot = session.lastSnapshot ?: session.snapshot()

/** Confirm that the declared app is verified according to the writing method of the current screen (the package name on the mobile phone, the site host name on the web page). */
internal fun ToolContext.confirmTarget(app: String): String = surface.declaredTarget(app)
