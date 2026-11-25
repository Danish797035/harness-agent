package com.meenai.mina.core.adb

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class LocalPortScanTest {
    @Test
    fun parsesTheSystemPortRangeOrFallsBack() {
        assertEquals(32768..60999, LocalPortScan.parseRange("32768\t60999\n"))
        assertEquals(40000..40000, LocalPortScan.parseRange(" 40000 40000 "))
        val fallback = LocalPortScan.FALLBACK_RANGE
        assertEquals(30000..65535, fallback)
        for (bad in listOf(null, "", "abc", "32768", "1 2 3", "60999 32768", "80 9000", "32768 70000")) {
            assertEquals(bad, fallback, LocalPortScan.parseRange(bad))
        }
    }

    /** Each scan and check makes the fake clock go forward: it stops when the budget is used up, and the next address is not scanned and the remaining ports are not checked. */
    private class SlowScanner(val clock: LongArray, val sweepMs: Long, val probeMs: Long, val open: List<Int>) : LocalPortScanner {
        val swept = mutableListOf<String>()
        val probed = mutableListOf<Int>()
        var range: IntRange? = null
        var skip: Set<Int> = emptySet()
        override suspend fun openPorts(host: String, ports: IntRange, skip: Set<Int>, budgetMs: Long): List<Int> {
            swept += host
            range = ports
            this.skip = skip
            clock[0] += minOf(sweepMs, budgetMs)
            return open.filter { it !in skip }
        }
        override fun speaksAdbTls(host: String, port: Int, timeoutMs: Int): Boolean {
            probed += port
            clock[0] += probeMs
            return false
        }
    }

    @Test
    fun timeCapStopsProbingAndSkipsTheNextHost() = runTest {
        val clock = longArrayOf(0)
        val scanner = SlowScanner(clock, sweepMs = 6_000, probeMs = 1_500, open = listOf(40001, 40002, 40003, 40004, 40005))
        val scan = LocalPortScan(scanner, { listOf("127.0.0.1", "192.0.2.10") }, budgetMs = 10_000, now = { clock[0] })
        val r = scan.scan(emptySet())
        assertEquals(listOf("127.0.0.1"), scanner.swept)
        // 6 s scanning + 3 times of 1.5 s verification will reach 10.5 s, and the rest will not be
        // verified.
        assertEquals(listOf(40001, 40002, 40003), scanner.probed)
        assertEquals(ScanResult(emptyList(), open = 5, adb = 0), r)
        assertTrue(clock[0] <= 10_500)
    }

    @Test
    fun bothHostsScannedWithinBudgetWhenNothingAnswers() = runTest {
        val clock = longArrayOf(0)
        val scanner = SlowScanner(clock, sweepMs = 2_000, probeMs = 10, open = listOf(40001))
        val r = LocalPortScan(scanner, { listOf("127.0.0.1", "192.0.2.10", "127.0.0.1") }, now = { clock[0] }).scan(emptySet())
        assertEquals(listOf("127.0.0.1", "192.0.2.10"), scanner.swept)
        assertEquals(2, r.open)
        assertEquals(0, r.adb)
    }

    @Test
    fun usesTheGivenRangeAndSkipsTriedAndOwnPorts() = runTest {
        val clock = longArrayOf(0)
        val scanner = SlowScanner(clock, sweepMs = 1, probeMs = 1, open = listOf(40001, 40002, 40003))
        val scan = LocalPortScan(scanner, { listOf("127.0.0.1") }, range = { 32768..60999 }, ownPorts = { setOf(40002) }, now = { clock[0] })
        scan.scan(setOf(40001))
        assertEquals(32768..60999, scanner.range)
        assertEquals(setOf(40001, 40002), scanner.skip)
        assertEquals(listOf(40003), scanner.probed)
    }

    @Test
    fun ownListeningPortsAreTracked() {
        OwnListeningPorts.add(45678)
        assertTrue(45678 in OwnListeningPorts.snapshot())
        OwnListeningPorts.remove(45678)
        assertFalse(45678 in OwnListeningPorts.snapshot())
    }

    /** True socket: Scan out fake adbd and a service that does not say adb in a small section of ports. Only adbd returns STLS. */
    @Test
    fun nioScannerFindsOpenPortsAndOnlyAdbdAnswersStls() = runBlocking {
        FakeAdbd(emptyList()).use { adbd ->
            ServerSocket(0, 4, InetAddress.getLoopbackAddress()).use { other ->
                val scanner = NioPortScanner()
                val lo = minOf(adbd.port, other.localPort)
                val hi = maxOf(adbd.port, other.localPort)
                // When the range is too large, only scan the vicinity of two ports.
                val ports = if (hi - lo > 2_000) listOf(lo..lo, hi..hi) else listOf(lo..hi)
                val open = ports.flatMap { scanner.openPorts("127.0.0.1", it, emptySet(), 5_000) }
                assertTrue(open.toString(), adbd.port in open && other.localPort in open)
                assertTrue(scanner.speaksAdbTls("127.0.0.1", adbd.port, 1_000))
                assertFalse(scanner.speaksAdbTls("127.0.0.1", other.localPort, 300))
                assertTrue(scanner.openPorts("127.0.0.1", adbd.port..adbd.port, setOf(adbd.port), 1_000).isEmpty())
            }
        }
    }
}
