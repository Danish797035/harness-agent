package com.meenai.mina.core.action

import com.meenai.mina.core.risk.IrrevAction
import com.meenai.mina.core.gate.Irreversible
import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.uistate.UiElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.meenai.mina.core.tool.Verb

/**
 * The risk of an action. [hit] is the result of irreversible judgment (no judgment or no hit is null);
 * [screen] is the screen on which the judgment is based, which is read only when used. The tool itself
 * can declare the risk and replace the implementation, and the judgment and approval do not need to be
 * changed.
 */
fun interface RiskJudge {
    fun assess(verb: Verb?, hit: Irreversible?, screen: () -> ScreenSnapshot?): Risk
}

/** According to the existing irreversible judgment and screen reading signal classification; whether to spend money is judged by the action category and the amount on the screen, not by the App. */
object ClassifiedRisk : RiskJudge {
    /** Change the actions of things on the interface; other writing operations (return, slide, open app, open URL) are just moving between interfaces. */
    private val CHANGE_VERBS = setOf(Verb.Tap, Verb.TypeText, Verb.ClearText, Verb.PressKey, Verb.Upload, Verb.Interact)

    /** This action will change things on the interface (not just moving between interfaces); irreversible and external actions are also made by these actions. */
    fun changesState(verb: Verb?): Boolean = verb in CHANGE_VERBS

    /** The amount with currency (¥, yuan, $) on the screen: At this time, actions such as submitting and deleting are counted as money ("Confirm" and "Go to Pay" on the settlement page); sending messages does not cost money, so it does not count. */
    private val AMOUNT = Regex("[¥￥$＄]\\s*\\d|\\d+(\\.\\d+)?\\s*元")

    override fun assess(verb: Verb?, hit: Irreversible?, screen: () -> ScreenSnapshot?): Risk {
        val level = when (hit?.action) {
            null -> if (verb in CHANGE_VERBS) ActionRisk.Change else ActionRisk.Read
            IrrevAction.send, IrrevAction.submit -> ActionRisk.External
            IrrevAction.pay, IrrevAction.order, IrrevAction.delete -> ActionRisk.Irreversible
            IrrevAction.other -> ActionRisk.Unknown
        }
        if (hit == null) return Risk(level)
        val payment = hit.action == IrrevAction.pay || hit.action == IrrevAction.order ||
            hit.action != IrrevAction.send && screen()?.elements?.any { !it.editable && AMOUNT.containsMatchIn(it.name) } == true
        return Risk(level, payment)
    }

    /** Which element the action falls on: Find the numbered element by number, type in the focused input box, and click on the coordinates to get the smallest element containing the point. */
    fun target(verb: Verb?, args: JsonObject, s: ScreenSnapshot): UiElement? {
        fun str(k: String) = (args[k] as? JsonPrimitive)?.contentOrNull
        str("ref")?.let { ref -> return s.elements.firstOrNull { it.ref == ref } }
        if (verb == Verb.TypeText || verb == Verb.ClearText) return s.elements.firstOrNull { it.focused && it.editable }
        val x = str("x")?.toDoubleOrNull() ?: return null
        val y = str("y")?.toDoubleOrNull() ?: return null
        return s.elements.filter { e -> e.bounds.size == 4 && x >= e.bounds[0] && x <= e.bounds[2] && y >= e.bounds[1] && y <= e.bounds[3] }
            .minByOrNull { (it.bounds[2] - it.bounds[0]).toLong() * (it.bounds[3] - it.bounds[1]) }
    }
}

