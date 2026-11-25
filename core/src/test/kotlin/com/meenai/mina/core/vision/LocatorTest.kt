package com.meenai.mina.core.vision

import com.meenai.mina.core.uistate.ScreenFamily
import com.meenai.mina.core.agent.FakeModel
import com.meenai.mina.core.model.ModelError
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.StopReason
import com.meenai.mina.core.uistate.ActionResult
import com.meenai.mina.core.uistate.ScreenPort
import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.uistate.UiElement
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Base64

internal val FRAME_A: ByteArray = JvmTestImageOps.encode(100, 200) { x, _ -> if (x < 50) 30 else 200 }
internal val FRAME_B: ByteArray = JvmTestImageOps.encode(100, 200) { _, y -> if (y < 100) 30 else 200 }
internal val BLACK: ByteArray = JvmTestImageOps.encode(100, 200) { _, _ -> 0 }

internal fun uiEl(ref: String, name: String, bounds: List<Int>, res: String? = null) =
    UiElement(ref, "button", name, 0, 0, bounds, res, true, false, false)

/** Press [shots] to take screenshots in order, and keep giving the last one after taking screenshots; [peek] returns to [elements]. */
internal class LocScreen(
    var elements: List<UiElement> = emptyList(),
    vararg shots: ByteArray?,
    var secure: Boolean = false,
    var hasWindow: Boolean = true,
) : ScreenPort {
    var peekFails = false
    var width = 1080
    var height = 2400
    private val queue = ArrayDeque(shots.toList().ifEmpty { listOf(FRAME_A) })
    var peeks = 0
    val taps = mutableListOf<String>()
    fun show(vararg next: ByteArray?) { queue.clear(); queue.addAll(next.toList()) }
    private fun read(id: String) = ScreenSnapshot(id, if (hasWindow) "com.android.settings" else null, elements, width, height, hasWindow = hasWindow)
    override suspend fun snapshot() = read("s")
    override suspend fun peek(): ScreenSnapshot {
        if (peekFails) throw IllegalStateException("unreadable")
        peeks++; return read("p$peeks")
    }
    override suspend fun screenshotPng(): ByteArray? = if (queue.size > 1) queue.removeFirst() else queue.firstOrNull()
    override suspend fun isProtected() = secure
    override suspend fun tapRef(ref: String, snapshotId: String): ActionResult { taps += "ref $ref"; return ActionResult.Ok(changed = true) }
    override suspend fun tapXY(x: Int, y: Int): ActionResult { taps += "$x,$y"; return ActionResult.Ok(changed = true) }
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = ActionResult.Ok()
    override suspend fun clearText(ref: String?, snapshotId: String?) = ActionResult.Ok()
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = ActionResult.Ok()
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = ActionResult.Ok()
    override suspend fun back(): ActionResult { taps += "phone_back"; return ActionResult.Ok(changed = true) }
    override suspend fun pressKey(keyCode: Int) = ActionResult.Ok()
    override suspend fun openApp(packageName: String, cold: Boolean) = ActionResult.Ok()
    override suspend fun waitStable(timeoutMs: Long) = true
    override suspend fun listApps() = listOf("com.android.settings" to "设置")
}

internal fun says(vararg replies: Any) = FakeModel(*replies.map { if (it is String) ModelReply(listOf(Part.Text(it)), StopReason.EndTurn, null) else it }.toTypedArray())

class LocatorTest {
    private val wlan = uiEl("@e1", "WLAN", listOf(0, 100, 999, 160))
    private fun locator(screen: ScreenPort, model: FakeModel, registry: LocateRegistry = LocateRegistry()) =
        Locator(screen, VisionService(model, JvmTestImageOps), registry, ScreenFamily("phone", "p"))

    private fun found(r: LocateResult) = r as? LocateResult.Found ?: throw AssertionError("expected Found, got $r")
    private fun notFound(r: LocateResult) = r as? LocateResult.NotFound ?: throw AssertionError("expected NotFound, got $r")

    @Test fun treePathUniqueExactMatch() = runTest {
        // Nested and co-centered elements with the same name count as one
        val inner = uiEl("@e2", "wlan", listOf(10, 110, 989, 150))
        val m = says()
        val reg = LocateRegistry()
        val r = found(locator(LocScreen(listOf(wlan, inner, uiEl("@e3", "WLAN 设置说明", listOf(0, 300, 999, 360)))), m, reg).locate("WLAN 那一行", " W LAN "))
        assertEquals("elements", r.source)
        assertEquals(500 to 130, r.x to r.y)
        assertArrayEquals(intArrayOf(0, 100, 999, 160), r.box)
        assertEquals("@v1", r.ref); assertTrue(r.snapshotId.startsWith("loc_"))
        assertTrue("没有走视觉", m.requests.isEmpty())
        val (p, hash) = reg.get(r.ref, r.snapshotId)!!
        assertEquals(Point(500, 130), p); assertEquals(JvmTestImageOps.aHash(FRAME_A), hash)
        // Resource ID also recognized
        val byId = found(locator(LocScreen(listOf(uiEl("@e9", "", listOf(0, 0, 100, 100), res = "com.x:id/Wifi_Switch"))), says()).locate("开关", "com.x:id/wifi_switch"))
        assertEquals(50 to 50, byId.x to byId.y)
    }

    @Test fun treePathAmbiguousFallsToVision() = runTest {
        val twice = listOf(wlan, uiEl("@e2", "WLAN", listOf(0, 500, 999, 560)))
        val m = says("""{"bbox_2d":[0,100,1000,161],"seen":"WLAN"}""", "是")
        val r = found(locator(LocScreen(twice), m).locate("顶上那个 WLAN", "WLAN"))
        assertEquals("vision", r.source)
        assertEquals("WLAN", r.seen)
        assertEquals(2, m.requests.size)
        // First time question: screenshot 2 + description and original text
        val text = (m.requests[0].single().parts[0] as Part.Text).text
        assertTrue(text, "顶上那个 WLAN" in text && "\"WLAN\"" in text)
    }

    @Test fun treePathSkippedWhenScreenMoved() = runTest {
        val s = LocScreen(listOf(wlan), FRAME_A, FRAME_B)
        val m = says("""{"bbox_2d":[0,100,1000,161],"seen":"WLAN"}""", "yes")
        val r = found(locator(s, m).locate("WLAN", "WLAN"))
        assertEquals("vision", r.source)
    }

    @Test fun rejectsMalformedBoxes() = runTest {
        for (reply in listOf(
            """{"bbox_2d":[0.1,0.2,0.3,0.4],"seen":"x"}""",
            """{"bbox_2d":[0,0,100,100]} {"bbox_2d":[200,200,300,300]}""",
            """{"bbox_2d":[300,0,100,100]}""",
            """{"bbox_2d":[0,0,1001,100]}""",
            "我在屏幕上没有看到它",
            "null",
            "```json\nnull\n```",
            // There is only one bbox_2d key, but two sets of four numbers are given
            """{"bbox_2d":[0,0,100,100],"other":[200,200,300,300]}""",
        )) {
            val m = says(reply, "是")
            val r = notFound(locator(LocScreen(), m).locate("某个图标", null))
            assertEquals(reply, "not_found", r.reason)
            assertEquals(reply, 1, m.requests.size)
        }
    }

    @Test fun scalesTo999() = runTest {
        val r = found(locator(LocScreen(), says("```json\n{\"bbox_2d\": [500, 0, 1000, 1000]}\n```", "Yes.")).locate("右半边", null))
        assertArrayEquals(intArrayOf(500, 0, 999, 999), r.box)
        assertEquals(750 to 500, r.x to r.y)
        assertEquals(null, r.seen)
        // The name has not been checked: reminder to read seen first
        assertNotNull(r.note)
    }

    @Test fun nameMismatchOnSeen() = runTest {
        val r = notFound(locator(LocScreen(), says("""{"bbox_2d":[0,100,1000,161],"seen":"蓝牙"}""")).locate("WLAN", "WLAN"))
        assertEquals("name_mismatch", r.reason)
        assertTrue(r.message, "蓝牙" in r.message)
        // Inclusion of each other is not considered inappropriate
        found(locator(LocScreen(), says("""{"bbox_2d":[0,100,1000,161],"seen":"WLAN 已连接"}""", "是")).locate("WLAN", "WLAN"))
    }

    @Test fun nameMismatchOnElementAtCenter() = runTest {
        val bt = uiEl("@e1", "蓝牙", listOf(0, 100, 999, 160))
        val r = notFound(locator(LocScreen(listOf(bt)), says("""{"bbox_2d":[0,100,1000,161],"seen":"WLAN"}""")).locate("WLAN", "WLAN"))
        assertEquals("name_mismatch", r.reason)
        assertTrue(r.message, "蓝牙" in r.message)
        // Containers with an area larger than half the screen are not considered "elements at that
        // point"
        val root = uiEl("@e1", "设置", listOf(0, 0, 999, 999))
        found(locator(LocScreen(listOf(root)), says("""{"bbox_2d":[0,100,1000,161],"seen":"WLAN"}""", "是")).locate("WLAN", "WLAN"))
    }

    @Test fun notConfirmedUnlessYes() = runTest {
        val box = """{"bbox_2d":[0,100,1000,161],"seen":"WLAN"}"""
        for (answer in listOf("否", "不是", "可能是", "No", "yesterday")) {
            val r = notFound(locator(LocScreen(), says(box, answer)).locate("WLAN", null))
            assertEquals(answer, "not_confirmed", r.reason)
        }
        for (answer in listOf("是", "是的", "Yes", " yes, it is")) {
            found(locator(LocScreen(), says(box, answer)).locate("WLAN", null))
        }
        // When I re-viewed the picture with a red frame, I asked about the target.
        val m = says(box, "是")
        found(locator(LocScreen(), m).locate("WLAN 开关", null))
        val confirm = m.requests[1].single()
        assertTrue(m.system!!, "\"WLAN 开关\"" in m.system!!)
        val framed = Base64.getDecoder().decode((confirm.parts.last() as Part.Image).pngBase64)
        assertTrue(JvmTestImageOps.decode(framed).px.any { (it.toInt() and 0xFF) == 255 })
    }

    @Test fun protectedContent() = runTest {
        for (s in listOf(LocScreen(secure = true), LocScreen(emptyList(), BLACK, secure = true))) {
            val m = says()
            val r = notFound(locator(s, m).locate("WLAN", "WLAN"))
            assertEquals("protected_content", r.reason)
            assertTrue("受保护的画面不交给视觉模型", m.requests.isEmpty())
            try {
                locator(s, m).describe("看看")
                fail("expected protected")
            } catch (e: ScreenUnavailable) {
                assertEquals("protected_content", e.reason)
            }
            assertTrue(m.requests.isEmpty())
        }
        // The app has not been opened yet (the virtual screen has not been built): I cannot take a
        // screenshot. Please explain clearly. It is not an error.
        val none = LocScreen(emptyList(), null, hasWindow = false)
        assertEquals("no_app_open", notFound(locator(none, says()).locate("WLAN", null)).reason)
        try {
            locator(none, says()).describe("看看"); fail("expected no_app_open")
        } catch (e: ScreenUnavailable) {
            assertEquals("no_app_open", e.reason)
        }
    }

    @Test fun paymentPageCanBeSentToVision() = runTest {
        val pages = listOf(
            listOf(uiEl("@e1", "确认支付", listOf(0, 800, 999, 900))),
            listOf(uiEl("@e1", "实付 ¥39.90", listOf(0, 600, 600, 660)), uiEl("@e2", "支付方式", listOf(0, 700, 999, 760))),
        )
        for (els in pages) {
            assertNull(locator(LocScreen(els), says()).visionBlocker())
        }
        // The price on the product page does not count on the payment page
        found(locator(LocScreen(listOf(uiEl("@e1", "¥39.90", listOf(0, 600, 600, 660)), uiEl("@e2", "立即购买", listOf(0, 900, 999, 999)))),
            says("""{"bbox_2d":[0,900,1000,1000]}""", "是")).locate("购买按钮", null))
        // If the screen cannot be read, there is no way to confirm or transmit.
        val m = says()
        val r = notFound(locator(LocScreen().apply { peekFails = true }, m).locate("图标", null))
        assertEquals("vision_unavailable", r.reason)
        assertTrue(m.requests.isEmpty())
    }

    @Test fun treeMatchNeedsOnScreenElement() = runTest {
        // The elements outside the screen are pushed to the edge and the area is 0: it does not count as
        // a hit and changes to the visual effect.
        val m = says("""{"bbox_2d":[0,100,1000,161],"seen":"WLAN"}""", "是")
        val r = found(locator(LocScreen(listOf(uiEl("@e1", "WLAN", listOf(0, 999, 999, 999)))), m).locate("WLAN", "WLAN"))
        assertEquals("vision", r.source)
        // Elements that happen to be half the screen are not considered "elements at that point"
        val half = uiEl("@e1", "蓝牙", listOf(0, 0, 998, 500))
        found(locator(LocScreen(listOf(half)), says("""{"bbox_2d":[0,100,1000,161],"seen":"WLAN"}""", "是")).locate("WLAN", "WLAN"))
    }

    @Test fun visionErrorIsVisionFailed() = runTest {
        assertEquals("vision_unavailable", notFound(locator(LocScreen(), says(ModelError.Network())).locate("WLAN", null)).reason)
        val box = """{"bbox_2d":[0,100,1000,161],"seen":"WLAN"}"""
        assertEquals("vision_unavailable", notFound(locator(LocScreen(), says(box, ModelError.Server(500))).locate("WLAN", null)).reason)
    }

    @Test fun describeSendsTaskAndScreenshot() = runTest {
        val m = says("[屏幕] 设置首页")
        assertEquals("[屏幕] 设置首页", locator(LocScreen(), m).describe("打开 WLAN"))
        val msg = m.requests.single().single()
        assertEquals("What I want to do now: 打开 WLAN", (msg.parts[0] as Part.Text).text)
        assertTrue(msg.parts[1] is Part.Image)
        assertTrue(m.system!!, "[Interactive elements]" in m.system!!)
    }

    /** The positioning accuracy of the image viewing model has not been verified. */
    private fun unverified(vararg replies: String) = FakeModel(
        *replies.map { ModelReply(listOf(Part.Text(it)), StopReason.EndTurn, null) }.toTypedArray(), groundingVerified = false)

    /** A useful screen: the eight buttons with names are not in the box you are looking for. */
    private fun adequate() = LocScreen((1..8).map { uiEl("@e$it", "设置项$it", listOf(0, 300 + it * 70, 999, 360 + it * 70)) })

    @Test fun unverifiedVisionBoxNeedsSecondConsistentLook() = runTest {
        val box = """{"bbox_2d":[0,100,1000,161],"seen":"WLAN"}"""
        // Frame it again and see the same thing. Check it twice and confirm it: hand it over.
        val m = unverified(box, "是", """{"bbox_2d":[10,105,990,158],"seen":"WLAN"}""", "是")
        val r = found(locator(adequate(), m).locate("WLAN 那一行", null))
        assertFalse(r.unverified)
        assertEquals("vision", r.source); assertEquals(4, m.requests.size)
        // Click the box for the first time
        assertEquals(500 to 131, r.x to r.y)
        // The words I saw were different the second time, the frame was somewhere else, I couldn't
        // confirm it the second time, and I didn't give seen: the first time. I didn't hand it over, and
        // I was prompted to use phone_describe.
        for (second in listOf(
            listOf("""{"bbox_2d":[0,100,1000,161],"seen":"蓝牙"}""", "是"),
            listOf("""{"bbox_2d":[0,500,1000,561],"seen":"WLAN"}""", "是"),
            listOf(box, "否"),
            listOf("null"),
        )) {
            val bad = notFound(locator(adequate(), unverified(box, "是", *second.toTypedArray())).locate("WLAN 那一行", null))
            assertEquals("$second", "unverified_grounding", bad.reason)
            assertTrue(bad.message, "phone_describe" in bad.message)
        }
        val noSeen = notFound(locator(adequate(), unverified("""{"bbox_2d":[0,100,1000,161]}""", "是", box, "是")).locate("WLAN 那一行", null))
        assertEquals("unverified_grounding", noSeen.reason)
    }

    @Test fun unusableTreeLetsAnUnbackedVisionBoxThroughMarkedUnverified() = runTest {
        // There is nothing in the tree (the app draws its own interface): there is nothing to compare
        // with. Just add a box once and re-read it before handing it over, marking it as no supporting
        // evidence.
        val m = unverified("""{"bbox_2d":[0,880,1000,940],"seen":"输入框"}""", "是")
        val r = found(locator(LocScreen(), m).locate("底部的输入框", null))
        assertTrue(r.unverified)
        assertEquals(2, m.requests.size)
        assertTrue(r.note!!, "no_effect" in r.note!!)
        // If you don’t see confirmation again, you won’t hand it over as usual.
        assertEquals("not_confirmed", notFound(locator(LocScreen(), unverified("""{"bbox_2d":[0,880,1000,940]}""", "否")).locate("输入框", null)).reason)
    }

    @Test fun partialTreeNeedsBackingOnlyWhenTheTargetIsInIt() = runTest {
        // Most of the controls have no names: a piece is missing
        val blank = (1..4).map { UiElement("@b$it", "button", "", 0, 0, listOf(0, 500 + it * 80, 200, 560 + it * 80), null, true, false, false) }
        val partial = blank + uiEl("@e9", "发送消息", listOf(800, 900, 999, 960))
        val box = """{"bbox_2d":[0,100,1000,161],"seen":"发送"}"""
        // The word you are looking for is in the tree (just the frame does not fall on it): still have
        // to look again
        assertEquals("unverified_grounding", notFound(locator(LocScreen(partial), unverified(box, "是", "null")).locate("发送按钮", "发送")).reason)
        // What you are looking for is not in the tree: there is nothing to compare it with
        val r = found(locator(LocScreen(partial), unverified("""{"bbox_2d":[0,100,1000,161],"seen":"表情"}""", "是")).locate("表情按钮", "表情"))
        assertTrue(r.unverified)
    }

    @Test fun unverifiedVisionBoxBackedByTreeLabel() = runTest {
        // The name of the element in the center of the box is the word you are looking for: one review
        // is enough (there are two with the same name in the control tree, so exact matching is not
        // required)
        val dup = uiEl("@e2", "WLAN", listOf(0, 600, 999, 660))
        val m = unverified("""{"bbox_2d":[0,100,1000,161],"seen":"WLAN"}""", "是")
        val r = found(locator(LocScreen(listOf(wlan, dup)), m).locate("WLAN", "WLAN"))
        assertEquals("vision", r.source); assertEquals(2, m.requests.size)
        // Whether the control tree matches exactly or not depends on the model.
        val tree = unverified()
        assertEquals("elements", found(locator(LocScreen(listOf(wlan)), tree).locate("WLAN", "WLAN")).source)
        assertEquals(0, tree.requests.size)
    }
}
