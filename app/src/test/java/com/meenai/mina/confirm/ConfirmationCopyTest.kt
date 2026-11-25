package com.meenai.mina.confirm

import org.junit.Rule
import com.meenai.mina.i18n.EnglishTexts
import com.meenai.mina.core.gate.ConfirmationRequest
import com.meenai.mina.core.risk.IrrevAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmationCopyTest {
    @get:Rule val texts = EnglishTexts()

    private fun req(content: String = "明天见", amount: String? = null, action: IrrevAction = IrrevAction.send, target: String = "张三") =
        ConfirmationRequest(action, target, content, amount, "com.chat")

    @Test fun longContentEllipsized() {
        val long = "字".repeat(80)
        val body = ConfirmationCopy.body(req(content = long), "聊天")
        assertTrue(body, body.endsWith("Content: " + "字".repeat(60) + "…"))
        // The fields written by the model are treated as plain text: newlines are pressed into spaces,
        // control characters and direction control characters are removed, and leading and trailing
        // blanks are removed.
        val odd = ConfirmationCopy.body(req(content = "  第一行\n第二行‮反向<b>粗</b>\u0007 ", target = " 张三\n"), "聊天")
        assertEquals("In 聊天 · To: 张三 · Content: 第一行 第二行反向<b>粗</b>", odd)
        assertFalse(odd.contains('\n'))
    }

    // Fix 1 M1: Delimiters, colons, line delimiters and zero-width characters in fields cannot create
    // fake fields
    @Test fun fieldsCannotFakeStructure() {
        val r = req(content = "明天见 · 金额:¥0\u2028第二段\u200B尾", target = "张三：李四")
        assertEquals("In 聊天 · To: 张三∶李四 · Content: 明天见 / 金额∶¥0 第二段尾", ConfirmationCopy.body(r, "聊天"))
    }

    // Fix 1 I3: Each declared button uses its own PendingIntent (with different data), and the button of
    // the old notification will not be replaced by the newly declared one.
    @Test fun intentDataUniquePerRequest() {
        assertTrue(ConfirmationNotifier.intentData(1, true) != ConfirmationNotifier.intentData(2, true))
        assertTrue(ConfirmationNotifier.intentData(1, true) != ConfirmationNotifier.intentData(1, false))
    }
}
