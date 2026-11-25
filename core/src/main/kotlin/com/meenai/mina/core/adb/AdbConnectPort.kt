package com.meenai.mina.core.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** The last connected wireless debugging port (App private storage). */
interface PortCache {
    fun load(): Int?
    fun save(port: Int)
}

/**
 * A search for the connection port gives several candidates from each source (excluding the port itself,
 * used for diagnostic information). Scan the machine when [scanned] is true: [scanOpen] ports are open,
 * among which [scanAdb] are like adbd; [manual] is the port filled in by the user this time.
 */
data class ConnectCandidates(
    val property: Int,
    val mdns: Int,
    val cache: Int,
    val connected: Boolean,
    val scanned: Boolean = false,
    val scanOpen: Int = 0,
    val scanAdb: Int = 0,
    val manual: Boolean = false,
)

/**
 * Find the wireless debugging connection port of this machine and connect it. Try in order, and stop if
 * the connection is successful: the last connected port, system properties (use only if they can be
 * read), each one found by mDNS, scan the local port ([scan], scan only when none of the previous ones
 * work), and the port filled in by the user on the boot page ([manual]). Relying on mDNS alone will not
 * work: adbd only registers the service once when wireless debugging is turned on. The registration is
 * gone after the mDNS daemon is restarted, and some networks still block multicast; mDNS may also detect
 * old ports that have expired at the same time, so try several of them. The cached port is rejected
 * quickly when it expires, so it ranks first. After the user fills in the port, he will not scan it
 * (before he was asked to fill it in because he could not scan it), and he will try it directly.
 * Rejection of TLS (UNAUTHORIZED, TLS_FAILED) does not necessarily mean that the key is incorrect: some
 * systems allow another service to receive the first TLS handshake of the same port first, rejecting any
 * certificate, and then the next connection falls to adbd. So try again with the same port every
 * [tlsRetryDelayMs], and if it still doesn't work, try other candidates; only if you can't connect after
 * trying it all, and some ports reject us like this, will it report UNAUTHORIZED (re-pairing is
 * required). If a port is already rejected, we will no longer scan the local port: if adbd finds it, it
 * will only be scanned out.
 */
class AdbConnectPort(
    private val property: () -> Int?,
    private val finder: AdbServiceFinder,
    private val cache: PortCache,
    private val mdnsTimeoutMs: Long = 3_000,
    /** Each time the connection ends (connection or failure), the number of candidates from each source is reported. */
    private val report: (ConnectCandidates) -> Unit = {},
    private val scan: LocalPortScan? = null,
    /** The port filled in by the user this time; if not filled in, it will be null. */
    private val manual: () -> Int? = { null },
    /** It will report once when starting and ending scanning the local port (the interface displays "Searching on this machine..."). */
    private val scanning: (Boolean) -> Unit = {},
    private val localHost: String = LOCALHOST,
    private val tlsRetryDelayMs: Long = 300,
) {
    /** Use [open] to try the candidates in sequence and return the first connected result; if none of them can be connected, it will throw UNAUTHORIZED indicating that the port rejected us, otherwise it will throw the last error. */
    suspend fun <T> connect(open: (host: String, port: Int) -> T): T {
        val tried = HashSet<AdbEndpoint>()
        val triedPorts = HashSet<Int>()
        var last: AdbConnectionException? = null
        var rejected: AdbConnectionException? = null
        val manualPort = manual()?.takeIf { it in 1..65535 }
        var scanResult: ScanResult? = null
        fun local(vararg ports: Int?) = ports.filterNotNull().map { AdbEndpoint(localHost, it) }
        val sources: List<suspend () -> List<AdbEndpoint>> = listOf(
            { local(cache.load()) },
            { local(property()) },
            { finder.findPorts(AdbServiceTypes.CONNECT, mdnsTimeoutMs).map { AdbEndpoint(localHost, it) } },
            {
                if (scan == null || manualPort != null || rejected != null) emptyList() else {
                    scanning(true)
                    try {
                        scan.scan(triedPorts).also { scanResult = it }.endpoints
                    } finally {
                        scanning(false)
                    }
                }
            },
            { local(manualPort) },
        )
        val counts = IntArray(sources.size)
        var connected = false
        try {
            for ((i, source) in sources.withIndex()) {
                // Sources that cannot be read (attributes are blocked, mDNS cannot be started) are
                // treated as non-existent and do not count as failures.
                val endpoints = try {
                    source()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
                counts[i] = endpoints.size
                for (ep in endpoints) {
                    if (ep.port !in 1..65535 || !tried.add(ep)) continue
                    triedPorts += ep.port
                    var retried = false
                    while (true) {
                        try {
                            return open(ep.host, ep.port).also { cache.save(ep.port); connected = true }
                        } catch (e: AdbConnectionException) {
                            val tls = e.reason == AdbConnectionException.Reason.UNAUTHORIZED ||
                                e.reason == AdbConnectionException.Reason.TLS_FAILED
                            if (tls && !retried) {
                                retried = true
                                delay(tlsRetryDelayMs)
                                continue
                            }
                            if (e.reason == AdbConnectionException.Reason.UNAUTHORIZED) rejected = e
                            last = e
                            break
                        }
                    }
                }
            }
            throw rejected ?: last ?: AdbConnectionException(AdbConnectionException.Reason.CONNECT_FAILED, "no wireless debugging port found")
        } finally {
            val s = scanResult
            report(
                ConnectCandidates(
                    property = counts[1], mdns = counts[2], cache = counts[0], connected = connected,
                    scanned = s != null, scanOpen = s?.open ?: 0, scanAdb = s?.adb ?: 0, manual = manualPort != null,
                ),
            )
        }
    }

    companion object {
        const val LOCALHOST = "127.0.0.1"
    }
}
