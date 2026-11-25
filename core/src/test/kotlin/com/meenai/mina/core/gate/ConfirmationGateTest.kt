package com.meenai.mina.core.gate

import com.meenai.mina.core.risk.IrrevAction
import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.uistate.UiElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.meenai.mina.core.testing.verb

/** When asking, you only need to agree to the step of spending money: the amount payable is ¥25 on the page below, and you should bring it with you when making your declaration. */
class ConfirmationGateTest {
    private fun req(action: IrrevAction, app: String, target: String = "张三", content: String = "明天见") =
        ConfirmationRequest(action, target, content, "¥25", app)
    private fun hit(action: IrrevAction) = Irreversible(action, "x")
    private fun el(name: String, editable: Boolean = false, top: Int = 0) =
        UiElement("@e1", "v", name, 0, 0, listOf(0, top, 999, top + 60), null, true, editable, false)
    /** Chat page with [peer]: The title is at the top, and the input box is [typed]. */
    private fun chat(app: String = "com.a", typed: String = "明天见", peer: String = "张三", vararg more: UiElement) =
        ScreenSnapshot("s", app, listOf(el(peer, top = 30), el("¥25", top = 800), el(typed, editable = true, top = 900), el("发送", top = 900)) + more, 1080, 2400)

    private fun approved(r: ConfirmationRequest, step: Int = 1) = ConfirmationGate().apply {
        assertEquals(DeclareResult.Pending, declare(r, r.app)); decide(true, step)
    }

    @Test fun approvalIsSingleUseAndScoped() {
        val g = approved(req(IrrevAction.send, "com.a"))
        assertEquals(Permit.Allowed, g.permits(hit(IrrevAction.send), chat(), 2))
        assertEquals(Permit.NoGrant, g.permits(hit(IrrevAction.send), chat(), 2))
        // Other actions, other Apps: This is not acceptable, and the agreement will be invalidated
        // accordingly.
        val kind = approved(req(IrrevAction.send, "com.a"))
        assertTrue(kind.permits(hit(IrrevAction.delete), chat(), 2) is Permit.Mismatch)
        assertEquals(Permit.NoGrant, kind.permits(hit(IrrevAction.send), chat(), 2))
        assertTrue(approved(req(IrrevAction.send, "com.a")).permits(hit(IrrevAction.send), chat(app = "com.b"), 2) is Permit.Mismatch)
        assertTrue(approved(req(IrrevAction.send, "com.a")).permits(hit(IrrevAction.send), null, 2) is Permit.Mismatch)
    }

    // Click the submit button to contact or apply for verification: the object (position, company) can
    // be anywhere on the screen, and there is no input box text to be verified.
    @Test fun contactButtonApprovalChecksTargetOnScreen() {
        val page = ScreenSnapshot("s", "www.jobs.example", listOf(el("产品经理 · 某公司", top = 400), el("¥25", top = 430), el("立即沟通", top = 460)), 1080, 2400)
        val r = req(IrrevAction.submit, "www.jobs.example", target = "产品经理", content = "点立即沟通,发出预设的招呼语")
        assertEquals(Permit.Allowed, approved(r).permits(hit(IrrevAction.submit), page, 2))
        assertTrue(approved(r.copy(target = "运营专员")).permits(hit(IrrevAction.submit), page, 2) is Permit.Mismatch)
    }

    @Test fun declineGrantsNothing() {
        val g = ConfirmationGate()
        g.declare(req(IrrevAction.send, "com.a"), "com.a")
        g.decide(false)
        assertEquals(Permit.NoGrant, g.permits(hit(IrrevAction.send), chat(), 1))
        g.decide(true)
        assertEquals(Permit.NoGrant, g.permits(hit(IrrevAction.send), chat(), 1))
    }

    @Test fun mismatchWhenForegroundDiffers() {
        val g = ConfirmationGate()
        assertEquals(DeclareResult.Mismatch, g.declare(req(IrrevAction.send, "com.a"), "com.b"))
        assertEquals(DeclareResult.Mismatch, g.declare(req(IrrevAction.send, "com.a"), null))
        g.decide(true)
        assertEquals(Permit.NoGrant, g.permits(hit(IrrevAction.send), chat(), 1))
    }

    @Test fun resetDropsUnusedApprovals() {
        val g = approved(req(IrrevAction.send, "com.a"))
        g.reset()
        assertEquals(Permit.NoGrant, g.permits(hit(IrrevAction.send), chat(), 2))
    }

    // C3: Agree to bind the declaration content
    @Test fun approvalMustMatchTargetAndContent() {
        assertTrue(approved(req(IrrevAction.send, "com.a", target = "李四")).permits(hit(IrrevAction.send), chat(), 2) is Permit.Mismatch)
        assertTrue(approved(req(IrrevAction.send, "com.a")).permits(hit(IrrevAction.send), chat(typed = "后天见"), 2) is Permit.Mismatch)
        // Scenario B: There is a message "See you tomorrow" in the chat, but there are other words in
        // the input box.
        val bubble = chat(typed = "后天见", more = arrayOf(el("明天见", top = 500)))
        assertTrue(approved(req(IrrevAction.send, "com.a")).permits(hit(IrrevAction.send), bubble, 2) is Permit.Mismatch)
        // Scenario A: Agree to "See you tomorrow" and type in "See you tomorrow + link"
        assertTrue(approved(req(IrrevAction.send, "com.a")).permits(hit(IrrevAction.send), chat(typed = "明天见 http://x.example"), 2) is Permit.Mismatch)
        assertTrue(approved(req(IrrevAction.send, "com.a")).permits(hit(IrrevAction.send), chat(typed = ""), 2, typing = "明天见 http://x.example") is Permit.Mismatch)
        // The declaration content is truncated (ending with...) than the prefix
        val long = "很长的一段话".repeat(40)
        val capped = long.take(199) + "…"
        assertEquals(Permit.Allowed, approved(req(IrrevAction.send, "com.a", content = capped)).permits(hit(IrrevAction.send), chat(typed = long), 2))
        // phone_type_text Enter: compares the words to be entered in this step
        val empty = chat(typed = "")
        assertEquals(Permit.Allowed, approved(req(IrrevAction.send, "com.a")).permits(hit(IrrevAction.send), empty, 2, typing = "明天见"))
        assertTrue(approved(req(IrrevAction.send, "com.a")).permits(hit(IrrevAction.send), empty, 2, typing = "别的话") is Permit.Mismatch)
        // Deletion does not affect content; objects can be anywhere on the screen that are not editable
        val del = ScreenSnapshot("s", "com.a", listOf(el("张三", top = 500), el("¥25", top = 550), el("删除", top = 600)), 1080, 2400)
        assertEquals(Permit.Allowed, approved(req(IrrevAction.delete, "com.a", content = "整个会话")).permits(hit(IrrevAction.delete), del, 2))
    }

    // Second round 2: The object must be in the title bar (send), not just in the input box, and must be
    // at least two words
    @Test fun targetBindingIsStrict() {
        // Typed "Zhang San said see you tomorrow" in Li Si's chat.
        val other = chat(peer = "李四", typed = "张三说明天见")
        assertTrue(approved(req(IrrevAction.send, "com.a", content = "张三说明天见")).permits(hit(IrrevAction.send), other, 2) is Permit.Mismatch)
        // It does not count if Zhang San only appears in the chat text (not at the top)
        val body = chat(peer = "李四", more = arrayOf(el("张三", top = 500)))
        assertTrue(approved(req(IrrevAction.send, "com.a")).permits(hit(IrrevAction.send), body, 2) is Permit.Mismatch)
        assertTrue(approved(req(IrrevAction.send, "com.a", target = "张")).permits(hit(IrrevAction.send), chat(peer = "张"), 2) is Permit.Mismatch)
    }

    // Second Round 3: You must bring the amount when placing an order, and the amount must be on the
    // screen
    @Test fun orderAmountMustBeOnScreen() {
        fun order(amount: String?) = ConfirmationRequest(IrrevAction.order, "耳机", "买一副耳机", amount, "com.a")
        val page = ScreenSnapshot("s", "com.a", listOf(el("耳机", top = 300), el("¥25.00", top = 400), el("立即购买", top = 900)), 1080, 2400)
        assertEquals(Permit.Allowed, approved(order("25元")).permits(hit(IrrevAction.order), page, 2))
        assertTrue(approved(order("¥39")).permits(hit(IrrevAction.order), page, 2) is Permit.Mismatch)
        assertTrue(approved(order(null)).permits(hit(IrrevAction.order), page, 2) is Permit.Mismatch)
    }

    @Test fun dollarAndTraditionalTotalsBindTheAmount() {
        fun order(amount: String) = ConfirmationRequest(IrrevAction.order, "耳機", "買一副耳機", amount, "com.a")
        val page = ScreenSnapshot("s", "com.a", listOf(el("耳機", top = 300), el("運費 NT$60", top = 350), el("總計 NT$1,290", top = 400), el("結帳", top = 900)), 1080, 2400)
        assertEquals(Permit.Allowed, approved(order("NT$1290")).permits(hit(IrrevAction.order), page, 2))
        assertTrue(approved(order("NT$60")).permits(hit(IrrevAction.order), page, 2) is Permit.Mismatch)
    }

    // C3: Only give the next step
    @Test fun approvalExpiresOnOtherWritesAndAfterThreeSteps() {
        for (t in listOf("phone_tap", "phone_swipe", "phone_scroll", "phone_back", "phone_launch_app", "phone_press_key")) {
            val g = approved(req(IrrevAction.send, "com.a"))
            g.noteWrite(verb(t))
            assertEquals(t, Permit.NoGrant, g.permits(hit(IrrevAction.send), chat(), 2))
        }
        val typing = approved(req(IrrevAction.send, "com.a"))
        typing.noteWrite(verb("phone_clear_text")); typing.noteWrite(verb("phone_type_text"))
        assertEquals(Permit.Allowed, typing.permits(hit(IrrevAction.send), chat(), 2))
        assertEquals(Permit.Allowed, approved(req(IrrevAction.send, "com.a"), step = 1).permits(hit(IrrevAction.send), chat(), 4))
        assertTrue(approved(req(IrrevAction.send, "com.a"), step = 1).permits(hit(IrrevAction.send), chat(), 5) is Permit.Mismatch)
    }

    // I1 / M2
    @Test fun paymentNeverApprovedAndDeclinedKindNotAskedAgain() {
        val g = ConfirmationGate()
        assertEquals(DeclareResult.Unsupported, g.declare(req(IrrevAction.pay, "com.a"), "com.a"))
        assertEquals(DeclareResult.Pending, g.declare(req(IrrevAction.send, "com.a"), "com.a"))
        g.decide(false)
        assertEquals(DeclareResult.Declined, g.declare(req(IrrevAction.send, "com.a", target = "李四"), "com.a"))
        assertEquals(DeclareResult.Pending, g.declare(req(IrrevAction.delete, "com.a"), "com.a"))
        g.reset()
        assertEquals(DeclareResult.Pending, g.declare(req(IrrevAction.send, "com.a"), "com.a"))
        // Even if there is other consent, the payment will not be released.
        g.decide(true)
        assertTrue(g.permits(hit(IrrevAction.pay), chat(), 1) is Permit.Mismatch)
    }

    // 0b-2: The amount is only compared with the number with currency; if there is a total, the total is
    // used, and if there is no total, the largest one outside the rolling area is used.
    @Test fun orderAmountComparedWithCurrencyAmountsAndTotal() {
        fun order(amount: String) = ConfirmationRequest(IrrevAction.order, "耳机", "买一副耳机", amount, "com.a")
        val page = ScreenSnapshot("s", "com.a", listOf(
            el("耳机", top = 100), el("已售25件", top = 200), el("满25减3", top = 300), el("¥2,599", top = 400), el("提交订单", top = 900),
        ), 1080, 2400)
        assertTrue(approved(order("25")).permits(hit(IrrevAction.order), page, 2) is Permit.Mismatch)
        assertTrue(approved(order("3元")).permits(hit(IrrevAction.order), page, 2) is Permit.Mismatch)
        assertEquals(Permit.Allowed, approved(order("2599元")).permits(hit(IrrevAction.order), page, 2))
        // Only the total is recognized when there is a total (the label and the amount are the same
        // element, or the one immediately following the label)
        val total = ScreenSnapshot("s", "com.a", listOf(
            el("耳机", top = 100), el("¥2599", top = 200), el("运费 ¥10", top = 300), el("合计:", top = 900), el("¥2609", top = 900), el("提交订单", top = 900),
        ), 1080, 2400)
        assertTrue(approved(order("2599")).permits(hit(IrrevAction.order), total, 2) is Permit.Mismatch)
        assertEquals(Permit.Allowed, approved(order("¥2609.00")).permits(hit(IrrevAction.order), total, 2))
        val inline = ScreenSnapshot("s", "com.a", listOf(el("耳机", top = 100), el("实付 ¥88", top = 900)), 1080, 2400)
        assertEquals(Permit.Allowed, approved(order("88元")).permits(hit(IrrevAction.order), inline, 2))
        // Prices in the scrolling area do not count
        val scrolled = ScreenSnapshot("s", "com.a", listOf(
            UiElement("@e9", "v", "", 0, 0, listOf(0, 0, 999, 800), null, false, false, true),
            el("耳机", top = 100), el("¥2599", top = 200), el("¥19", top = 850),
        ), 1080, 2400)
        assertTrue(approved(order("2599")).permits(hit(IrrevAction.order), scrolled, 2) is Permit.Mismatch)
        assertEquals(Permit.Allowed, approved(order("19")).permits(hit(IrrevAction.order), scrolled, 2))
    }

    private fun at(name: String, l: Int, t: Int, r: Int, b: Int, editable: Boolean = false, scrollable: Boolean = false, hint: Boolean = false) =
        UiElement("@e", "v", name, 0, 0, listOf(l, t, r, b), null, true, editable, scrollable, hint = hint)
    private fun order(amount: String) = ConfirmationRequest(IrrevAction.order, "耳机", "买一副耳机", amount, "com.a")
    private fun page(vararg e: UiElement) = ScreenSnapshot("s", "com.a", listOf(at("耳机", 0, 100, 999, 160)) + e, 1080, 2400)

    // Repair 1 I1(a): Divide the total into several segments
    @Test fun splitTotalIsJoined() {
        val p = page(at("合计:", 0, 900, 150, 960), at("¥", 160, 900, 190, 960), at("1299", 200, 900, 300, 960), at(".00", 300, 900, 350, 960),
            at("提交订单", 700, 900, 999, 960))
        assertEquals(Permit.Allowed, approved(order("1299元")).permits(Irreversible(IrrevAction.order, "提交订单"), p, 2))
        assertTrue(approved(order("1299.5")).permits(Irreversible(IrrevAction.order, "提交订单"), p, 2) is Permit.Mismatch)
        // There is total but the amount cannot be read: No release
        val unreadable = page(at("合计:", 0, 900, 150, 960), at("¥2599", 0, 300, 999, 360), at("提交订单", 700, 900, 999, 960))
        assertTrue(approved(order("2599")).permits(Irreversible(IrrevAction.order, "提交订单"), unreadable, 2) is Permit.Mismatch)
    }

    // Repair 1 I1(b): The "Total ¥1" in the remarks box and the total of each store in the list are not
    // counted; the amount of discounts is not counted
    @Test fun typedAndPerShopTotalsIgnored() {
        val p = page(
            at("", 0, 0, 999, 800, scrollable = true),
            at("店铺小计 合计 ¥30", 0, 200, 999, 260),
            at("合计¥1", 0, 400, 999, 460, editable = true),
            at("已优惠 ¥5", 0, 850, 400, 900),
            at("合计 ¥95", 0, 900, 600, 960), at("提交订单", 700, 900, 999, 960),
        )
        assertEquals(Permit.Allowed, approved(order("95")).permits(Irreversible(IrrevAction.order, "提交订单"), p, 2))
        for (a in listOf("1", "30", "5")) assertTrue(a, approved(order(a)).permits(Irreversible(IrrevAction.order, "提交订单"), p, 2) is Permit.Mismatch)
        // When there is no total: Discounts and shipping charges are not included in the maximum amount
        val noTotal = page(at("¥99", 0, 300, 999, 360), at("运费 ¥120", 0, 400, 999, 460), at("满200减150", 0, 500, 999, 560), at("立即购买", 0, 900, 999, 960))
        assertEquals(Permit.Allowed, approved(order("99")).permits(Irreversible(IrrevAction.order, "立即购买"), noTotal, 2))
    }

    // Fix 1 I1(c): The total is only in the scroll area, and there is a coupon outside: take the total
    // closest to the button
    @Test fun totalInsideScrollWithCouponOutside() {
        val p = page(
            at("", 0, 0, 999, 800, scrollable = true),
            at("合计 ¥10", 0, 100, 999, 160),
            at("合计 ¥88", 0, 700, 999, 760),
            at("优惠券 -¥10", 0, 820, 999, 880),
            at("提交订单", 700, 900, 999, 960),
        )
        assertEquals(Permit.Allowed, approved(order("88")).permits(Irreversible(IrrevAction.order, "提交订单"), p, 2))
        assertTrue(approved(order("10")).permits(Irreversible(IrrevAction.order, "提交订单"), p, 2) is Permit.Mismatch)
    }
}
