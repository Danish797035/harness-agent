package com.meenai.mina.core.oauth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Random value used for PKCE (RFC 7636) and callback verification. It is newly generated every time you
 * log in and is not saved. The verification string uses base64url to write 32 random bytes (43
 * characters, falling between 43-128 required by the specification, and the characters are all within
 * the allowed range).
 */
object Pkce {
    private val random = SecureRandom()

    /** New verification string (code_verifier). */
    fun verifier(bytes: Int = 32): String = randomString(bytes)

    /** S256: SHA-256 of the check string, base64url without padding. */
    fun challenge(verifier: String): String =
        b64url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    /** The random string (state) used to identify whether this is the login is returned as it is in the callback. */
    fun state(): String = randomString(24)

    private fun randomString(bytes: Int): String = ByteArray(bytes).also(random::nextBytes).let(::b64url)

    private fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
}
