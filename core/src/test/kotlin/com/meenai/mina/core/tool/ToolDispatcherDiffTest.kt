package com.meenai.mina.core.tool

import com.meenai.mina.core.observation.ObsKind
import com.meenai.mina.core.model.Author
import com.meenai.mina.core.agent.HistoryPruner
import com.meenai.mina.core.agent.visibleReads
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import com.meenai.mina.core.vision.JvmTestImageOps
import com.meenai.mina.core.vision.VisionService
import com.meenai.mina.core.vision.says
import com.meenai.mina.core.uistate.ActionResult
import com.meenai.mina.core.uistate.ScreenPort
import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.uistate.UiElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.meenai.mina.core.testing.TOOLS
import com.meenai.mina.core.assembly.toolDispatcher

/** Each time the screen is read, the next list of elements is changed (it remains the same after reading the last one); the position of the element with the same name remains unchanged. */
private class ListScreen(private vararg val screens: List<String>) : ScreenPort by FakeScreen() {
    val calls = mutableListOf<String>()
    var protectedPage = false
    var changing = false
    var shots = 0
    override suspend fun screenshotPng(): ByteArray { shots++; return byteArrayOf(9, 9) }
    var reads = 0
    var result: ActionResult = ActionResult.Ok(changed = true)
    private fun build(i: Int) = ScreenSnapshot("p${i + 1}", "com.x", screens[minOf(i, screens.size - 1)].mapIndexed { n, name ->
        UiElement("@e${n + 1}", "button", name, 500, 100 + 150 * (name.hashCode().mod(12)), listOf(0, 0, 10, 10), null, true, true, false)
    }, 1080, 2400, protected = protectedPage, changedDuringRead = changing)
    override suspend fun snapshot(): ScreenSnapshot = build(reads++)
    override suspend fun peek(): ScreenSnapshot = build(maxOf(0, reads - 1))
    override suspend fun tapRef(ref: String, snapshotId: String): ActionResult { calls += "tapRef $ref $snapshotId"; return result }
    override suspend fun back(): ActionResult { calls += "back"; return result }
    override suspend fun waitStable(timeoutMs: Long): Boolean { calls += "waitStable $timeoutMs"; return true }
}

class ToolDispatcherDiffTest {
    private fun call(name: String, args: String = "{}") = Part.ToolCall("c1", name, args)
    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
    private val ten = (1..10).map { "项$it" }

    @Test fun phoneObserveDoesNotCutOffElementsAfterTwoHundred() = runTest {
        val s = ListScreen((1..240).map { "项目$it" })
        val o = toolDispatcher(s).execute(call("phone_observe")).content
        assertEquals(240, o["elements"]!!.jsonArray.size)
        assertFalse(o["truncated"]!!.jsonPrimitive.boolean)
    }

    @Test fun tapResultCarriesScreenDiff() = runTest {
        val s = ListScreen(ten, ten.filter { it != "项3" } + "新的")
        val d = toolDispatcher(s, inlineScreen = true)
        val o = d.execute(call("phone_observe")).content
        assertEquals("p1", o.s("snapshotId"))
        val r = d.execute(call("phone_tap", """{"ref":"@e1","snapshotId":"p1"}""")).content
        assertEquals(listOf("tapRef @e1 p1", "waitStable 1500"), s.calls)
        val screen = r["screen"]!!.jsonObject
        assertFalse(screen["full"]!!.jsonPrimitive.boolean)
        assertEquals("p2", screen.s("snapshotId")); assertEquals("p1", screen.s("base"))
        assertEquals(listOf("新的"), screen["added"]!!.jsonArray.map { it.jsonObject.s("name") })
        val removed = screen["removed"]!!.jsonArray.single().jsonObject
        assertEquals("@e3", removed.s("ref")); assertEquals("项3", removed.s("name")); assertEquals("button", removed.s("role"))
        // The number of the next action comes from this screen in screen
        d.execute(call("phone_tap", """{"ref":"@e11","snapshotId":"p2"}"""))
        assertEquals("tapRef @e10 p2", s.calls[2])
    }

    @Test fun failedActionHasNoScreen() = runTest {
        val s = ListScreen(ten).apply { result = ActionResult.Failed("no_app_open") }
        val d = toolDispatcher(s, inlineScreen = true)
        d.execute(call("phone_observe"))
        val r = d.execute(call("phone_back")).content
        assertEquals("no_app_open", r.s("error")); assertNull(r["screen"])
        assertEquals(1, s.reads)
        // Screen reading tools without screen
        assertNull(d.execute(call("phone_list_elements")).content["screen"])
    }

    @Test fun sendsFullWhenBaseNotVisible() = runTest {
        val s = ListScreen(ten)
        val d = toolDispatcher(s, inlineScreen = true)
        d.execute(call("phone_observe"))
        val a = d.execute(call("phone_back")).content["screen"]!!.jsonObject
        assertFalse(a["full"]!!.jsonPrimitive.boolean)
        // There is no previous screen in the reading screen visible to the model (s2): Send the full
        // amount
        d.setVisibleObservations(setOf("p1"))
        val b = d.execute(call("phone_back")).content["screen"]!!.jsonObject
        assertTrue(b["full"]!!.jsonPrimitive.boolean)
        assertEquals(10, b["elements"]!!.jsonArray.size)
        // Visible on the previous screen (s3, full size): Another difference is found
        d.setVisibleObservations(setOf("p3"))
        val c = d.execute(call("phone_back")).content["screen"]!!.jsonObject
        assertFalse(c["full"]!!.jsonPrimitive.boolean)
        assertEquals("p3", c.s("base"))
    }

    @Test fun sendsFullWhenLastFullScreenNotVisible() = runTest {
        // The difference can only be understood by reading the last full screen: when it is cropped (or
        // folded), the full screen will be read even if the previous screen is still there.
        val s = ListScreen(ten)
        val d = toolDispatcher(s, inlineScreen = true)
        d.execute(call("phone_observe"))
        d.execute(call("phone_back"))
        d.setVisibleObservations(setOf("p2"))
        assertTrue(d.execute(call("phone_back")).content["screen"]!!.jsonObject["full"]!!.jsonPrimitive.boolean)
    }

    @Test fun flagOffHasNoScreen() = runTest {
        val s = ListScreen(ten)
        val d = toolDispatcher(s)
        d.execute(call("phone_observe"))
        assertNull(d.execute(call("phone_back")).content["screen"])
        // The port has already waited for the interface in the action (reported but not changed): no
        // longer waits for the effect or takes another action when judging the effect.
        assertEquals(listOf("back"), s.calls)
    }

    @Test fun inlineScreenIsRecallableAndRecorded() = runTest {
        val s = ListScreen(ten)
        val d = toolDispatcher(s, visionEnabled = true, recordFrames = true, inlineScreen = true)
        d.execute(call("phone_observe"))
        val r = d.execute(call("phone_back"))
        val id = r.content["screen"]!!.jsonObject.s("screenshotId")
        assertEquals("2", id)
        // The main model that can see the picture: after the action, the picture of this screen is sent
        // with the result, and it is the same picture as the review
        assertTrue(r.frame != null); assertTrue(r.image != null); assertEquals(listOf<Int?>(2), r.imageIds)
        assertEquals(10, r.screenElements!!.size)
        val back = d.execute(call("view_past_screens", """{"ids":[$id]}"""))
        assertEquals("true", back.content.s("ok")); assertEquals(1, back.images.size)
    }

    @Test fun inlineScreenFollowsProtectedAndPaymentRules() = runTest {
        val p = ListScreen(ten).apply { protectedPage = true }
        val d = toolDispatcher(p, visionEnabled = true, recordFrames = true, inlineScreen = true)
        val r = d.execute(call("phone_back"))
        assertNull(r.frame); assertEquals(0, p.shots)
        assertEquals("true", r.content["screen"]!!.jsonObject.s("protected"))
        // The payment page can be read and reviewed by the model.
        val pay = ListScreen(listOf("确认支付", "¥89.00"))
        val v = toolDispatcher(pay, visionEnabled = true, recordFrames = true, inlineScreen = true)
        val b = v.execute(call("phone_back"))
        assertTrue(b.frame != null)
        assertTrue(v.keepsRecallImages())
    }

    @Test fun noFrameWithoutRecordingOrVision() = runTest {
        val s = ListScreen(ten)
        val r = toolDispatcher(s, inlineScreen = true).execute(call("phone_back"))
        assertNull(r.frame); assertEquals(0, s.shots)
        assertTrue(r.content["screen"]!!.jsonObject.containsKey("screenshotId"))
    }

    @Test fun sendsFullUnlessWholeChainVisible() = runTest {
        // Real cutting: the differences d1 and d2 before the cutting line are cut, the nearest full
        // amount c1 before the line is retained, the difference chain is broken; the full amount must be
        // issued next time
        val s = ListScreen(ten)
        val d = toolDispatcher(s, inlineScreen = true)
        val h = mutableListOf(Message(Role.User, listOf(Part.Text("task")), Author.Mina))
        suspend fun step(id: String, name: String, args: String = "{}"): JsonObject {
            val c = Part.ToolCall(id, name, args)
            val r = d.execute(c).content
            h += Message(Role.Assistant, listOf(c), Author.Mina); h += Message(Role.Tool, listOf(Part.ToolResult(id, r.toString(), false)), Author.Mina)
            return r
        }
        step("c1", "phone_observe")
        (1..2).forEach { step("d$it", "phone_back") }
        // Round trip without screen pushes crop line past d2
        (1..4).forEach { step("w$it", "wait", """{"seconds":0.$it}""") }
        val visible = visibleReads(HistoryPruner.prune(h, TOOLS, HistoryPruner.Cut(2)))
        assertTrue("$visible", "p1" in visible && "p2" !in visible && "p3" !in visible)
        d.setVisibleObservations(visible)
        assertTrue(step("d3", "phone_back")["screen"]!!.jsonObject["full"]!!.jsonPrimitive.boolean)
        d.setVisibleObservations(visibleReads(HistoryPruner.prune(h, TOOLS, HistoryPruner.Cut(2))))
        assertFalse(step("d4", "phone_back")["screen"]!!.jsonObject["full"]!!.jsonPrimitive.boolean)
    }

    @Test fun dumpElementsIsDiffBase() = runTest {
        val s = ListScreen(ten)
        val d = toolDispatcher(s, inlineScreen = true)
        d.execute(call("phone_list_elements"))
        val screen = d.execute(call("phone_back")).content["screen"]!!.jsonObject
        assertFalse(screen["full"]!!.jsonPrimitive.boolean); assertEquals("p1", screen.s("base"))
    }

    @Test fun protectedScreenHidesElementsButPaymentScreenShowsThem() = runTest {
        val p = ListScreen(ten)
        val d = toolDispatcher(p, inlineScreen = true)
        d.execute(call("phone_observe"))
        p.protectedPage = true
        val a = d.execute(call("phone_back")).content["screen"]!!.jsonObject
        assertEquals("true", a.s("protected")); assertTrue("hand_to_user" in a.s("hint"))
        assertNull(a["elements"]); assertNull(a["added"])
        p.protectedPage = false
        // Then start again from the full amount
        assertTrue(d.execute(call("phone_back")).content["screen"]!!.jsonObject["full"]!!.jsonPrimitive.boolean)
        val pay = toolDispatcher(ListScreen(listOf("确认支付", "¥89.00")), inlineScreen = true)
        val b = pay.execute(call("phone_back")).content["screen"]!!.jsonObject
        assertEquals("true", b.s("full")); assertTrue(b["elements"]!!.jsonArray.isNotEmpty())
    }

    @Test fun changingScreenCarriesNote() = runTest {
        val s = ListScreen(ten).apply { changing = true }
        val screen = toolDispatcher(s, inlineScreen = true).execute(call("phone_back")).content["screen"]!!.jsonObject
        assertEquals("true", screen.s("screenChangedDuringRead")); assertTrue("observe again" in screen.s("note"))
    }

    @Test fun evidenceSkipsRepeatedReads() = runTest {
        val s = ListScreen(ten)
        val d = toolDispatcher(s, vision = VisionService(says(), JvmTestImageOps), inlineScreen = true)
        d.execute(call("phone_observe"))
        repeat(5) { d.execute(call("phone_back")) }
        val reads = d.evidence().read(ObsKind.Read, ObsKind.Reply)
        // Read the same screen five times and remember only one
        assertEquals(1, reads.count { "项1" in it })
    }

    @Test fun fullScreenAfterFiveDiffs() = runTest {
        val d = toolDispatcher(ListScreen(ten), inlineScreen = true)
        d.execute(call("phone_observe"))
        fun full(r: ToolExecution) = r.content["screen"]!!.jsonObject["full"]!!.jsonPrimitive.boolean
        repeat(5) { assertFalse(full(d.execute(call("phone_back")))) }
        assertTrue(full(d.execute(call("phone_back"))))
        assertFalse(full(d.execute(call("phone_back"))))
    }
}
