package com.meenai.mina.core.cap.phone

import com.meenai.mina.core.uistate.PlacePace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Rhythm in the app: short pause on the same screen, long pause on the new screen, apps that limit the
 * browsing speed, there are upper limits for the reading time and longer pauses based on the number of
 * words; the time spent by the model is included; the upper limit for the number of screens is only for
 * apps that limit the browsing speed. The user starts over after answering, and it is no longer set
 * after making an external action; "fast" never varies.
 */
class AppPacingTest {
    private val std = AppPacing.Limits()
    private val careful = AppPacing.Limits.CAREFUL

    @Test fun firstActionDoesNotWait() {
        assertEquals(0, AppPacing.human(Random(1)).waitBefore(0, 500))
    }

    @Test fun sameScreenAndNewScreenUseTheirRanges() {
        repeat(100) { seed ->
            val p = AppPacing.human(Random(seed))
            p.noteAction("com.a", 1_000, newScreen = false)
            val same = p.waitBefore(1_000)
            assertTrue("$same", same in std.sameScreenMs.first..std.maxDwellMs)
            if (same > std.sameScreenMs.last) assertTrue(same <= std.sameScreenMs.last + std.pauseMs.last)
            p.noteAction("com.a", 2_000, newScreen = true)
            val fresh = p.waitBefore(2_000, textChars = 5_000)
            // The standard range does not add reading time based on the number of words.
            assertTrue("$fresh", fresh in std.newScreenMs.first..std.maxDwellMs)
        }
        // On average, the new screen stays longer than the same screen.
        fun mean(newScreen: Boolean) = (0 until 200).map { s ->
            AppPacing.human(Random(s)).apply { noteAction("com.a", 0, newScreen) }.waitBefore(0)
        }.average()
        assertTrue(mean(false) < mean(true))
    }

    @Test fun standardDwellIsModest() {
        repeat(200) { seed ->
            val p = AppPacing.human(Random(seed))
            p.noteAction("com.a", 0, newScreen = seed % 2 == 0)
            assertTrue(p.waitBefore(0) in 500L..3_500L)
        }
    }

    @Test fun carefulAppsReadInProportionToTheContentWithBounds() {
        val p = AppPacing.human(Random(4))
        p.noteAction("com.slow", 0, newScreen = true, pace = PlacePace.CAREFUL)
        val empty = p.waitBefore(0, textChars = 0)
        assertTrue("$empty", empty in careful.newScreenMs.first..careful.newScreenMs.last + careful.pauseMs.last)
        // Add readMsPerChar to each word, the reading time is at most maxReadMs, and the entire stay is
        // at most maxDwellMs.
        assertEquals(empty + 100 * careful.readMsPerChar, p.waitBefore(0, textChars = 100))
        assertEquals((empty + careful.maxReadMs).coerceAtMost(careful.maxDwellMs), p.waitBefore(0, textChars = 1_000_000))
        // Continue on the same screen without reading time
        p.noteAction("com.slow", 0, newScreen = false, pace = PlacePace.CAREFUL)
        val same = p.waitBefore(0, textChars = 1_000)
        assertTrue("$same", same in careful.sameScreenMs.first..careful.sameScreenMs.last + careful.pauseMs.last)
    }

    @Test fun timeAlreadySpentCounts() {
        val p = AppPacing.human(Random(2))
        p.noteAction("com.a", 10_000, newScreen = true)
        val full = p.waitBefore(10_000)
        assertEquals(full - 400, p.waitBefore(10_400))
        assertEquals(0, p.waitBefore(10_000 + full))
        assertEquals(0, p.waitBefore(60_000))
    }

    @Test fun idleScrollOnlyOnNewCarefulScreens() {
        val p = AppPacing.human(Random(2))
        p.noteAction("com.a", 0, newScreen = true)
        assertFalse(p.idleAllowed())
        p.noteAction("com.slow", 0, newScreen = true, pace = PlacePace.CAREFUL)
        assertTrue(p.idleAllowed())
        p.noteAction("com.slow", 0, newScreen = false, pace = PlacePace.CAREFUL)
        assertFalse(p.idleAllowed())
    }

    @Test fun carefulAppsHaveAScreenBudgetThatResetsWhenTheUserAnswers() {
        val p = AppPacing(Random(1), screenCap = 3)
        repeat(3) { p.noteAction("com.slow", 0, newScreen = true, pace = PlacePace.CAREFUL) }
        // Actions on the same screen and scrolling down to read the rest of the content do not count
        p.noteAction("com.slow", 0, newScreen = false, pace = PlacePace.CAREFUL)
        p.noteAction("com.slow", 0, newScreen = true, pace = PlacePace.CAREFUL, counts = false)
        assertEquals(3, p.screensIn("com.slow"))
        assertTrue(p.capReached("com.slow", PlacePace.CAREFUL))
        // Ordinary apps have no upper limit
        repeat(10) { p.noteAction("com.a", 0, newScreen = true) }
        assertFalse(p.capReached("com.a", PlacePace.NORMAL))
        p.userAnswered()
        assertEquals(0, p.screensIn("com.slow"))
        assertFalse(p.capReached("com.slow", PlacePace.CAREFUL))
    }

    @Test fun anOutwardActionLiftsTheBudgetForThatApp() {
        val p = AppPacing(Random(1), screenCap = 1)
        p.noteAction("com.slow", 0, newScreen = true, pace = PlacePace.CAREFUL)
        p.noteAction("com.other", 0, newScreen = true, pace = PlacePace.CAREFUL)
        p.outwardDone("com.slow")
        assertFalse(p.capReached("com.slow", PlacePace.CAREFUL))
        assertTrue(p.capReached("com.other", PlacePace.CAREFUL))
        // The user does not recover even after answering.
        p.userAnswered()
        repeat(3) { p.noteAction("com.slow", 0, newScreen = true, pace = PlacePace.CAREFUL) }
        assertFalse(p.capReached("com.slow", PlacePace.CAREFUL))
    }

    @Test fun offNeverWaitsNorCaps() {
        val p = AppPacing.Off
        repeat(50) { p.noteAction("com.slow", 0, newScreen = true, pace = PlacePace.CAREFUL) }
        assertEquals(0, p.waitBefore(0, 10_000))
        assertFalse(p.capReached("com.slow", PlacePace.CAREFUL))
        assertFalse(p.idleAllowed())
    }

    @Test fun settingMapsToPacing() {
        assertSame(AppPacing.Off, OperatingPace.FAST.appPacing())
        assertEquals(AppPacing.Limits(), OperatingPace.STANDARD.appPacing().limits)
        assertEquals(AppPacing.Limits.CAREFUL, OperatingPace.STANDARD.appPacing().careful)
        assertEquals(AppPacing.Limits.CAREFUL, OperatingPace.HUMAN.appPacing().limits)
        assertTrue(OperatingPace.HUMAN.appPacing().paced)

        assertSame(TypingPacing.Instant, OperatingPace.FAST.typing(PlacePace.CAREFUL))
        assertEquals(TypingPacing.Limits(), OperatingPace.STANDARD.typing(PlacePace.NORMAL).limits)
        assertEquals(TypingPacing.Limits.CAREFUL, OperatingPace.STANDARD.typing(PlacePace.CAREFUL).limits)
        assertEquals(TypingPacing.Limits.CAREFUL, OperatingPace.HUMAN.typing(PlacePace.NORMAL).limits)

        assertEquals(OperatingPace.HUMAN, OperatingPace.parse("human"))
        assertEquals(OperatingPace.FAST, OperatingPace.parse(" FAST "))
        assertEquals(null, OperatingPace.parse("quick"))
        assertEquals(null, OperatingPace.parse(null))
    }
}
