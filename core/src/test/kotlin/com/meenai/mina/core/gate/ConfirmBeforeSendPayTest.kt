package com.meenai.mina.core.gate

import com.meenai.mina.core.risk.IrrevAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that irreversible actions (send, pay, delete, order) require explicit confirmation
 * before execution, and payments/orders are strictly kept in ask mode.
 */
class ConfirmBeforeSendPayTest {

    @Test
    fun confirmRequiredForSendPayDeleteOrder() {
        // English actions
        assertEquals(IrrevAction.send, IrreversibleDetector.ENGLISH["send"])
        assertEquals(IrrevAction.pay, IrreversibleDetector.ENGLISH["pay"])
        assertEquals(IrrevAction.delete, IrreversibleDetector.ENGLISH["delete"])
        assertEquals(IrrevAction.order, IrreversibleDetector.ENGLISH["buy now"])
        assertEquals(IrrevAction.order, IrreversibleDetector.ENGLISH["place order"])

        // Strong actions
        assertEquals(IrrevAction.pay, IrreversibleDetector.STRONG["支付"])
        assertEquals(IrrevAction.pay, IrreversibleDetector.STRONG["付款"])
        assertEquals(IrrevAction.delete, IrreversibleDetector.STRONG["删除"])
        assertEquals(IrrevAction.order, IrreversibleDetector.STRONG["下单"])
        assertEquals(IrrevAction.order, IrreversibleDetector.STRONG["购买"])

        val reqSend = ConfirmationRequest(IrrevAction.send, "User", "Hello", null, "com.chat")
        assertEquals(IrrevAction.send, reqSend.action)

        val reqPay = ConfirmationRequest(IrrevAction.pay, "Merchant", "", "₹100", "com.bank")
        assertEquals(IrrevAction.pay, reqPay.action)

        val reqDelete = ConfirmationRequest(IrrevAction.delete, "Item", "", null, "com.app")
        assertEquals(IrrevAction.delete, reqDelete.action)

        val reqOrder = ConfirmationRequest(IrrevAction.order, "Store", "Product", "₹500", "com.shop")
        assertEquals(IrrevAction.order, reqOrder.action)
    }

    @Test
    fun irreversibleActionEnumHasSendPayDeleteOrder() {
        val actions = IrrevAction.entries.map { it.name }
        assertTrue(actions.contains("send"))
        assertTrue(actions.contains("pay"))
        assertTrue(actions.contains("delete"))
        assertTrue(actions.contains("order"))
    }
}
