package com.meenai.mina.core.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Is the address on the local machine or local area network? Only look at the address itself, without
 * domain name resolution.
 * - - [isLocal] (strict): This machine (localhost, 127.0.0.0/8,::1), private network segment (10/8,
 * 172.16/12, 192.168/16, IPv6 fc00::/7), Link local (169.254/16, fe80::/10) and `.local` name. Used to
 * determine whether plaintext http is safe or not.
 * - - [bypassesProxy] (wider): The address that is still directly connected when using a network proxy -
 * in addition to [isLocal], there are also host names without dots (machine names in the LAN), Commonly
 * used suffixes for LAN (`.lan`, `.home.arpa`, `.internal`) and the shared network segment of
 * carrier-grade NAT 100.64/10 (networking tools often divide devices into this segment). Most of the
 * model service agents running on this machine and LAN cannot reach them, so they are connected
 * directly.
 */
object LocalHosts {
    fun isLocal(host: String): Boolean {
        val h = normalize(host)
        if (h.isEmpty()) return false
        if (h == "localhost" || h.endsWith(".localhost") || h.endsWith(".local")) return true
        ipv4(h)?.let { (a, b) ->
            return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
        }
        if (':' in h) {
            if (h == "::1") return true
            val first = h.substringBefore(':').toIntOrNull(16) ?: return false
            return first and 0xfe00 == 0xfc00 || first and 0xffc0 == 0xfe80
        }
        return false
    }

    /** When using a network proxy, the host is still directly connected. */
    fun bypassesProxy(host: String): Boolean {
        if (isLocal(host)) return true
        val h = normalize(host)
        if (h.isEmpty()) return false
        if (PROXY_SUFFIXES.any { h.endsWith(it) }) return true
        ipv4(h)?.let { (a, b) -> return a == 100 && b in 64..127 }
        if (':' in h) return false
        return '.' !in h
    }

    /** Plain text http, address that is not on the local machine or LAN: Key and conversation will be transmitted unencrypted on the Internet. Addresses you don't recognize don't count. */
    fun unencryptedRemote(url: String): Boolean {
        val u = url.trim().toHttpUrlOrNull() ?: return false
        return !u.isHttps && !isLocal(u.host)
    }

    private val PROXY_SUFFIXES = listOf(".lan", ".home.arpa", ".internal")

    /**
     * The browser's direct connection rules (the same set of [bypassesProxy], written in a format
     * recognized by the browser kernel); the local address kernel is originally directly connected, and
     * the host name without a dot is managed by "simple host name direct connection".
     */
    val BYPASS_RULES: List<String> = listOf(
        "localhost", "*.localhost", "*.local", "*.lan", "*.home.arpa", "*.internal",
        "127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "100.64.0.0/10",
        "fc00::/7", "fe80::/10",
    )

    /** No_proxy for command line tools: host, suffix and network segment separated by commas (tools that do not recognize network segments will recognize at least the first three). */
    const val NO_PROXY = "localhost,127.0.0.1,::1,127.0.0.0/8,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16,169.254.0.0/16,100.64.0.0/10," +
        "fc00::/7,fe80::/10,.localhost,.local,.lan,.home.arpa,.internal"

    private fun normalize(host: String) = host.trim().trimStart('[').trimEnd(']').trimEnd('.').lowercase()

    /** The first two segments of IPv4 dotted into four segments; null if not IPv4. */
    private fun ipv4(h: String): Pair<Int, Int>? {
        val parts = h.split('.')
        if (parts.size != 4) return null
        val n = parts.map { p -> p.toIntOrNull()?.takeIf { p.isNotEmpty() && p.all(Char::isDigit) && it in 0..255 } ?: return null }
        return n[0] to n[1]
    }
}
