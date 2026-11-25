package com.meenai.mina.core.uistate

import com.meenai.mina.core.assembly.toolDispatcher
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.prompt.Feedback
import com.meenai.mina.core.tool.FakeScreen
import com.meenai.mina.core.vision.JvmTestImageOps
import com.meenai.mina.core.vision.VisionService
import com.meenai.mina.core.vision.says
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When there are not enough trees and the text on the screen cannot be read, the screen reading result will say why (ocr), and the prompt will point to the picture to find it. */
class OcrStatusTest {
    private class Screen(val ocr: suspend () -> List<VisibleTextLine>?, val changing: Boolean = false) : ScreenPort by FakeScreen() {
        // An unnamed node: App draws its own interface
        override suspend fun snapshot() = ScreenSnapshot("p1", "com.example", emptyList(), 1080, 2400, changedDuringRead = changing)
        override suspend fun peek() = snapshot()
        override suspend fun readVisibleText() = ocr()
        override suspend fun screenshotPng(): ByteArray = JvmTestImageOps.encode(108, 240) { _, _ -> 128 }
    }

    private fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content

    private suspend fun observe(screen: ScreenPort, vision: Boolean = false) =
        toolDispatcher(screen, vision = if (vision) VisionService(says(), JvmTestImageOps) else null)
            .executeRead(Part.ToolCall("o", "phone_observe", "{}")).content

    @Test fun emptyOcrIsReportedWithFindVisuallyAdvice() = runTest {
        val r = observe(Screen({ emptyList() }), vision = true)
        assertEquals("unusable", r.s("treeQuality"))
        assertEquals("empty", r.s("ocr"))
        assertEquals(Feedback.format("tree_poor.no_text.visual", "ocr" to "empty"), r.s("hint"))
        // Don’t mention the image viewing tools when you don’t have the image viewing model.
        val plain = observe(Screen({ emptyList() }))
        assertEquals(Feedback.format("tree_poor.no_text", "ocr" to "empty"), plain.s("hint"))
    }

    @Test fun eachReasonForNoTextIsNamed() = runTest {
        assertEquals("unavailable", observe(Screen({ null })).s("ocr"))
        assertEquals("failed", observe(Screen({ throw IllegalStateException("recognizer") })).s("ocr"))
        assertEquals("illegible", observe(Screen({ listOf(VisibleTextLine("・・・", listOf(0, 0, 100, 40))) })).s("ocr"))
        assertEquals("timeout", observe(Screen({ delay(60_000); emptyList() })).s("ocr"))
    }

    @Test fun textIsReadEvenWhenTheTreeChangedDuringTheRead() = runTest {
        val r = observe(Screen({ listOf(VisibleTextLine("明天见", listOf(200, 150, 500, 190))) }, changing = true))
        assertEquals(listOf("明天见"), r["visibleText"]!!.jsonArray.map { (it as JsonObject).s("text") })
        assertFalse("ocr" in r)
        assertTrue(r.s("hint")!!.startsWith(Feedback["tree_poor"]))
    }
}
