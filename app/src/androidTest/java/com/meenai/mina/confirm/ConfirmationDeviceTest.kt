package com.meenai.mina.confirm

import org.junit.Rule
import com.meenai.mina.DeviceTaskState
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.cap.phone.a11y.DeviceSupport
import com.meenai.mina.cap.phone.a11y.DeviceSupport.SETTINGS
import com.meenai.mina.cap.phone.a11y.DeviceSupport.pollUntil
import com.meenai.mina.cap.phone.a11y.DeviceSupport.sh
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import com.meenai.mina.shell.IHarnessShell
import com.meenai.mina.task.ScriptedModel
import com.meenai.mina.task.TaskService
import android.util.Log
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.meenai.mina.task.testTasks
import com.meenai.mina.task.TaskEnvironment

/**
 * Real machine test: The script model declares a money-spending operation in the settings of the virtual
 * screen (it is just a report, never actually done; only money needs to be agreed, and the amount is
 * included in the report), check the title, text and "Agree" and "Disagree" buttons of the high-priority
 * notification, as well as the three ways of disagreeing, agreeing, and automatically rejecting the
 * review. Requires the built-in assistant to be running and the Mina accessibility service to be turned
 * on.
 */
@RunWith(AndroidJUnit4::class)
class ConfirmationDeviceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var shell: IHarnessShell
    // Tasks initiated by the interface will ask for the intention again before the first tool is called
    // (this model will be asked when the lightweight model is not configured): fixed answer ACT, no
    // script required
    private val model = ScriptedModel().apply { intent = "ACT" }
    private var appTask = -1

    @Before
    fun setUp() {
        shell = DeviceSupport.shell()
        DeviceSupport.service()
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        testTasks.modelFactory = { _, _ -> model }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
    }

    @After
    fun tearDown() {
        testTasks.evalHooks.reset()
        TaskService.cancel(context)
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }
        if (appTask >= 0 && shell.topTaskId(Display.DEFAULT_DISPLAY) == appTask) sh("am force-stop $SETTINGS")
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
    }

    private fun TaskState.running() = this !is TaskState.Succeeded && this !is TaskState.Failed && this !is TaskState.Cancelled

    private fun awaitState(predicate: (TaskState) -> Boolean): TaskState =
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf(predicate) } ?: error("state never matched: ${DeviceTaskState.state.value}")

    private fun attention(): Notification? = context.getSystemService(NotificationManager::class.java).activeNotifications
        .firstOrNull { it.notification.channelId == TaskService.CHANNEL_ATTENTION && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
        ?.notification

    private fun lastToolResult(call: ScriptedModel.Call): String =
        call.messages.last { it.role == Role.Tool }.parts.filterIsInstance<Part.ToolResult>().single().content

    /** Open the settings on the virtual screen, and then report "Place an order"; when you return, the task is waiting for user approval. */
    private fun startAndRequest() {
        TaskService.start(context, "测试确认关卡")
        assertEquals(1, model.awaitCall().number)
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(2, model.awaitCall().number)
        appTask = shell.topTaskId(testTasks.agentDisplayId.value)
        model.feed("request_confirmation", """{"action":"order","target":"测试项","content":"买测试项\n第二行","amount":"¥25","app":"$SETTINGS"}""")
        awaitState { it is TaskState.WaitingConfirmation }
    }

    @Test
    fun notificationHasApproveDecline() {
        startAndRequest()
        val n = pollUntil(5_000) { attention()?.takeIf { it.actions?.size == 2 } } ?: error("no confirmation notification")
        assertEquals("要你同意:下单", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(text, text.startsWith("在 ") && "对象:测试项" in text && "内容:买测试项 第二行" in text && "金额:¥25" in text)
        assertEquals(listOf("同意", "不同意"), n.actions.map { it.title.toString() })
        // and take over the same channel. It is a high priority when creating a channel; the user or the
        // system may lower it later (the App cannot change it back). The only requirement here is that
        // it is not lower than the default, and the actual level is recorded.
        assertEquals(TaskService.CHANNEL_ATTENTION, n.channelId)
        val importance = context.getSystemService(NotificationManager::class.java).getNotificationChannel(TaskService.CHANNEL_ATTENTION).importance
        Log.i("ConfirmationDeviceTest", "attention channel importance $importance")
        assertTrue("importance $importance", importance >= NotificationManager.IMPORTANCE_DEFAULT)
        // Only actions are shown on the lock screen, objects and content are not shown
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        // There is already the word "object" in the general prompt. What you want to check is that the
        // specific object and content are not revealed.
        val publicText = n.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        assertTrue(publicText, "测试项" !in publicText && "第二行" !in publicText)
        // These two buttons can also be seen in the system notification list.
        val dump = sh("dumpsys notification --noredact")
        assertTrue("buttons not in dumpsys notification", "同意" in dump && "不同意" in dump)

        // Answers clicked on old notifications (if the declaration is not correct) will not count.
        context.startService(Intent(context, TaskService::class.java).setAction(TaskService.ACTION_APPROVE)
            .putExtra(ConfirmationNotifier.EXTRA_REQUEST, 12345))
        Thread.sleep(1_000)
        assertTrue(DeviceTaskState.state.value.toString(), DeviceTaskState.state.value is TaskState.WaitingConfirmation)

        TaskService.decline(context, ConfirmationNotifier.requestKey(DeviceTaskState.state.value as TaskState.WaitingConfirmation))
        val result = lastToolResult(model.awaitCall())
        assertTrue(result, "confirmation_declined" in result)
        assertEquals(true, pollUntil(3_000) { if (attention() == null) true else null })
        model.feed("end_not_done", """{"message":"用户没有同意下单,没有做这一步"}""")
        val end = pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        assertTrue(end.toString(), end is TaskState.Failed && "用户没有同意下单" in end.reason)
    }

    @Test
    fun approveButtonAnswersTheLoop() {
        startAndRequest()
        val n = pollUntil(5_000) { attention()?.takeIf { it.actions?.size == 2 } } ?: error("no confirmation notification")
        // The intent sent by pressing the Agree button itself on the notification
        n.actions.first { it.title.toString() == "同意" }.actionIntent.send()
        val result = lastToolResult(model.awaitCall())
        assertTrue(result, "\"approved\":true" in result)
        assertTrue(DeviceTaskState.state.value.toString(), DeviceTaskState.state.value !is TaskState.WaitingConfirmation)
        // If you don’t actually do it after agreeing: just end it
        model.feed("end_not_done", """{"message":"测试到此为止"}""")
        pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
    }

    @Test
    fun evalAutoDeclineSkipsTheUser() {
        testTasks.evalHooks.autoDecideConfirmation = { false }
        TaskService.start(context, "测试确认关卡")
        assertEquals(1, model.awaitCall().number)
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(2, model.awaitCall().number)
        appTask = shell.topTaskId(testTasks.agentDisplayId.value)
        model.feed("request_confirmation", """{"action":"send","target":"测试","content":"转账","amount":"¥25","app":"$SETTINGS"}""")
        val result = lastToolResult(model.awaitCall())
        assertTrue(result, "confirmation_declined" in result)
        model.feed("end_not_done", """{"message":"没有同意"}""")
        val end = pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        assertTrue(end.toString(), end is TaskState.Failed)
    }

    /** After the second declaration, clicking the "Agree" button in the first notification will not count. */
    @Test
    fun firstNotificationCannotApproveSecondRequest() {
        startAndRequest()
        val first = pollUntil(5_000) { attention()?.takeIf { it.actions?.size == 2 } } ?: error("no confirmation notification")
        val firstApprove = first.actions.first { it.title.toString() == "同意" }.actionIntent
        TaskService.decline(context, ConfirmationNotifier.requestKey(DeviceTaskState.state.value as TaskState.WaitingConfirmation))
        assertTrue(lastToolResult(model.awaitCall()).contains("confirmation_declined"))
        // Change the type of action and apply again (if the same type of app has been rejected before,
        // it will not be asked again)
        model.feed("request_confirmation", """{"action":"submit","target":"测试项","content":"提交测试项","amount":"¥25","app":"$SETTINGS"}""")
        awaitState { it is TaskState.WaitingConfirmation && it.request.action.name == "submit" }
        pollUntil(5_000) { attention()?.takeIf { it.extras.getCharSequence(Notification.EXTRA_TITLE).toString() == "要你同意:提交" } }
        runCatching { firstApprove.send() } // The old button may have been invalidated by the system, and invalidation is still considered as passing.
        Thread.sleep(1_500)
        assertTrue(DeviceTaskState.state.value.toString(), DeviceTaskState.state.value is TaskState.WaitingConfirmation)
    }
}
