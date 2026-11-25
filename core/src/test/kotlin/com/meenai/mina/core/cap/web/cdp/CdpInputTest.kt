package com.meenai.mina.core.cap.web.cdp

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.meenai.mina.core.uistate.CdpKey
import com.meenai.mina.core.uistate.CdpKeys
import com.meenai.mina.core.uistate.KeyCombo
import com.meenai.mina.core.uistate.KeyModifier

class CdpInputTest {
    private fun test(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = runBlocking { withTimeout(30_000) { block() } }

    /** The result is returned according to the method name; if it is not written, an empty object is returned. */
    private fun server(handler: (FakeDevTools.Conn, JsonObject) -> JsonElement? = { _, _ -> null }) = FakeDevTools().apply {
        onMessage = { c, m -> c.reply(m, handler(c, m) ?: JsonObject(emptyMap())) }
    }

    private suspend fun session(s: FakeDevTools) = CdpSession.connect(s, "webview_devtools_remote_1", "/devtools/page/A")

    private fun JsonObject.method() = getValue("method").jsonPrimitive.content
    private fun JsonObject.p(k: String) = getValue("params").jsonObject[k]?.jsonPrimitive?.contentOrNull
    private fun FakeDevTools.sent() = connections.single().received.toList()
    private suspend fun <T> CdpSession.ok(block: suspend InputTrack.() -> T): T {
        val r = input(3_000, block)
        if (r !is InputOutcome.Done) throw AssertionError("expected Done, got $r")
        return r.value
    }

    // ---------- Key table ----------

    @Test fun everyWebPressKeyHasACdpKey() {
        // The keys released by press_key on the web page (tool layer WEB_KEYS) must be able to pass
        // through DevTools, and the key codes must match.
        val expected = mapOf(
            66 to Triple("Enter", "Enter", 13), 160 to Triple("Enter", "NumpadEnter", 13), 67 to Triple("Backspace", "Backspace", 8),
            112 to Triple("Delete", "Delete", 46), 61 to Triple("Tab", "Tab", 9), 19 to Triple("ArrowUp", "ArrowUp", 38),
            20 to Triple("ArrowDown", "ArrowDown", 40), 21 to Triple("ArrowLeft", "ArrowLeft", 37), 22 to Triple("ArrowRight", "ArrowRight", 39),
            92 to Triple("PageUp", "PageUp", 33), 93 to Triple("PageDown", "PageDown", 34),
        )
        for ((android, t) in expected) {
            val k = CdpKeys.forAndroid(android)
            assertNotNull("$android", k)
            assertEquals("$android", t, Triple(k!!.key, k.code, k.keyCode))
            assertEquals(android, k.androidKeyCode)
        }
        assertEquals("\r", CdpKeys.forAndroid(66)!!.text)
        // System keys are not given to web pages
        assertNull(CdpKeys.forAndroid(4)); assertNull(CdpKeys.forAndroid(3)); assertNull(CdpKeys.forAndroid(24))
    }

    @Test fun lettersAndDigits() {
        val a = CdpKeys.named("a")!!
        assertEquals(CdpKey("a", "KeyA", 65, "a", 29), a)
        assertEquals(CdpKey("z", "KeyZ", 90, "z", 54), CdpKeys.named("Z"))
        assertEquals(CdpKey("7", "Digit7", 55, "7", 14), CdpKeys.named("7"))
        assertEquals("Space", CdpKeys.named("space")!!.code)
        assertNull(CdpKeys.named("F13")); assertNull(CdpKeys.named("é")); assertNull(CdpKeys.named(""))
    }

    @Test fun parseCombos() {
        val c = CdpKeys.parseCombo("Control+A")!!
        assertEquals(listOf(KeyModifier.Control), c.modifiers); assertEquals("KeyA", c.key.code)
        assertEquals(2, c.bits); assertNull(c.text)
        assertEquals(0x1000 or 0x2000, c.androidMeta)
        val st = CdpKeys.parseCombo(" shift + tab ")!!
        assertEquals(listOf(KeyModifier.Shift), st.modifiers); assertEquals("Tab", st.key.key); assertEquals(8, st.bits)
        assertEquals("A", CdpKeys.parseCombo("Shift+a")!!.text)
        assertEquals(KeyModifier.Meta, CdpKeys.parseCombo("cmd+Enter")!!.modifiers.single())
        assertEquals(1 or 2 or 8, CdpKeys.parseCombo("Ctrl+Alt+Shift+Delete")!!.bits)
        assertEquals("Escape", CdpKeys.parseCombo("Esc")!!.key.key)
        for (bad in listOf("", "+", "Control+", "Control+Control+A", "Hyper+A", "Control+Shift", "A+B", "Control++")) {
            assertNull(bad, CdpKeys.parseCombo(bad))
        }
    }

    // ---------- Coordinates ----------

    @Test fun viewPixelsToCssPixels() {
        // Mobile version: view 1080 width, viewport 411 CSS pixels (dpr about 2.63)
        val phone = CdpGeometry.scale(1080, 411.0)
        assertEquals(205.5, CdpGeometry.toCss(540, phone), 0.01)
        // Desktop version: The simulated viewport is 1280 pixels wide, which is wider than the view. One
        // CSS pixel is less than one view pixel.
        val desktop = CdpGeometry.scale(1080, 1280.0)
        assertEquals(0.84375, desktop, 1e-9)
        assertEquals(640.0, CdpGeometry.toCss(540, desktop), 1e-9)
        assertEquals(1280.0, CdpGeometry.toCss(1080, desktop), 1e-9)
        // Inverse of view pixels = CSS × scale
        assertEquals(333, Math.round(CdpGeometry.toCss(333, desktop) * desktop).toInt())
        // No conversion is performed when the viewport width cannot be read.
        assertEquals(1.0, CdpGeometry.scale(1080, 0.0), 0.0)
        assertEquals(1.0, CdpGeometry.scale(0, 1280.0), 0.0)
    }

    // ---------- Mouse, keyboard ----------

    @Test fun clickIsMoveThenPressThenRelease() = test {
        val srv = server(); val s = session(srv)
        s.ok { mouseClick(10.5, 20.0) }
        val sent = srv.sent()
        assertEquals(listOf("mouseMoved", "mousePressed", "mouseReleased"), sent.map { it.p("type") })
        assertTrue(sent.all { it.method() == "Input.dispatchMouseEvent" && it.p("x") == "10.5" && it.p("y") == "20.0" && it.p("pointerType") == "mouse" })
        assertEquals(listOf("none", "left", "left"), sent.map { it.p("button") })
        assertEquals(listOf("0", "1", "1"), sent.map { it.p("clickCount") })
        assertEquals(listOf("0", "1", "0"), sent.map { it.p("buttons") })
        s.close()
    }

    /** It does not respond to the commands in [stuck] (according to the Input event type or method name), and the rest returns empty objects. */
    private fun hanging(vararg stuck: String) = FakeDevTools().apply {
        onMessage = { c, m -> if (m.p("type") !in stuck && m.method() !in stuck) c.reply(m, JsonObject(emptyMap())) }
    }

    private fun refusing(vararg refused: String) = FakeDevTools().apply {
        onMessage = { c, m -> if (m.p("type") in refused || m.method() in refused) c.replyError(m, -32000, "refused") else c.reply(m, JsonObject(emptyMap())) }
    }

    @Test fun firstEventRefusedMeansNothingWasSent() = test {
        val srv = refusing("mouseMoved"); val s = session(srv)
        val r = s.input(3_000) { mouseClick(1.0, 1.0) }
        assertTrue("$r", r is InputOutcome.NotSent)
        // No further pressing, no need to lift
        assertEquals(listOf("mouseMoved"), srv.sent().map { it.p("type") })
        s.close()
    }

    @Test fun readOnlyQueriesFailingMeansNothingWasSent() = test {
        val srv = hanging("Page.getLayoutMetrics"); val s = session(srv)
        val r = s.input(200) { viewportCssWidth(); mouseClick(1.0, 1.0) }
        assertTrue("$r", r is InputOutcome.NotSent)
        assertTrue(srv.sent().none { it.method() == "Input.dispatchMouseEvent" })
        s.close()
    }

    @Test fun closedSessionMeansNothingWasSent() = test {
        val srv = server(); val s = session(srv)
        s.close()
        val r = s.input(200) { insertText("x") }
        assertTrue("$r", r is InputOutcome.NotSent)
    }

    @Test fun timeoutOnFirstEventIsUncertain() = test {
        // The timed out post may have reached the page: it cannot be treated as not sent.
        val srv = hanging("mouseMoved"); val s = session(srv)
        val r = s.input(200) { mouseClick(1.0, 1.0) }
        assertTrue("$r", r is InputOutcome.Uncertain)
        assertTrue((r as InputOutcome.Uncertain).error is CdpTimeoutException)
        assertTrue(srv.sent().none { it.p("type") == "mousePressed" })
        s.close()
    }

    @Test fun clickStuckAfterPressReleasesTheButton() = test {
        val srv = hanging("mousePressed"); val s = session(srv)
        val r = s.input(200) { mouseClick(3.0, 4.0) }
        assertTrue("$r", r is InputOutcome.Uncertain)
        val sent = srv.sent().map { it.p("type") }
        assertEquals(listOf("mouseMoved", "mousePressed", "mouseReleased"), sent)
        assertEquals("0", srv.sent().last().p("buttons")); assertEquals("3.0", srv.sent().last().p("x"))
        s.close()
    }

    @Test fun laterEventRefusedIsUncertain() = test {
        val srv = refusing("mouseReleased"); val s = session(srv)
        assertTrue(s.input(3_000) { mouseClick(1.0, 1.0) } is InputOutcome.Uncertain)
        // After being rejected, try your best to lift again
        assertEquals(listOf("mouseMoved", "mousePressed", "mouseReleased", "mouseReleased"), srv.sent().map { it.p("type") })
        s.close()
    }

    @Test fun comboStuckReleasesKeysInReverse() = test {
        val srv = FakeDevTools().apply {
            // There is no response when raising the main button after pressing it
            onMessage = { c, m -> if (!(m.p("type") == "keyUp" && m.p("key") == "a" && m.p("modifiers") == "2")) c.reply(m, JsonObject(emptyMap())) }
        }
        val s = session(srv)
        val r = s.input(200) { pressCombo(CdpKeys.parseCombo("Control+A")!!) }
        assertTrue("$r", r is InputOutcome.Uncertain)
        val sent = srv.sent().map { "${it.p("type")}:${it.p("key")}" }
        assertEquals(listOf("rawKeyDown:Control", "rawKeyDown:a", "keyUp:a", "keyUp:a", "keyUp:Control"), sent)
        s.close()
    }

    @Test fun insertTextTimeoutIsUncertain() = test {
        val srv = hanging("Input.insertText"); val s = session(srv)
        assertTrue(s.input(200) { insertText("hello") } is InputOutcome.Uncertain)
        assertEquals(1, srv.sent().size)
        s.close()
    }

    @Test fun clearStuckOnSecondComboIsUncertain() = test {
        val srv = FakeDevTools().apply { onMessage = { c, m -> if (m.p("key") != "Backspace") c.reply(m, JsonObject(emptyMap())) } }
        val s = session(srv)
        val r = s.input(200) {
            pressCombo(CdpKeys.parseCombo("Control+A")!!)
            pressCombo(CdpKeys.parseCombo("Backspace")!!)
        }
        assertTrue("$r", r is InputOutcome.Uncertain)
        assertEquals("keyUp", srv.sent().last().p("type")); assertEquals("Backspace", srv.sent().last().p("key"))
        s.close()
    }

    @Test fun dragStuckMidwayReleasesTheButton() = test {
        var moves = 0
        val srv = FakeDevTools().apply {
            onMessage = { c, m -> if (!(m.p("type") == "mouseMoved" && m.p("buttons") == "1" && ++moves == 2)) c.reply(m, JsonObject(emptyMap())) }
        }
        val s = session(srv)
        val r = s.input(200) { mouseDrag(0.0, 0.0, 100.0, 0.0, 4, stepDelayMs = 0) }
        assertTrue("$r", r is InputOutcome.Uncertain)
        val sent = srv.sent()
        val release = sent.last { it.p("type") == "mouseReleased" }
        assertEquals("0.0", release.p("x"))
        assertEquals("false", sent.last { it.method() == "Input.setInterceptDrags" }.p("enabled"))
        s.close()
    }

    @Test fun nativeDragStuckCancelsTheDrag() = test {
        val srv = FakeDevTools().apply {
            onMessage = { c, m ->
                if (m.p("type") == "mouseMoved" && m.p("buttons") == "1") c.event("Input.dragIntercepted", buildJsonObject { put("data", buildJsonObject { put("dragOperationsMask", 1) }) })
                if (m.p("type") != "drop") c.reply(m, JsonObject(emptyMap()))
            }
        }
        val s = session(srv)
        val r = s.input(200) { mouseDrag(0.0, 0.0, 10.0, 10.0, 3, stepDelayMs = 20) }
        assertTrue("$r", r is InputOutcome.Uncertain)
        assertTrue(srv.sent().any { it.method() == "Input.cancelDragging" })
        s.close()
    }

    @Test fun viewportWidthComesFromLayoutMetrics() = test {
        val srv = server { _, m ->
            if (m.method() == "Page.getLayoutMetrics") buildJsonObject {
                put("cssLayoutViewport", buildJsonObject { put("clientWidth", 1280); put("clientHeight", 800) })
                put("cssVisualViewport", buildJsonObject { put("clientWidth", 1279.5); put("clientHeight", 799) })
            } else null
        }
        val s = session(srv)
        assertEquals(1279.5, s.ok { viewportCssWidth() }, 0.0)
        // Without page script
        assertTrue(srv.sent().none { it.method() == "Runtime.evaluate" })
        s.close()
    }

    @Test fun controlAPressesModifierAroundRawKey() = test {
        val srv = server(); val s = session(srv)
        s.ok { pressCombo(CdpKeys.parseCombo("Control+A")!!) }
        val sent = srv.sent()
        assertTrue(sent.all { it.method() == "Input.dispatchKeyEvent" })
        assertEquals(listOf("rawKeyDown:Control:2", "rawKeyDown:a:2", "keyUp:a:2", "keyUp:Control:null"),
            sent.map { "${it.p("type")}:${it.p("key")}:${it.p("modifiers")}" })
        assertEquals("65", sent[1].p("windowsVirtualKeyCode")); assertEquals("KeyA", sent[1].p("code"))
        // Shortcut keys do not produce words
        assertNull(sent[1].p("text"))
        s.close()
    }

    @Test fun enterAndShiftLetterCarryText() = test {
        val srv = server(); val s = session(srv)
        s.ok { pressCombo(KeyCombo(emptyList(), CdpKeys.forAndroid(66)!!)) }
        s.ok { pressCombo(CdpKeys.parseCombo("Shift+b")!!) }
        val sent = srv.sent()
        assertEquals("keyDown", sent[0].p("type")); assertEquals("\r", sent[0].p("text")); assertEquals("13", sent[0].p("windowsVirtualKeyCode"))
        assertEquals("keyUp", sent[1].p("type")); assertNull(sent[1].p("text"))
        val down = sent.first { it.p("code") == "KeyB" }
        assertEquals("keyDown", down.p("type")); assertEquals("B", down.p("text")); assertEquals("B", down.p("key")); assertEquals("8", down.p("modifiers"))
        s.close()
    }

    @Test fun arrowsAreRawKeys() = test {
        val srv = server(); val s = session(srv)
        s.ok { pressCombo(KeyCombo(emptyList(), CdpKeys.forAndroid(20)!!)) }
        assertEquals(listOf("rawKeyDown", "keyUp"), srv.sent().map { it.p("type") })
        s.close()
    }

    @Test fun insertText() = test {
        val srv = server(); val s = session(srv)
        s.ok { insertText("hello 你好") }
        val m = srv.sent().single()
        assertEquals("Input.insertText", m.method()); assertEquals("hello 你好", m.p("text"))
        s.close()
    }

    @Test fun hoverOnlyMoves() = test {
        val srv = server(); val s = session(srv)
        s.ok { mouseMove(5.0, 6.0) }
        val m = srv.sent().single()
        assertEquals("mouseMoved", m.p("type")); assertEquals("none", m.p("button"))
        s.close()
    }

    @Test fun pointerDragWhenPageDoesNotStartNativeDrag() = test {
        val srv = server(); val s = session(srv)
        val native = s.ok { mouseDrag(0.0, 0.0, 100.0, 50.0, 4, stepDelayMs = 0) }
        assertFalse(native)
        val sent = srv.sent()
        assertEquals("Input.setInterceptDrags", sent.first().method()); assertEquals("true", sent.first().p("enabled"))
        assertEquals("Input.setInterceptDrags", sent.last().method()); assertEquals("false", sent.last().p("enabled"))
        val mouse = sent.filter { it.method() == "Input.dispatchMouseEvent" }
        assertEquals(listOf("mouseMoved", "mousePressed", "mouseMoved", "mouseMoved", "mouseMoved", "mouseMoved", "mouseReleased"), mouse.map { it.p("type") })
        // Hold down the left button while moving
        assertTrue(mouse.drop(2).dropLast(1).all { it.p("buttons") == "1" && it.p("button") == "left" })
        assertEquals("50.0", mouse[3].p("x")); assertEquals("25.0", mouse[3].p("y"))
        assertEquals("100.0", mouse.last().p("x")); assertEquals("50.0", mouse.last().p("y"))
        s.close()
    }

    @Test fun nativeDragAndDropIsReplayedAtTarget() = test {
        val data = buildJsonObject { put("items", buildJsonArray { add(buildJsonObject { put("mimeType", "text/plain"); put("data", "x") }) }); put("dragOperationsMask", 1) }
        val srv = server { c, m ->
            // The browser hands over dragging after pressing and holding Move for the first time.
            if (m.method() == "Input.dispatchMouseEvent" && m.p("type") == "mouseMoved" && m.p("buttons") == "1") {
                c.event("Input.dragIntercepted", buildJsonObject { put("data", data) })
            }
            null
        }
        val s = session(srv)
        val native = s.ok { mouseDrag(0.0, 0.0, 100.0, 50.0, 5, stepDelayMs = 20) }
        assertTrue(native)
        val sent = srv.sent()
        val drags = sent.filter { it.method() == "Input.dispatchDragEvent" }
        assertEquals(listOf("dragEnter", "dragOver", "drop"), drags.map { it.p("type") })
        assertTrue(drags.all { it.p("x") == "100.0" && it.p("y") == "50.0" })
        assertEquals(data, drags.last().getValue("params").jsonObject["data"])
        // Native drag and drop ends with drop and is no longer lifted.
        assertTrue(sent.none { it.p("type") == "mouseReleased" })
        assertEquals("false", sent.last().p("enabled"))
        s.close()
    }

    @Test fun dragWorksWhereInterceptIsUnsupported() = test {
        val srv = FakeDevTools().apply {
            onMessage = { c, m -> if (m.method() == "Input.setInterceptDrags") c.replyError(m, -32601, "not found") else c.reply(m, JsonObject(emptyMap())) }
        }
        val s = session(srv)
        assertFalse(s.ok { mouseDrag(0.0, 0.0, 10.0, 10.0, 2, stepDelayMs = 0) })
        val sent = srv.sent()
        assertEquals(1, sent.count { it.method() == "Input.setInterceptDrags" })
        assertEquals("mouseReleased", sent.last().p("type"))
        s.close()
    }

    // ---------- Drop-down box ----------

    @Test fun optionMatching() {
        val o = listOf(SelectOption("请选择", "", false), SelectOption("北京", "bj", false), SelectOption("Shanghai", "sh", false), SelectOption("bj", "x", false))
        assertEquals(1, SelectMatch.indexOf(o, "bj"))
        assertEquals(1, SelectMatch.indexOf(o, " 北京 "))
        assertEquals(2, SelectMatch.indexOf(o, "shanghai"))
        assertEquals(2, SelectMatch.indexOf(o, "SH"))
        assertNull(SelectMatch.indexOf(o, "广州"))
    }

    /** A select: direction key on a fake page changes selectedIndex (skip disabled items), and when [keysWork] is false, the page blocks the key. */
    private class FakeSelect(val options: List<SelectOption>, var index: Int, val keysWork: Boolean = true, val isSelect: Boolean = true) {
        val log = mutableListOf<String>()
        fun handle(m: JsonObject): JsonElement? {
            val p = m.getValue("params").jsonObject
            return when (m.getValue("method").jsonPrimitive.content) {
                "DOM.getNodeForLocation" -> buildJsonObject { put("backendNodeId", 42); put("frameId", "F") }
                "DOM.resolveNode" -> buildJsonObject { put("object", buildJsonObject { put("type", "object"); put("objectId", "node-1") }) }
                "Runtime.callFunctionOn" -> {
                    val fn = p.getValue("functionDeclaration").jsonPrimitive.content
                    val arg = p["arguments"]?.jsonArray?.firstOrNull()?.jsonObject?.get("value")?.jsonPrimitive?.int
                    when {
                        "tagName" in fn -> buildJsonObject {
                            put("result", if (isSelect) buildJsonObject { put("type", "object"); put("objectId", "sel-1") }
                            else buildJsonObject { put("type", "object"); put("subtype", "null"); put("value", kotlinx.serialization.json.JsonNull) })
                        }
                        "options:" in fn -> buildJsonObject {
                            put("result", buildJsonObject {
                                put("type", "object")
                                put("value", buildJsonObject {
                                    put("disabled", false); put("multiple", false); put("index", index)
                                    put("options", buildJsonArray { options.forEach { o -> add(buildJsonObject { put("text", o.text); put("value", o.value); put("disabled", o.disabled) }) } })
                                })
                            })
                        }
                        "dispatchEvent" in fn -> { log += "script $arg"; index = arg!!; buildJsonObject { put("result", buildJsonObject { put("type", "number"); put("value", index) }) } }
                        else -> buildJsonObject { put("result", buildJsonObject { put("type", "number"); put("value", index) }) }
                    }
                }
                "Input.dispatchKeyEvent" -> {
                    val type = p.getValue("type").jsonPrimitive.content
                    val key = p.getValue("key").jsonPrimitive.content
                    if (type == "rawKeyDown") {
                        log += key
                        if (keysWork) {
                            val step = if (key == "ArrowDown") 1 else -1
                            var i = index + step
                            while (i in options.indices && options[i].disabled) i += step
                            if (i in options.indices) index = i
                        }
                    }
                    null
                }
                "DOM.focus" -> { log += "focus"; null }
                else -> null
            }
        }
    }

    private val cities = listOf(SelectOption("请选择", "", false), SelectOption("北京", "bj", false), SelectOption("天津", "tj", true), SelectOption("上海", "sh", false))

    @Test fun selectMovesWithArrowKeysAndReadsBack() = test {
        val sel = FakeSelect(cities, 0)
        val srv = server { _, m -> sel.handle(m) }
        val s = session(srv)
        val r = s.ok { selectOptionAt(100.0, 200.0, "上海") }
        assertEquals(SelectOutcome.Selected(3, "上海", changed = true, byScript = false), r)
        // Press twice after focusing (the second time skips the disabled Tianjin), no script
        assertEquals(listOf("focus", "ArrowDown", "ArrowDown"), sel.log)
        val loc = srv.sent().first { it.method() == "DOM.getNodeForLocation" }
        assertEquals("100", loc.p("x")); assertEquals("200", loc.p("y"))
        assertEquals("Runtime.releaseObject", srv.sent().last().method())
        s.close()
    }

    @Test fun selectUpwardsAndAlreadySelected() = test {
        val sel = FakeSelect(cities, 3)
        val s = session(server { _, m -> sel.handle(m) })
        assertEquals(SelectOutcome.Selected(1, "北京", changed = true, byScript = false), s.ok { selectOptionAt(1.0, 1.0, "bj") })
        // Go up once and skip the banned Tianjin
        assertEquals(listOf("focus", "ArrowUp"), sel.log)
        sel.log.clear()
        assertEquals(SelectOutcome.Selected(1, "北京", changed = false, byScript = false), s.ok { selectOptionAt(1.0, 1.0, "北京") })
        assertTrue(sel.log.isEmpty())
        s.close()
    }

    @Test fun selectFallsBackToScriptWhenKeysAreSwallowed() = test {
        val sel = FakeSelect(cities, 0, keysWork = false)
        val s = session(server { _, m -> sel.handle(m) })
        assertEquals(SelectOutcome.Selected(3, "上海", changed = true, byScript = true), s.ok { selectOptionAt(1.0, 1.0, "上海") })
        // If it doesn’t move after pressing it once, press it again.
        assertEquals(listOf("focus", "ArrowDown", "script 3"), sel.log)
        s.close()
    }

    @Test fun selectErrors() = test {
        val sel = FakeSelect(cities, 0)
        val s = session(server { _, m -> sel.handle(m) })
        assertEquals(SelectOutcome.NoSuchOption(listOf("请选择", "北京", "天津", "上海")), s.ok { selectOptionAt(1.0, 1.0, "广州") })
        assertEquals(SelectOutcome.OptionDisabled("天津"), s.ok { selectOptionAt(1.0, 1.0, "天津") })
        assertTrue(sel.log.isEmpty())
        s.close()
        val notSel = FakeSelect(cities, 0, isSelect = false)
        val s2 = session(server { _, m -> notSel.handle(m) })
        assertEquals(SelectOutcome.NotASelect, s2.ok { selectOptionAt(1.0, 1.0, "北京") })
        s2.close()
    }
}
