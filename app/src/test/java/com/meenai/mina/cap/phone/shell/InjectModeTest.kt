package com.meenai.mina.cap.phone.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Injection method: Press the button and wait for the system to decide who to vote to. If it is not
 * voted out, it will report failure (the caller will return); the touch still does not wait. A pair of
 * press/lift: it will return when the press is not successful, and it will only be lifted when the press
 * is successful.
 */
class InjectModeTest {
    // InputManager.INJECT_INPUT_EVENT_MODE_*
    private val async = 0
    private val waitForResult = 1
    private val waitForFinish = 2

    @Test fun keysWaitForTheDispatchResult() {
        assertEquals(waitForResult, ShellService.injectMode(key = true, waitForFinish = false))
    }

    @Test fun touchesStayAsync() {
        assertEquals(async, ShellService.injectMode(key = false, waitForFinish = false))
    }

    @Test fun explicitWaitForFinishWins() {
        assertEquals(waitForFinish, ShellService.injectMode(key = true, waitForFinish = true))
        assertEquals(waitForFinish, ShellService.injectMode(key = false, waitForFinish = true))
    }

    @Test fun undeliveredDownFallsBack() {
        var ups = 0
        var fallbacks = 0
        assertTrue(ShellService.sendPair({ false }, { ups++; true }, { fallbacks++; true }))
        assertEquals(1, fallbacks)
        assertEquals(0, ups)
        assertFalse(ShellService.sendPair({ false }, { true }, { false }))
    }

    @Test fun deliveredDownNeverFallsBackAndRetriesUpOnce() {
        var ups = 0
        var fallbacks = 0
        assertFalse(ShellService.sendPair({ true }, { ups++; false }, { fallbacks++; true }))
        assertEquals(2, ups)
        assertEquals(0, fallbacks)
    }
}
