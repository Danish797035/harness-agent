package com.meenai.mina.core.cap.web

import com.meenai.mina.core.tool.PACKAGE_NAME_RE
import java.net.URI
import java.net.URLDecoder
import com.meenai.mina.core.net.UrlHost

/** The page wants to jump to the link of the App; [packageName] is only available if it can be parsed from the link and is a legal package name. */
data class ExternalLink(val url: String, val packageName: String?)

/** URL rules: what can be opened, which are links to jump to the app, and whether the two addresses are the same site. */
object WebUrlPolicy {
    /** Browser's own protocol: neither navigation nor app jumping. */
    private val BROWSER_SCHEMES = setOf("http", "https", "file", "content", "javascript", "data", "about", "blob")

    private val SCHEME_RE = Regex("^[A-Za-z][A-Za-z0-9+.-]*$")

    /** The web address of the app store (host, path prefix): the purpose of opening them is to install or open apps. */
    private val STORE_PAGES = listOf(
        "play.google.com" to "/store/apps",
        "market.android.com" to "/details",
        "apps.apple.com" to "/",
        "itunes.apple.com" to "/",
    )

    /** null = can be opened; otherwise it is an invalid_arguments description given to the model. */
    fun checkNavigable(url: String): String? {
        val u = url.trim()
        if (u.isEmpty()) return "the URL is empty"
        val scheme = scheme(u) ?: return "the URL must start with http:// or https://"
        if (external(u) != null) return "this is a link that opens an app, so it is not opened in the browser; when the task needs that app, open it on the phone"
        if (scheme != "http" && scheme != "https") return "only http/https URLs can be opened, not $scheme:"
        if (host(u) == null) return "the URL has no domain"
        return null
    }

    /** intent:, custom protocol, app store link (including store web page) → link to jump to App; the protocol between ordinary web pages and the browser itself is null. */
    fun external(url: String): ExternalLink? {
        val u = url.trim()
        val scheme = scheme(u) ?: return null
        when (scheme) {
            "intent" -> {
                val pkg = u.substringAfter("#Intent;", "").split(';')
                    .firstOrNull { it.startsWith("package=") }?.removePrefix("package=")
                return ExternalLink(u, validPackage(pkg))
            }
            "market" -> return ExternalLink(u, validPackage(queryParam(u, "id")))
            "http", "https" -> {
                val h = host(u) ?: return null
                val path = runCatching { URI(u).rawPath }.getOrNull().orEmpty().ifEmpty { "/" }
                if (STORE_PAGES.none { (sh, prefix) -> h == sh && path.startsWith(prefix) }) return null
                return ExternalLink(u, validPackage(queryParam(u, "id")))
            }
        }
        return if (scheme in BROWSER_SCHEMES) null else ExternalLink(u, null)
    }

    /** Lowercase ASCII domain name (internationalized domain name converted to xn-- form), remove the port and the www. at the beginning; if not parsed, it will be null. */
    fun host(url: String): String? = UrlHost.of(url)

    /** Whether the two addresses are on the same site, see [UrlHost.sameSite]. */
    fun sameSite(a: String, b: String): Boolean = UrlHost.sameSite(a, b)

    /** Registerable domain name, see [UrlHost.registrable]. */
    fun registrable(host: String): String? = UrlHost.registrable(host)

    /** Same source: the protocol, host name, and port are all the same (the default port is completed according to the protocol). */
    fun sameOrigin(a: String, b: String): Boolean {
        val oa = origin(a) ?: return false
        return oa == origin(b)
    }

    private fun origin(url: String): Triple<String, String, Int>? {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase() ?: return null
        val h = UrlHost.raw(url) ?: return null
        val port = u.port.takeIf { it >= 0 } ?: portOf(u.rawAuthority) ?: when (scheme) { "http" -> 80; "https" -> 443; else -> -1 }
        return Triple(scheme, h, port)
    }

    private fun portOf(authority: String?): Int? =
        authority?.substringAfterLast('@')?.substringAfterLast(']')?.substringAfter(':', "")?.toIntOrNull()

    private fun validPackage(raw: String?): String? {
        val p = raw?.let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrNull() }?.trim() ?: return null
        return p.takeIf(PACKAGE_NAME_RE::matches)
    }

    /** Protocol names (lowercase); "example.com:8080" and "localhost:3000" that do not include protocols are not counted. */
    private fun scheme(url: String): String? {
        val i = url.indexOf(':')
        if (i <= 0) return null
        val s = url.substring(0, i)
        if (!SCHEME_RE.matches(s) || !s.all { it.code < 128 }) return null
        val rest = url.substring(i + 1)
        if ('.' in s || (rest.isNotEmpty() && rest.takeWhile { it != '/' }.all(Char::isDigit) && rest.first().isDigit())) return null
        return s.lowercase()
    }

    private fun queryParam(url: String, name: String): String? =
        url.substringAfter('?', "").substringBefore('#').split('&')
            .firstOrNull { it.startsWith("$name=") }?.removePrefix("$name=")?.takeIf { it.isNotBlank() }
}
