package com.meenai.mina.core.cap.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * Use the simulated scroll wheel drawn by the app to run [WheelDrags]: it can only be dragged. What is
 * read back are the [radius] items above and below the selected item. The name of the selected item may
 * contain status words. Reproduce the clock on the real machine: the 24-hour hour and minute wheels
 * cycle, the selected item is called "Selected, 21", and the up and down numbers are 20 and 22.
 */
class WheelDragsTest {
    /**
     * [size] values (starting from 0), [value] is selected; drag your finger up one grid and [rowPx]
     * pixels rotate one grid (the number becomes larger, and becomes smaller when [upIncreases] is
     * false); there is no response when dragging the first [deadDrags] times; [label] is the name of
     * each item (the second parameter of the selected item is true).
     */
    private class Wheel(
        var value: Int,
        val size: Int,
        val wrap: Boolean,
        val radius: Int = 1,
        val rowPx: Int = 80,
        val upIncreases: Boolean = true,
        var deadDrags: Int = 0,
        val label: (Int, Boolean) -> String = { v, sel -> if (sel) "已选中，$v" else "$v" },
    ) {
        var drags = 0
        private fun visibleValues(): List<Pair<Int, Boolean>> = (-radius..radius).mapNotNull { o ->
            val v = value + o
            val shown = if (wrap) Math.floorMod(v, size) else v.takeIf { it in 0 until size }
            shown?.let { it to (o == 0) }
        }
        fun items() = visibleValues().map { (v, sel) -> label(v, sel) }
        fun selected() = visibleValues().indexOfFirst { it.second }
        /** The center of each item read back: arranged according to the estimated grid of 80 pixels (the actual grid is [rowPx]), which is easy to calibrate. */
        fun centers() = visibleValues().indices.map { 300 + it * 80 }
        fun drag(upPx: Int) {
            drags++
            if (deadDrags > 0) { deadDrags--; return }
            val n = (upPx.toDouble() / rowPx).roundToInt() * if (upIncreases) 1 else -1
            value = if (wrap) Math.floorMod(value + n, size) else (value + n).coerceIn(0, size - 1)
        }
    }

    /** Run until it stops, return the last step and drag a few spaces for the first time. */
    private fun run(w: Wheel, target: String, rowHeight: Int = 80): Pair<WheelDrags.Step, Int?> {
        val plan = WheelDrags(target, rowHeight)
        var dragged = 0
        var first: Int? = null
        repeat(60) {
            when (val s = plan.next(w.items(), w.selected(), w.centers(), dragged)) {
                is WheelDrags.Step.Drag -> {
                    if (first == null) first = s.steps
                    dragged = (s.steps * plan.itemHeight * s.boost).roundToInt()
                    w.drag(dragged)
                }
                else -> return s to first
            }
        }
        error("did not stop")
    }

    @Test fun hoursFrom21To7WithStatePrefixedLabel() {
        val w = Wheel(21, 24, wrap = true)
        assertEquals(WheelDrags.Step.Done, run(w, "7").first)
        assertEquals(7, w.value)
    }

    @Test fun minutesFrom21To25() {
        val w = Wheel(21, 60, wrap = true)
        val (step, first) = run(w, "25")
        assertEquals(WheelDrags.Step.Done, step)
        assertEquals(25, w.value)
        assertEquals(4, first)
    }

    @Test fun englishStatePrefixAndUnitSuffix() {
        val en = Wheel(21, 24, wrap = true, label = { v, sel -> if (sel) "Selected, $v" else "$v" })
        assertEquals(WheelDrags.Step.Done, run(en, "7").first)
        assertEquals(7, en.value)
        val unit = Wheel(21, 24, wrap = true, label = { v, _ -> "${v}点" })
        assertEquals(WheelDrags.Step.Done, run(unit, "7").first)
        assertEquals(7, unit.value)
    }

    /** You can see the side you are approaching when 23 → 0: 21 → 7 is 10 spaces forward, not 14 spaces back. */
    @Test fun wrapSeenTakesTheShortWayRound() {
        val w = Wheel(21, 24, wrap = true, radius = 3)
        val (step, first) = run(w, "7")
        assertEquals(WheelDrags.Step.Done, step)
        assertEquals(10, first)
        assertEquals(7, w.value)
    }

    /** No response after dragging for the first time (the drag was too short and was just regarded as a click): Do not consider it as the end, drag further and try again. */
    @Test fun aDragWithNoEffectIsRetriedNotReportedAsTheEnd() {
        val w = Wheel(21, 24, wrap = true, deadDrags = 1)
        assertEquals(WheelDrags.Step.Done, run(w, "7").first)
        assertEquals(7, w.value)
    }

    /** It doesn't move no matter how you drag it: not_moving is reported (the model draws it by itself), and at_end is not reported. */
    @Test fun wheelThatNeverMovesIsNotMovingNotAtEnd() {
        val w = Wheel(21, 24, wrap = true, deadDrags = 100)
        assertEquals(WheelDrags.Step.Stop("not_moving"), run(w, "7").first)
        assertEquals(3, w.drags)
        assertEquals(21, w.value)
    }

    /** The non-circulating scroll wheel has been dragged to the end, and there are no items over there: it is the end. */
    @Test fun nonWrappingWheelStopsAtItsEnd() {
        val w = Wheel(8, 11, wrap = false)
        assertEquals(WheelDrags.Step.Stop("at_end"), run(w, "15").first)
        assertEquals(10, w.value)
    }

    /** Drag the wheel upward to reduce the number: correct the direction after the first rotation. */
    @Test fun wheelWhoseUpDragDecreasesIsCalibrated() {
        val w = Wheel(21, 60, wrap = true, upIncreases = false)
        assertEquals(WheelDrags.Step.Done, run(w, "25").first)
        assertEquals(25, w.value)
    }

    /** One grid is higher than estimated: calibrate according to the actual number of grids rotated and then drag it in place. */
    @Test fun rowHeightIsCalibrated() {
        val w = Wheel(21, 60, wrap = true, rowPx = 120)
        val (step, _) = run(w, "40", rowHeight = 80)
        assertEquals(WheelDrags.Step.Done, step)
        assertEquals(40, w.value)
        assertTrue(w.drags <= 4)
    }

    @Test fun valueNotOnTheWheelIsNotForced() {
        val w = Wheel(0, 60, wrap = true, radius = 2)
        val (step, _) = run(w, "75")
        assertTrue("$step", step is WheelDrags.Step.Stop)
    }
}
