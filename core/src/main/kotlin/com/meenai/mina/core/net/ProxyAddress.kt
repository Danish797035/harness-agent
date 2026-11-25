package com.meenai.mina.core.net

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI

/**
 * A network proxy address, in the form of `protocol://[user:password@]host:port`: the protocol is one of http, https,
 * socks5, and socks5h, without a path behind it. [toString] does not have an account password, and it
 * will not be leaked when written in the log or displayed on the interface.
 */
class ProxyAddress private constructor(
    val scheme: String,
    /** Hostname or IP; IPv6 without square brackets. */
    val host: String,
    val port: Int,
    val user: String,
    val password: String,
) {
    val socks: Boolean get() = scheme == "socks5" || scheme == "socks5h"
    val hasCredentials: Boolean get() = user.isNotEmpty()

    /**
     * Can Mina's own connection (model adjustment, update, built-in browser) be able to use it? The
     * https proxy must first master TLS with the proxy itself. The network library used for these
     * connections does not support it. Only the commands in the container (curl, etc.) can be used.
     */
    val forApp: Boolean get() = scheme != "https"

    fun javaProxy(): Proxy = Proxy(if (socks) Proxy.Type.SOCKS else Proxy.Type.HTTP, InetSocketAddress.createUnresolved(host, port))

    /** Written for people to see: protocol, host, port, without account and password. */
    val display: String get() = "$scheme://${if (host.contains(':')) "[$host]" else host}:$port"

    override fun toString(): String = display

    companion object {
        val SCHEMES = setOf("http", "https", "socks5", "socks5h")

        /** Parse the proxy address; if the string is empty or written incorrectly, it will be null. */
        fun parse(text: String): ProxyAddress? {
            val v = text.trim()
            if (v.isEmpty() || v.any { it.isWhitespace() }) return null
            val u = runCatching { URI(v) }.getOrNull() ?: return null
            val scheme = u.scheme?.lowercase() ?: return null
            if (scheme !in SCHEMES) return null
            val host = u.host?.removeSurrounding("[", "]")?.takeIf { it.isNotEmpty() } ?: return null
            if (u.port !in 1..65535) return null
            if (!(u.rawPath.isNullOrEmpty() || u.rawPath == "/") || u.rawQuery != null || u.rawFragment != null) return null
            val info = u.userInfo.orEmpty()
            val user = info.substringBefore(':')
            val password = if (info.contains(':')) info.substringAfter(':') else ""
            return ProxyAddress(scheme, host, u.port, user, password)
        }

        /** Empty string (no proxy), or a proxy address that can be resolved. */
        fun valid(text: String): Boolean = text.isBlank() || parse(text) != null
    }
}
