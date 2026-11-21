package com.meenai.mina.core.gemini

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * Keeps the Google cookies of the signed-in Gemini Web session up to date.
 *
 * Google rotates `__Secure-1PSIDTS` (and adds other short-lived cookies) on
 * almost every response. The web model sends the cookie header saved at login
 * time, so if the rotated values are dropped the session starts looking stale
 * after a few minutes and every reply ends in "sign in again". This jar merges
 * the `Set-Cookie` headers into one header string; the model writes the result
 * back to the saved session (see [GeminiWebClient.cookieSnapshot]).
 */
internal class GeminiCookieJar : CookieJar {

    private val cookies = LinkedHashMap<String, Cookie>()
    private var seeded: String? = null

    /** Replace the whole jar with the cookies of a saved session (login, sign-out). */
    @Synchronized
    fun seed(header: String) {
        val clean = GeminiWebClient.sanitizeCookieHeader(header)
        if (clean == seeded) return
        seeded = clean
        cookies.clear()
        for (part in clean.split(';')) {
            val name = part.substringBefore('=', "").trim()
            if (name.isEmpty()) continue
            val value = part.substringAfter('=', "").trim()
            cookies[name] = cookie(name, value)
        }
    }

    /** Current cookies as one `Cookie:` header value. Empty when nothing is seeded. */
    @Synchronized
    fun snapshot(): String = cookies.values.joinToString("; ") { "${it.name}=${it.value}" }

    @Synchronized
    fun clear() {
        cookies.clear()
        seeded = null
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        synchronized(this) {
            val now = System.currentTimeMillis()
            for (cookie in cookies) {
                if (cookie.expiresAt <= now) this.cookies.remove(cookie.name) else this.cookies[cookie.name] = cookie
            }
        }
    }

    // The model sends the header itself (and the tests use their own client), so
    // handing every saved cookie back is what keeps a rotated token alive.
    override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(this) { cookies.values.toList() }

    private fun cookie(name: String, value: String): Cookie = Cookie.Builder()
        .name(name)
        .value(value)
        .domain("gemini.google.com")
        .path("/")
        .build()
}
