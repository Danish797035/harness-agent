package com.meenai.mina.cap.phone.setup

import android.app.NotificationManager
import org.junit.Rule
import com.meenai.mina.i18n.EnglishTexts
import com.meenai.mina.core.shell.BuiltinShellFailure
import com.meenai.mina.core.shell.BuiltinShellStatus
import com.meenai.mina.core.shell.PairingChannelHealth
import com.meenai.mina.core.shell.PairingDiscovery
import com.meenai.mina.core.shell.PairingScreen
import com.meenai.mina.core.shell.PairingScreenReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingNoticeTest {
    @get:Rule val texts = EnglishTexts()

    @Test
    fun codeEntryAppearsOnlyOnceThePairingWindowIsFound() {
        assertNull(PairingNotice.of(BuiltinShellStatus.NotPaired, PairingDiscovery.Searching))
        assertEquals(PairingNotice.EnterCode(37001), PairingNotice.of(BuiltinShellStatus.NotPaired, PairingDiscovery.Found(37001)))
    }

    @Test
    fun enterCodeThenPairingThenSuccess() {
        val found = PairingDiscovery.Found(1)
        assertEquals(PairingNotice.EnterCode(1), PairingNotice.of(BuiltinShellStatus.NotPaired, found))
        assertEquals(PairingNotice.Working, PairingNotice.of(BuiltinShellStatus.Connecting, found))
        assertEquals(PairingNotice.Done, PairingNotice.of(BuiltinShellStatus.Running, PairingDiscovery.Idle))
    }

    @Test
    fun pairingFailuresKeepTheInputWithHowToRetry() {
        assertEquals(
            PairingNotice.EnterCode(5, "Wrong or expired code: in the pairing window, tap “Pair device with pairing code” again to get a new code, then enter it again"),
            PairingNotice.of(BuiltinShellStatus.Failed(BuiltinShellFailure.WrongCode), PairingDiscovery.Found(5)),
        )
        // Even if the window is closed (the pairing window cannot be found), the input box is still
        // left, and the same method is used
        val closed = PairingNotice.of(BuiltinShellStatus.Failed(BuiltinShellFailure.PairingWindowClosed), PairingDiscovery.Searching)
        closed as PairingNotice.EnterCode
        assertNull(closed.port)
        assertTrue(closed.error!!.endsWith("then enter it again"))
        // If pairing fails, please return to Mina
        assertEquals(
            PairingNotice.Problem("Connected, but couldn’t get ready"),
            PairingNotice.of(BuiltinShellStatus.Failed(BuiltinShellFailure.HelperStartFailed), PairingDiscovery.Found(5)),
        )
    }

    /** Pairing failure: The guide page asks the user to re-pair. After finding the pairing window, the input box is the same as when it was not paired; if it is not found yet, the user is still asked to return to Mina. */
    @Test
    fun revokedPairingGetsTheInputOnceThePairingWindowIsFound() {
        val revoked = BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked)
        assertEquals(PairingNotice.EnterCode(37001), PairingNotice.of(revoked, PairingDiscovery.Found(37001)))
        assertEquals(PairingNotice.Problem("Pairing expired"), PairingNotice.of(revoked, PairingDiscovery.Searching))
        assertEquals(PairingNotice.Problem("Pairing expired"), PairingNotice.of(revoked, PairingDiscovery.Idle))
        // The assistant started with the USB startup command stops and I have to run the command again.
        // When I find the pairing window, there is no input box.
        assertEquals(
            PairingNotice.Problem("Phone helper stopped"),
            PairingNotice.of(BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped), PairingDiscovery.Found(37001)),
        )
    }

    /** When the pairing window is found, the notification of whether to give the input box and whether to try after reading the code without obstacles (PairingScreen.attempt) are consistent for each state. */
    @Test
    fun codeInputMatchesWhatAutoReadWouldTry() {
        val reading = PairingScreenReading("123456", null)
        val statuses = listOf(
            BuiltinShellStatus.NotPaired, BuiltinShellStatus.WirelessDebuggingOff, BuiltinShellStatus.Connecting, BuiltinShellStatus.Running,
        ) + BuiltinShellFailure.entries.map { BuiltinShellStatus.Failed(it) }
        statuses.forEach { s ->
            val input = PairingNotice.of(s, PairingDiscovery.Found(5)) is PairingNotice.EnterCode
            assertEquals(s.toString(), PairingScreen.attempt(reading, portFound = true, s, emptySet()) != null, input)
        }
    }

    @Test
    fun codeEntryPopsAtOnceWithoutAccessibilityAndWaitsForAutoRead() {
        val enter = PairingNotice.EnterCode(5)
        assertEquals(0L, PairingNotice.delayBefore(enter, autoRead = false))
        assertEquals(PairingNotice.AUTO_READ_GRACE_MS, PairingNotice.delayBefore(enter, autoRead = true))
        assertEquals(0L, PairingNotice.delayBefore(PairingNotice.Working, autoRead = true))
    }

    @Test
    fun realertsAreCappedAtThree() {
        val enter = PairingNotice.EnterCode(5)
        val waits = generateSequence(0) { it + 1 }.map { PairingNotice.realertAfter(enter, it) }.takeWhile { it != null }.toList()
        assertEquals(3, waits.size)
        assertEquals(PairingNotice.MAX_REALERTS, waits.size)
        // The further back you go, the longer it will take
        assertEquals(waits.filterNotNull().sorted(), waits)
        // If you fill in the wrong information, you will be reminded again; otherwise, there will be no
        // reminder.
        assertEquals(PairingNotice.REALERT_MS[0], PairingNotice.realertAfter(PairingNotice.EnterCode(5, "x"), 0))
        listOf(PairingNotice.Working, PairingNotice.Done, PairingNotice.Problem("x")).forEach { assertNull(PairingNotice.realertAfter(it, 0)) }
    }

    @Test
    fun aSubmittedCodeShowsPairingUntilThePairingStarts() {
        val revoked = BuiltinShellStatus.Failed(BuiltinShellFailure.AuthorizationRevoked)
        // After filling in the code, pairing has not yet started: the status before filling in the code
        // will not be counted, and it will not end (the same is true when the assistant is originally
        // running, and the status will change after pairing starts)
        assertEquals(PairingNotice.Working to false, PairingNotice.follow(revoked, PairingDiscovery.Searching, repairing = false, submitted = true))
        assertEquals(PairingNotice.Working to false, PairingNotice.follow(BuiltinShellStatus.Running, PairingDiscovery.Searching, repairing = false, submitted = true))
        // If you do not fill in the code, it will be calculated according to the status as usual.
        assertEquals(PairingNotice.Problem("Pairing expired") to false, PairingNotice.follow(revoked, PairingDiscovery.Searching, repairing = false, submitted = false))
    }

    @Test
    fun followingEndsOnlyWhenRunningOutsideARepair() {
        // When re-pairing, the old running state is not considered a good match: no transmission, no end
        assertEquals(null to false, PairingNotice.follow(BuiltinShellStatus.Running, PairingDiscovery.Searching, repairing = true, submitted = false))
        assertEquals(PairingNotice.Done to true, PairingNotice.follow(BuiltinShellStatus.Running, PairingDiscovery.Idle, repairing = false, submitted = false))
    }

    @Test
    fun channelIsCreatedHighWhenMissing() {
        assertEquals(ChannelPlan.Create(PairingChannel.IDS.last()), PairingChannel.plan(emptyList()))
        assertEquals(PairingChannelHealth.Ready, PairingChannel.plan(emptyList()).health)
    }

    @Test
    fun channelKeepsWhatWorksMigratesOldOnesAndRespectsTheUser() {
        val old = PairingChannel.IDS.first()
        val new = PairingChannel.IDS.last()
        // It was built in an old version and is high enough: continue to use it without changing the ID.
        assertEquals(
            ChannelPlan.Use(old, PairingChannelHealth.Ready),
            PairingChannel.plan(listOf(ChannelSnapshot(old, NotificationManager.IMPORTANCE_HIGH, userSet = false))),
        )
        // The old version is not built high enough and has not been touched by users: Rebuild with new
        // ID
        assertEquals(
            ChannelPlan.Create(new),
            PairingChannel.plan(listOf(ChannelSnapshot(old, NotificationManager.IMPORTANCE_DEFAULT, userSet = false))),
        )
        // The user adjusts it down by himself: If he does not change it back, he will be prompted.
        assertEquals(
            ChannelPlan.Use(old, PairingChannelHealth.Lowered),
            PairingChannel.plan(listOf(ChannelSnapshot(old, NotificationManager.IMPORTANCE_DEFAULT, userSet = true))),
        )
        // User turned off: unable to send
        assertEquals(
            ChannelPlan.Use(new, PairingChannelHealth.Off),
            PairingChannel.plan(listOf(ChannelSnapshot(new, NotificationManager.IMPORTANCE_NONE, userSet = true))),
        )
        // The latest ID is not high enough, and there is no newer one to change: it can only prompt
        assertEquals(
            ChannelPlan.Use(new, PairingChannelHealth.Lowered),
            PairingChannel.plan(listOf(ChannelSnapshot(new, NotificationManager.IMPORTANCE_LOW, userSet = false))),
        )
        // Look at the new when both the old and the new are there
        assertEquals(
            ChannelPlan.Use(new, PairingChannelHealth.Ready),
            PairingChannel.plan(
                listOf(
                    ChannelSnapshot(old, NotificationManager.IMPORTANCE_LOW, userSet = true),
                    ChannelSnapshot(new, NotificationManager.IMPORTANCE_HIGH, userSet = false),
                ),
            ),
        )
    }
}
