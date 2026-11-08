package com.meenai.mina.capsule.acthere

import com.meenai.mina.core.access.AppAccessState
import com.meenai.mina.core.access.AppCategory
import com.meenai.mina.core.access.AppClassification
import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.StopReason
import com.meenai.mina.core.model.ToolSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test
    fun koiTargetNaMileToKoiTapNahiHota() = runBlocking {
        val coordinator = ActHereCoordinator()
        var tapExecuted = false

        // 1. When action is null (no target found on screen by name or coordinates)
        val nullResult = coordinator.executeGatedAction(
            action = null,
            performAction = {
                tapExecuted = true
                true
            }
        )
        assertFalse("When target is not found, action must not execute", nullResult.executed)
        assertFalse("performAction must never be called (no center or first element tap)", tapExecuted)
        assertTrue(nullResult.decision is StepDecision.TargetNotFound)
        assertTrue(nullResult.toolResultContent.contains("Target not found"))

        // 2. When target label is blank
        val blankResult = coordinator.executeGatedAction(
            action = ActHereAction(targetLabel = "   ", x = 500f, y = 500f),
            performAction = {
                tapExecuted = true
                true
            }
        )
        assertFalse(blankResult.executed)
        assertFalse(tapExecuted)
        assertTrue(blankResult.decision is StepDecision.TargetNotFound)

        // 3. In runActHereLoop with a fake model issuing phone_tap on a missing element
        val fakeModel = object : ChatModel {
            var turn = 0
            override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 4096, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                turn++
                return if (turn == 1) {
                    ModelReply(
                        parts = listOf(
                            Part.ToolCall("call_1", "phone_tap", """{"target":"NonExistentButton"}""")
                        ),
                        stopReason = StopReason.ToolUse,
                        usage = null
                    )
                } else {
                    ModelReply(
                        parts = listOf(Part.ToolCall("call_2", "done", "{}")),
                        stopReason = StopReason.ToolUse,
                        usage = null
                    )
                }
            }
        }

        val screenSnapshot = ScreenSnapshotData(
            elements = listOf(UiElementSummary(label = "ExistingButton", x = 100f, y = 200f)),
            screenTexts = listOf("ExistingButton"),
            foregroundPackages = listOf("com.example.app"),
            appAccessState = AppAccessState(available = true),
            topPackage = "com.example.app"
        )

        var tappedX: Float? = null
        var tappedY: Float? = null

        coordinator.runActHereLoop(
            model = fakeModel,
            goal = "Tap NonExistentButton",
            fetchScreen = { screenSnapshot },
            onStatus = {},
            onHighlight = { _, _ -> },
            onHandover = {},
            onConfirm = { true },
            onMaxStepsPrompt = { false },
            onStopped = {},
            performTap = { x, y ->
                tappedX = x
                tappedY = y
                true
            },
            performType = { _, _, _ -> true },
            performSwipe = { true }
        )

        assertNull("No tap should happen when target is not on screen", tappedX)
        assertNull("No tap should happen when target is not on screen", tappedY)
    }

    @Test
    fun gateWalaToolCallPaymentScreenParExecuteNahiHota() = runBlocking {
        val coordinator = ActHereCoordinator()
        var actionExecuted = false

        val action = ActHereAction(targetLabel = "Proceed to Pay", x = 300f, y = 400f)
        val result = coordinator.executeGatedAction(
            action = action,
            screenTexts = listOf("Order Summary", "Payment Gateway", "Enter UPI PIN", "Total ₹500"),
            performAction = {
                actionExecuted = true
                true
            }
        )

        assertFalse("Tool call on payment screen must not execute", result.executed)
        assertFalse("performAction must never be called on payment screen", actionExecuted)
        assertTrue("Decision must be PaymentScreenHandover", result.decision is StepDecision.PaymentScreenHandover)
        val handover = result.decision as StepDecision.PaymentScreenHandover
        assertEquals("Ye aap khud karo", handover.message)
        assertTrue(result.toolResultContent.contains("payment_screen_handover"))
        assertTrue(result.toolResultContent.contains("Ye aap khud karo"))
    }

    @Test
    fun confirmCancelParToolCallExecuteNahiHotaAurKaamRuktaHai() = runBlocking {
        val coordinator = ActHereCoordinator()
        var actionExecuted = false

        val deleteAction = ActHereAction(targetLabel = "Delete", x = 200f, y = 300f)
        val result = coordinator.executeGatedAction(
            action = deleteAction,
            onConfirmRequest = { false }, // User hits Cancel
            performAction = {
                actionExecuted = true
                true
            }
        )

        assertFalse("Tool call must not execute when confirm is cancelled", result.executed)
        assertFalse("performAction must not be called", actionExecuted)
        assertTrue("Coordinator must be stopped when user cancels", coordinator.isStopped)
        assertTrue(result.decision is StepDecision.RequireConfirmation)
        assertTrue(result.toolResultContent.contains("cancelled"))

        // Kaam rukta hai: next action cannot run
        val nextAction = ActHereAction(targetLabel = "Next", x = 100f, y = 100f)
        val nextResult = coordinator.executeGatedAction(
            action = nextAction,
            performAction = {
                actionExecuted = true
                true
            }
        )
        assertFalse("Next action after cancel must not execute", nextResult.executed)
        assertFalse("performAction must not be called after cancel", actionExecuted)
        assertTrue(nextResult.decision is StepDecision.Stopped)
    }

    @Test
    fun stopKeBaadAglaToolCallNahiChalta() = runBlocking {
        val coordinator = ActHereCoordinator()
        var actionExecuted = false

        // Before stop: normal action executes
        val firstAction = ActHereAction(targetLabel = "Home", x = 100f, y = 200f)
        val firstResult = coordinator.executeGatedAction(
            action = firstAction,
            performAction = {
                actionExecuted = true
                true
            }
        )
        assertTrue("First action before stop executes", firstResult.executed)
        assertTrue(actionExecuted)
        assertEquals(1, coordinator.stepCount)

        // User triggers stop
        coordinator.stop()
        assertTrue(coordinator.isStopped)

        actionExecuted = false

        // Next tool call after stop must not execute
        val secondAction = ActHereAction(targetLabel = "Profile", x = 150f, y = 250f)
        val secondResult = coordinator.executeGatedAction(
            action = secondAction,
            performAction = {
                actionExecuted = true
                true
            }
        )

        assertFalse("Tool call after stop must not execute", secondResult.executed)
        assertFalse("performAction must never be called after stop", actionExecuted)
        assertTrue(secondResult.decision is StepDecision.Stopped)
        assertEquals("Step count must not increment after stop", 1, coordinator.stepCount)
        assertTrue(secondResult.toolResultContent.contains("stopped"))
    }

    @Test
    fun xyPayButtonParHoToConfirmMaange() = runBlocking {
        val coordinator = ActHereCoordinator()
        val screenSnapshot = ScreenSnapshotData(
            elements = listOf(
                UiElementSummary(label = "Pay", x = 200f, y = 300f, bounds = listOf(150f, 280f, 250f, 320f))
            ),
            screenTexts = listOf("Amount: ₹499"),
            foregroundPackages = listOf("org.wikipedia"),
            appAccessState = AppAccessState(available = true),
            topPackage = "org.wikipedia"
        )

        var confirmPrompted = false
        var tapExecuted = false

        // Model sends phone_tap with coordinates matching Pay button
        val fakeModel = object : ChatModel {
            override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 4096, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                return ModelReply(
                    parts = listOf(
                        Part.ToolCall("call_1", "phone_tap", """{"x":200,"y":300}""")
                    ),
                    stopReason = StopReason.ToolUse,
                    usage = null
                )
            }
        }

        coordinator.runActHereLoop(
            model = fakeModel,
            goal = "Pay now",
            fetchScreen = { screenSnapshot },
            onStatus = {},
            onHighlight = { _, _ -> },
            onHandover = {},
            onConfirm = { label ->
                confirmPrompted = true
                assertEquals("Pay", label)
                true // User confirms
            },
            onMaxStepsPrompt = { false },
            onStopped = {},
            performTap = { x, y ->
                tapExecuted = true
                assertEquals(200f, x)
                assertEquals(300f, y)
                true
            },
            performType = { _, _, _ -> true },
            performSwipe = { true }
        )

        assertTrue("Pay button at (200, 300) must request confirmation", confirmPrompted)
        assertTrue("Tap should execute after confirmation", tapExecuted)
    }

    @Test
    fun normalizedBoundsConvertedToPixelFindsPayOnPixelTapAndRejectsNormalizedTap() = runBlocking {
        val coordinator = ActHereCoordinator()
        val screenWidth = 1080f
        val screenHeight = 2400f

        // Raw element with normalized bounds 0-999: [100, 500, 300, 600], center (216, 1320)
        // Converted to pixels in CapsuleController:
        val payElement = UiElementSummary(
            label = "Pay",
            x = 216f,
            y = 1320f,
            bounds = listOf(
                100 * screenWidth / 1000f,   // 108f
                500 * screenHeight / 1000f,  // 1200f
                300 * screenWidth / 1000f,   // 324f
                600 * screenHeight / 1000f   // 1440f
            )
        )
        // Another button with normalized bounds [100, 100, 300, 250], center (216, 420)
        val otherElement = UiElementSummary(
            label = "Cancel",
            x = 216f,
            y = 420f,
            bounds = listOf(
                100 * screenWidth / 1000f,   // 108f
                100 * screenHeight / 1000f,  // 240f
                300 * screenWidth / 1000f,   // 324f
                250 * screenHeight / 1000f   // 600f
            )
        )

        val screenSnapshot = ScreenSnapshotData(
            elements = listOf(payElement, otherElement),
            screenTexts = listOf("Amount: ₹499"),
            foregroundPackages = listOf("org.wikipedia"),
            appAccessState = AppAccessState(available = true),
            topPackage = "org.wikipedia"
        )

        // 1. Tap at pixel coordinates (150f, 1250f) inside Pay bounds [108f, 1200f, 324f, 1440f]
        var confirmPromptedForPay = false
        var tappedX: Float? = null
        var tappedY: Float? = null

        val fakeModelPixel = object : ChatModel {
            override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 4096, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                return ModelReply(
                    parts = listOf(
                        Part.ToolCall("call_1", "phone_tap", """{"x":150,"y":1250}""")
                    ),
                    stopReason = StopReason.ToolUse,
                    usage = null
                )
            }
        }

        coordinator.runActHereLoop(
            model = fakeModelPixel,
            goal = "Pay",
            fetchScreen = { screenSnapshot },
            onStatus = {},
            onHighlight = { _, _ -> },
            onHandover = {},
            onConfirm = { label ->
                if (label == "Pay") confirmPromptedForPay = true
                true
            },
            onMaxStepsPrompt = { false },
            onStopped = {},
            performTap = { x, y ->
                tappedX = x
                tappedY = y
                true
            },
            performType = { _, _, _ -> true },
            performSwipe = { true }
        )

        assertTrue("Pixel tap inside Pay bounds must find Pay and request confirm", confirmPromptedForPay)
        // Point 2: Tap button ke center pe karo (matchedEl.x, matchedEl.y), model ke x,y pe nahi
        assertEquals("Tap X must be button center (216f), not model X (150f)", 216f, tappedX)
        assertEquals("Tap Y must be button center (1320f), not model Y (1250f)", 1320f, tappedY)

        // 2. Tap at normalized number coordinates (200f, 550f) -> Pay must NOT be found!
        val coordinator2 = ActHereCoordinator()
        var confirmPrompted2 = false
        var tappedPay2 = false

        val fakeModelNormalized = object : ChatModel {
            var turn = 0
            override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 4096, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                turn++
                return if (turn == 1) {
                    ModelReply(
                        parts = listOf(
                            Part.ToolCall("call_1", "phone_tap", """{"x":200,"y":550}""")
                        ),
                        stopReason = StopReason.ToolUse,
                        usage = null
                    )
                } else {
                    ModelReply(
                        parts = listOf(Part.ToolCall("call_2", "done", "{}")),
                        stopReason = StopReason.ToolUse,
                        usage = null
                    )
                }
            }
        }

        coordinator2.runActHereLoop(
            model = fakeModelNormalized,
            goal = "Pay",
            fetchScreen = { screenSnapshot },
            onStatus = {},
            onHighlight = { _, _ -> },
            onHandover = {},
            onConfirm = { label ->
                if (label == "Pay") confirmPrompted2 = true
                true
            },
            onMaxStepsPrompt = { false },
            onStopped = {},
            performTap = { _, _ ->
                tappedPay2 = true
                true
            },
            performType = { _, _, _ -> true },
            performSwipe = { true }
        )

        assertFalse("Normalized numbers tap (200, 550) must not match Pay button", confirmPrompted2)
        assertFalse("Normalized numbers tap (200, 550) must not tap Pay button", tappedPay2)
    }

    @Test
    fun targetKhaliHoAurXyBhiNaHoToKoiTapNahiModelKoError() = runBlocking {
        val coordinator = ActHereCoordinator()
        val screenSnapshot = ScreenSnapshotData(
            elements = listOf(
                UiElementSummary(label = "First Button", x = 100f, y = 100f, bounds = listOf(50f, 80f, 150f, 120f))
            ),
            screenTexts = listOf("First Button"),
            foregroundPackages = listOf("org.wikipedia"),
            appAccessState = AppAccessState(available = true),
            topPackage = "org.wikipedia"
        )

        var tapExecuted = false
        var turnCount = 0
        var receivedToolResult: String? = null

        val fakeModel = object : ChatModel {
            override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 4096, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                turnCount++
                if (turnCount == 1) {
                    return ModelReply(
                        parts = listOf(
                            Part.ToolCall("call_1", "phone_tap", """{"target":""}""")
                        ),
                        stopReason = StopReason.ToolUse,
                        usage = null
                    )
                } else {
                    val toolResultPart = messages.lastOrNull()?.parts?.filterIsInstance<Part.ToolResult>()?.firstOrNull()
                    receivedToolResult = toolResultPart?.content
                    return ModelReply(
                        parts = listOf(
                            Part.ToolCall("call_2", "done", "{}")
                        ),
                        stopReason = StopReason.ToolUse,
                        usage = null
                    )
                }
            }
        }

        coordinator.runActHereLoop(
            model = fakeModel,
            goal = "Tap blank target",
            fetchScreen = { screenSnapshot },
            onStatus = {},
            onHighlight = { _, _ -> },
            onHandover = {},
            onConfirm = { false },
            onMaxStepsPrompt = { false },
            onStopped = {},
            performTap = { _, _ ->
                tapExecuted = true
                true
            },
            performType = { _, _, _ -> true },
            performSwipe = { true }
        )

        assertFalse("Empty target and no x,y must not tap pehla element", tapExecuted)
        assertEquals("Step count should increment", 1, coordinator.stepCount)
        assertNotNull("Model should receive error result", receivedToolResult)
        assertTrue("Tool result must contain error", receivedToolResult?.contains("error") == true)
    }

    @Test
    fun xyKhaliJagahParHoToTapNaHo() = runBlocking {
        val coordinator = ActHereCoordinator()
        val screenSnapshot = ScreenSnapshotData(
            elements = listOf(
                UiElementSummary(label = "Submit", x = 100f, y = 100f, bounds = listOf(50f, 80f, 150f, 120f))
            ),
            screenTexts = listOf("Some text"),
            foregroundPackages = listOf("org.wikipedia"),
            appAccessState = AppAccessState(available = true),
            topPackage = "org.wikipedia"
        )

        var tapExecuted = false
        var turnCount = 0
        var receivedToolResult: String? = null

        // Model sends phone_tap with coordinates on empty space on turn 1, then done on turn 2
        val fakeModel = object : ChatModel {
            override val capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 4096, reasoning = false)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                turnCount++
                if (turnCount == 1) {
                    return ModelReply(
                        parts = listOf(
                            Part.ToolCall("call_1", "phone_tap", """{"x":750,"y":900}""")
                        ),
                        stopReason = StopReason.ToolUse,
                        usage = null
                    )
                } else {
                    val toolResultPart = messages.lastOrNull()?.parts?.filterIsInstance<Part.ToolResult>()?.firstOrNull()
                    receivedToolResult = toolResultPart?.content
                    return ModelReply(
                        parts = listOf(
                            Part.ToolCall("call_2", "done", "{}")
                        ),
                        stopReason = StopReason.ToolUse,
                        usage = null
                    )
                }
            }
        }

        coordinator.runActHereLoop(
            model = fakeModel,
            goal = "Tap blank area",
            fetchScreen = { screenSnapshot },
            onStatus = {},
            onHighlight = { _, _ -> },
            onHandover = {},
            onConfirm = { false },
            onMaxStepsPrompt = { false },
            onStopped = {},
            performTap = { _, _ ->
                tapExecuted = true
                true
            },
            performType = { _, _, _ -> true },
            performSwipe = { true }
        )

        assertFalse("No tap must be executed on empty/khali space", tapExecuted)
        assertEquals("Loop must continue and stepCount increment", 1, coordinator.stepCount)
        assertFalse("Task must not be stopped on button not found", coordinator.isStopped)
        assertNotNull("Model must receive tool result", receivedToolResult)
        assertTrue("Error must say 'Is jagah koi button nahi'", receivedToolResult?.contains("Is jagah koi button nahi", ignoreCase = true) == true)
    }

    @Test
    fun screenTextMasksPasswordsAndOtpNumbers() {
        // 1. "Rs 1500" and "2026" must remain visible (not masked)
        val priceText = "Total: Rs 1500"
        val yearText = "Copyright 2026"
        val orderText = "Order 98765 placed at 14:30"
        assertEquals("Total: Rs 1500", ActHereCoordinator.maskSensitiveText(priceText))
        assertEquals("Copyright 2026", ActHereCoordinator.maskSensitiveText(yearText))
        assertEquals("Order 98765 placed at 14:30", ActHereCoordinator.maskSensitiveText(orderText))

        // 2. "OTP 482913" must be masked
        val rawOtp = "OTP 482913"
        val maskedOtp = ActHereCoordinator.maskSensitiveText(rawOtp)
        assertFalse("Raw OTP number must not appear in text", maskedOtp.contains("482913"))
        assertTrue("Masked OTP placeholder must appear", maskedOtp.contains("••••••"))

        // 3. Various OTP/PIN/verification formats
        val textWithOtp = "Your verification OTP is 492104 to complete login"
        val maskedVerification = ActHereCoordinator.maskSensitiveText(textWithOtp)
        assertFalse("Raw OTP number must not appear in text", maskedVerification.contains("492104"))
        assertTrue("Masked OTP placeholder must appear", maskedVerification.contains("••••••"))

        val textWithPin = "PIN: 5678"
        val maskedPin = ActHereCoordinator.maskSensitiveText(textWithPin)
        assertFalse("Raw PIN number must not appear", maskedPin.contains("5678"))
        assertTrue(maskedPin.contains("••••••"))

        val reverseOtp = "482913 is your OTP"
        val maskedReverseOtp = ActHereCoordinator.maskSensitiveText(reverseOtp)
        assertFalse("Raw reverse OTP number must not appear", maskedReverseOtp.contains("482913"))
        assertTrue(maskedReverseOtp.contains("••••••"))

        // 4. Card number (13-19 digit) must stay masked
        val cardText = "Card: 4532 1234 5678 9012"
        val maskedCard = ActHereCoordinator.maskSensitiveText(cardText)
        assertFalse("Card digits must not appear", maskedCard.contains("4532"))
        assertTrue(maskedCard.contains("••••••••••••••••"))

        // 5. Secret/password fields
        val secretElementLabel = ActHereCoordinator.maskElementLabel("my_secret_password", isSecret = true)
        assertEquals("••••••", secretElementLabel)

        val nonSecretElementLabel = ActHereCoordinator.maskElementLabel("Rs 1500", isSecret = false)
        assertEquals("Rs 1500", nonSecretElementLabel)
    }
}
