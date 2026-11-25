package com.meenai.mina.core.remote

import com.meenai.mina.core.mcp.McpRpc
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest

/**
 * The door where the computer is connected: Each request passes here first, and then is handed over to
 * the protocol layer. The order is fixed: the browser (with Origin) will always give 403; the same
 * address authentication failed too many times, first 429; the access token is incorrect, 401. Routing
 * and error serialization are left to the ingress adapter. Tokens are compared according to constant
 * time; the number of failures is recorded according to the source of the request
 * ([HttpRequest.source]), [clock] is replaceable, and the time is set during the test. Correct tokens
 * will not be released during the cooling period: releasing them is equivalent to telling the guesser
 * "you guessed correctly this time". Cooling down cannot stop guessing tokens. Requests forwarded by the
 * gateway are recorded separately according to the caller seen by the gateway. An anonymous caller
 * guessing wrong only cools itself. [token] recognizes connections from anywhere; [loopbackToken] (via
 * USB computer connection) only recognizes connections from the local loopback.
 */
class RemoteAccess(
    private val token: () -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxFailures: Int = 5,
    private val windowMs: Long = 60_000,
    private val lockMs: Long = 60_000,
    private val loopbackToken: () -> String? = { null },
) {
    sealed interface Verdict {
        data object Pass : Verdict
        data class Reject(val status: Int, val message: String, val headers: Map<String, String> = emptyMap()) : Verdict
    }

    private class Failures(var count: Int, var since: Long, var lockedUntil: Long)
    private val failures = HashMap<String, Failures>()

    fun check(req: HttpRequest): Verdict {
        // The browser's request always carries Origin; the MCP client on the computer does not. Web
        // pages should not be able to access this port (anti-DNS rebinding)
        if (req.header("origin") != null) return Verdict.Reject(403, "Browser requests are not allowed")
        val who = req.source
        val now = clock()
        synchronized(failures) {
            val f = failures[who]
            if (f != null && f.lockedUntil > now) {
                val wait = ((f.lockedUntil - now + 999) / 1000).coerceAtLeast(1)
                return Verdict.Reject(429, "Too many failed attempts", mapOf("Retry-After" to wait.toString()))
            }
        }
        val given = req.header("authorization")?.let { h ->
            if (h.length > 7 && h.substring(0, 7).equals("Bearer ", ignoreCase = true)) h.substring(7).trim() else null
        }
        val loopback = if (req.remote.isLoopbackAddress) loopbackToken() else null
        // Compete with both, just because you faced the first one does not mean you compete once less.
        if (given == null || !(matches(given, token()) or matches(given, loopback))) {
            fail(who, now)
            return Verdict.Reject(401, "Missing or wrong access token", mapOf("WWW-Authenticate" to "Bearer"))
        }
        synchronized(failures) { failures.remove(who) }

        return Verdict.Pass
    }

    /** Local monitoring and remote forwarding share authentication and protocol errors; only after passing, the task layer is entered. */
    fun handle(req: HttpRequest, next: (HttpRequest) -> HttpResponse): HttpResponse = when (val verdict = check(req)) {
        is Verdict.Reject -> if (req.path == McpRpc.PATH)
            McpRpc.transportError(verdict.status, verdict.message, verdict.headers)
        else RemoteTaskApi.error(verdict.status, "access_denied", verdict.message, verdict.headers)
        Verdict.Pass -> next(req)
    }

    private fun matches(given: String, expected: String?) = !expected.isNullOrEmpty() && same(given, expected)

    private fun fail(who: String, now: Long) = synchronized(failures) {
        val f = failures.getOrPut(who) { Failures(0, now, 0) }
        if (now - f.since > windowMs) { f.count = 0; f.since = now }
        f.count++
        if (f.count >= maxFailures) { f.lockedUntil = now + lockMs; f.count = 0; f.since = now }
        // If there are too many addresses, only the most recent one will be kept, and the table will not
        // be infinitely long.
        if (failures.size > 256) failures.entries.minByOrNull { it.value.since }?.let { failures.remove(it.key) }
    }

    companion object {
        /** Constant time comparison: First digest them into equal lengths, and then compare them byte by byte. The time consumption does not change with the number of characters faced before. */
        fun same(a: String, b: String): Boolean {
            val d = MessageDigest.getInstance("SHA-256")
            val x = d.digest(a.toByteArray(Charsets.UTF_8))
            val y = d.digest(b.toByteArray(Charsets.UTF_8))
            return MessageDigest.isEqual(x, y)
        }

        /**
         * Whether to accept connections from this address: local loopback, LAN private segment (10/8,
         * 172.16/12, 192.168/16), carrier-grade NAT segment commonly used in private networking
         * (100.64/10), IPv4 link local, and IPv6 only local (fc00::/7) and link local (fe80::/10).
         * Public network addresses (such as when using mobile data) will not be accepted.
         */
        fun privateAddress(a: InetAddress): Boolean {
            val v4 = when (a) {
                is Inet4Address -> a.address
                is Inet6Address -> a.address.let { b ->
                    // The writing method of mapping IPv4 to IPv6 (::ffff:a.b.c.d) is as follows: IPv4
                    if ((0 until 10).all { b[it] == 0.toByte() } && b[10] == 0xff.toByte() && b[11] == 0xff.toByte()) b.copyOfRange(12, 16) else null
                }
                else -> null
            }
            if (v4 != null) {
                val o0 = v4[0].toInt() and 0xff
                val o1 = v4[1].toInt() and 0xff
                return o0 == 127 || o0 == 10 || (o0 == 172 && o1 in 16..31) || (o0 == 192 && o1 == 168) ||
                    (o0 == 100 && o1 in 64..127) || (o0 == 169 && o1 == 254)
            }
            if (a.isLoopbackAddress) return true
            val b0 = a.address[0].toInt() and 0xff
            val b1 = a.address[1].toInt() and 0xff
            return (b0 and 0xfe) == 0xfc || (b0 == 0xfe && (b1 and 0xc0) == 0x80)
        }
    }
}
