package com.meenai.mina.core.shell

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessDebuggingAutoOnTest {
    /** Fake system switch: When [revert] is true, it is turned on and then turned back off by the system after a while (Wi-Fi is not allowed). */
    private inner class FakeSwitch {
        var on: Boolean? = false
        var revert = false
        var refuse = false
        var throws = false
        var writes = 0
        private var readsSinceWrite = 0

        fun read(): Boolean? {
            // The original version was dialed back after receiving the setting change: after writing, it
            // was read and opened first, and then it was read and closed.
            if (revert && on == true && ++readsSinceWrite > 1) on = false
            return on
        }

        fun write(): Boolean {
            writes++
            if (throws) throw SecurityException("denied")
            if (refuse) return false
            on = true
            readsSinceWrite = 0
            return true
        }
    }

    private val switch = FakeSwitch()
    private var wants = true
    private var canWrite = true
    private var clock = 0L

    private fun TestScope.autoOn() = WirelessDebuggingAutoOn(
        wants = { wants }, canWrite = { canWrite }, read = switch::read, write = switch::write,
        now = { clock + testScheduler.currentTime },
    )

    @Test
    fun decideNeedsConsentPermissionAndWifi() {
        fun d(wants: Boolean = true, canWrite: Boolean = true, wifi: Boolean = true, blocked: AutoOnBlock? = null, writes: Int = 0, since: Long = Long.MAX_VALUE) =
            WirelessDebuggingAutoOn.decide(wants, canWrite, wifi, blocked, writes, since)

        assertEquals(AutoOnGate.Act, d())
        // The user has turned off this item: no matter what else, it will not be written
        assertEquals(AutoOnGate.Skip(AutoOnSkip.Declined), d(wants = false, blocked = AutoOnBlock.NotAllowedHere, wifi = false))
        assertEquals(AutoOnGate.Skip(AutoOnSkip.NoPermission), d(canWrite = false))
        assertEquals(AutoOnGate.Skip(AutoOnSkip.NoWifi), d(wifi = false))
        AutoOnBlock.entries.forEach { assertEquals(AutoOnGate.Skip(AutoOnSkip.Blocked), d(blocked = it)) }
        assertEquals(AutoOnGate.Skip(AutoOnSkip.Blocked), d(writes = WirelessDebuggingAutoOn.MAX_WRITES))
    }

    @Test
    fun writesAreSpacedOutWithBackoff() {
        assertEquals(
            listOf(0L, 10_000L, 20_000L, 40_000L),
            (0..3).map(WirelessDebuggingAutoOn::gapAfter),
        )
        assertEquals(WirelessDebuggingAutoOn.MAX_GAP_MS, WirelessDebuggingAutoOn.gapAfter(30))
        assertEquals(AutoOnGate.Wait(6_000), WirelessDebuggingAutoOn.decide(true, true, true, null, 1, 4_000))
        assertEquals(AutoOnGate.Act, WirelessDebuggingAutoOn.decide(true, true, true, null, 1, 10_000))
    }

    @Test
    fun turnsOnAndStaysOnOnAnAllowedNetwork() = runTest {
        val a = autoOn()
        assertEquals(AutoOnGate.Act, a.gate(wifi = true))
        assertTrue(a.turnOn())
        assertEquals(true, switch.on)
        assertNull(a.blocked.value)
        assertFalse(a.writing)
        // Immediately afterwards, I have to write: press escape, etc.
        assertTrue(a.gate(wifi = true) is AutoOnGate.Wait)
    }

    @Test
    fun revertedBySystemStopsUntilReset() = runTest {
        switch.revert = true
        val a = autoOn()
        assertFalse(a.turnOn())
        assertEquals(AutoOnBlock.NotAllowedHere, a.blocked.value)
        assertEquals(false, switch.on)
        // How long does it take to stop writing after stopping (the system will pop up a box every time
        // you write)
        clock += 3_600_000
        assertEquals(AutoOnGate.Skip(AutoOnSkip.Blocked), a.gate(wifi = true))
        assertEquals(1, switch.writes)

        // Changed Wi-Fi, the user opened it manually or clicked Retry: Calculate from scratch
        a.reset()
        assertNull(a.blocked.value)
        assertEquals(AutoOnGate.Act, a.gate(wifi = true))
    }

    @Test
    fun refusedWriteStops() = runTest {
        switch.refuse = true
        val a = autoOn()
        assertFalse(a.turnOn())
        assertEquals(AutoOnBlock.Refused, a.blocked.value)

        a.reset()
        switch.refuse = false
        switch.throws = true
        assertFalse(a.turnOn())
        assertEquals(AutoOnBlock.Refused, a.blocked.value)
        assertFalse(a.writing)
    }

    @Test
    fun givesUpAfterTooManyWritesWithoutTheHelper() = runTest {
        val a = autoOn()
        repeat(WirelessDebuggingAutoOn.MAX_WRITES) {
            clock += 3_600_000
            assertEquals(AutoOnGate.Act, a.gate(wifi = true))
            assertTrue(a.turnOn())
            // The system (or other reasons) turned it off again, and the assistant did not wake up.
            switch.on = false
        }
        clock += 3_600_000
        assertEquals(AutoOnGate.Skip(AutoOnSkip.Blocked), a.gate(wifi = true))
        assertEquals(AutoOnBlock.GaveUp, a.blocked.value)
        // The assistant is up (engine reset): Recount
        a.reset()
        assertEquals(AutoOnGate.Act, a.gate(wifi = true))
    }

    @Test
    fun consentOffNeverWrites() = runTest {
        wants = false
        val a = autoOn()
        assertEquals(AutoOnGate.Skip(AutoOnSkip.Declined), a.gate(wifi = true))
        assertEquals(0, switch.writes)
    }
}
