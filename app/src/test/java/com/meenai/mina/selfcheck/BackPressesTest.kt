package com.meenai.mina.selfcheck

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Self-check and press return: The few times you press the input method while it is playing are to close the input method and are not included in the number of times; the total number of times has an upper limit. */
class BackPressesTest {
    /** One screen: In the input method, you need to press [imePresses] to close it (first clear the words on the screen, and then close it), and then press [pageLeavesAfter] to close the page and leave. */
    private class Screen(var imePresses: Int, val pageLeavesAfter: Int = 1) {
        var presses = 0
        private var pageBacks = 0
        var gone = false
        fun press() {
            presses++
            if (imePresses > 0) imePresses-- else if (++pageBacks >= pageLeavesAfter) gone = true
        }
    }

    private fun run(screen: Screen, tries: Int = 2, max: Int = 5) = runBlocking {
        BackPresses.until(tries, max, { screen.imePresses > 0 }, { screen.press() }, { screen.gone })
    }

    @Test fun keyboardThatTakesTwoPressesNoLongerFailsTheCheck() {
        // It turns out that if the input method eats it twice, it will be deemed a failure.
        val screen = Screen(imePresses = 2)
        assertTrue(run(screen))
        assertEquals(3, screen.presses)
    }

    @Test fun withoutKeyboardOnlyTheTriesCount() {
        val screen = Screen(imePresses = 0, pageLeavesAfter = Int.MAX_VALUE)
        assertFalse(run(screen))
        assertEquals(2, screen.presses)
    }

    @Test fun keyboardThatNeverHidesStopsAtTheCap() {
        val screen = Screen(imePresses = Int.MAX_VALUE)
        assertFalse(run(screen))
        assertEquals(5, screen.presses)
    }

    @Test fun firstPressLeavingIsEnough() {
        val screen = Screen(imePresses = 0)
        assertTrue(run(screen))
        assertEquals(1, screen.presses)
    }
}
