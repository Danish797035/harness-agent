package com.meenai.mina.core.action

import com.meenai.mina.core.tool.Risk
import com.meenai.mina.core.gate.ConfirmationGate
import com.meenai.mina.core.gate.Irreversible
import com.meenai.mina.core.guard.DIAGNOSTIC_VERBS
import com.meenai.mina.core.guard.GuardVerdict
import com.meenai.mina.core.guard.OperationGuards
import com.meenai.mina.core.guard.PAGE_READ_VERBS
import com.meenai.mina.core.guard.GuardScope
import com.meenai.mina.core.guard.elementsHash
import com.meenai.mina.core.guard.resultFingerprint
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.record.Redactor
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.replay.Anchors
import com.meenai.mina.core.replay.effectiveWrite
import com.meenai.mina.core.uistate.Described
import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.uistate.interfaces
import com.meenai.mina.core.tool.ActionPass
import com.meenai.mina.core.tool.ToolDispatcher
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.Verb
import com.meenai.mina.core.tool.parseArgs
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.meenai.mina.core.tool.SubActions
import com.meenai.mina.core.guard.OffScreenGuard
import com.meenai.mina.core.run.RunProvenance
import com.meenai.mina.core.run.SKIPPED_FOR_NEW_MESSAGE
import com.meenai.mina.core.run.waitOrSteer
import com.meenai.mina.core.tool.failCode
import com.meenai.mina.core.capability.Paced

/**
 * The only execution entrance for all actions that change the external world: [ToolDispatcher.execute].
 * You need a [ActionPass], which is only available here. Each source is in the same order: operation
 * guard → lock screen/taken level (write operation) → irreversible determination and consent check
 * (screen at the moment after pressing the level) → execution → guard check (failed fuse, screen change,
 * reread) → running record. Intentional differences between sources are written only in [OriginPolicy].
 */
class ActionExecutor(
    private val tools: ToolDispatcher,
    private val guards: OperationGuards,
    confirmations: ConfirmationGate?,
    private val gate: suspend () -> Unit,
    val recorder: ActionRecorder,
    /** Guard for tools that don't move the screen; null if not. */
    private val offScreen: OffScreenGuard? = null,
    approvals: ApprovalSettings = ApprovalSettings.ASK,
    provenance: RunProvenance = RunProvenance(),
    selfPackage: String? = null,
    autonomous: Boolean = false,
    /** The interval between two consecutive external actions (send, submit) on the same website or App (see [OutwardPacing]); the default is not equal. */
    private val pacing: OutwardPacing = OutwardPacing.Off,
) {
    private val decision = ConfirmDecision(tools, confirmations, approvals, recorder = recorder, provenance = provenance, selfPackage = selfPackage, autonomous = autonomous)
    private val pass = ActionPass()

    /** Equipped with a confirmation level (payment can be made with the user's consent). */
    val confirms: Boolean = confirmations != null

    /** The conclusion that the guard of the tool that does not move the screen has stopped; if not stopped, it is null. */
    val offScreenStop: GuardVerdict? get() = offScreen?.stop

    suspend fun run(call: Part.ToolCall, origin: Origin, followUp: FollowUp = FollowUp.NONE): ActionOutcome =
        run(call, origin, followUp, reads = null)

    /** [reads]: The range of reads counted individually in one call of the page script (when the source requires isolation); actions there are still counted as tasks. */
    private suspend fun run(call: Part.ToolCall, origin: Origin, followUp: FollowUp, reads: GuardScope?): ActionOutcome {
        val args = parseArgs(call.argsJson)
        // Tools that do not move the screen (sandbox type): do not enter the screen guard, and are
        // managed by their respective guards.
        val offScreen = tools.offScreen(call.name)
        val verb = tools.verb(call.name)
        // If the number of another screen is used: no judgment, no action, no record of which screen is
        // being operated, directly handed over to the distributor to explain which tool should be used
        if (tools.misaimed(call.name, args) != null) {
            return ran(Step(call, args, origin, emptyList(), writing = false, offScreen, verb, null, followUp, reads))
        }
        // Subsequent levels and judgments will be based on the screen called this time (until the end of
        // this call)
        tools.aim(call.name, args)
        try {
            return aimed(call, origin, followUp, reads, args, offScreen, verb)
        } catch (e: com.meenai.mina.core.access.AppAccessDenied) {
            val content = buildJsonObject {
                put("ok", false); put("error", e.code); put("message", e.message); put("retryable", false)
                e.packageName?.let { put("package", it) }
            }
            // Single step: As the result of this tool call, it is handed over to the model, allowing it
            // to change routes or explain to the user; batches and scripts are processed by themselves
            // according to Refused
            if (origin.policy.recordsRefusal) followUp.refused(content)
            recorder.result(origin.step, call.name, verb, content, content, null, null, 0, origin.policy.source, call.id)
            return ActionOutcome.Refused(e.code, content, emptyList())
        } finally {
            // Success or failure is recorded by the distributor; only this layer ends here
            tools.release(false)
        }
    }

    private suspend fun aimed(
        given: Part.ToolCall, origin: Origin, followUp: FollowUp, reads: GuardScope?, givenArgs: JsonObject, offScreen: Boolean, verb: Verb?,
    ): ActionOutcome {
        // First change the object pointed to by the description to the number on the current screen: the
        // subsequent judgment, approval, and execution are exactly the same as those according to the
        // number; if it cannot be found or is not unique, it will not be done.
        var call = given
        var args = givenArgs
        var note: JsonObject? = null
        tools.interfaces.checkAppAccess(tools.declared(call.name))
        when (val d = tools.interfaces.described(tools.declared(call.name), args)) {
            is Described.Failed -> return ran(Step(call, args, origin, emptyList(), writing = false, offScreen, verb, null, followUp, reads, preset = d.result))
            is Described.Resolved -> { args = d.args; call = call.copy(argsJson = d.args.toString()); note = d.note }
            null -> Unit
        }
        val policy = origin.policy
        val readScope = reads?.takeIf { verb in PAGE_READ_VERBS }
        val before = when {
            offScreen -> emptyList()
            readScope != null -> guards.beforeAction(call.name, args, verb in DIAGNOSTIC_VERBS, readScope)
            else -> guards.beforeAction(call.name, args, verb in DIAGNOSTIC_VERBS)
        }
        if (guards.stop != null) return refuse("guard_stopped", origin, before)
        if (policy.stopOnGuardWarning && before.isNotEmpty()) return refuse("guard_warning", origin, before)
        val writing = tools.writesScreen(call.name)
        if (writing && !policy.gatedByCaller) gate()
        // No execution is performed without available consent, and failed circuit breakers are not
        // counted.
        val d = decision.judge(call.name, args, origin)
        when (d) {
            is Decision.Required -> return blocked(call, args, origin, before, d.hit, null, followUp)
            is Decision.Mismatch -> return blocked(call, args, origin, before, d.hit, d.reason, followUp)
            Decision.Denied -> return refuse("approval_unavailable", origin, before)
            Decision.Unresolved -> return refuse("target_unresolved", origin, before)
            Decision.OwnApp -> return ownApp(call, args, origin, before, followUp)
            else -> Unit
        }
        // External actions: separated from the last time on the same website or app (the task can be
        // canceled while waiting, but the action has not started yet)
        val site = decision.outwardSite
        val paced = site?.let { pacing.waitBefore(it, recorder.now()) }?.takeIf { it > 0 } ?: 0L
        // During the interval, the user interrupted: I won’t do it this time, let the model read that
        // sentence first. The interval is calculated from the last time you did it, and the rest will be
        // made up the next time you do it again.
        if (paced > 0 && waitOrSteer { delay(paced) } == null) {
            return ran(Step(call, args, origin, before, writing = false, offScreen, verb, null, followUp, reads, preset = failCode(SKIPPED_FOR_NEW_MESSAGE)))
        }
        // Change the action of the screen: Stop for a while according to the rhythm of the screen's
        // capabilities (see AppDwell in the App), each step in a single step or a batch stops here; the
        // combined action (script) itself does not stop, and each step in it stops here. When the upper
        // limit of the number of screens is reached and the user interrupts while staying, it will not
        // be completed.
        val screenPaced = writing && !offScreen && !tools.composite(call.name)
        val dwelt = if (screenPaced) tools.beforeWrite(call.name, outward = site != null) else Paced.NONE
        dwelt.stop?.let { stop ->
            return ran(Step(call, args, origin, before, writing = false, offScreen, verb, null, followUp, reads, preset = stop, pacedMs = paced + dwelt.waitMs))
        }
        if (writing) {
            guards.noteAction()
            decision.noteWrite(call.name)
        }
        val step = Step(call, args, origin, before, writing, offScreen, verb, (d as? Decision.Traced)?.detail, followUp, reads, note = note,
            pacedMs = paced + dwelt.waitMs, idleScrolls = dwelt.idleScrolls, outward = site != null)
        // Once the action is started, complete it and record it: script timeout or task cancellation
        // cannot be caught in the middle or missed. The ability to tell this screen about external
        // actions (the upper limit of the number of pages on the web page only applies to browsing, see
        // PageDwell)
        site?.let(tools::outwardStart)
        var done = false
        try {
            val outcome = if (policy.actionCapMs == null) ran(step) else withContext(NonCancellable) { ran(step) }
            if (screenPaced && outcome is ActionOutcome.Ran) tools.afterWrite(call.name, outcome.exec.content)
            if (site != null && outcome is ActionOutcome.Ran && outcome.exec.content["ok"] != JsonPrimitive(false)) {
                done = true
                pacing.noteDone(site, recorder.now())
            }
            return outcome
        } finally {
            site?.let { tools.outwardEnd(it, done) }
        }
    }

    private class Step(
        val call: Part.ToolCall, val args: JsonObject, val origin: Origin, val before: List<GuardVerdict>, val writing: Boolean,
        val offScreen: Boolean, val verb: Verb?, val traced: String?, val followUp: FollowUp, val reads: GuardScope?,
        /** The result is obtained without giving the tool (the target cannot be found according to the description). */
        val preset: ToolExecution? = null,
        /** Attached is a description of the results (who matched according to the description). */
        val note: JsonObject? = null,
        /** How long to wait for the interval of external actions and the rhythm of this screen before execution; the wait for this call is included. */
        val pacedMs: Long = 0,
        /** Roll a few times during the pause before execution; only enter the running record. */
        val idleScrolls: Int = 0,
        /** Irreversible judgment recognizes that it is an external action (send, submit); it is recorded as this activity. */
        val outward: Boolean = false,
    )

    private suspend fun ran(s: Step): ActionOutcome {
        val call = s.call
        val policy = s.origin.policy
        val n = s.origin.step
        // Each action in the page script returns here and also completes the entire entry; the reads in
        // the script are only counted in this call.
        val subVerdicts = mutableListOf<GuardVerdict>()
        val sub = if (tools.composite(call.name)) {
            val script = Origin.Script(n)
            val window = if (script.policy.isolatedReads) GuardScope(canStop = false) else null
            SubActions { c ->
                val o = run(c, script, FollowUp.NONE, window)
                subVerdicts += o.before
                if (o is ActionOutcome.Ran) subVerdicts += o.after
                o.execution()
            }
        } else null
        val started = recorder.now()
        // Action to change the status: note down the position of the ledger before execution, and check
        // whether it has been re-read afterwards (including the screen that comes with this result) by
        // checking before answering.
        val changes = tools.declaredRisk(call.name) != Risk.Read && (s.offScreen || ClassifiedRisk.changesState(s.verb))
        val mark = tools.ledgerMark()
        val raw = (s.preset ?: offScreen?.exhausted(call.name)?.let { ToolExecution(it) } ?: execute(s, sub))
            .let { r -> s.note?.let { r.with(content = JsonObject(r.content + it)) } ?: r }
            .let { r -> if (s.pacedMs > 0) r.with(waitMs = r.waitMs + s.pacedMs) else r }
            .let { r -> if (s.idleScrolls > 0) r.with(record = JsonObject(r.record + ("idleScrolls" to JsonPrimitive(s.idleScrolls)))) else r }
        // It reported a failure and did not say that the interface has changed. The action has not been
        // changed to: There is no need to read it again before answering.
        val failed = raw.content["ok"] == JsonPrimitive(false) && raw.changed != true
        if (changes && s.preset == null && !failed) recorder.noteChange(mark)
        val durationMs = recorder.now() - started
        val resolved = s.followUp.resolve(raw) ?: return ActionOutcome.Cancelled(s.before)
        tools.interfaces.checkAppAccess(tools.declared(call.name), after = true)

        val after = subVerdicts.toMutableList()
        after += if (s.offScreen) offScreen?.result(call.name, s.args, resolved.content).orEmpty() else guards.result(call.name, resolved.content)
        // The actual effect of the action report is recorded first (no effect, changed place), and the
        // fingerprint will not be repeated in the subsequent screen.
        ((resolved.content["effect"] as? JsonObject)?.get("kind") as? JsonPrimitive)?.takeIf { !s.offScreen }?.let { after += guards.effect(it.content) }
        val elements = resolved.screenElements ?: resolved.content["elements"] as? JsonArray
        val beforeFrameProgress = guards.progressVersion
        elementsHash(elements)?.let { after += guards.frame("$n:${call.id}", it) }
        if (guards.progressVersion != beforeFrameProgress) after.removeAll { it.code == "no_progress" }
        if (!s.offScreen) {
            if (s.verb in PAGE_READ_VERBS) {
                // Reading content that has not been read since the last change of the screen (a new
                // screen, a new starting point for the entire page, a new result given by the script) is
                // not counted as re-reading.
                val fp = elementsHash(elements) ?: resultFingerprint(resolved.content)
                val beforeReadProgress = guards.progressVersion
                after += if (s.reads != null) guards.read(call.name, guards.firstSeen(fp, s.reads), s.reads, fp)
                else guards.read(call.name, guards.firstSeen(fp), fingerprint = fp)
                if (guards.progressVersion != beforeReadProgress) after.removeAll { it.code == "no_progress" }
            } else if (s.writing && resolved.changed != false && resolved.content["ok"] == JsonPrimitive(true)) {
                guards.pageChanged()
            }
        }
        val (exec, content) = s.followUp.present(resolved, s.before + after)

        // Screen: The model is handed over to the caller in one step and saved in its order; the
        // sub-step interface is saved only on the spot after the interface has changed; the playback is
        // not saved
        val picture = if (policy.frames == Frames.Caller && s.verb != Verb.ViewPastScreens && !s.offScreen) exec.image ?: exec.frame else null
        val shotId = when (policy.frames) {
            Frames.Caller -> picture?.let { recorder.nextShot() }
            Frames.WhenChanged -> (exec.image ?: exec.frame)?.takeIf { exec.changed != false }?.let(recorder::saveFrame)
            Frames.None -> null
        }
        recorder.result(n, call.name, s.verb, content, exec.record, exec.changed, shotId, durationMs, policy.source, call.id, exec.waitMs)
        ActivityRecord.of(tools, call.name, s.args, exec, n, policy.source, call.id, recorder.now(), s.outward)?.let(recorder::event)
        if (policy.frames == Frames.WhenChanged && shotId != null) recorder.event(RunEvent.Media(recorder.now(), shotId, n, RunEvent.placeLabel(exec.place)))
        val seen = if (policy.routeTargets) routeTarget(call, s.verb, s.origin, exec) else null
        s.traced?.let { recorder.event(RunEvent.State(recorder.now(), "gate_unresolved", "${call.name} $it")) }
        return ActionOutcome.Ran(exec, s.before, after, content, durationMs, shotId.takeIf { picture != null }, picture, seen)
    }

    private suspend fun execute(s: Step, sub: SubActions?): ToolExecution {
        val call = if (s.offScreen && offScreen != null) offScreen.capTimeout(s.call, s.args) else s.call
        val cap = s.origin.policy.actionCapMs ?: return tools.execute(call, pass, s.origin.step, sub)
        // Actions have their own upper limit
        return withTimeoutOrNull(cap) { tools.execute(call, pass, s.origin.step, sub) } ?: ToolExecution(buildJsonObject {
            put("ok", false); put("error", "action_timeout"); put("message", "the action did not finish within ${cap / 1000} s; read the screen before going on")
        })
    }

    /**
     * When playback is on, remember the target elements and the front and rear anchor points of the
     * route (read one more screen after the action and return to it). The target cannot be obtained, but
     * the action takes effect: the route cannot be missing steps, mark incomplete, and the entire route
     * is not recorded.
     */
    private suspend fun routeTarget(call: Part.ToolCall, verb: Verb?, origin: Origin, exec: ToolExecution): ScreenSnapshot? {
        if (!tools.captureTargets || tools.index[call.name]?.routable != true) return null
        // Only when the port clearly says "not a password box" will the original text be recorded.
        val secret = (exec.content["secret"] as? JsonPrimitive)?.booleanOrNull != false
        val args = Redactor.args(verb, call.argsJson, secret)
        val effective = effectiveWrite(verb, exec.content)
        val source = origin.policy.source
        val none = Anchors(null, emptyList())
        val ui = tools.interfaces
        val captured = ui.lastActionTarget()
        if (captured == null) {
            if (effective) recorder.event(RunEvent.ActionTarget(recorder.now(), origin.step, call.name, args, null, none, none, true,
                callId = call.id, source = source, incomplete = true))
            return null
        }
        val seen = if (effective) ui.peek() else null
        val after = seen?.let(ui::anchorsAfter) ?: none
        recorder.event(RunEvent.ActionTarget(recorder.now(), origin.step, call.name, args, captured.first, captured.second, after, effective,
            callId = call.id, protected = ui.lastActionProtected(), source = source))
        return seen
    }

    /** Effect on Mina herself: Do not do it; the model will receive instructions for each step as usual and record them in the running record. */
    private fun ownApp(call: Part.ToolCall, args: JsonObject, origin: Origin, before: List<GuardVerdict>, followUp: FollowUp): ActionOutcome {
        val content = Refusals.content(Refusals.OWN_APP, origin.policy.refusals, before, null, null)
        if (origin.policy.recordsRefusal) {
            followUp.refused(content)
            recorder.result(origin.step, call.name, tools.verb(call.name), content, tools.portRecord(call.name, args), null, null, 0, origin.policy.source, call.id)
        }
        return ActionOutcome.Refused(Refusals.OWN_APP, content, before)
    }

    private fun refuse(code: String, origin: Origin, before: List<GuardVerdict>) =
        ActionOutcome.Refused(code, Refusals.content(code, origin.policy.refusals, before, null, null), before)

    private fun blocked(
        call: Part.ToolCall, args: JsonObject, origin: Origin, before: List<GuardVerdict>, hit: Irreversible, reason: String?, followUp: FollowUp,
    ): ActionOutcome {
        recorder.confirmation(hit, if (reason != null) "mismatch" else "required")
        val code = if (reason != null) "confirmation_mismatch" else "confirmation_required"
        val content = Refusals.content(code, origin.policy.refusals, before, hit, reason)
        if (origin.policy.recordsRefusal) {
            followUp.refused(content)
            recorder.result(origin.step, call.name, tools.verb(call.name), content, tools.portRecord(call.name, args), null, null, 0, origin.policy.source, call.id)
        }
        return ActionOutcome.Refused(code, content, before, hit)
    }
}
