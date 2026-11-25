package com.meenai.mina.core.net

import java.net.IDN
import java.net.URI
import java.net.URLDecoder

/** The host name and site in the URL: "Which website is on" (screen reading, running status, knowledge matching, skill draft) are all counted according to it. */
object UrlHost {
    /** Common two-section public suffixes. */
    private val TWO_PART_SUFFIXES = setOf(
        "com.cn", "net.cn", "org.cn", "gov.cn", "edu.cn", "ac.cn",
        "com.hk", "com.tw", "com.mo", "com.sg", "com.au", "com.br",
        "co.uk", "org.uk", "ac.uk", "co.jp", "ne.jp", "or.jp", "co.kr", "co.in", "co.nz",
    )

    /** Hosting platform: Each user's subdomain name is a different site, and the platform domain name itself is used as a public suffix. */
    private val PRIVATE_SUFFIXES = setOf(
        "github.io", "vercel.app", "pages.dev", "netlify.app", "blogspot.com", "herokuapp.com",
        "amazonaws.com", "workers.dev", "web.app", "appspot.com", "firebaseapp.com",
    )

    /** Words often used as the second public suffix under national top-level domain names. */
    private val GENERIC_SECOND_LEVEL = setOf("co", "com", "net", "org", "gov", "edu", "ac", "or", "ne", "go")

    /** Lowercase ASCII domain name (internationalized domain name converted to xn-- form), remove the port and the www. at the beginning; if not parsed, it will be null. */
    fun of(url: String): String? = raw(url)?.removePrefix("www.")?.ifEmpty { null }

    /**
     * Whether the two addresses are the same site: the host name is the same (excluding www), or they
     * belong to the same registrable domain name that can be determined. Don't look at the protocol and
     * port. Uncertain suffixes will be judged as different sites.
     */
    fun sameSite(a: String, b: String): Boolean {
        val ha = of(a) ?: return false
        val hb = of(b) ?: return false
        if (ha == hb) return true
        val sa = registrable(ha) ?: return false
        return sa == registrable(hb)
    }

    internal fun raw(url: String): String? {
        val u = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        // The host name URI containing non-ASCII characters cannot resolve the host, so it is taken from
        // the authority.
        val h = u.host ?: u.rawAuthority?.let(::hostOfAuthority) ?: return null
        val ascii = runCatching { IDN.toASCII(h.trimEnd('.')) }.getOrNull()?.lowercase() ?: return null
        return ascii.ifEmpty { null }
    }

    private fun hostOfAuthority(authority: String): String? {
        val a = URLDecoder.decode(authority.substringAfterLast('@'), "UTF-8")
        if (a.startsWith("[")) return a.substringBefore(']') + "]"
        return a.substringBefore(':').ifEmpty { null }
    }

    /** The domain name can be registered (the public suffix plus a paragraph); it is null when the suffix is uncertain or the host name is the suffix itself. */
    fun registrable(host: String): String? {
        if (host.startsWith("[") || host.all { it.isDigit() || it == '.' }) return null
        val labels = host.split('.')
        if (labels.size < 2 || labels.any { it.isEmpty() }) return null
        fun tail(n: Int) = labels.takeLast(n).joinToString(".")
        val suffixLen = when {
            tail(2) in PRIVATE_SUFFIXES || tail(2) in TWO_PART_SUFFIXES -> 2
            labels.last().length == 2 && labels.size >= 2 &&
                (labels[labels.size - 2].length <= 3 || labels[labels.size - 2] in GENERIC_SECOND_LEVEL) -> 2
            else -> 1
        }
        if (labels.size <= suffixLen) return null
        return tail(suffixLen + 1)
    }
}
