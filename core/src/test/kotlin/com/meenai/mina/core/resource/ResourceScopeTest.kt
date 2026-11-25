package com.meenai.mina.core.resource

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class ResourceScopeTest {
    private val released = CopyOnWriteArrayList<String>()

    private inner class Res(val name: String) {
        fun release() { released += name }
    }

    /** After it is built, the task is canceled before it is handed back to the caller: when the withContext of the thread is changed and handed back, the cancellation is thrown, the resource is still returned to the scope, and is released at the end. */
    @Test
    fun cancelledRightAfterCreationStillReleased() = runBlocking {
        val scope = ResourceScope()
        val outer = launch(Dispatchers.Default) {
            val task = currentCoroutineContext().job
            scope.acquire(Dispatchers.IO, Res::release) { Res("display").also { task.cancel() } }
            fail("cancellation should surface after creation")
        }
        outer.join()
        assertTrue(outer.isCancelled)
        scope.close()
        assertEquals(listOf("display"), released)
    }

    @Test
    fun normalPathReleasesOnceInReverseOrder() = runBlocking {
        val scope = ResourceScope()
        val a = scope.acquire(Dispatchers.IO, Res::release) { Res("a") }
        scope.acquire(Dispatchers.Default, Res::release) { Res("b") }
        scope.acquire(Dispatchers.IO, Res::release) { Res("c") }
        assertEquals("a", a.name)
        assertEquals(emptyList<String>(), released)
        scope.close()
        scope.close()
        assertEquals(listOf("c", "b", "a"), released)
    }

    /** Release can also be done in the context given during registration (such as something that can only be closed in the main thread). */
    @Test
    fun releaseRunsInAcquireContext() = runBlocking {
        val pool = Executors.newSingleThreadExecutor { Thread(it, "owner-thread") }
        try {
            val scope = ResourceScope()
            var thread: String? = null
            scope.acquire(pool.asCoroutineDispatcher(), { _: Res -> thread = Thread.currentThread().name }) { Res("web") }
            scope.close()
            assertTrue(thread.toString(), thread!!.startsWith("owner-thread"))
        } finally {
            pool.shutdown()
        }
    }

    /** If one cannot be released, the rest will be released; the first error will be thrown after all are released. */
    @Test
    fun failingReleaseDoesNotSkipOthers() = runBlocking {
        val scope = ResourceScope()
        scope.acquire(Dispatchers.IO, Res::release) { Res("a") }
        scope.acquire(Dispatchers.IO, { _: Res -> throw IllegalStateException("boom") }) { Res("b") }
        scope.acquire(Dispatchers.IO, Res::release) { Res("c") }
        try {
            scope.close()
            fail("release failure should surface")
        } catch (e: IllegalStateException) {
            assertEquals("boom", e.message)
        }
        assertEquals(listOf("c", "a"), released)
    }

    /** The scope is closed: any newly created resources are immediately discarded, and the caller gets an error instead of an unmanaged resource. */
    @Test
    fun acquireAfterCloseReleasesImmediately() = runBlocking {
        val scope = ResourceScope()
        scope.close()
        try {
            scope.acquire(Dispatchers.IO, Res::release) { Res("late") }
            fail("closed scope should refuse")
        } catch (e: IllegalStateException) {
            assertTrue(e !is CancellationException)
        }
        assertEquals(listOf("late"), released)
    }

    /** Multiple threads register and close at the same time: each is released exactly once. */
    @Test
    fun concurrentAcquireAndCloseReleaseEachOnce() = runBlocking {
        repeat(50) { round ->
            released.clear()
            val scope = ResourceScope()
            val made = CopyOnWriteArrayList<String>()
            val jobs = (0 until 8).map { i ->
                async(Dispatchers.Default, start = CoroutineStart.DEFAULT) {
                    runCatching { scope.acquire(Dispatchers.IO, Res::release) { Res("$round-$i").also { made += it.name } } }
                }
            }
            val closers = (0 until 3).map { async(Dispatchers.Default) { scope.close() } }
            jobs.awaitAll()
            closers.awaitAll()
            scope.close()
            assertEquals(made.sorted(), released.sorted())
        }
    }

    @Test
    fun acquireReturnsCreatedObject() = runBlocking {
        val scope = ResourceScope()
        val r = Res("x")
        assertSame(r, scope.acquire(Dispatchers.IO, Res::release) { r })
        scope.close()
    }
}
