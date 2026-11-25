package com.meenai.mina.core.action

import com.meenai.mina.core.gate.ConfirmationGate
import com.meenai.mina.core.risk.IrrevAction
import com.meenai.mina.core.gate.Irreversible
import com.meenai.mina.core.gate.IrreversibleDetector
import com.meenai.mina.core.gate.Permit
import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.uistate.interfaces
import com.meenai.mina.core.tool.ToolDispatcher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.meenai.mina.core.tool.Verb
import com.meenai.mina.core.run.RunProvenance

/** The approval conclusion before an action is executed. */
sealed interface Decision {
    /** No judgment is required, or the judgment is released (including the user’s consent during payment). */
    data object Clear : Decision
    /** Released, but cannot find which element the point is on: traces left in the running record. */
    data class Traced(val detail: String) : Decision
    /** If you can't find which element the point is on, this source is not a blind spot. */
    data object Unresolved : Decision
    /** This step costs money and requires the user's consent this time, which is not available. */
    data class Required(val hit: Irreversible) : Decision
    /** There is consent, but it does not correspond to this step; that consent has become invalid. */
    data class Mismatch(val hit: Irreversible, val reason: String) : Decision
    /** It costs money, but the user next to the mobile phone cannot be asked for this run, so he directly refuses. */
    data object Denied : Decision
    /** Act on Mina herself, don't do it. */
    data object OwnApp : Decision
}

/**
 * Irreversible determination, approval and consent verification. In the entire code base, there are only
 * the judgments of calling [IrreversibleDetector] and [ConfirmationGate.permits] (tested). Each source
 * only states the difference in [OriginPolicy]; whether to ask or not is determined by
 * [ApprovalPolicy.decide] (only payment is asked).
 */
class ConfirmDecision(
    private val tools: ToolDispatcher,
    private val confirmations: ConfirmationGate?,
    private val approvals: ApprovalSettings = ApprovalSettings.ASK,
    private val risks: RiskJudge = ClassifiedRisk,
    private val recorder: ActionRecorder? = null,
    private val provenance: RunProvenance = RunProvenance(),
    /** The start of the project is autonomous execution (no confirmation level): payment is not asked, and interface target guessing does not block actions; in the middle, it is replaced by autonomous viewing of [ApprovalSettings.autonomous]. */
    private val autonomous: Boolean = false,
    /** Mina's own package name: no write operations will be performed on it (do not give yourself permission or change settings). */
    private val selfPackage: String? = null,
) {
    /**
     * The step just passed is the website or App (see [OutwardPacing.siteOf]) where it is located when
     * performing external actions (send, submit: determine hit, release), and the execution entrance is
     * separated from the last external action accordingly; it is not null. It is also recognized when
     * executing independently (judge according to the screen in the model's hand, without reading
     * another screen), and the rhythm will not be invalidated.
     */
    var outwardSite: String? = null
        private set

    /** The judgment is placed after locking the screen and waiting: press the screen judgment read again at this moment. */
    suspend fun judge(tool: String, args: JsonObject, origin: Origin): Decision {
        outwardSite = null
        // Tools that declare alwaysAsk (an arbitrary shell command) must be
        // confirmed even in autonomous mode. Autonomous runs cannot show a
        // prompt, so they are denied instead of silently executing.
        val mustAsk = tools.approvalRisk(tool)?.takeIf { it.alwaysAsk }
        if (mustAsk != null) {
            if (autonomous || approvals.autonomous()) return Decision.Denied
            return approve(tool, args, origin, null, mustAsk, null, null, null) ?: Decision.Clear
        }
        if (autonomous || approvals.autonomous()) {
            val target = tools.gateTarget(tool)
            if (IrreversibleDetector.needsCheck(target, args)) {
                val aim = tools.interfaces.actionTarget(tools.declared(tool), args)
                outwardSite = outward(IrreversibleDetector.classify(target, aim.args, aim.aimed), aim.aimed)
            }
            return Decision.Clear
        }
        val policy = origin.policy
        val target = tools.gateTarget(tool)
        // Where this step falls is given by the side of the interface; when you click on @vN found in
        // the picture, the parameters have been replaced by its registered coordinates and the text you
        // are looking for.
        val aim = tools.interfaces.actionTarget(tools.declared(tool), args)
        val checked = aim.args
        if (ownApp(tool, args, aim.aimed)) return Decision.OwnApp
        tools.approvalRisk(tool)?.let { declared ->
            return approve(tool, checked, origin, null, declared, null, null, null) ?: Decision.Clear
        }
        if (confirmations == null && !policy.judgeWithoutGate) return Decision.Clear
        val verb = tools.verb(tool)
        val typing = if (verb == Verb.TypeText) (args["text"] as? JsonPrimitive)?.contentOrNull else null
        // Actions without the word "irreversible" (general changes, movement between interfaces) do not
        // cost money, no questions asked
        if (!IrreversibleDetector.needsCheck(target, args)) return Decision.Clear
        val snap = aim.aimed
        val fresh = aim.now()
        if (ownApp(tool, args, fresh)) return Decision.OwnApp
        // Reread the confirmed text (only for @vN): When there is no text in the reading screen
        // (self-drawn interface), click it to check the object when agreeing to pay.
        val seen = aim.seen
        val hit = IrreversibleDetector.classify(target, checked, snap, fresh)
        val risk = risks.assess(verb, hit) { fresh ?: snap }
        outwardSite = outward(hit, fresh ?: snap)
        approve(tool, checked, origin, hit, risk, fresh ?: snap, typing, seen)?.let { return it }
        if (hit != null || !IrreversibleDetector.unresolved(target, checked, snap, fresh)) return Decision.Clear
        return if (policy.refuseUnresolved) Decision.Unresolved else Decision.Traced(unresolvedDetail(args, aim.readAgeMs, fresh != null))
    }

    /** The hit is the website or app on this screen when sending or submitting. */
    private fun outward(hit: Irreversible?, screen: ScreenSnapshot?): String? =
        if (hit?.action == IrrevAction.send || hit?.action == IrrevAction.submit) OutwardPacing.siteOf(screen?.packageName, screen?.web != null) else null

    /** The writing operation affects Mina itself: Mina is on the screen, or Mina (including its application details page) needs to be opened. */
    private fun ownApp(tool: String, args: JsonObject, screen: ScreenSnapshot?): Boolean {
        val self = selfPackage ?: return false
        if (!tools.writesScreen(tool)) return false
        if (screen?.packageName == self) return true
        return listOf("package", "uri").any { k -> (args[k] as? JsonPrimitive)?.contentOrNull?.let { self in it } == true }
    }

    /** Judge by source; check the user’s one-time consent when spending money. Leave the behavior null. */
    private fun approve(
        tool: String, checked: JsonObject, origin: Origin, hit: Irreversible?, risk: Risk, screen: ScreenSnapshot?,
        typing: String?, seen: String?,
    ): Decision? {
        val policy = origin.policy
        // If the miss is irreversible (risk declared by the tool itself): The element in which the
        // action falls is recorded as "other"
        val asked = hit ?: Irreversible(IrrevAction.other,
            screen?.let { ClassifiedRisk.target(tools.verb(tool), checked, it) }?.name?.trim()?.takeIf { it.isNotEmpty() } ?: tool)
        // All external information, those that require money and those that have not been released are
        // recorded; changes on the interface are not recorded.
        fun audit(decision: String) {
            if (decision != "allowed" || risk.level.outward || risk.payment) recorder?.approval(origin, tool, asked, risk, decision)
        }
        when (ApprovalPolicy.decide(risk, provenance)) {
            Approval.Allow -> return null.also { audit("allowed") }
            Approval.Deny -> return Decision.Denied.also { audit("denied") }
            Approval.Ask -> Unit
        }
        val required = Decision.Required(asked)
        val gate = confirmations?.takeIf { policy.usesGrant } ?: return required.also { audit("required") }
        return when (val p = gate.permits(asked, screen, origin.step, typing, listOfNotNull(seen))) {
            Permit.Allowed -> null.also { audit("allowed") }
            Permit.NoGrant -> required.also { audit("required") }
            is Permit.Mismatch -> Decision.Mismatch(asked, p.reason).also { audit("mismatch") }
        }
    }

    /** A write operation was performed: the consent was voided or retained according to its rules. */
    fun noteWrite(tool: String) {
        confirmations?.noteWrite(tools.verb(tool))
    }

    /** Traces: Which number was clicked, how long ago the screen reading was, or the coordinates of the point. */
    private fun unresolvedDetail(args: JsonObject, readAgeMs: Long?, freshRead: Boolean): String {
        fun arg(k: String) = args[k]?.let { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() }
        val ref = arg("ref")
        return if (ref != null) {
            val sid = arg("snapshotId")
            "ref=$ref snapshotId=$sid age=${readAgeMs?.let { "${it}ms" } ?: "unknown"}"
        } else "x=${arg("x")},y=${arg("y")} freshRead=$freshRead"
    }

    companion object {
        /**
         * Prediction of not reading the screen (used for division of labor between large and small
         * models): According to the last screen reading, this step may be irreversible, or you may not
         * know what you clicked (you can’t find the element based on the picture); such a step is left
         * to the main model.
         */
        fun mayBeIrreversible(tools: ToolDispatcher, tool: String, args: JsonObject): Boolean {
            val target = tools.gateTarget(tool)
            if (!IrreversibleDetector.needsCheck(target, args)) return false
            val aim = tools.interfaces.actionTarget(tools.declared(tool), args)
            val checked = aim.args
            val snap = aim.aimed
            return aim.bySight || IrreversibleDetector.classify(target, checked, snap) != null ||
                IrreversibleDetector.unresolved(target, checked, snap)
        }
    }
}
