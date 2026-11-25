package com.meenai.mina.ui

import org.junit.Rule
import com.meenai.mina.i18n.ChineseTexts
import com.meenai.mina.core.shell.AutoOnBlock
import com.meenai.mina.core.shell.HelperRestart
import com.meenai.mina.core.shell.RecoveryCapability
import com.meenai.mina.core.shell.RecoveryConsent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RecoveryCopyTest {
    @get:Rule val texts = ChineseTexts()

    @Test
    fun pendingNoteOnlyForDefaultOnesNotYetApplied() {
        val a11y = RecoveryCapability.Accessibility
        val fresh = RecoveryConsent()
        // The mobile phone operation has not been configured yet: the switch shows that it is on, and
        // the following says that the settings must be completed first.
        assertNotNull(RecoveryCopy.pending(a11y, fresh, paired = false, autoFailed = emptySet()))
        // It’s ready and being supplemented: don’t say much; failed to supplement: tell the truth
        assertNull(RecoveryCopy.pending(a11y, fresh, paired = true, autoFailed = emptySet()))
        assertNotNull(RecoveryCopy.pending(a11y, fresh, paired = true, autoFailed = setOf(a11y)))
        // It has taken effect, or the user has turned it off: nothing is said.
        assertNull(RecoveryCopy.pending(a11y, RecoveryConsent(applied = setOf(a11y)), paired = false, autoFailed = setOf(a11y)))
        assertNull(RecoveryCopy.pending(a11y, RecoveryConsent(declined = setOf(a11y)), paired = false, autoFailed = emptySet()))
    }

    /** The assistant has only been started using USB commands: the two items for restarting the assistant through wireless debugging are clearly unavailable; the other items remain the same. */
    @Test
    fun wirelessOnlyCapabilitiesSayTheyNeedPairingWhenStartedOverUsb() {
        val usb = HelperRestart.UsbCommand
        listOf(RecoveryCapability.RestoreAfterReboot, RecoveryCapability.AutoWirelessDebugging).forEach { c ->
            val note = RecoveryCopy.pending(c, RecoveryConsent(applied = setOf(c)), paired = true, autoFailed = emptySet(), restart = usb)
            assertNotNull(c.name, note)
            // There is not much to say if the user has turned it off; if the user has paired it, it
            // remains the same.
            assertNull(RecoveryCopy.pending(c, RecoveryConsent(declined = setOf(c)), paired = true, autoFailed = emptySet(), restart = usb))
            assertNull(RecoveryCopy.pending(c, RecoveryConsent(applied = setOf(c)), paired = true, autoFailed = emptySet(), restart = HelperRestart.Wireless))
        }
        assertNull(RecoveryCopy.pending(RecoveryCapability.Accessibility, RecoveryConsent(), paired = true, autoFailed = emptySet(), restart = usb))
    }

    @Test
    fun autoOnNoteOnlyWhenStopped() {
        assertNull(RecoveryCopy.autoOnBlocked(null))
        assertNotNull(RecoveryCopy.autoOnBlocked(AutoOnBlock.NotAllowedHere))
        assertEquals(AutoOnBlock.entries.size, AutoOnBlock.entries.mapNotNull(RecoveryCopy::autoOnBlocked).toSet().size)
    }
}
