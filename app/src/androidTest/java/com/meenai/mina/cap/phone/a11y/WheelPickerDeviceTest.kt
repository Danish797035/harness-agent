package com.meenai.mina.cap.phone.a11y

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.meenai.mina.cap.phone.a11y.DeviceSupport.TEST_PACKAGE
import com.meenai.mina.core.cap.phone.WheelMath
import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.uistate.UiElement
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real machine test: Open [WheelPickerTestActivity] on the Agent virtual screen, turn the original
 * digital wheel from 0 to 40, and complete the call in one call. The "locked" one can only be scrolled
 * without any obstacles (one space down, and 20 spaces back from 0 to the nearest); both are marked as
 * scroll wheels when reading the screen.
 */
@RunWith(AndroidJUnit4::class)
class WheelPickerDeviceTest {
    private val agent = DeviceSupport.newAgent()
    private val screen get() = agent.screen

    @After
    fun tearDown() = agent.release()

    private fun open(): ScreenSnapshot {
        DeviceSupport.sh("am start --display ${agent.display.displayId} -n $TEST_PACKAGE/${WheelPickerTestActivity::class.java.name}")
        val s = DeviceSupport.pollUntil(8_000) {
            runBlocking { screen.snapshot() }.takeIf { s -> s.elements.count { it.role == "NumberPicker" } == 2 }
        }
        return s ?: error("wheel page never showed: ${runBlocking { screen.snapshot() }.elements.map { "${it.role}:${it.name}" }}")
    }

    /** The original digital scroll wheel does not have a name in the screen reading: identify it by position, the left side of the page is "minutes" and the right side is "locked" (see [WheelPickerTestActivity]). */
    private fun ScreenSnapshot.picker(label: String): UiElement {
        val pickers = elements.filter { it.role == "NumberPicker" }.sortedBy { it.centerX }
        check(pickers.size == 2) { "expected 2 NumberPickers, got ${pickers.map { it.centerX }}" }
        return if (label == WheelPickerTestActivity.MINUTES) pickers[0] else pickers[1]
    }

    private fun flatten(n: com.meenai.mina.core.uistate.UiTreeNode): List<com.meenai.mina.core.uistate.UiTreeNode> =
        listOf(n) + n.children.flatMap(::flatten)

    /** Let the tool adjust to the same value again: the scroll wheel has really stopped there, without moving at all, and it is the starting point. */
    private suspend fun assertStaysAt(label: String, value: String) {
        val s = screen.snapshot()
        val again = screen.setWheel(s.picker(label).ref, s.snapshotId, value)
        assertTrue("$again", again.reached && again.attempts == 0 && WheelMath.matches(again.from, value))
    }

    @Test
    fun wheelsAreMarkedInTheTree() = runBlocking<Unit> {
        val s = open()
        val wheels = s.windows.flatMap { it.nodes }.flatMap(::flatten).filter { it.wheel }
        assertEquals(2, wheels.size)
    }

    @Test
    fun lockedWheelReaches40From0ByStepping() = runBlocking<Unit> {
        val s = open()
        val o = screen.setWheel(s.picker(WheelPickerTestActivity.LOCKED).ref, s.snapshotId, "40")
        assertTrue("$o", o.reached)
        assertEquals("actions", o.method)
        assertEquals("00", o.from)
        assertTrue("$o", WheelMath.matches(o.value, "40"))
        // 0 → 40 Go back 20 squares to the nearest; once for each square, reading back is not counted.
        assertTrue("$o", o.attempts in 20..24)
        assertStaysAt(WheelPickerTestActivity.LOCKED, "40")
    }

    @Test
    fun typableWheelReaches40() = runBlocking<Unit> {
        val s = open()
        val o = screen.setWheel(s.picker(WheelPickerTestActivity.MINUTES).ref, s.snapshotId, "40")
        assertTrue("$o", o.reached)
        assertTrue("$o", o.method in setOf("text", "actions"))
        assertStaysAt(WheelPickerTestActivity.MINUTES, "40")
        // The other roller has not been touched
        assertStaysAt(WheelPickerTestActivity.LOCKED, "0")
    }

    @Test
    fun valueNotOnTheWheelIsReportedNotForced() = runBlocking<Unit> {
        val s = open()
        val o = screen.setWheel(s.picker(WheelPickerTestActivity.LOCKED).ref, s.snapshotId, "75")
        assertTrue("$o", !o.reached)
        assertTrue("$o", o.value != null)
    }
}
