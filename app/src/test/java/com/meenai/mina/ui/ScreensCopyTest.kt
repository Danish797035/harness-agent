package com.meenai.mina.ui

import com.meenai.mina.core.agent.FailKind
import com.meenai.mina.core.agent.GateReason
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.i18n.EnglishTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** The sorting and text on the "Backstage Screen" page (does not touch the interface or real tasks). */
class ScreensCopyTest {
    @get:Rule val texts = EnglishTexts()

    private fun row(displayId: Int, key: String = "k$displayId", foreground: String? = null, web: Boolean = false) =
        ScreenRow(key, "c$displayId", "做点什么", displayId, TaskState.Running(0, "打开设置"), foreground, web)

    /** Those who have built screens come first (according to the order of screen creation); those who have not yet built screens (-1) come in the back, maintaining the original order of each other. */
    @Test fun screensAreOrderedWithUnbuiltOnesLast() {
        val rows = listOf(row(-1, "a"), row(15), row(-1, "b"), row(12))
        assertEquals(listOf("k12", "k15", "a", "b"), orderedScreens(rows).map { it.key })
        assertEquals(emptyList<ScreenRow>(), orderedScreens(emptyList()))
    }

    /** The second line tells which app you are using, and does not include things like screen number, pixels, and density used for development. */
    @Test fun placeSaysWhichAppNotDisplayInternals() {
        assertEquals("Using WeChat", ScreensCopy.place(row(12, foreground = "WeChat")))
        assertEquals("Using the browser", ScreensCopy.place(row(-1, web = true)))
        assertEquals("Hasn’t opened an app yet", ScreensCopy.place(row(-1)))
        assertEquals("Can’t see which app is open right now", ScreensCopy.place(row(12)))
        listOf(row(12, foreground = "WeChat"), row(12), row(-1)).map(ScreensCopy::place).forEach {
            assertFalse(it, it.contains("12") || it.contains("dpi") || it.contains("×"))
        }
    }

    /** There is a saying for every state: those who are waiting, those who want you to take action, and those who are actually working can be separated. */
    @Test fun everyStateHasALabel() {
        assertEquals("Running", ScreensCopy.state(TaskState.Running(1, "点了一下")))
        assertEquals("Task done", ScreensCopy.state(TaskState.Succeeded("好了")))
        assertEquals("Task not finished", ScreensCopy.state(TaskState.Failed("出错了", FailKind.Model)))
        assertEquals("Task stopped", ScreensCopy.state(TaskState.Cancelled))
        val needsYou = listOf(
            TaskState.WaitingInput("密码是什么"),
            TaskState.WaitingTakeover("payment", "要你付款"),
            TaskState.Held(GateReason("waiting_user", "在系统的选择框里选一个", needsYou = true)),
        )
        assertEquals(listOf("Needs you", "Needs you", "Needs you"), needsYou.map(ScreensCopy::state))
        // Just waiting for conditions (lock screen, assistant not connected) does not count as
        // "requiring you to handle it"
        assertEquals("Waiting", ScreensCopy.state(TaskState.Held(GateReason("locked", "锁屏了"))))
        assertEquals("Waiting", ScreensCopy.state(TaskState.Idle))
    }
}
