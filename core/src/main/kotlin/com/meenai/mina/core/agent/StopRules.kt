package com.meenai.mina.core.agent

import com.meenai.mina.core.net.ProxyFailure
import com.meenai.mina.core.guard.GuardVerdict
import com.meenai.mina.core.model.ModelError
import com.meenai.mina.core.tool.Outcome

/**
 * Stopping rules: When does the task end and with what outcome (abandonment, guard calls to stop, model
 * error, no answer given, run out of steps). Don’t finish it here: The reply without tool call is the
 * answer, which will be handed over after checking by the loop. Pure function; the instructions shown to
 * the user in the ending are not sent to the model.
 */
internal object StopRules {
    /** After an action is executed: if you give up, press it to end; otherwise, stop when the guard ([stop], screen guard or guard of a tool that does not move the screen) calls to stop. None are null, continue to do it. */
    fun afterAction(outcome: Outcome?, stop: GuardVerdict?): TaskState? = when (outcome) {
        is Outcome.Failure -> TaskState.Failed(outcome.message, FailKind.GaveUp)
        else -> stop?.let(::guard)
    }

    /** The tool is adjusted in the last round after the number of steps is used up (only end_not_done is given): give up and press it to end, otherwise the number of steps is used up. */
    fun afterFinalTurn(outcome: Outcome?, maxSteps: Int): TaskState = when (outcome) {
        is Outcome.Failure -> TaskState.Failed(outcome.message, FailKind.GaveUp)
        else -> stepLimit(maxSteps)
    }

    fun stepLimit(maxSteps: Int) = TaskState.Failed("Reached the $maxSteps-step limit; the task is not finished.", FailKind.StepLimit)

    /** After being reminded once, the model still responded with empty words (or was truncated by the output upper limit). */
    fun noAnswer() = TaskState.Failed("The model stopped without giving an answer.", FailKind.Model)

    /** After being returned once, the tool call of the model comes back in text form (see [LeakedToolCall]): the original markup is not handed over as an answer. */
    fun toolCallAsText() = TaskState.Failed("The model's tool call was not recognized by the server and came back as plain text, so it was not executed.", FailKind.Model)

    /** The model call failed (it still doesn't work after trying again, or it doesn't work even if you try again). */
    fun modelFailed(e: Throwable): TaskState.Failed =
        if (e is ModelError) TaskState.Failed(userReason(e), FailKind.Model)
        else TaskState.Failed("Model call error: ${e.message ?: e::class.simpleName}", FailKind.Model)

    fun guard(v: GuardVerdict): TaskState.Failed {
        val why = when (v.code) {
            "repeating_action" -> "the same action was repeated with no effect"
            "no_progress" -> "the screen did not change after repeated attempts"
            "read_loop" -> "the page did not change and repeated reads found nothing"
            "too_many_failures" -> "actions kept failing"
            "sandbox_loop" -> "the same command kept failing unchanged"
            else -> "the operation safety guard was triggered"
        }
        return TaskState.Failed("Stopped safely (${v.code}): $why.", FailKind.Guard)
    }
}

internal fun userReason(e: ModelError): String = when (e) {
    is ModelError.Auth -> "The model service rejected the request: the API key is invalid or lacks permission. Check the API key in Settings and that the account can use this model."
    is ModelError.QuotaExceeded -> "The model account's quota is used up. Check the provider account's balance or plan."
    is ModelError.ModelNotFound -> {
        // 404 does not necessarily mean that the model name is wrong (the address is wrong, or the
        // gateway or transfer service says otherwise): if there are original words, follow the original
        // words, and it is not determined that the model does not exist.
        val check = if (e.model.isEmpty()) "Check the model name and Base URL in Settings › Models."
            else "Open Settings › Models and check the model name (use the full model ID, e.g. Gemini model names look like gemini-flash-latest) or switch to the matching preset."
        when {
            e.detail.isNotEmpty() ->
                if (e.model.isEmpty()) "The server replied: ${quoted(e.detail)}. The model name may be wrong, or the service may be temporarily unavailable. $check"
                else "Calling model “${e.model}”: the server replied: ${quoted(e.detail)}. The model name may be wrong, or the service may be temporarily unavailable. $check"
            e.model.isEmpty() -> "Model not found. $check"
            else -> "Model “${e.model}” does not exist at this provider. $check"
        }
    }
    is ModelError.ModelNotAvailable ->
        "Model “${e.model.ifEmpty { "the current model" }}” is not available for this account (not enabled or no permission). Switch models in Settings › Models, or enable it in the provider's console."
    is ModelError.ToolsUnsupported ->
        "Model “${e.model.ifEmpty { "the current model" }}” does not support tool calls, and Harness Agent needs tools to work. Switch to a model that supports tool calls in Settings › Models."
    is ModelError.ContextTooLong -> "The conversation exceeds the model's context length. Switch to a model with a longer context, or break the task into smaller parts."
    is ModelError.ContentRefused -> "The model refused this content. Adjust the task description or switch models."
    is ModelError.RateLimited -> "The model service is rate limiting; it was still too frequent after retries. Try again later, or check the account's rate limits."
    is ModelError.Network -> "Could not connect to the model service, still failing after retries. Check the network and the Base URL."
    is ModelError.Proxy -> when (e.failure) {
        ProxyFailure.Unreachable ->
            "Could not reach the network proxy, or it did not forward this request; still failing after retries. Check that the proxy is on and that the address and port in Settings › Network proxy are correct; clear it if no proxy is needed."
        ProxyFailure.AuthFailed -> "The network proxy rejected the username or password (HTTP 407). Check the proxy's username and password in Settings › Network proxy."
    }
    is ModelError.Server -> "Model service error (HTTP ${e.status}). Try again later."
    is ModelError.StreamCut ->
        "The model's reply was cut off before it finished and the upstream connection closed; retries did not help. Common causes: unstable network, or a relay or gateway with a short idle timeout (likely when the model thinks for a long time). Try again later."
    is ModelError.BadResponse -> "${e.message}. Check that the Base URL matches the API type."
    is ModelError.ChatGptLimitReached ->
        "The ChatGPT quota is used up, try again later. Usage is under ChatGPT Settings › Usage (chatgpt.com/settings/usage); the Plus quota refreshes every 5 hours and is shared with other apps signed in with ChatGPT. You can also switch to another model."
    is ModelError.ChatGptNotEligible ->
        "This account cannot use its ChatGPT quota in other apps (needs Plus or Pro, and the region and account settings must allow it).${if (e.detail.isEmpty()) "" else " The server says: ${quoted(e.detail)}."} You can switch to another model."
    is ModelError.SignInExpired -> "The ${e.account} sign-in has expired. Sign in with ${e.account} again in Settings › Models."
    is ModelError.AccountPlanNotAllowed ->
        "Your ${e.account} plan cannot use this API (HTTP 403).${if (e.detail.isEmpty()) "" else " The server says: ${quoted(e.detail)}."} You can use ${e.account}'s API key instead, or switch models in Settings › Models."
    is ModelError.FirstTokenTimeout -> "The model service did not send the first token in time (timeout). Try again later."
    is ModelError.AllLimitsExhausted -> e.message ?: "All free limits exhausted, please try again later."
}

/** Quoting the original words of the server in the sentence: remove the period at the end (which comes with the original words), and add its own "." from the sentence that quoted it; the ellipsis (truncated "...", "...") does not change. */
private fun quoted(detail: String): String =
    when {
        detail.endsWith("..") -> detail
        detail.endsWith(".") || detail.endsWith("。") -> detail.dropLast(1)
        else -> detail
    }
