package com.meenai.mina.core.model

import com.meenai.mina.core.net.ProxyFailure

sealed class ModelError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class Auth : ModelError("The API key is invalid or lacks permission")
    class QuotaExceeded : ModelError("The quota is used up")
    class RateLimited(val retryAfterMs: Long?) : ModelError("Too many requests, try again later")
    class ContextTooLong : ModelError("The conversation exceeds the model's context length")
    /**
     * The service provider does not have this model (or the address is wrong and the requested interface
     * does not exist); [model] is the requested model name, and it is empty if it is not known. [detail]
     * is the truncated original message from the server JSON error (empty if none): gateways and relay
     * services also use 404 for other cases (such as their upstream models currently being unavailable),
     * so inspecting the original message distinguishes which case it is.
     */
    class ModelNotFound(val model: String = "", val detail: String = "") :
        ModelError(if (model.isEmpty()) "Model not found" else "Model not found: “$model”")
    /**
     * The service provider has this model, but this account cannot be used (not activated, not
     * authorized, not purchased); [detail] is the original word of the server (truncated), and it is
     * empty if not. Just change a model or go to the service provider's backend to activate it. Trying
     * again is useless. See [ModelAccessErrors].
     */
    class ModelNotAvailable(val model: String = "", val detail: String = "") :
        ModelError(if (model.isEmpty()) "This account cannot use this model (not enabled or no permission)" else "This account cannot use model “$model” (not enabled or no permission)")
    /** The server clearly stated that this model does not support calling tools (requests with tools were rejected). Mina relies on tools to work, it cannot be used as a master model. */
    class ToolsUnsupported(val model: String = "") :
        ModelError(if (model.isEmpty()) "This model does not support tool calls" else "Model “$model” does not support tool calls")
    class Network(cause: Throwable? = null) : ModelError("Network connection failed", cause)
    /**
     * When using an online proxy, an error occurs on the proxy side (cannot connect to the proxy, or the
     * proxy rejects the account and password), which is not a problem with the service provider. See
     * [com.meenai.mina.core.net.NetProxy].
     */
    class Proxy(val failure: ProxyFailure, cause: Throwable? = null) : ModelError(
        when (failure) {
            ProxyFailure.Unreachable -> "Could not reach the network proxy"
            ProxyFailure.AuthFailed -> "The network proxy rejected the username or password"
        },
        cause,
    )
    /**
     * The connection is closed before the streaming reply ends (there is no end mark and no end reason):
     * most likely the gateway, proxy idle timeout or network switching on the way cuts off the
     * connection. If the reply is incomplete and cannot be used, it is usually enough to resend the same
     * request; it has nothing to do with the interface configuration.
     */
    class StreamCut : ModelError("The reply was cut off before it finished")
    class Server(val status: Int) : ModelError("Model service error (HTTP $status)")
    class ContentRefused : ModelError("The model refused this content")
    /** The quota of the ChatGPT package for other apps has reached the upper limit: do not try again, restore or change models when the quota is reached. */
    class ChatGptLimitReached : ModelError("The ChatGPT quota is used up")
    /** This ChatGPT account cannot use the package quota in other apps (package does not match, region or account policy does not allow): Do not try again. */
    class ChatGptNotEligible(val detail: String = "") :
        ModelError(if (detail.isEmpty()) "This account cannot use its ChatGPT quota in other apps" else "This account cannot use its ChatGPT quota in other apps: $detail")
    /** The login has expired (the new token was refused, or the user disconnected the app from the account), and you need to log in again. [account] Which company is the login (name on the interface). */
    class SignInExpired(val account: String = "ChatGPT") : ModelError("Sign-in has expired, please sign in again")
    /**
     * The interface for logging in with an account (such as xAI) returns HTTP 403: The login is
     * successful, but this subscription level cannot adjust this interface. Do not retry; use this API
     * Key instead. Which company does [account] belong to? [detail] is the original message (truncated)
     * from the server, and is empty if not.
     */
    class AccountPlanNotAllowed(val account: String, val detail: String = "") :
        ModelError("Your ${account} plan cannot use this API")
    class BadResponse(detail: String = "", cause: Throwable? = null) :
        ModelError(if (detail.isEmpty()) "The model returned unparseable content" else "The model returned unparseable content: $detail", cause)
    class FirstTokenTimeout(message: String = "First token timeout exceeded 30 seconds") : ModelError(message)
    class AllLimitsExhausted(message: String, val waitMinutes: Int) : ModelError(message)
}
