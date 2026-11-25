package com.meenai.mina.cap.web

import android.os.SystemClock
import android.view.WindowManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewAssetLoader
import com.meenai.mina.core.uistate.ActionResult
import com.meenai.mina.core.uistate.ScreenSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.coroutines.resume

/**
 * Real machine test: The borrowed label (the one the user is looking at on the browser page) is used as
 * the current label of the task. Take the tag pool and web port directly without going through the task:
 * "User's tag" is hung on another web page display (replacing the interface of the browser page),
 * borrowing the task pool, and web tools read and click on it; the borrowed tag is not hung on the task
 * display, does not count as the upper limit, and opens a new tab when opening the URL; it is not
 * destroyed when it is returned and the task ends; when the user closes the page, the task accepts it
 * and continues to use it. No built-in assistant required, no touching the home screen.
 */
@RunWith(AndroidJUnit4::class)
class BorrowedTabDeviceTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private lateinit var host: WebHost
    private lateinit var userHost: WebHost
    private lateinit var pool: WebTabPool
    private lateinit var userTab: WebTab
    private val task = "task-borrow"

    private fun fixture(name: String) = "https://appassets.androidplatform.net/assets/web-fixtures/$name"

    @Before
    fun setUp() {
        val bounds = ctx.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
        val dpi = ctx.resources.displayMetrics.densityDpi
        instr.runOnMainSync {
            val assets = WebViewAssetLoader.AssetsPathHandler(instr.context)
            val loaders = listOf(WebViewAssetLoader.Builder().addPathHandler("/assets/", assets).build())
            host = WebHost(ctx, bounds.width(), bounds.height(), dpi)
            userHost = WebHost(ctx, bounds.width(), bounds.height(), dpi)
            pool = WebTabPool(ctx, host, perTask = 2, budget = TabBudget(), assetLoaders = loaders)
            // User's tags: not owned by the pool, but hung on their own display (the interface of the
            // browser page is an active window on the real machine)
            userTab = WebTab(ctx, "browser1", "user-browser", loaders)
            userTab.devtools.setHandOperated(userTab)
            userHost.attach(userTab, desktop = false)
        }
        runBlocking { assertTrue(userTab.load(fixture("inputs.html"))) }
    }

    @After
    fun tearDown() {
        instr.runOnMainSync {
            pool.close()
            if (!userTab.destroyed) userTab.destroy()
            host.close()
            userHost.close()
        }
    }

    private fun screen() = WebScreen(pool, task, host, SystemClock::uptimeMillis)
    private fun main(block: () -> Unit) = instr.runOnMainSync(block)

    private suspend fun js(tab: WebTab, script: String): String = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { c -> tab.view.evaluateJavascript(script) { c.resume(it) } }
    }

    private fun ScreenSnapshot.ref(resourceId: String): String =
        elements.firstOrNull { it.resourceId == resourceId }?.ref ?: error("$resourceId not in ${elements.map { it.resourceId }}")

    @Test
    fun webToolsActOnTheBorrowedTabWhereItIs() = runBlocking<Unit> {
        var attached = false
        main { attached = pool.attachExternal(task, userTab) }
        assertTrue(attached)
        main {
            assertSame(userTab, pool.current(task))
            assertTrue(pool.isExternal(userTab.id))
            // If it is not linked to the web page display of the task, it does not count as the upper
            // limit.
            assertSame(userHost, (userTab.view.parent as android.view.View).tag)
            assertEquals(0, pool.tabCount())
        }
        val s = screen()
        val snap = s.snapshot()
        assertTrue(snap.snapshotId.startsWith("wbrowser1-"))
        val r = s.tapRef(snap.ref("#counter"), snap.snapshotId)
        assertTrue("$r", r is ActionResult.Ok)
        assertEquals("1", js(userTab, "window.clicks"))
        assertEquals("true", js(userTab, "window.lastTrusted"))
        // The screenshot shows the view itself
        assertNotNull(s.screenshotPng())
        // Open the URL without redirecting the user to this page: Open a new tab
        val nav = s.navigate(fixture("elements.html"), newTab = false)
        assertTrue(nav.newTab)
        main {
            assertNotSame(userTab, pool.current(task))
            assertTrue(userTab.view.url!!.endsWith("inputs.html"))
            assertEquals(1, pool.tabCount())
        }
        // It can still be used if you switch it back; return it when the task is over and it will not be
        // destroyed.
        main { assertSame(userTab, pool.switchTo(task, userTab.id)) }
        main { pool.closeAll(task) }
        main {
            assertFalse(userTab.destroyed)
            assertFalse(pool.isExternal(userTab.id))
            assertSame(userHost, (userTab.view.parent as android.view.View).tag)
        }
    }

    @Test
    fun detachReturnsAndAdoptKeepsThePage() = runBlocking<Unit> {
        main { assertTrue(pool.attachExternal(task, userTab)) }
        // I borrowed it twice and just cut it.
        main { assertTrue(pool.attachExternal(task, userTab)) }
        // Don’t use the same one for other tasks?
        main { assertFalse(pool.attachExternal("other", userTab)) }
        main {
            assertTrue(pool.detachExternal(userTab.id))
            assertEquals(null, pool.current(task))
            assertFalse(userTab.destroyed)
            assertFalse(pool.detachExternal(userTab.id))
        }
        // The user closes the page: the task accepts it as a normal label and hangs it on the task
        // display, but the page status is still there
        js(userTab, "document.getElementById('counter').click()")
        main { assertTrue(pool.attachExternal(task, userTab)) }
        main {
            assertTrue(pool.detachExternal(userTab.id))
            assertTrue(pool.adopt(task, userTab, current = true))
            assertSame(userTab, pool.current(task))
            assertFalse(pool.isExternal(userTab.id))
            assertEquals(task, userTab.ownerTaskId)
            assertSame(host, (userTab.view.parent as android.view.View).tag)
            assertEquals(1, pool.tabCount())
        }
        assertEquals("1", js(userTab, "window.clicks"))
        // Those collected will be closed together with the end of the mission.
        main { pool.closeAll(task) }
        main { assertTrue(userTab.destroyed) }
    }
}
