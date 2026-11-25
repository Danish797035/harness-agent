package com.meenai.mina.browser

import com.meenai.mina.core.cap.web.WebUrlPolicy
import java.net.URLEncoder

/**
 * A sentence entered in the address bar and homepage search box → the URL to be opened.
 * - - Open it as it is with `://` (whether it can be opened will be judged by
 * [WebUrlPolicy.checkNavigable]);
 * - - localhost, IPv4, IPv6 in square brackets (can include port and path) add http://;
 * - - Like a domain name (English letters, numbers, and hyphens separated by dots, the last paragraph
 * starts with at least two letters or xn--, and can include ports and paths) add https://;
 * - - Use Bing to search for the rest (including spaces, Chinese, only one word, email, etc.).
 */
object AddressInput {
    const val SEARCH_PREFIX = "https://www.bing.com/search?q="

    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*://")
    private val LABEL = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?$")
    private val TLD = Regex("^([A-Za-z]{2,63}|xn--[A-Za-z0-9-]{1,59})$")
    private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")
    private val IPV6 = Regex("^\\[[0-9A-Fa-f:.]+]$")
    private val PORT = Regex("^\\d{1,5}$")

    /** null if the input is empty (only whitespace). */
    fun resolve(input: String): String? {
        val s = input.trim()
        if (s.isEmpty()) return null
        if (s.any { it.isWhitespace() }) return search(s)
        if (SCHEME.containsMatchIn(s)) return s
        val (host, port) = authority(s) ?: return search(s)
        if (port != null && (!PORT.matches(port) || port.toInt() !in 1..65535)) return search(s)
        return when {
            host.equals("localhost", ignoreCase = true) || IPV4.matches(host) || IPV6.matches(host) -> "http://$s"
            domain(host) -> "https://$s"
            else -> search(s)
        }
    }

    fun search(query: String): String = SEARCH_PREFIX + URLEncoder.encode(query.trim(), "UTF-8")

    /** When the URL is a Bing search result page, it is the search term, otherwise it is null. */
    fun searchQuery(url: String): String? {
        if (!url.startsWith(SEARCH_PREFIX)) return null
        val q = url.removePrefix(SEARCH_PREFIX).substringBefore('&').substringBefore('#')
        return runCatching { java.net.URLDecoder.decode(q, "UTF-8") }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** The host name and port from the beginning to the first /? #; if there are characters such as @ that do not resemble the host name, it is null. */
    private fun authority(s: String): Pair<String, String?>? {
        val auth = s.substringBefore('/').substringBefore('?').substringBefore('#')
        if (auth.isEmpty() || '@' in auth) return null
        if (auth.startsWith("[")) {
            val end = auth.indexOf(']')
            if (end < 0) return null
            val rest = auth.substring(end + 1)
            if (rest.isNotEmpty() && !rest.startsWith(":")) return null
            return auth.substring(0, end + 1) to rest.removePrefix(":").ifEmpty { null }
        }
        val parts = auth.split(':')
        return when (parts.size) {
            1 -> parts[0] to null
            2 -> parts[0] to parts[1]
            else -> null
        }
    }

    private fun domain(host: String): Boolean {
        val labels = host.split('.')
        if (labels.size < 2) return false
        return labels.all { LABEL.matches(it) } && TLD.matches(labels.last())
    }
}
