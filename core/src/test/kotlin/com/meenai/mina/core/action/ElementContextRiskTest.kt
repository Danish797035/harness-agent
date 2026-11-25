package com.meenai.mina.core.action

import com.meenai.mina.core.gate.IrreversibleDetector
import com.meenai.mina.core.gate.classify
import com.meenai.mina.core.risk.IrrevAction
import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.uistate.UiElement
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the element does is only judged according to its own context (name, same line), not the words on the entire screen. */
class ElementContextRiskTest {
    private fun screen(vararg e: UiElement) = ScreenSnapshot("s1", "com.any", e.toList(), 1080, 2400)

    @Test fun aSearchPlaceholderNextToASendButtonIsStillASendBox() {
        val field = UiElement("@e1", "EditText", "你好", 400, 0, listOf(0, 2200, 799, 2300), null, true, true, false, focused = true, placeholder = "搜索或提问")
        val send = UiElement("@e2", "Button", "发送", 900, 0, listOf(800, 2200, 999, 2300), null, true, false, false)
        val enter = Json.parseToJsonElement("""{"text":"你好","pressEnter":true}""").jsonObject
        assertEquals(IrrevAction.send, IrreversibleDetector.classify("phone_type_text", enter, screen(field, send))?.action)
        assertNull(IrreversibleDetector.classify("phone_type_text", enter, screen(field)))
    }
}
