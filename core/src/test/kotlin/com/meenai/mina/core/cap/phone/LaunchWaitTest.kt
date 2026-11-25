package com.meenai.mina.core.cap.phone

import com.meenai.mina.core.assembly.toolDispatcher
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.tool.FakeScreen
import com.meenai.mina.core.tool.execute
import com.meenai.mina.core.uistate.APP_IN_USE_BY_OTHER_TASK
import com.meenai.mina.core.uistate.ActionResult
import com.meenai.mina.core.uistate.AppWait
import com.meenai.mina.core.uistate.ScreenPort
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** wait of phone_launch_app: When another task uses this App, the model can ask to wait for it to be released before launching it. */
class LaunchWaitTest {
    /** The first time it is opened, the report is occupied. After that, it has been occupied the same number of times as [busyTimes]; [wait] is the result of waiting. */
    private class BusyScreen(private val wait: AppWait, private var busyTimes: Int = 1) : ScreenPort by FakeScreen() {
        val calls = mutableListOf<String>()
        override suspend fun openApp(packageName: String, cold: Boolean): ActionResult {
            calls += "open $packageName"
            return if (busyTimes-- > 0) ActionResult.Failed(APP_IN_USE_BY_OTHER_TASK) else ActionResult.Ok()
        }
        override suspend fun awaitAppFree(packageName: String, userId: Int?, timeoutMs: Long): AppWait {
            calls += "wait $packageName $timeoutMs"
            return wait
        }
    }

    private suspend fun launch(screen: ScreenPort, args: String) =
        toolDispatcher(screen).execute(Part.ToolCall("l", "phone_launch_app", args))

    private fun error(r: com.meenai.mina.core.tool.ToolExecution) = r.content["error"]?.jsonPrimitive?.content

    @Test fun withoutWaitTheInUseResultComesBackAtOnce() = runTest {
        val screen = BusyScreen(AppWait.Free)
        val r = launch(screen, """{"package":"com.example.chat"}""")
        assertEquals(APP_IN_USE_BY_OTHER_TASK, error(r))
        assertEquals(listOf("open com.example.chat"), screen.calls)
        // Tell the model in the description that it can ask to wait
        assertTrue(r.content["message"]!!.jsonPrimitive.content.contains("wait"))
    }

    @Test fun waitOpensTheAppOnceItIsFree() = runTest {
        val screen = BusyScreen(AppWait.Free)
        val r = launch(screen, """{"package":"com.example.chat","wait":30}""")
        assertEquals("true", r.content["ok"]!!.jsonPrimitive.content)
        assertEquals(listOf("open com.example.chat", "wait com.example.chat 30000", "open com.example.chat"), screen.calls)
    }

    @Test fun mutualWaitFailsFastWithItsOwnCode() = runTest {
        val screen = BusyScreen(AppWait.WouldDeadlock)
        val r = launch(screen, """{"package":"com.example.chat","wait":60}""")
        assertEquals(LaunchApp.WAIT_WOULD_DEADLOCK, error(r))
        assertEquals(listOf("open com.example.chat", "wait com.example.chat 60000"), screen.calls)
    }

    @Test fun waitingTooLongSaysItIsStillInUse() = runTest {
        val screen = BusyScreen(AppWait.TimedOut)
        assertEquals(LaunchApp.WAIT_TIMED_OUT, error(launch(screen, """{"package":"com.example.chat","wait":5}""")))
    }

    /** Wait does not work when it is not occupied; waiting beyond the upper limit is caused by wrong parameters and will not be opened. */
    @Test fun waitIsBoundedAndOnlyUsedWhenBusy() = runTest {
        val free = BusyScreen(AppWait.Free, busyTimes = 0)
        assertEquals("true", launch(free, """{"package":"com.example.chat","wait":10}""").content["ok"]!!.jsonPrimitive.content)
        assertEquals(listOf("open com.example.chat"), free.calls)
        val tooLong = BusyScreen(AppWait.Free)
        val r = launch(tooLong, """{"package":"com.example.chat","wait":${LaunchApp.MAX_WAIT_S + 1}}""")
        assertEquals("invalid_arguments", error(r))
        assertEquals(emptyList<String>(), tooLong.calls)
    }
}
