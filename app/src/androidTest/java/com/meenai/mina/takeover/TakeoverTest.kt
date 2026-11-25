package com.meenai.mina.takeover

import org.junit.Rule
import com.meenai.mina.DeviceTaskState
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.cap.phone.a11y.DeviceSupport
import com.meenai.mina.cap.phone.a11y.DeviceSupport.SETTINGS
import com.meenai.mina.cap.phone.a11y.DeviceSupport.pollUntil
import com.meenai.mina.cap.phone.a11y.DeviceSupport.sh
import com.meenai.mina.core.agent.AgentLoop
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import com.meenai.mina.shell.IHarnessShell
import com.meenai.mina.task.ScriptedModel
import com.meenai.mina.task.TaskService
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.meenai.mina.task.testTasks
import com.meenai.mina.task.TaskEnvironment

/**
 * Real machine test: The script model requests takeover on the settings subpage of the virtual screen;
 * "I'll handle it" moves the settings to the home screen and restores the sound, "Handles it" moves back
 * to the virtual screen, mutes the sound again, and the Agent observes first in the next round. It will
 * temporarily occupy the main screen (dedicated test machine), and when it is finished, turn off the
 * settings from the main screen. Requires the built-in assistant to be running and the Mina
 * accessibility service to be turned on.
 */
@RunWith(AndroidJUnit4::class)
class TakeoverTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var shell: IHarnessShell
    private val model = ScriptedModel()
    private var settingsTask = -1
    private lateinit var priorAudio: String

    @Before
    fun setUp() {
        shell = DeviceSupport.shell()
        DeviceSupport.service()
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        priorAudio = shell.getAudioMode(SETTINGS)!!
        testTasks.modelFactory = { _, _ -> model }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
    }

    @After
    fun tearDown() {
        TaskService.cancel(context)
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        // When canceling, the App remains on the home screen (not taken away from the user's eyes); the
        // test machine can be closed by the tester himself
        if (settingsTask >= 0 && shell.topTaskId(Display.DEFAULT_DISPLAY) == settingsTask) sh("am force-stop $SETTINGS")
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
    }

    private fun TaskState.running() = this !is TaskState.Succeeded && this !is TaskState.Failed && this !is TaskState.Cancelled

    private fun awaitState(predicate: (TaskState) -> Boolean): TaskState =
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf(predicate) } ?: error("state never matched: ${DeviceTaskState.state.value}")

    /**
     * When there are too many notifications in this App, the system will automatically add a group
     * summary (without title and button). It may use the same channel and needs to be skipped.
     * Previously, "I got an empty title after being queued behind other tests" and I got this summary.
     */
    private fun attentionRecord() = context.getSystemService(NotificationManager::class.java).activeNotifications
        .firstOrNull { it.notification.channelId == TaskService.CHANNEL_ATTENTION && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }

    private fun attention(): Notification? = attentionRecord()?.notification

    private fun attentionActions(): List<String>? = attention()?.actions?.map { it.title.toString() }

    /**
     * Click the notification button: The button should open [TakeoverActivity] (so the notification bar
     * will be closed) without displaying the interface, which will transfer it to the task service. The
     * PendingIntent of the send button of the test process itself will be blocked by the restriction of
     * starting Activity in the background (clicking from the notification bar is not restricted), so
     * checking after the target of the button, use the same Intent to start it directly.
     */
    private fun tapAttentionAction(title: String, action: String) {
        val a = attention()?.actions?.firstOrNull { it.title.toString() == title } ?: error("no action $title: ${attentionActions()}")
        assertTrue(a.actionIntent.isActivity)
        context.startActivity(Intent(context, TakeoverActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun attentionTitle(): String? = attention()?.extras?.getCharSequence("android.title")?.toString()

    @Test
    fun handOverMovesToMainAndBack() {
        TaskService.start(context, "测试接管")
        model.awaitCall()
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        model.awaitCall()
        val displayId = testTasks.agentDisplayId.value
        assertTrue(displayId > 0)
        val appTask = shell.topTaskId(displayId)
        assertTrue(appTask >= 0)
        assertEquals("ignore", pollUntil(5_000) { shell.getAudioMode(SETTINGS)?.takeIf { it == "ignore" } })
        // Open the settings subpage and request takeover on it. Some systems open subpages in another
        // task (the login and payment pages of Apps often do this), and the task that should be taken
        // over and moved should be the task that the user wants to process at the top of the virtual
        // screen.
        sh("am start --display $displayId -a android.settings.DISPLAY_SETTINGS")
        settingsTask = pollUntil(3_000) { shell.topTaskId(displayId).takeIf { it != appTask } } ?: appTask
        android.util.Log.i("TakeoverTest", "app task $appTask, sub-page task $settingsTask")

        model.feed("hand_to_user", """{"reason":"captcha","message":"请完成页面上的验证"}""")
        assertEquals(TaskState.WaitingTakeover("captcha", "请完成页面上的验证"), awaitState { it is TaskState.WaitingTakeover })
        assertEquals("需要你处理:验证码", pollUntil(5_000) { attentionTitle() })
        assertEquals(listOf("我来处理", "跳过", "取消任务"), pollUntil(5_000) { attentionActions() })

        // "I'll handle it": It is set to the top of the home screen and can make a sound; this move is
        // intentional and does not count as being taken away by the user.
        tapAttentionAction("我来处理", TaskService.ACTION_TAKE_OVER)
        assertEquals(settingsTask, pollUntil(5_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it == settingsTask } })
        assertEquals(priorAudio, pollUntil(5_000) { shell.getAudioMode(SETTINGS)?.takeIf { it == priorAudio } })
        assertEquals(listOf("处理好了", "我自己做完了", "取消任务"),
            pollUntil(5_000) { attentionActions()?.takeIf { it.size == 3 } })
        Thread.sleep(1_500) // Let the "removed" poll run for at least two rounds
        assertTrue(DeviceTaskState.state.value.toString(), DeviceTaskState.state.value is TaskState.WaitingTakeover)
        assertEquals(Takeover.Stage.HandedOver, DeviceTaskState.takeoverStage.value)

        // "Processed" (the buttons on the interface go to the same entrance): Return to the virtual
        // screen, mute the sound again, and the Agent receives a prompt to verify first.
        TaskService.handBack(context)
        val history = model.awaitCall().messages
        assertEquals(settingsTask, pollUntil(5_000) { shell.topTaskId(displayId).takeIf { it == settingsTask } })
        assertTrue(shell.topTaskId(Display.DEFAULT_DISPLAY) != settingsTask)
        assertEquals("ignore", shell.getAudioMode(SETTINGS))
        // The return result is the tool result of hand_to_user: complete, and requires verification
        // first.
        val last = history.last()
        assertEquals(Role.Tool, last.role)
        val text = last.parts.filterIsInstance<Part.ToolResult>().single().content
        assertTrue(text, "\"outcome\":\"complete\"" in text && AgentLoop.HANDBACK_NOTE in text)
        assertEquals(true, pollUntil(3_000) { if (attentionActions() == null) true else null })

        model.feed("phone_observe")
        val afterObserve = model.awaitCall().messages
        val result = afterObserve.last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().single()
        assertTrue(result.content, "\"currentApp\":\"$SETTINGS\"" in result.content)

        // The answer quotes an element name that I just read: it has been really read, the literal
        // verification is correct, and the ending is verified and completed.
        val quote = Regex("\"name\":\"([^\"\\\\]{2,40})\"").findAll(result.content).map { it.groupValues[1] }
            .firstOrNull { n -> n.count { it.isLetter() } >= 2 && n.none { it.isDigit() } } ?: error("no element name on screen: ${result.content}")
        val answer = "done,设置里有「$quote」"
        model.answer(answer)
        val end = pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        assertTrue("$end", end is TaskState.Succeeded && end.answer == answer && end.verified)
        // The status changes to end first, and the sound is restored in the ending. Wait until busy
        // changes back to false and then look again.
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }
        assertEquals(priorAudio, shell.getAudioMode(SETTINGS))
    }

    /** Open settings and request takeover, wait until the notification appears; return the Agent's virtual screen number. */
    private fun requestTakeover(): Int {
        TaskService.start(context, "测试接管")
        model.awaitCall()
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        model.awaitCall()
        val displayId = testTasks.agentDisplayId.value
        assertTrue(displayId > 0)
        settingsTask = shell.topTaskId(displayId)
        assertTrue(settingsTask >= 0)
        model.feed("hand_to_user", """{"reason":"login","message":"请在设置里看一下"}""")
        awaitState { it is TaskState.WaitingTakeover }
        pollUntil(5_000) { attentionActions() } ?: error("no takeover notification")
        return displayId
    }

    private fun lastToolResult(history: List<Message>): String {
        val last = history.last()
        assertEquals(Role.Tool, last.role)
        return last.parts.filterIsInstance<Part.ToolResult>().single().content
    }

    /** The center of the first clickable item in the settings window on the home screen that is not a switch; clicking it will only lead to the sub-page. */
    private fun clickableOnMainScreen(): Rect? = DeviceSupport.service().windowsOn(Display.DEFAULT_DISPLAY)
        .mapNotNull { it.root }.firstOrNull { it.packageName?.toString() == SETTINGS }
        ?.let { root ->
            fun find(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
                if (n.isClickable && !n.isCheckable && n.isVisibleToUser && !n.text.isNullOrBlank()) return n
                for (i in 0 until n.childCount) n.getChild(i)?.let { c -> find(c)?.let { return it } }
                return null
            }
            find(root)?.let { Rect().also(it::getBoundsInScreen) }
        }

    @Test
    fun skipButtonPresent() {
        requestTakeover()
        assertEquals(listOf("我来处理", "跳过", "取消任务"), attentionActions())
        tapAttentionAction("跳过", TaskService.ACTION_SKIP)
        val text = lastToolResult(model.awaitCall().messages)
        assertTrue(text, "\"outcome\":\"skipped\"" in text && "\"reason\":\"takeover_skipped\"" in text)
        // I haven’t taken it over yet, and the App is always on the virtual screen.
        assertTrue(shell.topTaskId(Display.DEFAULT_DISPLAY) != settingsTask)
    }

    @Test
    fun handBackReportsInputCount() {
        requestTakeover()
        tapAttentionAction("我来处理", TaskService.ACTION_TAKE_OVER)
        assertEquals(settingsTask, pollUntil(5_000) { shell.topTaskId(Display.DEFAULT_DISPLAY).takeIf { it == settingsTask } })
        assertEquals(Takeover.Stage.HandedOver, pollUntil(5_000) { DeviceTaskState.takeoverStage.value.takeIf { it == Takeover.Stage.HandedOver } })

        // The user taps the app twice on the home screen: enter an entry, return, and enter again
        repeat(2) { i ->
            val target = pollUntil(5_000) { clickableOnMainScreen() } ?: error("nothing clickable in settings on the main screen")
            sh("input -d ${Display.DEFAULT_DISPLAY} tap ${target.centerX()} ${target.centerY()}")
            Thread.sleep(1_000)
            if (i == 0) {
                sh("input -d ${Display.DEFAULT_DISPLAY} keyevent KEYCODE_BACK")
                Thread.sleep(1_000)
            }
        }

        TaskService.handBack(context)
        val text = lastToolResult(model.awaitCall().messages)
        assertTrue(text, "\"outcome\":\"complete\"" in text)
        val count = Regex("\"inputCount\":(\\d+)").find(text)?.groupValues?.get(1)?.toInt() ?: error("no inputCount: $text")
        assertTrue(text, count >= 2)
        assertTrue(text, "\"stoppedAt\":" in text)
    }
}
