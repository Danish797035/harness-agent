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
    /** Active status flag. False when signed out. */
    val signedIn: Boolean = true,
    /** Timestamp when session was saved or last validated. */
    val savedAtMs: Long = System.currentTimeMillis(),
) {
    fun signedOut(): GeminiWebSession = copy(signedIn = false, cookies = "", snlm0e = "")

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String?): GeminiWebSession? =
            text?.takeIf { it.isNotBlank() }?.let {
                runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull()
            }
    }
}
