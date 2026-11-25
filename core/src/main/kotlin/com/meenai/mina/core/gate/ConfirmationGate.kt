package com.meenai.mina.core.gate

import com.meenai.mina.core.action.ConfirmChoice
import com.meenai.mina.core.risk.IrrevAction
import com.meenai.mina.core.uistate.Hant
import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.uistate.UiElement
import java.math.BigDecimal
import kotlin.math.abs
import com.meenai.mina.core.tool.Verb

/** It is a costly operation to declare the model; the fields have been blanked and truncated, and are displayed as such on the interface. [app] is the package name. */
data class ConfirmationRequest(val action: IrrevAction, val target: String, val content: String, val amount: String?, val app: String)

sealed interface DeclareResult {
    /** If the declared App is inconsistent with the frontend (or the frontend cannot be read), it will not be handed over to the user. */
    data object Mismatch : DeclareResult
    /** In this task, the user has already rejected similar operations in the same App, so no further questions will be asked. */
    data object Declined : DeclareResult
    /** Payment is not made here, but taken over. */
    data object Unsupported : DeclareResult
    data object Pending : DeclareResult
}

sealed interface Permit {
    data object Allowed : Permit
    /** No consent. */
    data object NoGrant : Permit
    /** There is consent, but it does not correspond to this step (or has expired); this consent has been invalidated. */
    data class Mismatch(val reason: String) : Permit
}

/**
 * The user agrees to an operation that costs money (only payment is asked when asking): only the next
 * matching operation is allowed. Agree to bind the declaration content: the same front-end App, similar
 * actions, and objects appear on the screen (in the title bar when sending), the words to be sent when
 * sending are equal to the declaration, and the declared amount is on the screen; the next hit
 * irreversible operation will be used regardless of whether it is correct or not. If any writing
 * operation other than typing is performed (including returning, opening the app), or [MAX_STEPS] steps
 * have passed, the consent will be invalidated. Can only be agreed this time; valid only within one
 * mission, starting and ending [reset]. The task loop and interface response may be in different
 * threads, and both reading and writing are locked.
 */
class ConfirmationGate {
    private class Grant(val request: ConfirmationRequest, val step: Int)

    private var pending: ConfirmationRequest? = null
    private var grant: Grant? = null
    private val declined = mutableSetOf<Pair<IrrevAction, String>>()

    @Synchronized fun declare(req: ConfirmationRequest, foregroundApp: String?): DeclareResult {
        if (req.action == IrrevAction.pay) return DeclareResult.Unsupported
        if (req.action to req.app in declined) return DeclareResult.Declined
        if (foregroundApp == null || foregroundApp != req.app) return DeclareResult.Mismatch
        pending = req
        return DeclareResult.Pending
    }

    /** [step] is the step number of the answer, and expiration is calculated based on it. */
    fun decide(approved: Boolean, step: Int = 0) {
        decide(if (approved) ConfirmChoice.Once else ConfirmChoice.Deny, step)
    }

    /** A one-time consent will be recorded according to the user's choice. */
    @Synchronized fun decide(choice: ConfirmChoice, step: Int) {
        val req = pending ?: return
        pending = null
        if (!choice.approved) {
            declined += req.action to req.app
            return
        }
        grant = Grant(req, step)
    }

    /**
     * It’s a screen at this moment;[screen]It is the text to be entered in this step and press Enter
     * (when type_text is used).[typing]It is the text you found by looking at the picture, click and
     * re-check the text you have confirmed: when there is no text in the reading screen (self-drawn
     * interface), the subject can check it. If the word to be sent cannot be read, it will not be
     * checked (the characters on the screen cannot be checked). Use it regardless of the consequences if
     * you agree. Payments are never released.[seen] 是看图找到的点复看确认过的所见文字:读屏里没有文字(自绘界面)时,对象可以对它核,
     * 要发的字读不到也就不核(画面上的字核不了)。有同意时不论结果都用掉它。付款永远不放行。
     */
    @Synchronized fun permits(hit: Irreversible, screen: ScreenSnapshot?, step: Int, typing: String? = null, seen: List<String> = emptyList()): Permit {
        val g = grant ?: return Permit.NoGrant
        grant = null
        val r = g.request
        val reason = when {
            hit.action == IrrevAction.pay -> "payment is never approved here"
            step - g.step > MAX_STEPS -> "the approval expired ($MAX_STEPS steps)"
            hit.action != r.action -> "approved ${r.action.name} but this step is ${hit.action.name}"
            screen?.packageName == null || screen.packageName != r.app -> "approved in ${r.app} but the screen shows ${screen?.packageName ?: "unknown"}"
            !targetShown(r, screen) && !(textless(screen) && seenShows(r, seen)) -> "the target \"${r.target}\" is not shown " + (if (r.action == IrrevAction.send) "in the title area" else "on the screen")
            r.action == IrrevAction.send && !contentMatches(r.content, screen, typing) && !(textless(screen) && seenShows(r, seen)) -> "the text to send is not exactly what was approved"
            !amountShown(r.amount, screen, hit.matched) -> "the approved amount ${r.amount ?: "(none)"} is not on the screen"
            else -> return Permit.Allowed
        }
        return Permit.Mismatch(reason)
    }

    /** There is not a single element with words in the screen reader (self-drawn interface, canvas). */
    private fun textless(s: ScreenSnapshot) = s.elements.none { it.name.isNotBlank() }

    /** The object of declaration appears in the text (at least two words) that has been confirmed by re-reading. */
    private fun seenShows(r: ConfirmationRequest, seen: List<String>): Boolean {
        val t = norm(r.target).take(MATCH_CHARS)
        return t.length >= 2 && seen.any { t in norm(it) }
    }

    /** A write operation was performed: typing and clearing the input box did not affect the consent, and the rest (clicking elsewhere, sliding, returning, opening the app) made it invalid. */
    /** There is an operation that has been agreed to but not used yet. */
    val hasGrant: Boolean @Synchronized get() = grant != null

    /** The operation that the user agreed to but has not yet been used; if not, it is null. */
    val granted: ConfirmationRequest? @Synchronized get() = grant?.request

    /** [verb]: The action of the write operation just performed; entering and clearing does not invalidate the consent (the consent is the step to be entered). */
    @Synchronized fun noteWrite(verb: Verb?) {
        if (verb !in KEEPS_GRANT) grant = null
    }

    @Synchronized fun reset() {
        pending = null
        grant = null
        declined.clear()
    }

    /**
     * The object must be in non-editable text on the screen (typing in the input box does not count), at
     * least two words; when sending, only recognize the title bar at the top of the screen (where the
     * name of the chat partner is), and other actions can be anywhere on the screen.
     */
    private fun targetShown(r: ConfirmationRequest, s: ScreenSnapshot): Boolean {
        val t = norm(r.target).take(MATCH_CHARS)
        if (t.length < 2) return false
        return s.elements.any { e ->
            !e.editable && t in norm(e.name) && (r.action != IrrevAction.send || (e.bounds.size == 4 && e.bounds[1] <= TITLE_BAND))
        }
    }

    /**
     * What you want to send is the agreed paragraph: when you click send, the word in an input box is
     * equal to it, and when type_text is pressed, the word to be entered is equal to it. Declarations
     * are truncated (ending with...) when compared to the prefix.
     */
    private fun contentMatches(content: String, s: ScreenSnapshot, typing: String?): Boolean {
        val capped = content.endsWith("…")
        val c = norm(content.removeSuffix("…"))
        fun same(x: String) = norm(x).let { if (capped) it.startsWith(c) else it == c }
        if (typing != null) return same(typing)
        return s.elements.any { it.editable && !it.hint && same(it.name) }
    }

    /**
     * The amount declared is only compared with the number with currency (¥/￥, Yuan, $) on the screen:
     * "25 items sold" and "3 minus 25" are not counted; the amounts in the input box and prompts are not
     * counted; the amounts next to discounts, reductions, deductions, freight, coupons, red envelopes,
     * and saved (or with a negative sign) are not counted. When there is total/actual
     * payment/payable/total: each total outside the scrollable area must be equal to the declared
     * amount; when they are all in the scrollable area, look at the one closest to the button; the total
     * is not allowed when the total is in and the amount cannot be read (the following paragraphs on the
     * same line cannot be read even if they are spelled out). If there is no total, it must be equal to
     * the largest amount outside the scrollable area. Applications without numbers will not be allowed.
     */
    private fun amountShown(amount: String?, s: ScreenSnapshot, button: String): Boolean {
        val declared = amount?.let { currencyFree(it) }?.let { NUMBER.find(it)?.value }?.toBigDecimalOrNull() ?: return false
        fun same(x: BigDecimal) = x.compareTo(declared) == 0
        val shown = s.elements.filter { !it.editable && !it.hint }
        val scrollables = s.elements.filter { it.scrollable }
        fun outside(e: UiElement) = scrollables.none { inside(e, it) }
        val labels = shown.filter { e -> TOTAL_LABELS.any { it in Hant.fold(e.name) } }
        if (labels.isNotEmpty()) {
            val totals = labels.map { it to totalOf(it, shown) }
            if (totals.any { it.second == null }) return false
            val out = totals.filter { outside(it.first) }
            if (out.isNotEmpty()) return out.all { same(it.second!!) }
            val b = shown.firstOrNull { it.name.trim() == button }
            val near = totals.minByOrNull { (e, _) -> if (b == null) -top(e) else abs(centerY(e) - centerY(b)) }!!
            return same(near.second!!)
        }
        val largest = shown.filter(::outside).flatMap { amounts(it.name) }.maxOrNull() ?: return false
        return same(largest)
    }

    /** To total the amount behind the label: first look at it by itself, and then put together the paragraphs on the right side of the same line in horizontal order. */
    private fun totalOf(label: UiElement, shown: List<UiElement>): BigDecimal? {
        fun after(text: String) = TOTAL_LABELS.mapNotNull { w -> text.indexOf(w).takeIf { it >= 0 }?.let { text.substring(it + w.length) } }
            .firstNotNullOfOrNull { amounts(it).firstOrNull() }
        return after(Hant.fold(label.name)) ?: after(Hant.fold(IrreversibleDetector.rowText(label, shown)))
    }

    private fun currencyFree(t: String) = t.replace(THOUSANDS, "")

    /** For amounts with currency, remove words such as discounts or negative signs next to them. */
    private fun amounts(name: String): List<BigDecimal> {
        val t = currencyFree(Hant.fold(name))
        return CURRENCY.findAll(t).filter { m ->
            val before = t.substring(maxOf(0, m.range.first - DISCOUNT_WINDOW), m.range.first)
            DISCOUNTS.none { it in before } && !before.trimEnd().endsWith("-")
        }.mapNotNull { m -> m.groupValues[1].ifEmpty { m.groupValues[2] }.toBigDecimalOrNull() }.toList()
    }

    private fun top(e: UiElement) = if (e.bounds.size == 4) e.bounds[1] else 0
    private fun centerY(e: UiElement) = if (e.bounds.size == 4) (e.bounds[1] + e.bounds[3]) / 2 else 0

    private fun inside(c: UiElement, p: UiElement): Boolean {
        val a = c.bounds; val b = p.bounds
        return a.size == 4 && b.size == 4 && a[0] >= b[0] && a[1] >= b[1] && a[2] <= b[2] && a[3] <= b[3]
    }


    private fun norm(s: String) = s.filterNot { it.isWhitespace() }.lowercase()

    companion object {
        /** After so many steps after agreeing, it will become invalid without being used. */
        const val MAX_STEPS = 3
        const val MATCH_CHARS = 20
        /** Send objects above this line (0–999): about 15% of the title bar from the top of the screen. */
        const val TITLE_BAND = 150
        private val NUMBER = Regex("\\d+(\\.\\d+)?")
        private val CURRENCY = Regex("[¥￥$＄]\\s*(\\d+(?:\\.\\d+)?)|(\\d+(?:\\.\\d+)?)\\s*元")
        private val THOUSANDS = Regex("(?<=\\d),(?=\\d{3}(?!\\d))")
        private val TOTAL_LABELS = IrreversibleDetector.TOTAL_LABELS
        private val DISCOUNTS = listOf("优惠", "减", "抵扣", "运费", "券", "红包", "已省")
        private const val DISCOUNT_WINDOW = 4
        private val KEEPS_GRANT = setOf(Verb.TypeText, Verb.ClearText)
    }
}

