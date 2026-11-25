package com.meenai.mina.core.cap.web

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettlePolicyTest {
    private val ledger = NetworkLedger()

    private fun TestScope.sent(id: String, type: String = "Fetch") = ledger.onEvent("Network.requestWillBeSent", buildJsonObject {
        put("requestId", id); put("type", type)
        putJsonObject("request") { put("url", "https://a.test/$id"); put("method", "GET") }
    }, currentTime)

    private fun TestScope.done(id: String) = ledger.onEvent("Network.loadingFinished", buildJsonObject { put("requestId", id) }, currentTime)

    /** The page keeps changing: waiting for the old limit to be reached and still not quiet. */
    private val restless: suspend (Long) -> Boolean = { cap -> delay(cap); false }
    /** Page goes quiet after 500 milliseconds. */
    private val calm: suspend (Long) -> Boolean = { cap -> delay(minOf(500, cap)); cap >= 500 }

    private suspend fun TestScope.settle(dom: suspend (Long) -> Boolean): Boolean =
        SettlePolicy.settle({ currentTime }, dom, { ledger.actionBusy(currentTime) }, domCapMs = 3_000, netCapMs = 5_000, pollMs = 100)

    @Test fun restlessDomWithIdleNetworkKeepsTheOldCap() = runTest {
        ledger.markAction()
        val start = currentTime
        assertTrue(settle(restless))
        assertEquals(3_000, currentTime - start)
    }

    @Test fun calmDomWithIdleNetworkReturnsAsBefore() = runTest {
        ledger.markAction()
        val start = currentTime
        assertTrue(settle(calm))
        assertEquals(500, currentTime - start)
    }

    @Test fun calmDomWaitsForASlowFetch() = runTest {
        ledger.markAction()
        val start = currentTime
        sent("1")
        launch { delay(2_000); done("1") }
        assertTrue(settle(calm))
        // The request ends after 2 seconds, then is quiet for another 500 milliseconds, and takes one
        // last look at the page.
        val took = currentTime - start
        assertTrue("$took", took in 2_500..3_200)
    }

    @Test fun hangingFetchStopsAtTheNetworkCap() = runTest {
        ledger.markAction()
        val start = currentTime
        sent("1")
        assertFalse(settle(calm))
        assertEquals(5_000, currentTime - start)
    }

    @Test fun restlessDomAndSlowFetchStillWithinNetworkCap() = runTest {
        ledger.markAction()
        val start = currentTime
        sent("1")
        launch { delay(4_000); done("1") }
        settle(restless)
        assertTrue("${currentTime - start}", currentTime - start <= 5_000)
    }

    @Test fun longLivedAndEarlierRequestsDoNotExtend() = runTest {
        sent("before")
        ledger.markAction()
        sent("es", type = "EventSource")
        sent("media", type = "Media")
        val start = currentTime
        assertTrue(settle(calm))
        assertEquals(500, currentTime - start)
    }

    @Test fun longPollPastStaleDoesNotExtend() = runTest {
        ledger.markAction()
        sent("poll")
        delay(10_000)
        val start = currentTime
        assertTrue(settle(calm))
        assertEquals(500, currentTime - start)
    }

    // ---------- Wait until the text is stable before reading the page ----------

    /** The text changes by time: [at] milliseconds ago given the previous sample. */
    private fun TestScope.content(vararg steps: Pair<Long, Pair<Int, Int>>): suspend () -> Pair<Int, Int>? {
        val start = currentTime
        return { steps.last { (at, _) -> currentTime - start >= at }.second }
    }

    private suspend fun TestScope.stable(sample: suspend () -> Pair<Int, Int>?): Boolean =
        SettlePolicy.contentStable({ currentTime }, sample, intervalMs = 250, stableIntervals = 2, emptyIntervals = 6, capMs = 4_000)

    @Test fun steadyTextIsStableAfterTwoUnchangedIntervals() = runTest {
        val start = currentTime
        assertTrue(stable(content(0L to (500 to 1))))
        assertEquals(500, currentTime - start)
    }

    @Test fun growingTextWaitsUntilItStopsGrowing() = runTest {
        val start = currentTime
        // Batch rendering: 0 to 1 second is always long, and remains unchanged after that
        assertTrue(stable(content(0L to (100 to 1), 400L to (300 to 2), 900L to (800 to 3))))
        assertEquals(1_500, currentTime - start)
    }

    @Test fun sameLengthDifferentTextIsAChange() = runTest {
        val start = currentTime
        assertTrue(stable(content(0L to (100 to 1), 300L to (100 to 2))))
        assertEquals(1_000, currentTime - start)
    }

    @Test fun emptyPageWaitsLongerAndRestlessPageStopsAtTheCap() = runTest {
        var start = currentTime
        assertTrue(stable(content(0L to (0 to 0))))
        assertEquals(1_500, currentTime - start)
        start = currentTime
        var n = 0
        assertFalse(stable { 100 to ++n })
        assertTrue(currentTime - start <= 4_000)
    }

    @Test fun noSampleMeansNotStable() = runTest {
        assertFalse(stable { null })
    }
}
