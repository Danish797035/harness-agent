package com.meenai.mina.capsule.acthere

import com.meenai.mina.capsule.CapsuleSecurityPolicy
import com.meenai.mina.core.access.AppAccessState
import com.meenai.mina.core.model.Author
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import com.meenai.mina.core.model.ToolSpec
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    data class TargetNotFound(val message: String) : StepDecision
    data object MaxStepsReached : StepDecision
    data object Stopped : StepDecision
}

data class StepExecutionResult(
    val executed: Boolean,
    val decision: StepDecision,
    val toolResultContent: String,
)

data class UiElementSummary(
    val label: String,
    val x: Float,
    val y: Float,
    val clickable: Boolean = true,
    val editable: Boolean = false,
    val secret: Boolean = false,
    val bounds: List<Float> = emptyList(),
)

data class ScreenSnapshotData(
    val elements: List<UiElementSummary>,
    val screenTexts: List<String>,
    val foregroundPackages: Collection<String>?,
    val appAccessState: AppAccessState,
    val topPackage: String?,
)

/**
 * Coordinates on-screen actions ("Yahin karo"), ensuring:
 * - Sensitive buttons (Send, Delete, Pay, Buy, Order, Transfer, Post, Submit) require explicit user confirmation.
 * - Payment screens (UPI PIN, card, CVV, checkout, Pay button) hand over control to the user ("Ye aap khud karo").
 * - Blocked apps, Aadhaar, bank, and UPI apps prevent actions.
 * - Stop immediately halts all steps.
 * - Maximum 15 steps before pausing and asking the user.
 * - Multi-turn agent loop drives actions via Main model with gated tools.
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

        val CARD_NUMBER_REGEX = Regex("\\b(?:\\d[ -]*?){13,19}\\b")

        val SENSITIVE_KEYWORD_BEFORE_NUMBER_REGEX = Regex(
            "(?i)\\b(otp|pin|cvv|code|verification|password|passcode|ओटीपी|पिन|सीवीवी)\\b((?:\\s+(?:is|hai|code|no|num|number))*[\\s:=#-]*)\\b(\\d{3,8})\\b"
        )

        val SENSITIVE_NUMBER_BEFORE_KEYWORD_REGEX = Regex(
            "(?i)\\b(\\d{3,8})\\b((?:\\s+(?:is|hai|your|apka|aapka))*(?:\\s+(?:verification|otp|code|pin))*[\\s:=#-]*)\\b(otp|pin|cvv|code|verification|password|passcode|ओटीपी|पिन|सीवीवी)\\b"
        )

        fun maskSensitiveText(text: String): String {
            var masked = SENSITIVE_KEYWORD_BEFORE_NUMBER_REGEX.replace(text) { matchResult ->
                val keyword = matchResult.groupValues[1]
                val connector = matchResult.groupValues[2]
                "$keyword$connector••••••"
            }
            masked = SENSITIVE_NUMBER_BEFORE_KEYWORD_REGEX.replace(masked) { matchResult ->
                val connector = matchResult.groupValues[2]
                val keyword = matchResult.groupValues[3]
                "••••••$connector$keyword"
            }
            masked = CARD_NUMBER_REGEX.replace(masked, "••••••••••••••••")
            return masked
        }

        fun maskElementLabel(label: String, isSecret: Boolean): String {
            if (isSecret) return "••••••"
            return maskSensitiveText(label)
        }

        fun findElementAt(elements: List<UiElementSummary>, x: Float, y: Float): UiElementSummary? {
            // 1. Explicit bounds check if available
            elements.firstOrNull { el ->
                if (el.bounds.size == 4) {
                    x >= el.bounds[0] && x <= el.bounds[2] && y >= el.bounds[1] && y <= el.bounds[3]
                } else false
            }?.let { return it }

            // 2. Proximity check around center (within 50 pixels)
            return elements
                .filter { el -> kotlin.math.hypot(el.x - x, el.y - y) <= 50f }
                .minByOrNull { el -> kotlin.math.hypot(el.x - x, el.y - y) }
        }
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
        if (action.targetLabel.isBlank()) {
            return StepDecision.TargetNotFound("Target not found on screen. No tap performed.")
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

    /**
     * Executes an action through the security and confirmation gate.
     * Guarantees:
     * - Null/blank target never triggers fallback taps (no center/first button tap).
     * - Payment screen actions are never executed and return handover decision.
     * - Confirmation cancel halts the task and does not execute.
     * - Stop prevents all subsequent actions.
     */
    suspend fun executeGatedAction(
        action: ActHereAction?,
        screenTexts: Collection<String> = emptyList(),
        foregroundPackages: Collection<String>? = null,
        appAccessState: AppAccessState? = null,
        onConfirmRequest: suspend (label: String) -> Boolean = { false },
        onStatusUpdate: (String) -> Unit = {},
        performAction: suspend (ActHereAction) -> Boolean = { false },
    ): StepExecutionResult {
        if (action == null || action.targetLabel.isBlank()) {
            return StepExecutionResult(
                executed = false,
                decision = StepDecision.TargetNotFound("Target not found on screen. No tap performed."),
                toolResultContent = """{"error":"Target not found on screen. No tap performed."}""",
            )
        }

        val decision = evaluateStep(action, screenTexts, foregroundPackages, appAccessState)
        return when (decision) {
            is StepDecision.Stopped -> {
                StepExecutionResult(
                    executed = false,
                    decision = decision,
                    toolResultContent = """{"status":"stopped","message":"Task stopped"}""",
                )
            }
            is StepDecision.BlockedApp -> {
                StepExecutionResult(
                    executed = false,
                    decision = decision,
                    toolResultContent = """{"status":"blocked","message":"${decision.message}"}""",
                )
            }
            is StepDecision.PaymentScreenHandover -> {
                StepExecutionResult(
                    executed = false,
                    decision = decision,
                    toolResultContent = """{"status":"payment_screen_handover","message":"${decision.message}"}""",
                )
            }
            is StepDecision.TargetNotFound -> {
                StepExecutionResult(
                    executed = false,
                    decision = decision,
                    toolResultContent = """{"error":"${decision.message}"}""",
                )
            }
            is StepDecision.MaxStepsReached -> {
                StepExecutionResult(
                    executed = false,
                    decision = decision,
                    toolResultContent = """{"status":"max_steps_reached","message":"15 step limit reached"}""",
                )
            }
            is StepDecision.RequireConfirmation -> {
                val confirmed = onConfirmRequest(decision.actionLabel)
                if (!confirmed) {
                    stop()
                    StepExecutionResult(
                        executed = false,
                        decision = decision,
                        toolResultContent = """{"status":"cancelled","message":"User cancelled sensitive action"}""",
                    )
                } else {
                    confirmAction(action)
                    onStatusUpdate("Tap kar raha hoon: ${action.targetLabel}")
                    val success = performAction(action)
                    StepExecutionResult(
                        executed = success,
                        decision = decision,
                        toolResultContent = """{"status":"success","action":"${action.targetLabel}"}""",
                    )
                }
            }
            is StepDecision.ProceedWithStatus -> {
                onStatusUpdate(decision.statusMessage)
                val success = performAction(action)
                StepExecutionResult(
                    executed = success,
                    decision = decision,
                    toolResultContent = """{"status":"success","action":"${action.targetLabel}"}""",
                )
            }
        }
    }

    /**
     * Multi-turn agent loop for "Yahin karo", driving the task via the Main model
     * with accessibility tools (phone_tap, phone_type, phone_swipe, done) gated
     * by ActHereCoordinator.
     */
    suspend fun runActHereLoop(
        model: ChatModel,
        goal: String,
        fetchScreen: suspend () -> ScreenSnapshotData,
        onStatus: (String) -> Unit,
        onHighlight: suspend (Float, Float) -> Unit,
        onHandover: (String) -> Unit,
        onConfirm: suspend (String) -> Boolean,
        onMaxStepsPrompt: suspend () -> Boolean,
        onStopped: () -> Unit,
        performTap: suspend (Float, Float) -> Boolean,
        performType: suspend (String, Float, Float) -> Boolean,
        performSwipe: suspend (String) -> Boolean,
    ) {
        reset()
        var screen = fetchScreen()

        if (!canOpen(screen.foregroundPackages, screen.appAccessState)) {
            onStopped()
            return
        }

        if (isPaymentScreen(screen.screenTexts)) {
            onHandover("Ye aap khud karo")
            stop()
            return
        }

        val tools = listOf(
            ToolSpec(
                name = "phone_tap",
                description = "Tap on an element on the screen by target label or coordinates",
                parametersJsonSchema = """{"type":"object","properties":{"target":{"type":"string","description":"Label of button or element to tap"},"x":{"type":"number"},"y":{"type":"number"}},"required":["target"]}"""
            ),
            ToolSpec(
                name = "phone_type",
                description = "Type text into a field on the screen",
                parametersJsonSchema = """{"type":"object","properties":{"text":{"type":"string","description":"Text to type"},"target":{"type":"string"},"x":{"type":"number"},"y":{"type":"number"}},"required":["text"]}"""
            ),
            ToolSpec(
                name = "phone_swipe",
                description = "Swipe or scroll the screen",
                parametersJsonSchema = """{"type":"object","properties":{"direction":{"type":"string","enum":["up","down","left","right"]}},"required":["direction"]}"""
            ),
            ToolSpec(
                name = "done",
                description = "Finish the task when completed",
                parametersJsonSchema = """{"type":"object","properties":{"summary":{"type":"string"}},"required":["summary"]}"""
            )
        )

        val systemPrompt = "You are an AI assistant acting directly on the current Android screen.\n" +
            "Current foreground app: ${screen.topPackage ?: "unknown"}\n" +
            "Task goal: $goal\n\n" +
            "Rules:\n" +
            "1. ONLY perform actions inside this current app to achieve the user's goal. Never switch to another app.\n" +
            "2. Choose specific elements matching the goal. Never guess or tap random locations.\n" +
            "3. When the goal is completed, call 'done'.\n" +
            "4. Use the provided tools (phone_tap, phone_type, phone_swipe, done)."

        fun formatScreen(data: ScreenSnapshotData): String {
            val elementLines = data.elements.take(30).joinToString("\n") { el ->
                val maskedLabel = maskElementLabel(el.label, el.secret)
                "- \"$maskedLabel\" at (${el.x.toInt()}, ${el.y.toInt()})" +
                    (if (el.clickable) " [clickable]" else "") +
                    (if (el.editable) " [input]" else "")
            }
            val textLines = data.screenTexts.take(20).joinToString("\n") { raw ->
                "• ${maskSensitiveText(raw)}"
            }
            return "User goal: $goal\n\nScreen elements:\n$elementLines\n\nVisible text:\n$textLines"
        }

        val history = mutableListOf<Message>()
        history.add(Message(Role.User, listOf(Part.Text(formatScreen(screen))), Author.User))

        while (!isStopped) {
            if (!canOpen(screen.foregroundPackages, screen.appAccessState)) {
                onStopped()
                break
            }
            if (isPaymentScreen(screen.screenTexts)) {
                onHandover("Ye aap khud karo")
                stop()
                break
            }
            if (stepCount >= maxSteps) {
                val cont = onMaxStepsPrompt()
                if (!cont) {
                    stop()
                    break
                } else {
                    continueSteps()
                }
            }

            val reply = try {
                kotlinx.coroutines.withTimeoutOrNull(20_000L) {
                    model.complete(systemPrompt, history, tools)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                break
            } ?: break

            val toolCalls = reply.parts.filterIsInstance<Part.ToolCall>()
            if (toolCalls.isEmpty()) {
                break
            }

            history.add(Message(Role.Assistant, reply.parts, Author.Mina))

            var shouldBreak = false
            val toolResults = mutableListOf<Part>()

            for (call in toolCalls) {
                if (isStopped) {
                    shouldBreak = true
                    break
                }

                if (call.name == "done") {
                    shouldBreak = true
                    toolResults.add(Part.ToolResult(call.id, """{"status":"done"}""", false))
                    break
                }

                val json = runCatching { Json.parseToJsonElement(call.argsJson).jsonObject }.getOrNull()
                when (call.name) {
                    "phone_tap" -> {
                        val target = json?.get("target")?.jsonPrimitive?.contentOrNull.orEmpty()
                        val parsedX = json?.get("x")?.jsonPrimitive?.floatOrNull
                        val parsedY = json?.get("y")?.jsonPrimitive?.floatOrNull

                        val matchedEl = if (parsedX != null && parsedY != null) {
                            findElementAt(screen.elements, parsedX, parsedY)
                        } else {
                            screen.elements.firstOrNull { it.label.contains(target, ignoreCase = true) }
                        }

                        if (parsedX != null && parsedY != null && matchedEl == null) {
                            toolResults.add(Part.ToolResult(call.id, """{"error":"is jagah koi button nahi"}""", true))
                            shouldBreak = true
                            break
                        }

                        val action = if (matchedEl != null) {
                            ActHereAction(ActHereActionType.Tap, targetLabel = matchedEl.label.ifBlank { target }, x = parsedX ?: matchedEl.x, y = parsedY ?: matchedEl.y)
                        } else null

                        val execResult = executeGatedAction(
                            action = action,
                            screenTexts = screen.screenTexts,
                            foregroundPackages = screen.foregroundPackages,
                            appAccessState = screen.appAccessState,
                            onConfirmRequest = onConfirm,
                            onStatusUpdate = onStatus,
                            performAction = { act ->
                                onHighlight(act.x, act.y)
                                performTap(act.x, act.y)
                            }
                        )

                        toolResults.add(Part.ToolResult(call.id, execResult.toolResultContent, !execResult.executed))

                        if (execResult.decision is StepDecision.PaymentScreenHandover) {
                            onHandover("Ye aap khud karo")
                            shouldBreak = true
                        } else if (execResult.decision is StepDecision.BlockedApp || execResult.decision is StepDecision.Stopped || execResult.decision is StepDecision.TargetNotFound) {
                            shouldBreak = true
                        } else if (execResult.decision is StepDecision.MaxStepsReached) {
                            val cont = onMaxStepsPrompt()
                            if (!cont) {
                                stop()
                                shouldBreak = true
                            } else {
                                continueSteps()
                            }
                        } else if (!execResult.executed && execResult.decision is StepDecision.RequireConfirmation) {
                            shouldBreak = true
                        }
                    }
                    "phone_type" -> {
                        val text = json?.get("text")?.jsonPrimitive?.contentOrNull.orEmpty()
                        val target = json?.get("target")?.jsonPrimitive?.contentOrNull.orEmpty()
                        val parsedX = json?.get("x")?.jsonPrimitive?.floatOrNull
                        val parsedY = json?.get("y")?.jsonPrimitive?.floatOrNull

                        val matchedEl = if (parsedX != null && parsedY != null) {
                            findElementAt(screen.elements, parsedX, parsedY)
                        } else {
                            screen.elements.firstOrNull { it.editable || it.label.contains(target, ignoreCase = true) }
                        }

                        if (parsedX != null && parsedY != null && matchedEl == null) {
                            toolResults.add(Part.ToolResult(call.id, """{"error":"is jagah koi button nahi"}""", true))
                            shouldBreak = true
                            break
                        }

                        val action = if (matchedEl != null) {
                            ActHereAction(ActHereActionType.Type, targetLabel = matchedEl.label.ifBlank { target.ifBlank { "Input" } }, x = parsedX ?: matchedEl.x, y = parsedY ?: matchedEl.y, textToType = text)
                        } else if (target.isNotBlank()) {
                            ActHereAction(ActHereActionType.Type, targetLabel = target, textToType = text)
                        } else null

                        val execResult = executeGatedAction(
                            action = action,
                            screenTexts = screen.screenTexts,
                            foregroundPackages = screen.foregroundPackages,
                            appAccessState = screen.appAccessState,
                            onConfirmRequest = onConfirm,
                            onStatusUpdate = onStatus,
                            performAction = { act ->
                                performType(act.textToType ?: text, act.x, act.y)
                            }
                        )

                        toolResults.add(Part.ToolResult(call.id, execResult.toolResultContent, !execResult.executed))

                        if (execResult.decision is StepDecision.PaymentScreenHandover) {
                            onHandover("Ye aap khud karo")
                            shouldBreak = true
                        } else if (!execResult.executed) {
                            shouldBreak = true
                        }
                    }
                    "phone_swipe" -> {
                        val dir = json?.get("direction")?.jsonPrimitive?.contentOrNull.orEmpty()
                        val action = ActHereAction(ActHereActionType.Scroll, targetLabel = "Swipe $dir")
                        val execResult = executeGatedAction(
                            action = action,
                            screenTexts = screen.screenTexts,
                            foregroundPackages = screen.foregroundPackages,
                            appAccessState = screen.appAccessState,
                            onConfirmRequest = onConfirm,
                            onStatusUpdate = onStatus,
                            performAction = {
                                performSwipe(dir)
                            }
                        )

                        toolResults.add(Part.ToolResult(call.id, execResult.toolResultContent, !execResult.executed))
                        if (!execResult.executed) shouldBreak = true
                    }
                    else -> {
                        toolResults.add(Part.ToolResult(call.id, """{"error":"Unknown tool"}""", true))
                    }
                }
            }

            history.add(Message(Role.Tool, toolResults, Author.Mina))

            if (shouldBreak || isStopped) break

            screen = fetchScreen()
            history.add(Message(Role.User, listOf(Part.Text("Updated screen:\n" + formatScreen(screen))), Author.User))
        }

        if (isStopped) {
            onStopped()
        }
    }
}
