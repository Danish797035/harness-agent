package com.meenai.mina.core.cap.web

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Which addresses can be obtained for downloading: first pass [WebUrlPolicy.checkNavigable] (same rule
 * as web_navigate), and then disable local, LAN and reserved addresses. When downloading, the app itself
 * sends a request in the background, and the page can use it to access the device on the local area
 * network where the phone is located, so it is more stringent than opening a web page. Every jump and
 * every domain name resolution is checked according to this rule ([guardedDns] blocks domain names
 * resolved to the intranet).
 */
object DownloadAddressPolicy {
    /** The reason why it cannot be taken; it can be taken as null. */
    fun refusal(url: String): String? {
        WebUrlPolicy.checkNavigable(url)?.let { return it }
        val host = url.toHttpUrlOrNull()?.host ?: return "not a web address"
        return if (blockedHost(host)) "local or private network address" else null
    }

    /** The name of this machine or LAN, or literally the intranet/reserved IP. Ordinary domain names will be determined by [guardedDns] after resolution. */
    fun blockedHost(host: String): Boolean {
        val h = host.trim().trimEnd('.').lowercase().removePrefix("[").removeSuffix("]")
        if (h.isEmpty() || h == "localhost" || LOCAL_SUFFIXES.any { h.endsWith(it) }) return true
        if (!IPV4.matches(h) && ':' !in h) return false
        // Literal IP:getByName does not check DNS
        val a = runCatching { InetAddress.getByName(h) }.getOrNull() ?: return true
        return blockedAddress(a)
    }

    fun blockedAddress(a: InetAddress): Boolean {
        if (a.isAnyLocalAddress || a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress || a.isMulticastAddress) return true
        val b = a.address
        return when (a) {
            is Inet4Address -> blockedV4(b)
            is Inet6Address -> {
                val u0 = b[0].toInt() and 0xff
                // fc00::/7 unique local;::ffff:a.b.c.d and 64:ff9b::/96 embedded IPv4 are judged as IPv4
                (u0 and 0xfe) == 0xfc ||
                    (b.copyOfRange(0, 10).all { it == 0.toByte() } && b[10] == 0xff.toByte() && b[11] == 0xff.toByte() && blockedV4(b.copyOfRange(12, 16))) ||
                    (b.copyOfRange(0, 12).contentEquals(NAT64) && blockedV4(b.copyOfRange(12, 16)))
            }
            else -> true
        }
    }

    private fun blockedV4(b: ByteArray): Boolean {
        val o0 = b[0].toInt() and 0xff
        val o1 = b[1].toInt() and 0xff
        return o0 == 0 || o0 == 10 || o0 == 127 || o0 >= 224 ||
            (o0 == 100 && o1 in 64..127) || (o0 == 169 && o1 == 254) || (o0 == 172 && o1 in 16..31) ||
            (o0 == 192 && o1 == 168) || (o0 == 192 && o1 == 0 && (b[2].toInt() and 0xff) == 0) || (o0 == 198 && o1 in 18..19)
    }

    /** If one of the resolved addresses is an intranet/reserved address, the entire address will be rejected ([BlockedAddress]). */
    fun guardedDns(delegate: Dns = Dns.SYSTEM): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> {
            if (blockedHost(hostname)) throw BlockedAddress(hostname)
            val all = delegate.lookup(hostname)
            if (all.any(::blockedAddress)) throw BlockedAddress(hostname)
            return all
        }
    }

    class BlockedAddress(host: String) : UnknownHostException("$host resolves to a local or private network address")

    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val LOCAL_SUFFIXES = listOf(".localhost", ".local", ".lan", ".home.arpa", ".internal", ".intranet")
    private val NAT64 = byteArrayOf(0, 0x64, 0xff.toByte(), 0x9b.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
}
