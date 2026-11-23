package com.meenai.mina.core.gemini

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Google Gemini Web (gemini.google.com) logged-in session credentials.
 * Stored encrypted in the device Keystore/cipher just like API keys and OAuth sessions.
 */
@Serializable
data class GeminiWebSession(
    /** Full Cookie header containing __Secure-1PSID, __Secure-1PSIDTS, etc. */
    val cookies: String,
    /** The "SNlM0e" XSRF/access token extracted from window.WIZ_global_data or page HTML. */
    val snlm0e: String,
    /** Frontend build label (e.g. "cfb2h") for the bl query parameter. */
    val cfb2h: String = "",
    /** Session ID (e.g. "FdrFJe") for the f.sid query parameter. */
    val fdrfje: String = "",
    /** Logged-in Google account email if known. */
    val email: String = "",
    /** "PI9WOb" server token from the Gemini page, sent as X-Server-Token. */
    val serverToken: String = "",
    /** "dSupif" Oxygen GraphQL origin, e.g. https://geminiweb-pa.clients6.google.com. */
    val oxygenUrl: String = "",
    /** "QrtxK" signed-in account index, sent as X-Goog-AuthUser when present. */
    val authUserIndex: String = "",
    /** "GruApd" hydrofoil Mendel flags for the GraphQL request. */
    val mendelFlags: String = "",
    /**
     * Exact User-Agent used when the cookies were obtained. Google's
     * `__Secure-1PSIDTS` cookie is bound to it, so every later request must
     * send the same string or the session is rejected immediately.
     */
    val userAgent: String = "",
    /** Account tier capacity from `GetUserStatus`, used in the model header. */
    val capacity: Int = 2,
    /** Where [capacity] sits in the model header (12 for normal, 13 for some tiers). */
    val capacityField: Int = 12,
    /** Hex id of the account's default model, from `GetUserStatus`. */
    val defaultModelId: String = "",
    /** Model number of [defaultModelId], mirrored into the request payload. */
    val defaultModelNumber: Int = 0,
    /** Active status flag. False when signed out. */
    val signedIn: Boolean = true,
    /**
     * Google rejected the signed-in request but guest traffic answers, so this
     * session speaks to Gemini as a guest. Set after a successful guest retry and
     * cleared by signing out (a fresh sign-in tries the real account again).
     */
    val guestMode: Boolean = false,
    /** Timestamp when session was saved or last validated. */
    val savedAtMs: Long = System.currentTimeMillis(),
) {
    fun signedOut(): GeminiWebSession = copy(
        signedIn = false,
        cookies = "",
        snlm0e = "",
        cfb2h = "",
        fdrfje = "",
        email = "",
        serverToken = "",
        oxygenUrl = "",
        authUserIndex = "",
        mendelFlags = "",
        userAgent = "",
        capacity = 2,
        capacityField = 12,
        defaultModelId = "",
        defaultModelNumber = 0,
        guestMode = false,
    )

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String?): GeminiWebSession? =
            text?.takeIf { it.isNotBlank() }?.let {
                runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull()
            }
    }
}
