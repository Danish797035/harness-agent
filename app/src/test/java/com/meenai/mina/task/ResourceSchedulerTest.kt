package com.meenai.mina.task

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.meenai.mina.core.uistate.AppWait
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class ResourceSchedulerTest {
    @Test fun simultaneousAppClaimsHaveExactlyOneOwnerAndReleaseFreesIt() {
        val resources = ResourceScheduler()
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val wins = AtomicInteger()
        for (owner in listOf("a", "b")) Thread {
            start.await()
            if (resources.reserveApp("com.example", owner, { true }, { true })) wins.incrementAndGet()
            done.countDown()
        }.start()
        start.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1, wins.get())
        val first = resources.appHolder("com.example")!!
        resources.releaseOwner(first)
        assertTrue(resources.reserveApp("com.example", if (first == "a") "b" else "a", { true }, { true }))
    }

    @Test fun clipboardLeaseSerializesTwoTasks() {
        val resources = ResourceScheduler()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondFinished = CountDownLatch(1)
        val secondEntered = AtomicBoolean(false)
        val first = Thread {
            resources.clipboard.lock()
            try { entered.countDown(); release.await() } finally { resources.clipboard.unlock() }
        }
        val second = Thread {
            resources.clipboard.lock()
            try { secondEntered.set(true) } finally { resources.clipboard.unlock(); secondFinished.countDown() }
        }
        first.start()
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        second.start()
        assertFalse(secondFinished.await(100, TimeUnit.MILLISECONDS))
        assertFalse(secondEntered.get())
        release.countDown()
        assertTrue(secondFinished.await(5, TimeUnit.SECONDS))
        first.join(5_000)
    }

    // ---------- Wait for other tasks to release the App ----------

    private val active: (String) -> Boolean = { true }

    /** If "No one is occupied in the registration", you can let it go (don't look at other screens during the test). */
    private fun ResourceScheduler.freeFor(pkg: String, owner: String): suspend () -> Boolean = { appFree(pkg, owner, active, { true }) }

    /** As soon as the occupied task is released, the waiting one will wake up immediately (without waiting for the next polling). */
    @Test fun releaseWakesTheWaiter() = runTest {
        val r = ResourceScheduler()
        assertTrue(r.reserveApp("com.chat", "a", active, { true }))
        val waiter = async { r.awaitApp("com.chat", "b", 60_000, active, r.freeFor("com.chat", "b"), pollMs = 30_000) }
        advanceTimeBy(5_000)
        assertFalse(waiter.isCompleted)
        r.releaseOwner("a")
        runCurrent()
        assertEquals(AppWait.Free, waiter.await())
        assertTrue("醒来远早于下一次轮询", currentTime < 30_000)
        assertTrue(r.reserveApp("com.chat", "b", active, { true }))
    }

    /** It's still occupied when the time limit expires: if you honestly say you can't wait, it's no longer counted as waiting. */
    @Test fun waitingTimesOut() = runTest {
        val r = ResourceScheduler()
        assertTrue(r.reserveApp("com.chat", "a", active, { true }))
        assertEquals(AppWait.TimedOut, r.awaitApp("com.chat", "b", 3_000, active, r.freeFor("com.chat", "b")))
        assertEquals(3_000L, currentTime)
        // b is no longer registered as waiting: a now waits for b. The apps in hand will not be regarded
        // as waiting for each other.
        assertTrue(r.reserveApp("com.mail", "b", active, { true }))
        assertEquals(AppWait.TimedOut, r.awaitApp("com.mail", "a", 1_000, active, r.freeFor("com.mail", "a")))
    }

    /** a is chatting and waiting for the email in b's hand; b is waiting for each other when chatting: b doesn't wait on the spot, and a still waits. */
    @Test fun mutualWaitIsRefusedAtOnce() = runTest {
        val r = ResourceScheduler()
        assertTrue(r.reserveApp("com.chat", "a", active, { true }))
        assertTrue(r.reserveApp("com.mail", "b", active, { true }))
        val a = async { r.awaitApp("com.mail", "a", 60_000, active, r.freeFor("com.mail", "a")) }
        runCurrent()
        assertEquals(AppWait.WouldDeadlock, r.awaitApp("com.chat", "b", 60_000, active, r.freeFor("com.chat", "b")))
        assertEquals("没等:时间没走", 0L, currentTime)
        // b. Don’t wait. Put the mail away after finishing. a. Just wait.
        r.releaseOwner("b")
        runCurrent()
        assertEquals(AppWait.Free, a.await())
    }

    /** Three tasks in a circle (a waits for b, b waits for c, c waits for a) are also recognizable; if they are not in a circle, wait as usual. */
    @Test fun longerCyclesAreFoundAndChainsWithoutCycleWait() = runTest {
        val r = ResourceScheduler()
        for ((pkg, owner) in listOf("p.a" to "a", "p.b" to "b", "p.c" to "c")) assertTrue(r.reserveApp(pkg, owner, active, { true }))
        val a = async { r.awaitApp("p.b", "a", 60_000, active, r.freeFor("p.b", "a")) }
        val b = async { r.awaitApp("p.c", "b", 60_000, active, r.freeFor("p.c", "b")) }
        runCurrent()
        assertEquals(AppWait.WouldDeadlock, r.awaitApp("p.a", "c", 60_000, active, r.freeFor("p.a", "c")))
        // d does not occupy anything, until a's hand does not form a circle
        val d = async { r.awaitApp("p.a", "d", 2_000, active, r.freeFor("p.a", "d")) }
        advanceTimeBy(2_001)
        assertEquals(AppWait.TimedOut, d.await())
        a.cancel(); b.cancel()
    }

    /** The task of occupying is no longer there (there is no time to let go): it does not count as occupying, nor does it count as waiting for each other. */
    @Test fun goneHolderDoesNotBlockOrDeadlock() = runTest {
        val r = ResourceScheduler()
        assertTrue(r.reserveApp("com.chat", "gone", active, { true }))
        val alive: (String) -> Boolean = { it != "gone" }
        assertEquals(AppWait.Free, r.awaitApp("com.chat", "b", 1_000, alive, { r.appFree("com.chat", "b", alive, { true }) }))
    }
}
