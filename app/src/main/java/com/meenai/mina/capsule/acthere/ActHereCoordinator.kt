package com.meenai.mina.capsule.acthere

import com.meenai.mina.capsule.CapsuleSecurityPolicy
import com.meenai.mina.core.access.AppAccessState

enum class ActHereActionType {
    Tap,
    Type,
    Scroll,
}

data class ActHereAction(
    val type: ActHereActionType = ActHereActionType.Tap,
    val targetLabel: String,
    val x: Float = 0f,
    val y: Float = 0f,
    val textToType: String? = null,
)

sealed interface StepDecision {
    data class ProceedWithStatus(val statusMessage: String, val action: ActHereAction) : StepDecision
    data class RequireConfirmation(val actionLabel: String, val action: ActHereAction) : StepDecision
    data class PaymentScreenHandover(val message: String) : StepDecision
    data class BlockedApp(val message: String) : StepDecision
    data object MaxStepsReached : StepDecision
    data object Stopped : StepDecision
}

/**
 * Coordinates on-screen actions ("Yahin karo"), ensuring:
 * - Sensitive buttons (Send, Delete, Pay, Buy, Order, Transfer, Post, Submit) require explicit user confirmation.
 * - Payment screens (UPI PIN, card, CVV, checkout, Pay button) hand over control to the user ("Ye aap khud karo").
 * - Blocked apps, Aadhaar, bank, and UPI apps prevent actions.
 * - Stop immediately halts all steps.
 * - Maximum 15 steps before pausing and asking the user.
 */
class ActHereCoordinator(
    val maxSteps: Int = 15,
    private val securityChecker: (packages: Collection<String>?, state: AppAccessState) -> Boolean = { pkgs, st ->
        CapsuleSecurityPolicy.canPerformScreenAction(pkgs, false, st)
    },
) {
    var isStopped: Boolean = false
        private set

    var stepCount: Int = 0
        private set

    companion object {
        val SENSITIVE_KEYWORDS = listOf(
            "send",
            "delete",
            "pay",
            "buy",
            "order",
            "transfer",
            "post",
            "submit",
            "remove",
            "purchase",
            "भेजें",
            "भेजे",
            "हटाएं",
            "हटाए",
            "भुगतान",
            "पे",
            "खरीदें",
            "खरीदे",
            "ऑर्डर",
            "ट्रांसफर",
            "पोस्ट",
            "सबमिट",
        )

        val PAYMENT_SCREEN_KEYWORDS = listOf(
            "upi pin",
            "mpin",
            "card number",
            "cvv",
            "expiry",
            "otp",
            "payment gateway",
            "razorpay",
            "paytm",
            "phonepe",
            "google pay",
            "gpay",
            "bhim",
            "select payment method",
            "proceed to pay",
            "pay now",
            "checkout",
            "credit card",
            "debit card",
            "enter pin",
            "pay button",
            "payment",
            "यूपीआई पिन",
            "पिन दर्ज करें",
            "कार्ड नंबर",
            "सीवीवी",
            "ओटीपी",
            "चेकआउट",
            "भुगतान करें",
        )
    }

    fun canOpen(foregroundPackages: Collection<String>?, appAccessState: AppAccessState): Boolean {
        return securityChecker(foregroundPackages, appAccessState)
    }

    fun stop() {
        isStopped = true
    }

    fun reset() {
        isStopped = false
        stepCount = 0
    }

    fun continueSteps() {
        stepCount = 0
    }

    fun isSensitiveAction(targetLabel: String): Boolean {
        val lower = targetLabel.trim().lowercase()
        return SENSITIVE_KEYWORDS.any { lower.contains(it) }
    }

    fun isPaymentScreen(screenTexts: Collection<String>, targetLabel: String = ""): Boolean {
        if (targetLabel.isNotBlank()) {
            val lowerLabel = targetLabel.trim().lowercase()
            if (lowerLabel.contains("upi pin") || lowerLabel.contains("cvv") || lowerLabel.contains("mpin")) {
                return true
            }
        }
        if (screenTexts.isEmpty()) return false
        return screenTexts.any { text ->
            val lower = text.lowercase()
            PAYMENT_SCREEN_KEYWORDS.any { keyword -> lower.contains(keyword) }
        }
    }

    fun evaluateStep(
        action: ActHereAction,
        screenTexts: Collection<String> = emptyList(),
        foregroundPackages: Collection<String>? = null,
        appAccessState: AppAccessState? = null,
    ): StepDecision {
        if (isStopped) {
            return StepDecision.Stopped
        }
        if (foregroundPackages != null && appAccessState != null) {
            if (!securityChecker(foregroundPackages, appAccessState)) {
                return StepDecision.BlockedApp("Action blocked for sensitive or secured app")
            }
        }
        if (stepCount >= maxSteps) {
            return StepDecision.MaxStepsReached
        }
        if (isPaymentScreen(screenTexts, action.targetLabel)) {
            return StepDecision.PaymentScreenHandover("Ye aap khud karo")
        }
        if (isSensitiveAction(action.targetLabel)) {
            return StepDecision.RequireConfirmation(action.targetLabel, action)
        }

        stepCount++
        return StepDecision.ProceedWithStatus("Tap kar raha hoon: ${action.targetLabel}", action)
    }

    fun confirmAction(action: ActHereAction): StepDecision {
        if (isStopped) {
            return StepDecision.Stopped
        }
        if (stepCount >= maxSteps) {
            return StepDecision.MaxStepsReached
        }
        stepCount++
        return StepDecision.ProceedWithStatus("Tap kar raha hoon: ${action.targetLabel}", action)
    }
}
