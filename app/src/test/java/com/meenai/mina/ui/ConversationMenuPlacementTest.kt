package com.meenai.mina.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The placement of the sidebar long-press menu: if it can be placed at the bottom, put it at the bottom; if it cannot be placed, put it on the top; if it cannot be placed, move the row up. Available range 100..1000, row height 50, menu 150, interval 8. */
class ConversationMenuPlacementTest {
    private fun place(rowTop: Float) = placeMenu(rowTop, rowHeight = 50f, menuHeight = 150f, gap = 8f, top = 100f, bottom = 1000f)

    @Test fun roomBelowPutsTheMenuBelow() {
        assertEquals(MenuPlacement(rowTop = 300f, menuTop = 358f, above = false), place(300f))
        // Just enough space: 792 + 50 + 8 + 150 = 1000
        assertEquals(MenuPlacement(792f, 850f, above = false), place(792f))
    }

    @Test fun nearTheBottomPutsTheMenuAbove() {
        assertEquals(MenuPlacement(rowTop = 800f, menuTop = 642f, above = true), place(800f))
        assertEquals(MenuPlacement(940f, 782f, above = true), place(940f))
    }

    @Test fun aRowPartlyOffTheUsableAreaIsMovedIn() {
        // Scroll to the line below the status bar and move it to the top of the available range. The
        // menu is connected below.
        assertEquals(MenuPlacement(100f, 158f, above = false), place(60f))
        // The line blocked by the keyboard is moved above the bottom edge of the available range, and
        // the menu is placed above it.
        assertEquals(MenuPlacement(950f, 792f, above = true), place(990f))
    }

    @Test fun noRoomEitherSideMovesTheRowUp() {
        // Only 100..400 is left in the available range (the keyboard takes up more than half of the
        // screen): the top and bottom cannot fit, so the rows are moved to 400-150-8-50 = 192, and the
        // menu is below
        assertEquals(MenuPlacement(192f, 250f, above = false), placeMenu(200f, 50f, 150f, 8f, top = 100f, bottom = 400f))
        // No matter how small it is, it won’t go over the top
        assertEquals(MenuPlacement(100f, 158f, above = false), placeMenu(150f, 50f, 150f, 8f, top = 100f, bottom = 250f))
    }
}
