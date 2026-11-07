package com.meenai.mina.core.router

import com.meenai.mina.core.model.ModelError
import java.io.IOException
import java.net.SocketTimeoutException

enum class RouterAction {
    SWITCH,
    FIX_THEN_SWITCH,
    KEY_BAD,
    CONTEXT,
    STOP
}

typealias FirstTokenTimeoutException = ModelError.FirstTokenTimeout

/**
 * Classifies model execution errors into standardized routing actions:
 * - SWITCH: Temporary or capacity errors (429, 402, 500, 502, 503, network/stream timeout).
 * - FIX_THEN_SWITCH: 400 errors related to tool schema or formatting (retry once sanitized, then switch).
 * - KEY_BAD: 401/403 invalid credentials; disables the entire provider.
 * - CONTEXT: Context window exceeded; compact history or pick a higher-capacity model.
 * - STOP: Refusal or safety policies; do not switch, report directly to the user.
 */
object RouterErrorClassifier {

    private val SAFETY_KEYWORDS = listOf(
        "safety", "refusal", "content policy", "harmful", "prohibited_content",
        "recitation", "blocklist", "spii", "flagged as violation"
    )

    private val SCHEMA_KEYWORDS = listOf(
        "schema", "parameter", "parameters", "function", "tool", "additionalproperties",
        "validation", "invalid argument", "invalid_request_error", "properties",
        "unrecognized field", "missing required property", "cannot parse arguments"
    )

    private val CONTEXT_KEYWORDS = listOf(
        "context_length", "maximum context", "context window", "too long",
        "exceeds context", "tokens exceeded", "max_tokens exceeded", "token limit"
    )

    private val KEY_BAD_KEYWORDS = listOf(
        "invalid_api_key", "api_key_invalid", "unauthorized", "invalid key",
        "permission_denied", "forbidden", "not authorized", "access denied",
        "authentication_error"
    )

    fun classify(
        throwable: Throwable,
        status: Int? = null,
        body: String? = null,
    ): RouterAction {
        val lowerBody = (body ?: throwable.message ?: "").lowercase()

        // 1. Safety / Content refusal -> STOP
        if (throwable is ModelError.ContentRefused) return RouterAction.STOP
        if (SAFETY_KEYWORDS.any { lowerBody.contains(it) }) return RouterAction.STOP

        // Model not available / not supported / not found -> SWITCH (not key bad)
        if (throwable is ModelError.ModelNotAvailable || throwable is ModelError.ModelNotFound ||
            lowerBody.contains("no provider supported") || lowerBody.contains("has no provider supported") ||
            lowerBody.contains("provider not supported")
        ) {
            return RouterAction.SWITCH
        }

        // 2. Auth / Invalid Key -> KEY_BAD
        if (throwable is ModelError.Auth || throwable is ModelError.AccountPlanNotAllowed) {
            return RouterAction.KEY_BAD
        }
        if (status == 401 || status == 403) {
            return RouterAction.KEY_BAD
        }
        if (KEY_BAD_KEYWORDS.any { lowerBody.contains(it) }) {
            return RouterAction.KEY_BAD
        }

        // 3. Context too long -> CONTEXT
        if (throwable is ModelError.ContextTooLong || status == 413) {
            return RouterAction.CONTEXT
        }
        if (status == 400 && CONTEXT_KEYWORDS.any { lowerBody.contains(it) }) {
            return RouterAction.CONTEXT
        }

        // 4. Schema or formatting issue -> FIX_THEN_SWITCH
        if (status == 400 || throwable is ModelError.BadResponse) {
            if (SCHEMA_KEYWORDS.any { lowerBody.contains(it) }) {
                return RouterAction.FIX_THEN_SWITCH
            }
        }

        // 5. Rate limit, quota, server errors, stream cutoff, timeouts -> SWITCH
        if (throwable is ModelError.RateLimited || throwable is ModelError.QuotaExceeded ||
            throwable is ModelError.Server || throwable is ModelError.Network ||
            throwable is ModelError.StreamCut || throwable is ModelError.FirstTokenTimeout ||
            throwable is SocketTimeoutException || throwable is IOException
        ) {
            return RouterAction.SWITCH
        }

        if (status in listOf(429, 402, 500, 502, 503, 504)) {
            return RouterAction.SWITCH
        }

        // Fallback default for unexpected model errors is to switch safely
        return RouterAction.SWITCH
    }
}
