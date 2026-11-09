package com.meenai.mina.capsule

import com.meenai.mina.core.access.AppAccessState
import com.meenai.mina.core.access.AppCategory
import com.meenai.mina.core.access.AppClassification
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests security requirements:
 * 1. Blocked apps automatically block share and automated operations ("blocked app pe share band").
 * 2. Device lock automatically blocks share and automated operations ("lock pe share band").
 * 3. Aadhaar, bank, and UPI apps automatically block screen share and operations.
 * 4. Normal unblocked app allows screen operations when device is unlocked.
 */
class CapsuleSecurityPolicyTest {

    @Test
    fun lockedDeviceBlocksShareAndActions() {
        val state = AppAccessState()
        // Lock pe share band
        val allowed = CapsuleSecurityPolicy.canPerformScreenAction(
            packages = listOf("com.example.app"),
            locked = true,
            state = state,
        )
        assertFalse(allowed)
    }

    @Test
    fun blockedAppBlocksShareAndActions() {
        val state = AppAccessState(blocked = setOf("com.blocked.app"))
        // Blocked app pe share band
        val allowed = CapsuleSecurityPolicy.canPerformScreenAction(
            packages = listOf("com.blocked.app"),
            locked = false,
            state = state,
        )
        assertFalse(allowed)
    }

    @Test
    fun financeCategoryAppBlocksShareAndActions() {
        val state = AppAccessState(
            classifications = mapOf(
                "com.mybank.custom" to AppClassification(AppCategory.Finance)
            )
        )
        val allowed = CapsuleSecurityPolicy.canPerformScreenAction(
            packages = listOf("com.mybank.custom"),
            locked = false,
            state = state,
        )
        assertFalse(allowed)
        assertTrue(CapsuleSecurityPolicy.isSensitiveApp("com.mybank.custom", state))
    }

    @Test
    fun aadhaarBankUpiAppsAreDetectedAndBlocked() {
        val state = AppAccessState()
        val sensitivePackages = listOf(
            "in.gov.uidai.maadhaar",
            "com.phonepe.app",
            "net.one97.paytm",
            "com.google.android.apps.nbu.paisa.user",
            "com.sbi.upi",
            "com.hdfc.bank",
            "com.icici.banking",
            "in.org.npci.upiapp",
        )

        for (pkg in sensitivePackages) {
            assertTrue("Expected $pkg to be recognized as sensitive", CapsuleSecurityPolicy.isSensitiveApp(pkg, state))
            assertFalse(
                "Expected screen action for $pkg to be disallowed",
                CapsuleSecurityPolicy.canPerformScreenAction(listOf(pkg), locked = false, state = state)
            )
        }
    }

    @Test
    fun normalUnblockedAppAllowedWhenUnlocked() {
        val state = AppAccessState()
        val allowed = CapsuleSecurityPolicy.canPerformScreenAction(
            packages = listOf("com.example.calculator"),
            locked = false,
            state = state,
        )
        assertTrue(allowed)
    }
}
