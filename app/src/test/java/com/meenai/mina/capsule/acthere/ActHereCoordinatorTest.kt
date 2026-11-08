package com.meenai.mina.capsule.acthere

import com.meenai.mina.core.access.AppAccessState
import com.meenai.mina.core.access.AppCategory
import com.meenai.mina.core.access.AppClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 4 unit tests:
 * 1. send/delete/pay jaise button par confirm ke bina tap nahi hota
 * 2. payment screen par agent tap nahi karta
 * 3. blocked app par Yahin karo nahi khulta
 * 4. Stop dabane par agla step nahi chalta
 * 5. 15 step ke baad ruk jata hai
 */
class ActHereCoordinatorTest {

    @Test
    fun sendDeletePayJaiseButtonParConfirmKeBinaTapNahiHota() {
        val coordinator = ActHereCoordinator()
        var tapsExecuted = 0

        fun executeIfProceed(decision: StepDecision) {
            if (decision is StepDecision.ProceedWithStatus) {
                tapsExecuted++
            }
        }

        val sensitiveLabels = listOf(
            "Send", "Delete", "Pay", "Buy", "Order", "Transfer", "Post", "Submit",
            "भेजें", "हटाएं", "भुगतान", "खरीदें", "ऑर्डर"
        )

        for (label in sensitiveLabels) {
            val action = ActHereAction(targetLabel = label, x = 100f, y = 200f)
            val decision = coordinator.evaluateStep(action)

            // Must require confirmation
            assertTrue("Button '$label' must require confirmation", decision is StepDecision.RequireConfirmation)
            val req = decision as StepDecision.RequireConfirmation
            assertEquals(label, req.actionLabel)

            // Without confirm, no tap executed
            executeIfProceed(decision)
            assertEquals("Taps must remain 0 without confirmation for '$label'", 0, tapsExecuted)

            // When user confirms, step proceeds
            val confirmed = coordinator.confirmAction(action)
            assertTrue("Confirmed action for '$label' must proceed", confirmed is StepDecision.ProceedWithStatus)
            executeIfProceed(confirmed)
            assertEquals(1, tapsExecuted)

            // Reset tap count and coordinator for next label
            tapsExecuted = 0
            coordinator.reset()
        }

        // Non-sensitive button must proceed immediately without confirmation
        val normalAction = ActHereAction(targetLabel = "Next", x = 50f, y = 50f)
        val normalDecision = coordinator.evaluateStep(normalAction)
        assertTrue(normalDecision is StepDecision.ProceedWithStatus)
        executeIfProceed(normalDecision)
        assertEquals(1, tapsExecuted)
    }

    @Test
    fun paymentScreenParAgentTapNahiKarta() {
        val coordinator = ActHereCoordinator()

        val paymentScreens = listOf(
            listOf("Total: ₹499", "Enter UPI PIN to authenticate"),
            listOf("Checkout", "Card Number", "CVV", "Expiry Date"),
            listOf("Payment Gateway", "Proceed to Pay"),
            listOf("यूपीआई पिन दर्ज करें", "राशि: ₹200"),
            listOf("Checkout screen", "Pay button")
        )

        for (screenTexts in paymentScreens) {
            val action = ActHereAction(targetLabel = "Pay", x = 200f, y = 300f)
            val decision = coordinator.evaluateStep(action, screenTexts = screenTexts)

            assertTrue(
                "On payment screen $screenTexts agent must hand over to user",
                decision is StepDecision.PaymentScreenHandover
            )
            val handover = decision as StepDecision.PaymentScreenHandover
            assertEquals("Ye aap khud karo", handover.message)
        }

        // Even an action directly targeting UPI PIN credentials must yield handover
        val upiPinAction = ActHereAction(targetLabel = "Enter UPI PIN", x = 100f, y = 100f)
        val directDecision = coordinator.evaluateStep(upiPinAction)
        assertTrue(directDecision is StepDecision.PaymentScreenHandover)
    }

    @Test
    fun blockedAppParYahinKaroNahiKhulta() {
        val coordinator = ActHereCoordinator()

        val state = AppAccessState(
            available = true,
            blocked = setOf("com.secret.vault"),
            classifications = mapOf(
                "com.hdfcbank.mobile" to AppClassification(AppCategory.Finance)
            )
        )

        // Blocked app
        assertFalse(coordinator.canOpen(listOf("com.secret.vault"), state))

        // Banking app (Finance category)
        assertFalse(coordinator.canOpen(listOf("com.hdfcbank.mobile"), state))

        // Aadhaar app
        assertFalse(coordinator.canOpen(listOf("in.gov.uidai.maadhaarplus"), state))

        // UPI app
        assertFalse(coordinator.canOpen(listOf("com.google.android.apps.nbu.paisa.user"), state))
        assertFalse(coordinator.canOpen(listOf("com.phonepe.app"), state))

        // Normal app
        assertTrue(coordinator.canOpen(listOf("org.wikipedia"), state))

        // Evaluating step while foreground is blocked returns BlockedApp decision
        val blockedDecision = coordinator.evaluateStep(
            action = ActHereAction(targetLabel = "Next"),
            foregroundPackages = listOf("com.secret.vault"),
            appAccessState = state
        )
        assertTrue(blockedDecision is StepDecision.BlockedApp)
    }

    @Test
    fun stopDabaneParAglaStepNahiChalta() {
        val coordinator = ActHereCoordinator()

        // Step 1 before stop works
        val d1 = coordinator.evaluateStep(ActHereAction(targetLabel = "Step 1"))
        assertTrue(d1 is StepDecision.ProceedWithStatus)
        assertEquals(1, coordinator.stepCount)

        // User hits Stop
        coordinator.stop()
        assertTrue(coordinator.isStopped)

        // Step 2 after stop must not proceed
        val d2 = coordinator.evaluateStep(ActHereAction(targetLabel = "Step 2"))
        assertTrue("Subsequent step must be Stopped", d2 is StepDecision.Stopped)
        assertEquals("Step count must not increment after stop", 1, coordinator.stepCount)

        // Confirming pending action after stop must also return Stopped
        val dConfirm = coordinator.confirmAction(ActHereAction(targetLabel = "Send"))
        assertTrue(dConfirm is StepDecision.Stopped)
    }

    @Test
    fun fifteenStepKeBaadRukJataHai() {
        val coordinator = ActHereCoordinator(maxSteps = 15)

        // 1 to 15 steps proceed normally
        for (i in 1..15) {
            val decision = coordinator.evaluateStep(ActHereAction(targetLabel = "Button $i"))
            assertTrue("Step $i must proceed", decision is StepDecision.ProceedWithStatus)
            assertEquals(i, coordinator.stepCount)
        }

        // Step 16 must halt and signal MaxStepsReached
        val step16 = coordinator.evaluateStep(ActHereAction(targetLabel = "Button 16"))
        assertTrue("Step 16 must trigger MaxStepsReached", step16 is StepDecision.MaxStepsReached)
        assertEquals(15, coordinator.stepCount)

        // When user chooses to continue, step counter resets and steps can resume
        coordinator.continueSteps()
        val resumedStep = coordinator.evaluateStep(ActHereAction(targetLabel = "Button 16"))
        assertTrue("Step after continuation must proceed", resumedStep is StepDecision.ProceedWithStatus)
        assertEquals(1, coordinator.stepCount)
    }
}
