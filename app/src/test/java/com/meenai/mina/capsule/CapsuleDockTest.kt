package com.meenai.mina.capsule

import com.meenai.mina.cap.phone.a11y.OverlayArea
import org.junit.Assert.assertEquals
import org.junit.Test

class CapsuleDockTest {
    // 1000×2000 screen, status bar 80, bottom gesture bar 40; visible capsule distance 30, top and
    // bottom 20, shadow edge 10
    private val area = DockArea.of(OverlayArea(1000, 2000, insetTop = 80, insetBottom = 40), margin = 30, gap = 20, room = 10)

    @Test fun areaAvoidsBarsAndKeepsMargins() {
        assertEquals(DockArea(1000, left = 20, top = 90, right = 980, bottom = 1950, edgeLeft = -10, edgeRight = 1010), area)
    }

    @Test fun fractionAndYRoundTrip() {
        assertEquals(90, CapsuleDocking.y(0f, area, 100))
        assertEquals(1850, CapsuleDocking.y(1f, area, 100))
        assertEquals(970, CapsuleDocking.y(0.5f, area, 100))
        assertEquals(0.5f, CapsuleDocking.fraction(970, area, 100), 0.001f)
        // Clamping out of range
        assertEquals(0f, CapsuleDocking.fraction(-500, area, 100))
        assertEquals(1f, CapsuleDocking.fraction(5000, area, 100))
        assertEquals(90, CapsuleDocking.y(-3f, area, 100))
    }

    @Test fun sameFractionAfterRotation() {
        val landscape = DockArea.of(OverlayArea(2000, 1000, insetLeft = 80, insetTop = 40), margin = 30, gap = 20, room = 10)
        val f = CapsuleDocking.fraction(CapsuleDocking.y(0.25f, area, 100), area, 100)
        val y = CapsuleDocking.y(f, landscape, 100)
        // Horizontal screen: upper 50, lower 990, range 1/4 of 840
        assertEquals(50 + 210, y)
        // Bangs on the left: get out of the way when docking on the left
        assertEquals(100, CapsuleDocking.dockedX(DockSide.Left, landscape, 200))
        assertEquals(1780, CapsuleDocking.dockedX(DockSide.Right, landscape, 200))
    }

    @Test fun tallerWindowMovesUpToFit() {
        // The card is 600 meters high and cannot be placed down from the height of the capsule.
        assertEquals(1950 - 600, CapsuleDocking.fitY(CapsuleDocking.y(1f, area, 100), area, 600))
        assertEquals(500, CapsuleDocking.fitY(500, area, 600))
        // Higher than the entire range: close to the top
        assertEquals(90, CapsuleDocking.fitY(500, area, 5000))
    }

    @Test fun dragStopsAtTheScreenEdge() {
        assertEquals(-10, CapsuleDocking.dragX(-300, area, 200))
        assertEquals(1010 - 200, CapsuleDocking.dragX(5000, area, 200))
        assertEquals(400, CapsuleDocking.dragX(400, area, 200))
    }

    @Test fun releaseSnapsToTheNearestSide() {
        assertEquals(CapsuleDock(DockSide.Left, 0f), CapsuleDocking.release(250, 90, 200, 100, area))
        assertEquals(DockSide.Right, CapsuleDocking.release(450, 500, 200, 100, area).side)
        assertEquals(DockSide.Left, CapsuleDocking.release(380, 500, 200, 100, area).side)
        assertEquals(20, CapsuleDocking.dockedX(DockSide.Left, area, 200))
        assertEquals(780, CapsuleDocking.dockedX(DockSide.Right, area, 200))
        // When the bangs are on the left, the width of the two sides is different: 100 on the left and
        // 20 on the right
        val lopsided = DockArea.of(OverlayArea(2000, 1000, insetLeft = 80, insetTop = 40), margin = 30, gap = 20, room = 10)
        assertEquals(100, CapsuleDocking.edgeOffset(DockSide.Left, lopsided))
        assertEquals(20, CapsuleDocking.edgeOffset(DockSide.Right, lopsided))
    }

    @Test fun keyboardPushesTheRangeUp() {
        // The input method is above 1200: the visible part separates it 20
        val up = area.above(1200, gap = 20, room = 10)
        assertEquals(1190, up.bottom)
        assertEquals(1190 - 600, CapsuleDocking.fitY(CapsuleDocking.y(1f, area, 100), up, 600))
        assertEquals(area, area.above(null, 20, 10))
        // The input method is higher than the swingable range: does not go over the top
        assertEquals(area.top, area.above(10, 20, 10).bottom)
    }

    // ---------- Card ----------

    /** The tallest card (visible 1160 high) along with the upper and lower shadow sides of the window height. */
    private val full = 1160 + 20

    @Test fun cardCapIsAShareOfTheScreen() {
        // 2000 high screen: up to 58%, it can fit in it (dialogue cards are only this high, task cards
        // are up to this high)
        assertEquals(1160, CardDocking.maxHeight(2000, area, room = 10))
        // Input method pop-up (top 1200): after receiving it, the middle section becomes shorter
        val up = area.above(1200, gap = 20, room = 10)
        assertEquals(1190 - 90 - 20, CardDocking.maxHeight(2000, up, room = 10))
        // Horizontal screen: calculated according to the height of the horizontal screen
        val landscape = DockArea.of(OverlayArea(2000, 1000, insetLeft = 80, insetTop = 40), margin = 30, gap = 20, room = 10)
        assertEquals(580, CardDocking.maxHeight(1000, landscape, room = 10))
    }

    @Test fun cardTopFollowsItsFraction() {
        // The top is placed in proportion between 90..(1950 - the highest card): the highest card is in
        // the middle, top and bottom
        assertEquals(90 + 340, CardDocking.y(0.5f, area, area, full, full))
        assertEquals(90, CardDocking.y(0f, area, area, full, full))
        assertEquals(1950 - full, CardDocking.y(1f, area, area, full, full))
        // Same ratio after screen rotation
        val landscape = DockArea.of(OverlayArea(2000, 1000, insetLeft = 80, insetTop = 40), margin = 30, gap = 20, room = 10)
        assertEquals(50 + 170, CardDocking.y(0.5f, landscape, landscape, 600, 600))
    }

    @Test fun shorterTaskCardSharesTheTopWithTheChatCard() {
        // The task card is only 500 high according to its content: the top part is the same as the
        // dialogue card of the same proportion, and the bottom part is shorter.
        for (f in listOf(0f, 0.3f, 0.5f, 1f)) {
            assertEquals(CardDocking.y(f, area, area, full, full), CardDocking.y(f, area, area, full, 500))
        }
        // In the past, the fixed high card recording ratio was read as the above ratio: the dialogue
        // cards were put back to their original positions.
        assertEquals(CapsuleDocking.y(0.7f, area, full), CardDocking.y(0.7f, area, area, full, full))
    }

    @Test fun switchingCardsKeepsTheTopAndShiftsUpOnlyWhenItOverflows() {
        val top = CardDocking.y(1f, area, area, full, 500)
        // Task cards are replaced by dialogue cards: they can be placed down, the upper part does not
        // move, and the lower part grows
        assertEquals(top, CardDocking.y(1f, area, area, full, full))
        // Input method pop-up (top 1200): short cards can be placed down without moving, dialogue cards
        // (shortened to 1100 + shadow edge) are moved up until they can be placed down
        val up = area.above(1200, gap = 20, room = 10)
        val shortTop = CardDocking.y(0f, area, up, full, 500)
        assertEquals(90, shortTop)
        assertEquals(90 + 204, CardDocking.y(0.3f, area, up, full, 500))
        assertEquals(1190 - 700, CardDocking.y(1f, area, up, full, 700))
        assertEquals(90, CardDocking.y(0.5f, area, up, full, 1100))
    }

    @Test fun cardDragStaysOnScreenAndRemembersTheTopFraction() {
        assertEquals(90, CardDocking.dragY(430, -1000f, area, area, full, full))
        assertEquals(1950 - full, CardDocking.dragY(430, 2000f, area, area, full, full))
        assertEquals(530, CardDocking.dragY(430, 100f, area, area, full, full))
        // Drag the short task card all the way down: it will stop at a place where the dialogue card can
        // also be placed, and the recorded ratio will not exceed the range.
        assertEquals(1950 - full, CardDocking.dragY(430, 2000f, area, area, full, 500))
        assertEquals(1f, CardDocking.release(1950 - full, area, full))
        assertEquals(0.5f, CardDocking.release(430, area, full), 0.001f)
        // Press the range that does not allow the input method to note: the input method is still in the
        // same place after it is folded, and the same is true when it is moved to another height.
        assertEquals(430, CardDocking.y(CardDocking.release(430, area, full), area, area, full, full))
        assertEquals(430, CardDocking.y(CardDocking.release(430, area, full), area, area, full, 500))
        // When the input method pops up, drag: the entire page cannot be moved above it.
        val up = area.above(1200, gap = 20, room = 10)
        assertEquals(1190 - 500, CardDocking.dragY(430, 2000f, area, up, full, 500))
    }
}
