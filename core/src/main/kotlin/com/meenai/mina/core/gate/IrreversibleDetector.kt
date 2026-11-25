package com.meenai.mina.core.gate

import com.meenai.mina.core.uistate.CdpKeys
import com.meenai.mina.core.uistate.KeyCombo
import com.meenai.mina.core.uistate.KeyModifier
import com.meenai.mina.core.uistate.ScreenDiagnosis
import com.meenai.mina.core.uistate.ElementContext
import com.meenai.mina.core.uistate.Hant
import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.uistate.UiElement
import com.meenai.mina.core.risk.GateTarget
import com.meenai.mina.core.risk.IrrevAction
import com.meenai.mina.core.uistate.PressKeys
import com.meenai.mina.core.tool.LOCATED_REF
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** [matched]: The hit text (button name, resource ID, target parameter, input box or starting point of sliding). */
data class Irreversible(val action: IrrevAction, val matched: String)

/**
 * The word list before the write operation is executed: only the button name, enter/confirm key, and
 * horizontal sliding of the list line are recognized, and no semantic understanding is performed. If you
 * are not sure, it is better to block more (the model can continue after filling request_confirmation
 * once). Only submit deliberately only recognizes complete equality.
 *
 * Screen reading: The snapshot of [classify] is the screen reading to which ref belongs (the most recent
 * one when there is no ref), and fresh is the screen that is re-read at this moment. Coordinates, Enter,
 * slide and press fresh to judge (return to snapshot only if you can't read it); ref will judge both,
 * and any hit will count.
 */
object IrreversibleDetector {
    /**
     * It counts if it appears in the name (short name, or every paragraph in the spelled name); but if
     * it is preceded by "to be" or "already", followed by [STATUS_SUFFIXES], it is the order status or
     * entry (pending payment, payment successful, purchase record), it does not count.
     */
    val STRONG: Map<String, IrrevAction> = linkedMapOf(
        "支付" to IrrevAction.pay, "付款" to IrrevAction.pay, "转账" to IrrevAction.pay,
        "删除" to IrrevAction.delete, "撤回" to IrrevAction.delete,
        "下单" to IrrevAction.order, "购买" to IrrevAction.order, "结算" to IrrevAction.order, "结账" to IrrevAction.order,
        "取消订单" to IrrevAction.order, "确认收货" to IrrevAction.order, "退款" to IrrevAction.order,
        "转发" to IrrevAction.send,
    )
    private const val STRONG_MAX_LEN = 12
    private val STATUS_PREFIXES = listOf('待', '已')
    val STATUS_SUFFIXES = listOf("成功", "记录", "售后", "明细", "方式", "列表", "中心", "入口", "码")

    /** These payment words only recognize the button itself, or are preceded by "immediate", "confirm" and "go" ("recharge center", "phone recharge" and "discount payment" are the entrances and do not count). */
    val EXACT_PAY = listOf("收银台", "买单", "充值", "储值", "提现", "转出", "打赏", "缴费")
    private val EXACT_PAY_PREFIXES = listOf("", "立即", "确认", "去")

    /** Red envelopes only recognize these statements on the button ("WeChat red envelopes" are bubbles in chat). */
    val RED_PACKET = listOf("红包", "发红包", "塞钱进红包")

    /** Chinese: Equal to the word, or starting with the word and with only a few more characters (such as "Send (1)"). */
    val PREFIX_WORDS: Map<IrrevAction, List<String>> = linkedMapOf(
        IrrevAction.send to listOf("发送", "传送", "发布", "发表"),
        IrrevAction.delete to listOf("清空", "移除", "注销", "退出群聊"),
        IrrevAction.order to listOf("提交订单"),
    )
    private const val PREFIX_SLACK = 4

    /** Equal to words, or followed only by punctuation ("Comments (23)" and "Reply 3" are entries and counts, not counted). */
    val PUNCT_WORDS: Map<IrrevAction, List<String>> = linkedMapOf(IrrevAction.send to listOf("评论", "回复"))

    /** Equal to the word, or preceded by "immediately" or "confirm". */
    val BOOKING_WORDS = listOf("预约", "报名")
    private val BOOKING_PREFIXES = listOf("", "立即", "确认")

    /** Only exact equality is recognized: "Submit search" category is not blocked. */
    val SUBMIT_WORDS = listOf("提交", "确认提交", "送出", "submit")

    /**
     * Click the button to contact others and submit an application for the user (click the website to
     * send the greeting and resume to the other party), and press submit: equal to the word, or preceded
     * by "immediately", "immediately", "one click" and "go", followed by only punctuation. "Continue
     * communication", "Delivered", "Communication record", "Contact customer service", "Contact us",
     * "Private message" and "Message" do not count: they are existing conversations, records, customer
     * service pages, or just opening the place where you write a message (what is actually sent is sent
     * later).
     */
    val CONTACT_WORDS = listOf(
        "沟通", "打招呼", "打个招呼", "发起聊天", "发起沟通", "联系ta", "联系他", "联系她", "联系对方",
        "投递", "投简历", "投递简历", "应聘",
    )
    private val CONTACT_PREFIXES = listOf("", "立即", "马上", "一键", "去")
    /** "Application" itself is too general (applying for a refund, applying for permissions): I only recognize these terms. */
    val APPLY_WORDS = listOf("立即申请", "一键申请", "申请职位", "申请岗位", "申请该职位")
    /** English: The entire name is equal to these (case-insensitive, blank merging, and trailing punctuation removed); individual Apply (application of the filter panel), Contact, and Message do not count. */
    val CONTACT_ENGLISH = listOf(
        "say hi", "say hello", "send greeting", "apply now", "easy apply", "quick apply", "apply for this job", "apply to this job",
        "submit application", "contact now", "contact recruiter", "contact employer", "contact seller", "contact host", "contact owner",
        "contact landlord", "contact agent",
    )

    /** English: The name starts with this word (according to word boundaries, regardless of case), and the length is not limited, such as "Send message" and "Delete conversation". */
    val ENGLISH: Map<String, IrrevAction> = linkedMapOf(
        "send" to IrrevAction.send, "post" to IrrevAction.send, "reply" to IrrevAction.send,
        "pay" to IrrevAction.pay,
        "delete" to IrrevAction.delete, "remove" to IrrevAction.delete, "clear all" to IrrevAction.delete,
        "place order" to IrrevAction.order, "buy now" to IrrevAction.order, "checkout" to IrrevAction.order,
    )

    /** Universal confirmation on pop-ups and bottom panels: only counts if there are strong words or amounts on the same screen. */
    val GENERIC = listOf("确认", "确定", "ok", "完成", "confirm")
    private val AMOUNT = Regex("[¥￥$＄]\\s*\\d|\\d+(\\.\\d+)?\\s*元")
    /** The amount with these words is the total, regardless of whether it is in the scrollable area or not, it is considered context. */
    val TOTAL_LABELS = listOf("合计", "实付", "应付", "总计")
    /** When there is an amount on the same screen, these words indicate the payment panel (payment method, cashier, password verification). */
    val PAY_SHEET_WORDS = listOf("支付方式", "付款方式", "支付宝", "银行卡", "收银台", "免密")
    /** These words are also common on ordinary pages (account balance, login password): it is only considered a payment panel when there are confirmation/payment buttons in the lower half of the screen and in the same area. */
    val WEAK_PAY_SHEET_WORDS = listOf("余额", "密码", "指纹", "零钱")
    /** Words introducing payment methods (common on product pages and specifications panels) are not payment actions, so remove them first before making a judgment. */
    private val PAYMENT_ADS = Regex("支持[^\\s，,。]{0,8}?(?:付款|支付)|货到付款|先用后付|花呗|分期|付款方式说明")
    /** When there are these order buttons on the page, it is the product page or specification panel: do not judge by the words payment panel. */
    private val ORDER_CONTEXT = listOf("立即购买", "加入购物车", "立即下单")
    /** Below this line in 0–999 is the area at the bottom of the screen (payment panel, bottom pop-up layer). */
    private const val BOTTOM_REGION = 500
    private val PAY_SHEET_ENGLISH = Regex("(?<![A-Za-z0-9_])(pay|payment|checkout total)(?![A-Za-z0-9_])", RegexOption.IGNORE_CASE)

    /** Let’s remove it from the name first, and then make a strong statement: the payment method option is not a payment action. */
    val EXCLUDED = listOf("支付宝", "支付方式", "支付设置")

    /** The resource ID is the word separated by _ -. and camel case; submit and post are not here (too common in resource IDs). */
    private val RESOURCE_WORDS: Map<String, IrrevAction> = mapOf(
        "send" to IrrevAction.send, "reply" to IrrevAction.send,
        "pay" to IrrevAction.pay,
        "delete" to IrrevAction.delete, "remove" to IrrevAction.delete,
        "checkout" to IrrevAction.order,
    )

    /** If the input box is clearly a search box, it will not be sent; if you press Enter in other input boxes, press send. Search for these words in the prompt, and search by word in the resource ID. */
    private val SEARCH_FIELD = listOf("search", "搜索", "搜寻")
    private val SEARCH_BUTTONS = setOf("搜索", "搜寻", "search")

    private const val KEY_ENTER = 66
    private const val KEY_NUMPAD_ENTER = 160
    private const val KEY_DPAD_CENTER = 23
    private const val KEY_SPACE = 62
    private const val KEY_DEL = 67
    private const val KEY_FORWARD_DEL = 112
    private val ACTIVATING_KEYS = setOf(KEY_ENTER, KEY_NUMPAD_ENTER, KEY_DPAD_CENTER, KEY_SPACE)

    /** Swipe horizontally beyond the screen width ratio (0-999), start from the list row, and press "Slide to Delete". */
    private const val SWIPE_DELETE_MIN = 400
    /** Clickable elements with a width wider than this (0–999) are counted as list rows. */
    private const val ROW_MIN_WIDTH = 600
    /** Elements higher than this (0-999) are not considered "on the same line" (containers, large blocks of text). */
    private const val ROW_MAX_HEIGHT = 150
    /** Scrollable areas shorter than this (0–999) are counted as carousels. */
    private const val CAROUSEL_MAX_HEIGHT = 400

    /** Enter the name of the "Clear" button next to the input box (remove whitespace and lowercase letters). */
    private val FIELD_CLEAR_WORDS = setOf("清空", "清除", "清空输入", "清除输入", "清空搜索", "清除搜索", "清空内容", "清除内容",
        "clear", "cleartext", "clearsearch", "clearquery", "×", "✕", "x")
    /** The clear key is at most this far (0–999) from the left and right edges of the input box. */
    private const val FIELD_NEAR = 80

    // What is judged and what judgment is based on is declared by the tool ([GateTarget], only External
    // tools have it); returning, clearing the input box, and opening the App will not submit anything
    // and no judgment will be made.
    /** Keys to change the selected item: Arrow keys, Home/End, page turning. */
    private val SELECTION_KEYS = setOf(19, 20, 21, 22, 122, 123, 92, 93)
    /** A control that may trigger page actions (onchange submission) when the selected item is changed. */
    private val SELECTION_ROLES = setOf("combobox", "listbox", "option", "radio", "radiogroup", "menu", "menubar", "menuitem",
        "menuitemradio", "menuitemcheckbox", "tree", "treeitem")
    private val OPTION_ROLES = setOf("option", "radio", "menuitem", "menuitemradio", "menuitemcheckbox", "treeitem")

    /** Whether this call needs to be judged (read the screen only if it is judged). */
    fun needsCheck(target: GateTarget?, args: JsonObject): Boolean = when (target) {
        // Upload key file control, the judgment is the same as clicking
        GateTarget.Tap, GateTarget.FileControl -> true
        // It will be judged when typing and pressing the carriage return; it will be judged when
        // clicking when typing to a certain point (the button clicked may be a send button)
        GateTarget.Enter -> bool(args, "pressEnter") || atPoint(args)
        GateTarget.Key -> key(args) in ACTIVATING_KEYS
        GateTarget.Swipe -> horizontal(args) != null
        // The interactions that can trigger submission on the web page are: press the focus for each key
        // combination, press the starting point and end point of dragging (slide to pay, drag into the
        // delete area), drop-down selection, press the drop-down box and the selected item (the page may
        // be submitted as soon as it is changed); hovering will not submit
        GateTarget.Interact -> when (str(args, "action")) {
            "key" -> combo(args) != null
            "drag", "select" -> true
            // Clicking "OK" in the pop-up box on the page may mean submission (confirm deletion, confirm
            // payment): press the word in the pop-up box to confirm; cancel will not be judged
            "dialog" -> str(args, "answer") == "accept"
            else -> false
        }
        null -> false
    }

    /** null = Not an irreversible operation. */
    fun classify(target: GateTarget?, args: JsonObject, snapshot: ScreenSnapshot?, fresh: ScreenSnapshot? = null): Irreversible? {
        if (!needsCheck(target, args)) return null
        return when (target) {
            GateTarget.Tap, GateTarget.FileControl -> tapHit(args, snapshot, fresh)
            GateTarget.Enter -> (if (atPoint(args)) tapHit(args, snapshot, fresh) else null)
                ?: if (bool(args, "pressEnter")) enterHit(args, snapshot, fresh ?: snapshot, KEY_ENTER) else null
            GateTarget.Key -> enterHit(args, snapshot, fresh ?: snapshot, key(args)!!)
            GateTarget.Swipe -> swipeHit(args, fresh ?: snapshot)
            GateTarget.Interact -> when (str(args, "action")) {
                "key" -> comboHit(args, snapshot, fresh ?: snapshot)
                "select" -> selectHit(args, snapshot, fresh)
                "dialog" -> dialogHit(fresh ?: snapshot)
                else -> dragHit(args, snapshot, fresh)
            }
            null -> null
        }
    }

    private fun combo(args: JsonObject) = str(args, "key")?.let(CdpKeys::parseCombo)

    /** What you type is a point (coordinates, or @vN before changing to coordinates), not an element in the tree. */
    private fun atPoint(args: JsonObject) = args["x"] != null || str(args, "ref")?.let(LOCATED_REF::matches) == true

    /**
     * Press the key combination to determine what it can do to the focus: Enter and space (with or
     * without modifier keys) will activate the focus, press the enter/space rule of press_key (same as
     * clicking it, the space in the input box is typing); the direction keys, Home/End, and page turning
     * will change the selected item, only when the focus is a selection type control, press the control
     * and the items it can select (same as select); When the focus is not on the input box, the page may
     * treat delete, backspace, single alphanumeric, Ctrl/Alt/Meta + alphanumeric as commands: when the
     * focus is clicked on the element, it will be judged (letters on the selection control are selected
     * by the first letter, and by control and optional items); when the focus is on the page itself (no
     * focused element), delete and backspace will be blocked. The other command keys will only be judged
     * by pressing that control when the page declares which control it is a shortcut key
     * (aria-keyshortcuts, accesskey); Tab, Escape, etc. only move the focus or close it. The key in the
     * input box is editing, and it does not matter.
     */
    private fun comboHit(args: JsonObject, snapshot: ScreenSnapshot?, s: ScreenSnapshot?): Irreversible? {
        val combo = combo(args)!!
        val code = combo.key.androidKeyCode
        if (code == KEY_ENTER || code == KEY_SPACE) return enterHit(args, snapshot, s, code)
        val screen = s ?: snapshot ?: return null
        val focused = screen.elements.firstOrNull { it.focused }
        if (focused?.editable == true) return null
        // Select the keys on the control to change the selected item (arrow keys, press the first word
        // to jump to the selection), press the control and the items it can select.
        fun selection(e: UiElement) = selectionTargets(e, screen).firstNotNullOfOrNull { elementHit(it, screen) }
        if (code in SELECTION_KEYS) return focused?.takeIf { it.role in SELECTION_ROLES }?.let(::selection)
        val deleting = code == KEY_DEL || code == KEY_FORWARD_DEL
        val printable = combo.key.text != null && combo.key.text != " "
        if (!deleting && !printable) return null
        val typeAhead = printable && combo.modifiers.all { it == KeyModifier.Shift }
        if (focused != null) {
            val own = if (typeAhead && focused.role in SELECTION_ROLES) selection(focused) else elementHit(focused, screen)
            return own ?: declaredHit(combo, screen)
        }
        if (deleting) return Irreversible(IrrevAction.delete, "${combo.key.key}:焦点不明")
        return declaredHit(combo, screen)
    }

    /** The page declares the control of this key combination (aria-keyshortcuts, accesskey), click it to judge. */
    private fun declaredHit(combo: KeyCombo, s: ScreenSnapshot): Irreversible? = s.elements
        .filter { e -> e.keyShortcuts.any { sameCombo(CdpKeys.parseCombo(it), combo) } }
        .firstNotNullOfOrNull { elementHit(it, s) }

    private fun sameCombo(a: KeyCombo?, b: KeyCombo) = a != null && a.key.code == b.key.code && a.modifiers.toSet() == b.modifiers.toSet()

    /** Selection control and the items it can select: the options in the control. When the focus is the option, there are other options of the same type (radio group, menu). */
    private fun selectionTargets(focused: UiElement, s: ScreenSnapshot): List<UiElement> {
        val inside = s.elements.filter { it !== focused && it.role in OPTION_ROLES && inside(it, focused) }
        val peers = if (focused.role in OPTION_ROLES) s.elements.filter { it !== focused && it.role == focused.role } else emptyList()
        return listOf(focused) + inside + peers
    }

    /** Confirm the page pop-up box: Press the button name to judge the word in the pop-up frame (see the context of the same screen for the general "OK"), and then click the strong word in the whole sentence to judge. */
    private fun dialogHit(s: ScreenSnapshot?): Irreversible? {
        val message = s?.web?.dialog?.message?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val action = matchName(message, s) ?: message.split(Regex("[\\s，,。.！!？?：:；;]+")).firstNotNullOfOrNull { strong(it) } ?: return null
        return Irreversible(action, "dialog:${message.take(40)}")
    }

    /** Drop-down selection: Click the drop-down box to judge, and click the button to judge the text of the selected item. */
    private fun selectHit(args: JsonObject, snapshot: ScreenSnapshot?, fresh: ScreenSnapshot?): Irreversible? {
        tapHit(args, snapshot, fresh)?.let { return it }
        val option = str(args, "option") ?: return null
        return matchName(option, fresh ?: snapshot)?.let { Irreversible(it, "option:${option.trim()}") }
    }

    /** The starting point and end point of dragging are determined by one click each (the numbers share the snapshotId). */
    private fun dragHit(args: JsonObject, snapshot: ScreenSnapshot?, fresh: ScreenSnapshot?): Irreversible? {
        fun end(ref: String, x: String, y: String) = buildJsonObject {
            val r = str(args, ref)
            if (r != null) {
                put("ref", r)
                str(args, "snapshotId")?.let { put("snapshotId", it) }
            } else {
                args[x]?.let { put("x", it) }
                args[y]?.let { put("y", it) }
            }
        }
        return listOf(end("ref", "x", "y"), end("toRef", "toX", "toY")).firstNotNullOfOrNull { tapHit(it, snapshot, fresh) }
            ?.let { it.copy(matched = "drag:${it.matched}") }
    }

    /**
     * This screen is the payment page or payment panel: there are words such as confirmation of payment
     * and payment password, or there is an amount and it looks like a payment panel (the product page
     * and specifications panel are not included). Returns the basis for the hit; not null.
     */
    fun paymentPage(s: ScreenSnapshot): String? {
        ScreenDiagnosis.paymentText(s.elements.map { it.name })?.let { return it }
        val shown = s.elements.filter { !it.editable && !it.hint }
        val amount = shown.any { e -> TOTAL_LABELS.any { it in Hant.fold(e.name) } || AMOUNT.containsMatchIn(rowText(e, shown)) }
        return if (amount && paySheet(shown)) "payment sheet with an amount" else null
    }

    /** The click cannot be found on which element it is acting on (the screen reading is missing, the number does not belong to this screen reading, and there is no element at the coordinates); for running records to leave traces. */
    fun unresolved(target: GateTarget?, args: JsonObject, snapshot: ScreenSnapshot?, fresh: ScreenSnapshot? = null): Boolean =
        target == GateTarget.Tap && tapTargets(args, snapshot, fresh).isEmpty()

    /**
     * The button name matches the word list; [screen]. When given, use the general "Confirm/Confirm" to
     * see if there are strong words or amounts on the same screen. [hit] is the clicked element (when
     * known): the amount in the scrollable area is only counted when the clicked element is not in that
     * area.
     */
    fun matchName(name: String, screen: ScreenSnapshot? = null, hit: UiElement? = null): IrrevAction? {
        val raw = Hant.fold(name).trim()
        if (raw.isEmpty()) return null
        val n = raw.filterNot(Char::isWhitespace).lowercase()
        if (n in SUBMIT_WORDS) return IrrevAction.submit
        if (contacts(raw)) return IrrevAction.submit
        if (n in RED_PACKET) return IrrevAction.pay
        if (EXACT_PAY.any { w -> EXACT_PAY_PREFIXES.any { n == it + w } }) return IrrevAction.pay
        strong(raw)?.let { return it }
        for ((action, words) in PREFIX_WORDS) for (w in words) {
            if (n.startsWith(w) && n.length <= w.length + PREFIX_SLACK) return action
        }
        for ((action, words) in PUNCT_WORDS) for (w in words) {
            if (n.startsWith(w) && n.drop(w.length).none { it.isLetterOrDigit() }) return action
        }
        if (BOOKING_WORDS.any { w -> BOOKING_PREFIXES.any { n == it + w } }) return IrrevAction.order
        val lower = raw.lowercase()
        for ((w, action) in ENGLISH) {
            if (lower.startsWith(w) && lower.getOrNull(w.length)?.isLetter() != true) return action
        }
        if (screen != null && n.trimEnd { !it.isLetterOrDigit() } in GENERIC) return contextAction(screen, hit)
        return null
    }

    /** The name is a button for contacting others and submitting applications (see [CONTACT_WORDS]). */
    private fun contacts(raw: String): Boolean {
        val n = raw.filterNot(Char::isWhitespace).lowercase().trimEnd { !it.isLetterOrDigit() }
        if (n in APPLY_WORDS || CONTACT_WORDS.any { w -> CONTACT_PREFIXES.any { n == it + w } }) return true
        val e = raw.lowercase().trim().trimEnd { !it.isLetterOrDigit() }.replace(Regex("\\s+"), " ")
        return e in CONTACT_ENGLISH
    }

    /** Strong words appear in each paragraph of the short name as a whole or in the spelled name; remove words such as payment method first. */
    private fun strong(label: String): IrrevAction? {
        val folded = Hant.fold(label)
        for (p in listOf(folded) + folded.split(Regex("\\s+"))) {
            var t = p.filterNot(Char::isWhitespace).replace(PAYMENT_ADS, "")
            EXCLUDED.forEach { t = t.replace(it, "") }
            if (t.isEmpty() || t.length > STRONG_MAX_LEN) continue
            STRONG.entries.firstOrNull { (w, _) -> occurrences(t, w).any { actionAt(t, w, it) } }?.let { return it.value }
        }
        return null
    }

    private fun occurrences(t: String, w: String) = generateSequence(t.indexOf(w).takeIf { it >= 0 }) { i -> t.indexOf(w, i + 1).takeIf { it >= 0 } }

    /** This is an action, not a state: the front is not "waiting" or "already", and the back (skipping the punctuation) is not a status word. */
    private fun actionAt(t: String, w: String, i: Int): Boolean {
        if (i > 0 && t[i - 1] in STATUS_PREFIXES) return false
        val rest = t.substring(i + w.length).dropWhile { !it.isLetterOrDigit() }
        return STATUS_SUFFIXES.none { rest.startsWith(it) }
    }

    /**
     * The context of the same screen (pop-up window, bottom panel): if there is a payment word, it means
     * payment; otherwise, pressing the first strong word that is not an order (such as deleting the
     * confirmation box); if there is an amount and there are words such as payment method, cashier, and
     * verification on the same screen (or payment/payment in a paragraph of any length), it is also a
     * payment; otherwise, if there is a word or amount of money, press the order (the consent to place
     * the order must match the amount on the screen, see [ConfirmationGate]); if there is neither, it is
     * not. Amount: Total for one category; other amounts are not counted when they are in the scrollable
     * area and the clicked element is not in that area (price file, product list of filter panel).
     */
    private fun contextAction(screen: ScreenSnapshot, hit: UiElement?): IrrevAction? {
        val found = screen.elements.mapNotNull { strong(it.name) }
        if (IrrevAction.pay in found) return IrrevAction.pay
        found.firstOrNull { it != IrrevAction.order }?.let { return it }
        val shown = screen.elements.filter { !it.editable && !it.hint }
        val scrollables = screen.elements.filter { it.scrollable }
        val amount = shown.any { e ->
            // Sum up a category of words to make a total (the amount may be split into the next few
            // paragraphs, or the number may not be loaded yet); otherwise, the amount is spelled out as
            // a whole line.
            TOTAL_LABELS.any { it in Hant.fold(e.name) } ||
                (AMOUNT.containsMatchIn(rowText(e, shown)) && scrollables.none { s -> inside(e, s) && hit != null && !inside(hit, s) })
        }
        if (amount && paySheet(shown)) return IrrevAction.pay
        return if (IrrevAction.order in found || amount) IrrevAction.order else null
    }

    /**
     * The screen with the amount does not look like a payment panel: there are words such as payment
     * method, cashier, password verification, or payment/payment in a paragraph of any length (first
     * remove the "Support Huabei payment" and "Cash on delivery" introduction); the page has Buy Now, do
     * not click on these when adding to the shopping cart. Balance, password, fingerprint, and change
     * are only counted when the lower half of the screen has confirmation/payment buttons.
     */
    private fun paySheet(shown: List<UiElement>): Boolean {
        if (shown.any { e -> ORDER_CONTEXT.any { it in Hant.fold(e.name).filterNot(Char::isWhitespace) } }) return false
        val bottomButton = shown.any { b ->
            b.clickable && top(b) >= BOTTOM_REGION && Hant.fold(b.name).filterNot(Char::isWhitespace).lowercase().let { n ->
                n.trimEnd { !it.isLetterOrDigit() } in GENERIC || "支付" in n || "付款" in n
            }
        }
        return shown.any { e ->
            val t = Hant.fold(e.name).filterNot(Char::isWhitespace).replace(PAYMENT_ADS, "")
            PAY_SHEET_WORDS.any { it in t } || PAY_SHEET_ENGLISH.containsMatchIn(e.name) ||
                listOf("支付", "付款").any { w -> occurrences(t, w).any { actionAt(t, w, it) } } ||
                (bottomButton && top(e) >= BOTTOM_REGION && WEAK_PAY_SHEET_WORDS.any { it in t })
        }
    }

    /**
     * The paragraphs on the same line starting from [start] and to the right of it are put together in
     * horizontal order ("Actual payment | ¥ | 39.90"). Same row: The vertical centers of both are
     * between the upper and lower edges of each other, and the large container of the entire screen will
     * not put other rows in.
     */
    internal fun rowText(start: UiElement, elements: List<UiElement>): String {
        if (start.bounds.size != 4) return start.name
        fun cy(e: UiElement) = (e.bounds[1] + e.bounds[3]) / 2
        val row = elements.filter { e ->
            e !== start && e.bounds.size == 4 && e.bounds[0] >= start.bounds[0] &&
                cy(e) in start.bounds[1]..start.bounds[3] && cy(start) in e.bounds[1]..e.bounds[3]
        }.sortedBy { it.bounds[0] }
        return start.name + row.joinToString("") { it.name.filterNot(Char::isWhitespace) }
    }

    private fun top(e: UiElement) = if (e.bounds.size == 4) e.bounds[1] else 0

    private fun matchResource(id: String): IrrevAction? = resourceWords(id).firstNotNullOfOrNull { RESOURCE_WORDS[it] }

    /** Resource IDs are _ -. and camel-cased words. */
    private fun resourceWords(id: String): List<String> =
        id.substringAfterLast('/').replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase().split('_', '-', '.')

    /**
     * Changing the words in the input box itself will not submit anything: clicking on the input box
     * (just focusing, the words in the box are the typed content), clicking on the small icon without a
     * name in the box (clear ×, voice) or the clear key, or buttons next to the box, on the same line,
     * with the name "clear", are all editing. What is submitted is the subsequent carriage return, send,
     * and search.
     */
    internal fun editsField(e: UiElement, s: ScreenSnapshot): Boolean {
        if (e.editable) return true
        if (e.bounds.size != 4) return false
        val fields = s.elements.filter { it.editable && it.bounds.size == 4 && it !== e }
        val n = Hant.fold(e.name).filterNot(Char::isWhitespace).lowercase()
        val clears = n in FIELD_CLEAR_WORDS || e.resourceId?.let { r -> "clear" in resourceWords(r) } == true
        // The small icon without a name in the box (clear
        val bareIcon = n.isEmpty() && e.resourceId?.let(::matchResource) == null
        if ((clears || bareIcon) && fields.any { inside(e, it) }) return true
        if (!clears) return false
        val cy = (e.bounds[1] + e.bounds[3]) / 2
        return fields.any { f -> cy in f.bounds[1]..f.bounds[3] && e.bounds[0] >= f.bounds[0] - FIELD_NEAR && e.bounds[0] <= f.bounds[2] + FIELD_NEAR }
    }

    private fun elementHit(e: UiElement, s: ScreenSnapshot): Irreversible? {
        for (x in withChildren(e, s)) {
            if (editsField(x, s)) continue
            matchName(x.name, s, x)?.let { return Irreversible(it, x.name.trim()) }
            x.resourceId?.let { r -> matchResource(r)?.let { return Irreversible(it, r) } }
        }
        return null
    }

    private fun tapHit(args: JsonObject, snapshot: ScreenSnapshot?, fresh: ScreenSnapshot?): Irreversible? {
        val targets = tapTargets(args, snapshot, fresh)
        // Click the input box or its clear key: edit, do not look at the target written in the model
        // ("clear the search box")
        if (targets.isNotEmpty() && targets.all { (e, s) -> editsField(e, s) }) return null
        for ((e, s) in targets) elementHit(e, s)?.let { return it }
        // There is no element with a name on the coordinate point (self-drawn interface, icon without
        // name, this is often the case for points found when looking at pictures): judge based on text
        // on the same line
        if (str(args, "ref") == null && targets.all { (e, s) -> withChildren(e, s).all { it.name.isBlank() } }) {
            val s = fresh ?: snapshot
            val y = num(args, "y")
            if (s != null && y != null) rowHit(s, y)?.let { return it }
        }
        // When the element cannot be found or the icon does not have text, the target written by the
        // model itself also counts; the point found by looking at the picture with the original text
        // (text) and the word (seen) seen by the model are the same.
        return listOf("target", "text", "seen").firstNotNullOfOrNull { k ->
            str(args, k)?.let { t -> matchName(t, fresh ?: snapshot)?.let { Irreversible(it, t.trim()) } }
        }
    }

    /**
     * The clicked element (together with the screen it is on): ref → the element in the original reading
     * screen, plus the element at its center position at the moment; coordinates → the smallest
     * clickable element at that point at the moment.
     */
    private fun tapTargets(args: JsonObject, snapshot: ScreenSnapshot?, fresh: ScreenSnapshot?): List<Pair<UiElement, ScreenSnapshot>> {
        val ref = str(args, "ref")
        if (ref != null) {
            val s = snapshot?.takeIf { it.snapshotId == str(args, "snapshotId") } ?: return emptyList()
            val e = s.elements.firstOrNull { it.ref == ref } ?: return emptyList()
            val now = fresh?.let { f -> center(e)?.let { (x, y) -> at(f, x, y) }?.let { it to f } }
            return listOfNotNull(e to s, now)
        }
        val s = fresh ?: snapshot ?: return emptyList()
        val x = num(args, "x") ?: return emptyList()
        val y = num(args, "y") ?: return emptyList()
        return listOfNotNull(at(s, x, y)?.let { it to s })
    }

    /** Are there any irreversible words in the names of short elements (text in a line, buttons) that span [y] vertically? */
    private fun rowHit(s: ScreenSnapshot, y: Int): Irreversible? =
        rowElements(s, y).filterNot { editsField(it, s) }.firstNotNullOfOrNull { e -> matchName(e.name, s, e)?.let { Irreversible(it, e.name.trim()) } }

    private fun rowElements(s: ScreenSnapshot, y: Int) = s.elements
        .filter { it.name.isNotBlank() && !it.editable && it.bounds.size == 4 && y in it.bounds[1]..it.bounds[3] && height(it) <= ROW_MAX_HEIGHT }

    private fun at(s: ScreenSnapshot, x: Int, y: Int): UiElement? {
        val under = s.elements.filter { contains(it, x, y) }
        return under.filter { it.clickable }.minByOrNull(::area) ?: under.minByOrNull(::area)
    }

    /** A clickable container without a name will be judged together with the non-clickable text sub-elements that fall within it; non-clickable blank nodes (such as the root of the entire screen) will not be expanded. */
    private fun withChildren(e: UiElement, s: ScreenSnapshot): List<UiElement> {
        if (e.name.isNotBlank() || !e.clickable) return listOf(e)
        return listOf(e) + s.elements.filter { it !== e && !it.clickable && it.name.isNotBlank() && inside(it, e) }
    }

    /**
     * Enter, confirm, and space act on the focus: when the focus is on the input box, unless it is
     * clearly a search box, it will be counted as sending (spaces are just typing, not counting); when
     * the focus is on a button, it will be judged by words; when it is not clear where the focus is,
     * only if all the input boxes on the screen are search boxes, then the input box will be released,
     * otherwise it will be counted as a hit. When type_text is given to ref, the input box will be used.
     * The "Search" enter key type of the input method cannot be read and cannot be used as a basis.
     */
    private fun enterHit(args: JsonObject, snapshot: ScreenSnapshot?, s: ScreenSnapshot?, key: Int): Irreversible? {
        val keyName = if (key == KEY_SPACE) "空格" else "回车"
        val ref = str(args, "ref")
        val field = if (ref == null) null
        else snapshot?.takeIf { it.snapshotId == str(args, "snapshotId") }?.elements?.firstOrNull { it.ref == ref }
        val focused = field ?: s?.elements?.firstOrNull { it.focused }
        if (focused != null) {
            if (focused.editable) {
                if (key == KEY_SPACE || isSearch(focused, s ?: snapshot)) return null
                return Irreversible(IrrevAction.send, "$keyName:${label(focused)}")
            }
            return (s ?: snapshot)?.let { elementHit(focused, it) }
        }
        val fields = s?.elements?.filter { it.editable }.orEmpty()
        if (fields.isNotEmpty() && fields.all { isSearch(it, s) }) return null
        return Irreversible(IrrevAction.send, "$keyName:焦点不明")
    }

    /**
     * Recognize the search box according to the semantics of the element itself: the search box control
     * recognized by the port, the role has search (web searchbox, Android search box control class), the
     * prompt (the one that is still typed, or displayed at the moment) or the resource ID has the word
     * search, or there is a clickable "search" button in the same line. When there are words in the
     * input box, the name will be the typed word ("Search for me" is the message), and only the prompt
     * will show the name. Pressing Enter in the search box is to search, which can be withdrawn, not to
     * send.
     */
    private fun isSearch(e: UiElement, s: ScreenSnapshot?): Boolean {
        // There is a send button in the same row: it is a box for sending messages (the chat box for
        // "search or ask questions"), and the prompt says that searching will still be judged by
        // sending.
        if (s != null && ElementContext.row(e, s).any { b -> b.clickable && !b.editable && sendLike(b.name) }) return false
        if (e.searchField || "search" in e.role.lowercase()) return true
        val hint = Hant.fold(listOfNotNull(e.name.takeIf { e.hint }, e.placeholder).joinToString(" ")).lowercase()
        if (SEARCH_FIELD.any { it in hint } || e.resourceId?.let { "search" in resourceWords(it) } == true) return true
        if (s == null || e.bounds.size != 4) return false
        return s.elements.any { b ->
            b !== e && b.clickable && !b.editable && b.bounds.size == 4 &&
                Hant.fold(b.name).filterNot(Char::isWhitespace).lowercase() in SEARCH_BUTTONS &&
                (b.bounds[1] + b.bounds[3]) / 2 in e.bounds[1]..e.bounds[3]
        }
    }

    /** On the button are words related to sending (send, publish, publish, send). */
    private fun sendLike(name: String): Boolean {
        val n = Hant.fold(name).filterNot(Char::isWhitespace).lowercase()
        if (n.isEmpty()) return false
        return PREFIX_WORDS.getValue(IrrevAction.send).any { n.startsWith(it) && n.length <= it.length + PREFIX_SLACK } || n == "send" || n == "post"
    }

    /**
     * The horizontal swipe is long enough, and the starting point is on the list row (a wide clickable
     * element, itself non-scrollable): Many App lists delete a row in this way. There is no container
     * orientation information in the screen reading: only short scrollable areas (carousels) are
     * recognized; long swipes on horizontal containers with full screen height are still blocked (the
     * prompt prompts the model to use short swipes instead).
     */
    private fun swipeHit(args: JsonObject, s: ScreenSnapshot?): Irreversible? {
        val (sx, sy) = horizontal(args) ?: return null
        s ?: return null
        // The starting point is in a short scrollable area (horizontal carousel type), which means page
        // turning, not deletion.
        if (s.elements.any { it.scrollable && contains(it, sx, sy) && height(it) < CAROUSEL_MAX_HEIGHT }) return null
        val row = s.elements.filter { contains(it, sx, sy) && it.clickable && !it.scrollable && width(it) >= ROW_MIN_WIDTH }
            .minByOrNull(::area) ?: return null
        return Irreversible(IrrevAction.delete, "swipe on ${label(row)}")
    }

    /** The starting point of a long enough slide (0–999); null if it is not a slide or not long enough. scroll calculates the starting point of the finger according to the distributor's conversion. */
    private fun horizontal(args: JsonObject): Pair<Int, Int>? {
        if (args.containsKey("startX")) {
            val x1 = num(args, "startX") ?: return null; val y1 = num(args, "startY") ?: return null
            val x2 = num(args, "endX") ?: return null; val y2 = num(args, "endY") ?: return null
            val dx = abs(x2 - x1)
            return if (dx >= SWIPE_DELETE_MIN && dx > abs(y2 - y1)) x1 to y1 else null
        }
        val dir = str(args, "direction") ?: return null
        if (dir != "left" && dir != "right") return null
        val dist = min(650, (num(args, "amount") ?: 3) * 100)
        if (dist < SWIPE_DELETE_MIN) return null
        val cx = num(args, "x") ?: 500; val cy = num(args, "y") ?: 500
        val dx = if (dir == "left") dist else -dist
        return max(50, min(949, (cx - dx / 2.0).roundToInt())) to cy
    }

    private fun key(args: JsonObject): Int? = str(args, "key")?.let(PressKeys::resolve)

    private fun label(e: UiElement) = e.name.trim().ifEmpty { e.resourceId ?: e.ref }

    private fun center(e: UiElement): Pair<Int, Int>? =
        if (e.bounds.size != 4) null else (e.bounds[0] + e.bounds[2]) / 2 to (e.bounds[1] + e.bounds[3]) / 2

    private fun contains(e: UiElement, x: Int, y: Int) =
        e.bounds.size == 4 && x in e.bounds[0]..e.bounds[2] && y in e.bounds[1]..e.bounds[3]

    private fun inside(child: UiElement, parent: UiElement): Boolean {
        val c = child.bounds; val p = parent.bounds
        return c.size == 4 && p.size == 4 && c[0] >= p[0] && c[1] >= p[1] && c[2] <= p[2] && c[3] <= p[3]
    }

    private fun height(e: UiElement) = if (e.bounds.size != 4) 0 else e.bounds[3] - e.bounds[1]

    private fun width(e: UiElement) = if (e.bounds.size != 4) 0 else e.bounds[2] - e.bounds[0]

    private fun area(e: UiElement): Long =
        if (e.bounds.size != 4) Long.MAX_VALUE else (e.bounds[2] - e.bounds[0]).toLong() * (e.bounds[3] - e.bounds[1])

    private fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Like tap, it tolerates [a,b](take the midpoint). */
    private fun num(o: JsonObject, k: String): Int? {
        fun d(e: JsonElement?) = (e as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        val v = o[k]
        if (v is JsonArray && v.size == 2) {
            val a = d(v[0]); val b = d(v[1])
            if (a != null && b != null) return ((a + b) / 2).roundToInt()
        }
        return d(v)?.roundToInt()
    }

    private fun bool(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase() == "true"
}
