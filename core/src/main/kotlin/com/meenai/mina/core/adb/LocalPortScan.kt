package com.meenai.mina.core.adb

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.SocketChannel
import java.util.TreeSet
import java.util.concurrent.ConcurrentHashMap

/** A candidate for the connection port: which address and port of the local machine. */
data class AdbEndpoint(val host: String, val port: Int)

/** Mina's own listening port opened on the local machine (such as login callback): skip it when scanning the port to avoid connecting to herself. */
object OwnListeningPorts {
    private val ports = ConcurrentHashMap.newKeySet<Int>()
    fun add(port: Int) { ports += port }
    fun remove(port: Int) { ports -= port }
    fun snapshot(): Set<Int> = ports.toSet()
}

/** Scan the underlying action of the local port; replace it with a fake one in the test. */
interface LocalPortScanner {
    /** Which ones in [ports] on [host] can establish TCP connections (in ascending order), skip [skip]; stop after [budgetMs], and use the ones found. */
    suspend fun openPorts(host: String, ports: IntRange, skip: Set<Int>, budgetMs: Long): List<Int>

    /** Only when you send CNXN and the other party responds to STLS is wireless debugging adbd (other services will not even respond to STLS themselves). */
    fun speaksAdbTls(host: String, port: Int, timeoutMs: Int): Boolean
}

/** The result of a local scan: several ports are open and several are like adbd (diagnostic information only records the number). */
data class ScanResult(val endpoints: List<AdbEndpoint>, val open: Int, val adb: Int)

/**
 * The last resort to find the connection port: when the previous sources do not provide a port that can
 * be connected, scan the machine's own address. The wireless debugging adbd monitors a port randomly
 * allocated by the system, which falls within the temporary port range ([range], if it cannot be read,
 * use [FALLBACK_RANGE]). Scan addresses one by one in the order of [hosts] (first 127.0.0.1, then the
 * local Wi-Fi address; adbd may only bind one of them). Each open port sends CNXN to see if STLS is
 * returned; if there is an address like adbd, it will not scan the next one. Only scan this machine and
 * do not touch other devices. The total of scanning and verification does not exceed [budgetMs].
 */
class LocalPortScan(
    private val scanner: LocalPortScanner,
    private val hosts: () -> List<String>,
    private val range: () -> IntRange = { FALLBACK_RANGE },
    private val ownPorts: () -> Set<Int> = OwnListeningPorts::snapshot,
    private val budgetMs: Long = 10_000,
    private val probeTimeoutMs: Int = 1_000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Scan it again; [skip] is the port that the previous source has tried. */
    suspend fun scan(skip: Set<Int>): ScanResult {
        val deadline = now() + budgetMs
        val ports = range()
        val excluded = skip + ownPorts()
        val found = ArrayList<AdbEndpoint>()
        var open = 0
        for (host in hosts().distinct()) {
            val left = deadline - now()
            if (left <= 0) break
            val openPorts = scanner.openPorts(host, ports, excluded, left)
            open += openPorts.size
            for (port in openPorts) {
                if (now() >= deadline) break
                if (scanner.speaksAdbTls(host, port, probeTimeoutMs)) found += AdbEndpoint(host, port)
            }
            if (found.isNotEmpty()) break
        }
        return ScanResult(found, open, found.size)
    }

    companion object {
        /** Scan this section when you cannot read the system's temporary port range (covering common settings in each version). */
        val FALLBACK_RANGE = 30_000..65_535

        /**
         * Parse `/proc/sys/net/ipv4/ip_local_port_range` (two numbers, separated by blanks). Use
         * [FALLBACK_RANGE] when it cannot be read, the format is wrong, or the range is unreasonable.
         */
        fun parseRange(text: String?): IntRange {
            val nums = text?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toIntOrNull() } ?: return FALLBACK_RANGE
            if (nums.size != 2) return FALLBACK_RANGE
            val (lo, hi) = nums
            return if (lo in 1024..65_535 && hi in lo..65_535) lo..hi else FALLBACK_RANGE
        }
    }
}

/**
 * Real scan: java.nio non-blocking connection, at most [concurrency] connections at the same time, each
 * one can wait at most [connectTimeoutMs]. Ports that cannot be connected to the local address will be
 * rejected immediately. Scan the entire section for a few seconds. Run on the IO thread and stop when
 * the coroutine is cancelled.
 */
class NioPortScanner(
    private val concurrency: Int = 200,
    private val connectTimeoutMs: Long = 150,
) : LocalPortScanner {
    override suspend fun openPorts(host: String, ports: IntRange, skip: Set<Int>, budgetMs: Long): List<Int> = withContext(Dispatchers.IO) {
        val ctx = currentCoroutineContext()
        sweep(InetAddress.getByName(host), ports, skip, System.currentTimeMillis() + budgetMs) { ctx.isActive }
    }

    private fun sweep(addr: InetAddress, ports: IntRange, skip: Set<Int>, deadline: Long, active: () -> Boolean): List<Int> {
        val open = TreeSet<Int>()
        val next = ports.iterator()
        Selector.open().use { selector ->
            val startedAt = HashMap<SelectionKey, Long>()
            try {
                while ((next.hasNext() || startedAt.isNotEmpty()) && System.currentTimeMillis() < deadline && active()) {
                    while (next.hasNext() && startedAt.size < concurrency) {
                        val port = next.nextInt()
                        if (port in skip) continue
                        val ch = SocketChannel.open()
                        try {
                            ch.configureBlocking(false)
                            if (ch.connect(InetSocketAddress(addr, port))) {
                                if (!selfConnected(ch, port)) open += port
                                ch.close()
                            } else {
                                startedAt[ch.register(selector, SelectionKey.OP_CONNECT, port)] = System.currentTimeMillis()
                            }
                        } catch (e: IOException) {
                            ch.close()
                        }
                    }
                    selector.select(SELECT_MS)
                    for (key in selector.selectedKeys()) {
                        val ch = key.channel() as SocketChannel
                        val ok = try { ch.finishConnect() } catch (e: IOException) { false }
                        if (ok && !selfConnected(ch, key.attachment() as Int)) open += key.attachment() as Int
                        startedAt.remove(key)
                        key.cancel()
                        ch.close()
                    }
                    selector.selectedKeys().clear()
                    val t = System.currentTimeMillis()
                    val late = startedAt.filterValues { t - it > connectTimeoutMs }.keys
                    for (key in late) {
                        startedAt.remove(key)
                        key.cancel()
                        key.channel().close()
                    }
                }
            } finally {
                for (key in startedAt.keys) runCatching { key.channel().close() }
            }
        }
        return open.toList()
    }

    /** When scanning the temporary port range, the system may happen to assign the same local port to this connection, so it connects itself. */
    private fun selfConnected(ch: SocketChannel, port: Int): Boolean = runCatching {
        val s = ch.socket()
        s.localPort == port && s.localAddress == s.inetAddress
    }.getOrDefault(false)

    override fun speaksAdbTls(host: String, port: Int, timeoutMs: Int): Boolean = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), timeoutMs)
            if (s.localPort == port && s.localAddress == s.inetAddress) return false
            s.soTimeout = timeoutMs
            s.getOutputStream().apply {
                write(AdbMessage(AdbMessage.CNXN, AdbMessage.VERSION, AdbMessage.MAX_PAYLOAD, HOST_BANNER).encode())
                flush()
            }
            AdbMessage.read(DataInputStream(s.getInputStream()), maxData = 4096)?.command == AdbMessage.STLS
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private companion object {
        const val SELECT_MS = 20L
        val HOST_BANNER = "host::\u0000".toByteArray(Charsets.US_ASCII)
    }
}
