package com.meenai.mina.core.uistate

import com.meenai.mina.core.prompt.Feedback
import com.meenai.mina.core.replay.Anchors
import com.meenai.mina.core.replay.Selector
import com.meenai.mina.core.tool.AgentTool
import com.meenai.mina.core.tool.LOCATED_REF
import com.meenai.mina.core.tool.SurfaceLayer
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.tool.ToolDispatcher
import com.meenai.mina.core.tool.ToolConfig
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.ToolIndex
import com.meenai.mina.core.tool.Until
import com.meenai.mina.core.tool.UntilOutcome
import com.meenai.mina.core.tool.Verb
import com.meenai.mina.core.tool.argStr
import com.meenai.mina.core.tool.fail
import com.meenai.mina.core.tool.failCode
import com.meenai.mina.core.tool.writesScreen
import com.meenai.mina.core.visual.Shots
import com.meenai.mina.core.vision.LocateResult
import com.meenai.mina.core.vision.Locator
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Where an action falls is given by the side of the interface. The irreversible judgment and approval of
 * the execution entrance can only read it: [args] is the parameter used for judgment (when you click @vN
 * found in the picture, it is replaced by its registered coordinates and what you see), [aimed] is the
 * view of the model (the screen read found by snapshotId, if no number is given, it is the screen used
 * for coordinate conversion), [bySight] What represents the point is the position obtained by looking at
 * the picture, [seen] is the text seen after reviewing the visual model and confirmed, [readAgeMs] is
 * how long it has been since the screen reading (it is not known to be null). [now] Take another look at
 * this moment (only read when it is determined to be irreversible; do not register the number).
 */
class ActionTarget internal constructor(
    val args: JsonObject,
    val aimed: ScreenSnapshot?,
    val bySight: Boolean,
    val seen: String?,
    val readAgeMs: Long?,
    private val look: suspend () -> ScreenSnapshot?,
) {
    suspend fun now(): ScreenSnapshot? = look()
}

/** According to the description, refer to the parameters of the target: the word, name or input box prompt displayed by the element; you can add a role. */
const val DESCRIBED_ELEMENT = "element"
const val DESCRIBED_ROLE = "role"
/** Given the description at the same time, I don’t know which one to click. */
private val DESCRIBED_CONFLICTS = listOf("ref", "snapshotId", "x", "y")
/** The error code when the page popup hangs (see DIALOG_OPEN in cap/web). */
private const val DIALOG_OPEN_CODE = "dialog_open"
/** If more than one matches, list at most a few. */
private const val DESCRIBED_LISTED = 6
/** The reason why I looked for it through looking at the picture, didn’t find it or didn’t confirm it (the rest is that I couldn’t get the picture or couldn’t see it). */
private val SIGHT_MISSED = setOf("not_found", "name_mismatch", "not_confirmed", "unverified_grounding", "vision_unavailable")

/** The result of finding the target according to the description: [Resolved] is the parameter after changing the number and the found element, [Failed] is the failure result for the model. */
sealed interface Described {
    /**
     * [note]: Press the beginning or include a description of the action result when matching (what is
     * described, who actually matched); the same value is null. [element] is null: there is no point in
     * the tree, and it is found by looking at the picture (the parameter is changed to @vN).
     */
    class Resolved(val args: JsonObject, val element: UiElement?, val note: JsonObject? = null) : Described
    class Failed(val result: ToolExecution) : Described
}

/**
 * There is an interface status shared by interface capabilities (mobile phones, web pages) in a task: a
 * copy of the screen reading status for each screen, which screen is being operated, the action target
 * for the execution entrance, and the target and anchor point of the route. The distributor uses it to
 * finish the interface before and after each call (take a look before the action, compare the actual
 * effect, attach the interface after the action, clear the points obtained from the picture); the
 * distributor and the core loop do not know it, and only use it through [SurfaceLayer]. The opening is
 * put into the tool context by [attach], and tools with interfaces are taken from the context.
 */
internal class Interfaces(
    surfaces: List<Surface>,
    stableRefs: Boolean,
    private val config: ToolConfig,
    val shots: Shots,
    /** The interface text that was last read (shared by several screens); if it is the same as it is read after the action, it will not be remembered again. */
    lastRead: () -> String?,
) : SurfaceLayer {
    companion object {
        /**
         * The interface status of this task: [surfaces] is the screen connected to the port. When
         * [stableRefs] is on, the stable number is used to read the screen across the screen (the action
         * has its own screen and is always on when the element is prioritized). Put it into [ctx], and
         * get the interface tools from there.
         */
        fun attach(ctx: ToolContext, surfaces: List<Surface>, stableRefs: Boolean): Interfaces =
            Interfaces(surfaces, stableRefs || ctx.config.inlineScreen || ctx.config.adaptiveVision, ctx.config, ctx.shots) {
                ctx.evidence.recentReads.lastOrNull()
            }.also { ctx[INTERFACES] = it }
    }

    val focus = SurfaceFocus(surfaces)
    val sessions: Map<String, ScreenSession> = focus.surfaces.associate { s ->
        s.id to ScreenSession(s, stableRefs, config.visionEnabled, config.recordFrames, lastRead, shots, config.vision, config.adaptiveVision)
    }
    private val gateView = GateView()

    suspend fun checkAppAccess(tool: AgentTool?, after: Boolean = false) {
        if (tool == null || tool.offScreen || tool.capability !in sessions || tool.verb == Verb.ListApps) return
        sessionFor(tool).checkAppAccess(current = after || tool.verb !in APP_TARGET_VERBS)
    }

    /** The screen reading status of the screen being operated at the moment. */
    val session: ScreenSession get() = sessions.getValue(focus.current.id)

    override val current: String get() = focus.current.id

    override fun enter(id: String?) = focus.enter(id)

    override fun leave(succeeded: Boolean) = focus.leave(succeeded)

    /** When it is on, the target element and anchor point are taken before the write operation is executed (set by the loop when the playback switch is on). */
    override var captureTargets: Boolean = false

    /** Tool [tool] The screen reading status of that screen; when it is not a screen tool, it is the most recently operated one. */
    private fun sessionFor(tool: AgentTool?): ScreenSession = tool?.capability?.let(sessions::get) ?: session

    /** Which screen this tool acts on; null if the tool is not a screen. */
    override fun surfaceOf(tool: AgentTool?): String? = tool?.capability?.takeIf { it in sessions }

    /**
     * Use the number of another screen to operate the tool on this screen: the result is wrong_surface,
     * indicating which screen the number belongs to and which tool should be used. If you cannot
     * recognize which screen the number belongs to (it has expired too long or was spelled incorrectly),
     * it will be null, and it will be handed over to the tool as usual (processed as expired).
     */
    override fun misaimed(tool: AgentTool, args: JsonObject, tools: ToolIndex): ToolExecution? {
        val own = tool.capability?.let(sessions::get) ?: return null
        val sid = args.argStr("snapshotId") ?: return null
        if (own.owns(sid)) return null
        val owner = sessions.values.firstOrNull { it.owns(sid) }
            ?: sessions.values.firstOrNull { s -> sid.startsWith(s.surface.family.snapshotPrefix) && sid.drop(s.surface.family.snapshotPrefix.length).firstOrNull()?.isDigit() == true }
            ?: return null
        if (owner === own) return null
        val ownerId = owner.surface.id
        return fail("wrong_surface", Feedback.format("wrong_surface",
            "id" to sid, "owner" to ownerId, "tool" to tool.name, "surface" to own.surface.id,
            "instead" to (tools.counterpart(tool.name, ownerId) ?: tools.nameOf(Verb.Observe, ownerId) ?: ownerId),
            "observe" to (tools.nameOf(Verb.Observe, own.surface.id) ?: tool.name)))
    }

    /** What is done on the interface before a call: take a look at the target of the route and the action before changing the interface. Ended by [finish]. */
    private class Prep(
        val session: ScreenSession,
        val probe: ScreenSession.EffectProbe?,
        val aimed: Pair<UiElement?, List<Int>?>?,
    ) {
        /** This call reports the actual effect from the interface side. */
        val reportsEffect: Boolean get() = probe != null
    }

    override suspend fun around(tool: AgentTool, args: JsonObject, inlineScreen: Boolean, body: suspend (reportsEffect: Boolean) -> ToolExecution): ToolExecution {
        if (tool.offScreen || tool.verb == Verb.ListApps) return body(false)
        // Launch by target to leave the restricted application; old pages are not collected at this
        // time, only allowed pages are attached after launch.
        if (tool.verb in APP_TARGET_VERBS) {
            val raw = body(false)
            return finish(tool, args, Prep(session, null, null), raw, inlineScreen)
        }
        val prep = prepare(tool, args)
        return finish(tool, args, prep, body(prep.reportsEffect), inlineScreen)
    }

    private suspend fun prepare(tool: AgentTool, o: JsonObject): Prep {
        val session = this.session
        gateView.capture = if (captureTargets && tool.routable) gateView.captureTarget(session, tool.verb, o) else null
        // Change the action of the interface: take a look (element table + screen) before taking action,
        // and compare the actual effect after taking action
        val probe = if (tool.reportsEffect) session.probe(o.argStr("snapshotId")) else null
        val aimed = probe?.let { effectTarget(session, tool.verb, o, it.before) }
        return Prep(session, probe, aimed)
    }

    /** The call is not on the interface (or it does not go to [prepare]): the route target is not left. */
    override fun skip() { gateView.capture = null }

    /**
     * After a call: the successful action is attached according to [inlineScreen] interface difference;
     * the picture is attached according to the observation strategy, and the actual effect is reported;
     * after moving this screen (failures are also counted, and those with incorrect parameters and not
     * executed are not counted), the points on this screen that were obtained from the previous pictures
     * are no longer credible.
     */
    private suspend fun finish(tool: AgentTool, o: JsonObject, prep: Prep, raw: ToolExecution, inlineScreen: Boolean): ToolExecution {
        val session = prep.session
        session.probeDone()
        val readsAtAction = session.reads
        var result = raw
        val ok = result.content["ok"] == JsonPrimitive(true)
        if (inlineScreen && tool.showsScreenAfter && ok) result = session.withScreen(result)
        // The visual main model in the old mode directly looks at the post-action picture; the
        // hierarchical mode only adds pictures when there are differences in the interface or when the
        // next observation is needed.
        if (tool.showsScreenAfter && ok && !config.adaptiveVision && result.images.isEmpty() && "screen" !in result.content) result = session.withFrame(result)
        val probe = prep.probe
        val aimed = prep.aimed
        if (ok && probe != null && aimed != null) {
            val typed = if (tool.verb == Verb.TypeText) o.argStr("text") else null
            result = session.withEffect(result, probe, tool.verb, readsAtAction, aimed.first, aimed.second, typed)
        }
        if (tool.writesScreen && result.content["error"] != JsonPrimitive("invalid_arguments")) session.clearLocated()
        return result
    }

    /**
     * The target used to judge the effect: the action according to the number is the element in the
     * screen of the model. When input and cleared without a number, it is the input box with focus
     * before the action; the screen area is the range of the target, and the coordinate action
     * (including the point found by looking at the picture) is a small area around the landing point.
     * Neither is (null, null).
     */
    private fun effectTarget(session: ScreenSession, verb: Verb, o: JsonObject, before: ScreenSnapshot?): Pair<UiElement?, List<Int>?> {
        val ref = o.argStr("ref")?.takeIf { !LOCATED_REF.matches(it) }
        val el = when {
            ref != null -> gateView.snapshotFor(session, o)?.elements?.firstOrNull { it.ref == ref }
            verb == Verb.TypeText || verb == Verb.ClearText -> before?.elements?.firstOrNull { it.focused && it.editable }
            else -> null
        }
        fun num(a: JsonObject, k: String) = (a[k] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()?.toInt()
        val point = gateView.gateArgs(session, verb, o).let { a -> num(a, "x")?.let { x -> num(a, "y")?.let { y -> x to y } } }
        val region = el?.bounds?.takeIf { it.size == 4 } ?: point?.let { (x, y) ->
            val d = EffectTuning.POINT_REGION
            listOf((x - d).coerceAtLeast(0), (y - d).coerceAtLeast(0), (x + d).coerceAtMost(999), (y + d).coerceAtMost(999))
        }
        return el to region
    }

    /**
     * Clicking and typing the target by description ([DESCRIBED_ELEMENT] parameter, no number and
     * coordinates): Now reread the screen (registration number), find the only matching element, and
     * replace the parameters with its ref + snapshotId. The subsequent judgment, approval, execution,
     * and verification are the same as by number. When typing, if a piece of the tree is missing or
     * cannot be used, and there is no matching input box in it, just look at the picture to find it (see
     * [bySight]). If this type of call is not found, it will be null; if it cannot be found, there is
     * more than one, or the parameters conflict, the failure result will be given to the model and will
     * not be executed.
     */
    suspend fun described(tool: AgentTool?, args: JsonObject): Described? {
        val verb = tool?.verb
        if (verb != Verb.Tap && verb != Verb.TypeText) return null
        val session = tool.capability?.let(sessions::get) ?: return null
        val text = args.argStr(DESCRIBED_ELEMENT)
        if (text == null) {
            if (DESCRIBED_ROLE in args) return Described.Failed(fail("invalid_arguments", "$DESCRIBED_ROLE: only used together with $DESCRIBED_ELEMENT"))
            return null
        }
        DESCRIBED_CONFLICTS.firstOrNull { it in args }?.let {
            return Described.Failed(fail("invalid_arguments", "$DESCRIBED_ELEMENT: give either $DESCRIBED_ELEMENT or $it, not both"))
        }
        val role = args.argStr(DESCRIBED_ROLE)?.trim()?.ifEmpty { null }
        // A screen of internal reading: the model has not seen it, so it does not count as being read
        // (cannot clear "the screen in hand is out of date"); this screen is reserved for the following
        // actions as "before action"
        val snap = screenAttempt { session.snapshot(internal = true) }
            ?: return Described.Failed(fail("screen_unavailable", Feedback["screen_unavailable"]))
        // The page pop-up box hangs: the page is parked and nothing can be clicked
        if (snap.web?.dialog != null) {
            session.clearPrimed()
            return Described.Failed(failCode(DIALOG_OPEN_CODE))
        }
        val observe = session.surface.family.tool("observe")
        val shown = text.trim().take(80)
        val m = DescribedTarget.match(snap.elements, ElementQuery(text, role, field = verb == Verb.TypeText))
        if (m !is DescribedMatch.Found) session.clearPrimed()
        return when (m) {
            is DescribedMatch.Found -> Described.Resolved(
                JsonObject(args.filterKeys { it != DESCRIBED_ELEMENT && it != DESCRIBED_ROLE } +
                    mapOf("ref" to JsonPrimitive(m.element.ref), "snapshotId" to JsonPrimitive(snap.snapshotId))),
                m.element,
                // Not exactly the same name: tell the truth which one matches the description.
                if (m.exact) null else JsonObject(mapOf("resolvedFrom" to JsonPrimitive(shown), "matched" to JsonPrimitive(m.element.name.take(80)),
                    "matchedRef" to JsonPrimitive(m.element.ref), "snapshotId" to JsonPrimitive(snap.snapshotId))),
            )
            is DescribedMatch.Ambiguous -> Described.Failed(fail("element_ambiguous", Feedback.format("described.ambiguous",
                "text" to shown, "count" to m.candidates.size, "snapshot" to snap.snapshotId,
                "candidates" to m.candidates.take(DESCRIBED_LISTED).joinToString("; ") { e ->
                    val near = DescribedTarget.neighbour(e, snap.elements)?.let { " near 「${it.take(30)}」" }.orEmpty()
                    val off = if (DescribedTarget.usable(e, verb == Verb.TypeText)) "" else " (not actionable)"
                    "${e.ref} ${e.role} 「${e.name.take(40)}」 at ${e.bounds}$near$off"
                })))
            DescribedMatch.None -> {
                // While typing, a piece of the tree on this screen is missing or cannot be used: If the
                // tree does not match up, it does not mean that it is not on the screen. Look at the
                // picture to find this frame.
                val locator = session.locator
                if (verb == Verb.TypeText && locator != null && session.treeBlind(snap)) bySight(session, locator, args, shown)
                else Described.Failed(fail("element_not_found", Feedback.format("described.none",
                    "text" to shown, "role" to (role?.let { " with role $it" } ?: ""), "observe" to observe)))
            }
        }
    }

    /**
     * Look at the picture to find the input box that the description refers to (the same set of checks
     * as find_visually: check again in the red box and mark the unsubstantiated ones). Change the
     * parameters to the found @vN: After that, it is the same as passing @vN in the model itself. First
     * check that the screen has not changed, that the click has reached the irreversible judgment, and
     * after pasting, press the text on the screen to check that the point is not clicked.
     */
    private suspend fun bySight(session: ScreenSession, locator: Locator, args: JsonObject, shown: String): Described =
        when (val r = locator.locate(shown, null)) {
            // I couldn’t find it (or I made an error when I looked at the picture): Change the
            // explanation or give me some advice; I can’t get the screen or view it (it’s protected and
            // I haven’t opened the app yet) Follow the instructions of find_visually
            is LocateResult.NotFound -> Described.Failed(fail("element_not_found", Feedback.format("described.not_seen",
                "text" to shown, "reason" to r.reason,
                "next" to if (r.reason in SIGHT_MISSED) Feedback["described.not_seen.retry"] else r.message)))
            is LocateResult.Found -> {
                session.locatedInfo[r.ref] = Located(shown, null, r.seen, r.unverified)
                Described.Resolved(
                    JsonObject(args.filterKeys { it != DESCRIBED_ELEMENT && it != DESCRIBED_ROLE } +
                        mapOf("ref" to JsonPrimitive(r.ref), "snapshotId" to JsonPrimitive(r.snapshotId))),
                    null,
                    JsonObject(listOfNotNull("resolvedFrom" to JsonPrimitive(shown), "foundBySight" to JsonPrimitive(true),
                        r.seen?.let { "seen" to JsonPrimitive(it) }).toMap()),
                )
            }
        }

    /** Execute the goal of this step seen at the entrance (see [ActionTarget]); read-only, no action will be performed. */
    fun actionTarget(tool: AgentTool?, args: JsonObject): ActionTarget {
        val session = sessionFor(tool)
        val verb = tool?.verb
        val checked = gateView.gateArgs(session, verb, args)
        return ActionTarget(checked, gateView.snapshotFor(session, checked), gateView.visionSourced(session, verb, args),
            gateView.confirmedSeen(session, verb, args), snapshotAgeMs(args.argStr("snapshotId")), ::peek)
    }

    /** The number of milliseconds since that screen reading; it is null if it is not the last few times of any screen. */
    private fun snapshotAgeMs(snapshotId: String?): Long? = sessions.values.firstNotNullOfOrNull { s ->
        s.recentSnapshots.firstOrNull { it.snapshotId == snapshotId }?.let { snap -> s.readAt[snap.snapshotId]?.let { System.currentTimeMillis() - it } }
    }

    /** That time the screen was read (find the screen in the tool [tool] and the snapshotId in the parameters); if it is not the last few times, it is null. */
    fun snapshotFor(tool: AgentTool?, args: JsonObject): ScreenSnapshot? = gateView.snapshotFor(sessionFor(tool), args)

    /** The running record uses: the port number corresponding to the model number at the moment; it is empty when the stable number is turned off, there is no @eN, or the number has expired. */
    override fun portRecord(tool: AgentTool?, args: JsonObject): JsonObject = gateView.portRecord(sessionFor(tool), args)

    /** When the last call was a write operation, its target element (null) was not recognized or recognized with the action's previous anchor; otherwise it was null. */
    fun lastActionTarget(): Pair<Selector?, Anchors>? = gateView.capture?.let { it.target to it.before }

    /** The screen before the last write operation is protected by the system against screenshots. */
    fun lastActionProtected(): Boolean = gateView.capture?.snapshot?.protected == true

    /** Read one screen at this moment after the last write operation (no registration number), and get the newly appeared text after the action; if it is not a write operation or cannot be read, it is null. */
    suspend fun actionAfter(): Anchors? {
        if (gateView.capture == null) return null
        return peek()?.let(::anchorsAfter)
    }

    /** Same as above, using the action already read by the caller. */
    fun anchorsAfter(after: ScreenSnapshot): Anchors? = gateView.anchorsAfter(after)

    /** At this moment, re-read the screen that was recently operated, without registering the number or changing the screen reading in the model's hand; if it cannot be read, it will be null. */
    suspend fun peek(): ScreenSnapshot? = session.peek()

    /** Wait until the recently operated screen is stable, at most [timeoutMs]; if the status cannot be read, return as usual. */
    override suspend fun settle(timeoutMs: Long) {
        screenAttempt { session.screen.waitStable(timeoutMs) }
    }

    /** The frame (original image) of the recently operated screen and its frame number are registered for review; protected pages and payment pages are not cut off, and if not cut off, it is null. */
    override suspend fun visibleText(): List<String> = peek()?.elements?.map { it.name }.orEmpty()

    override suspend fun waitUntil(until: Until, clock: () -> Long, interrupted: () -> Boolean): UntilOutcome =
        UntilWait(session.screen, ::peek, clock, interrupted).run(until)

    override suspend fun imageNow(): Pair<ByteArray, Int>? = session.currentFrame()

    /** The main model can view a screen after the action: the screen and the element table are at the same time; it is null when there is no app on the screen. */
    override suspend fun viewNow(): ToolDispatcher.Glance? = session.currentView()

    /** The model can still see which screen readings (after cropping and compression); if the different bases are not among them, the full amount will be emitted. */
    override fun setVisible(ids: Set<String>) { sessions.values.forEach { it.visible = ids } }

    /** The current version of the screen written in the running status (mobile version, computer version of the web page); if not available or cannot be read, it is null. */
    override suspend fun mode(): String? = focus.surfaces.firstNotNullOfOrNull { it.mode }?.invoke()
}

private val APP_TARGET_VERBS = setOf(Verb.LaunchApp, Verb.OpenLink, Verb.ShareText, Verb.SetAlarm, Verb.SetTimer, Verb.ShowAlarms, Verb.AddCalendarEvent)
