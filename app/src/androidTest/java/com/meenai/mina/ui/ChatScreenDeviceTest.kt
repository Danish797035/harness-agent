package com.meenai.mina.ui

import com.meenai.mina.DeviceTaskState
import android.app.UiAutomation
import kotlinx.coroutines.runBlocking
import com.meenai.mina.cap.sandbox.ShareExporter
import com.meenai.mina.cap.sandbox.SandboxState
import com.meenai.mina.cap.sandbox.SandboxDirs
import com.meenai.mina.core.agent.SpeedFlags
import com.meenai.mina.core.cap.sandbox.SandboxStatus
import com.meenai.mina.core.agent.Turn
import com.meenai.mina.chat.ConversationStore
import androidx.compose.ui.test.onAllNodesWithContentDescription
import android.net.Uri
import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import com.meenai.mina.ui.viewer.FileViewerActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.HarnessApp
import com.meenai.mina.cap.phone.a11y.DeviceSupport
import com.meenai.mina.cap.phone.a11y.DeviceSupport.SETTINGS
import com.meenai.mina.cap.phone.a11y.DeviceSupport.pollUntil
import com.meenai.mina.cap.phone.a11y.DeviceSupport.sh
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.settings.FeatureFlags
import com.meenai.mina.settings.Prerequisites
import com.meenai.mina.task.ScriptedModel
import com.meenai.mina.task.TaskService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import com.meenai.mina.task.testTasks
import com.meenai.mina.task.TaskEnvironment

/**
 * Walk through the dialogue page on a real machine: the script model opens the settings on the virtual
 * screen, checks the steps, small windows (collapse, expand, drag, automatically expand when needed) and
 * result cards. At each stage, screenshot a picture to the external file directory shots/ of the App
 * (manually viewed by adb pull from the development machine). Requires the built-in assistant to be
 * running and the Mina accessibility service to be turned on.
 */
@RunWith(AndroidJUnit4::class)
class ChatScreenDeviceTest {

    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    // Tasks initiated from the interface need to ask another question about the intention before the
    // first action; the fixed answer is "to operate". Each question in the script is a step in the task
    // cycle itself.
    private val model = ScriptedModel().apply { intent = "ACT" }
    private val vm get() = (context.applicationContext as HarnessApp).chat
    private val anyway = Prerequisites.Check(emptyList())
    private var savedSpeed: SpeedFlags? = null

    @Before
    fun setUp() {
        DeviceSupport.shell()
        DeviceSupport.service()
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        testTasks.modelFactory = { _, _ -> model }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
        File(context.getExternalFilesDir(null), "shots").mkdirs()
        // The interface state is shared by the entire process: wait until all the tasks of the previous
        // test are completed, and then change to a new conversation to avoid connecting to its end
        // state.
        DeviceTaskState.runs.value.forEach { it.cancel() }
        pollUntil(20_000) { DeviceTaskState.busy.value.takeIf { !it } }
        // The parallel switch is a persistent setting, and other tests (or crashes in the middle) may
        // leave it true; each test decides by itself, and it is turned off by default.
        FeatureFlags.get(context).let { f -> savedSpeed = f.speedFlags(); f.setSpeed(savedSpeed!!.copy(parallelTasks = false)) }
        // When the conversation page is still in the end state of the previous conversation,
        // newConversation will be regarded as "busy" and not executed: wait for it to return to idle,
        // and confirm that it has been replaced by an empty conversation.
        assertNotNull("composer never went idle", pollUntil(20_000) { true.takeIf { !vm.busy() } })
        compose.runOnUiThread { vm.newConversation() }
        compose.waitForIdle()
        assertTrue("not a fresh conversation: ${vm.ui.value.exchanges}", vm.ui.value.exchanges.isEmpty())
    }

    @After
    fun tearDown() {
        DeviceTaskState.runs.value.forEach { it.cancel() }
        TaskService.cancel(context)
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf { !it.active() } }
        pollUntil(20_000) { DeviceTaskState.busy.value.takeIf { !it } }
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
        // Restore persistent settings to how they were before this test without leaving the device on
        // the test switch
        savedSpeed?.let { FeatureFlags.get(context).setSpeed(it) }
    }

    private fun TaskState.active() = this !is TaskState.Succeeded && this !is TaskState.Failed && this !is TaskState.Cancelled && this != TaskState.Idle

    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(400)
        compose.waitForIdle()
        // The default UiAutomation will suppress other accessibility services, and Mina's service will
        // be disconnected
        val bmp = InstrumentationRegistry.getInstrumentation()
            .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot()
        File(context.getExternalFilesDir(null), "shots/$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The line of activity in dumpsys that is in the foreground at the moment. */
    private fun topResumed(dump: String): String? =
        dump.lineSequence().firstOrNull { it.contains("topResumedActivity") || it.contains("mResumedActivity") }

    /** Is the activity in the foreground [cls](written as "package name/.relative class name" in dumpsys) of this App? */
    private fun onTop(cls: Class<*>): Boolean {
        val line = topResumed(sh("dumpsys activity activities")) ?: return false
        val short = "${context.packageName}/" + cls.name.removePrefix(context.packageName)
        return line.contains("$short ") || line.contains("${context.packageName}/${cls.name} ")
    }

    private fun awaitState(predicate: (TaskState) -> Boolean): TaskState =
        pollUntil(15_000) { DeviceTaskState.state.value.takeIf(predicate) } ?: error("state never matched: ${DeviceTaskState.state.value}")

    @Test
    fun runsTaskWithStepsPipAndResult() {
        shot("01-idle")

        compose.typeAndSend("打开设置看看电池")
        if (vm.ui.value.blocked != null) {
            // This phone is not equipped with a model, etc.: I also took a screenshot of the
            // notification of being stopped, and then ran it using a script model to bypass the check.
            shot("02-blocked")
            // Bypassing the check and directly handing it over to the interface state, the word
            // interface in the input box will not be cleared for it; the input box cannot be edited
            // after the task starts, so clear it first
            compose.onNode(hasSetTextAction()).performTextClearance()
            compose.runOnUiThread { vm.dismissBlocked(); assertTrue(vm.send("打开设置看看电池", anyway)) }
        }

        assertEquals(1, model.awaitCall().number)
        model.feed("phone_launch_app", """{"package":"$SETTINGS","reason":"先打开系统设置"}""")
        assertEquals(2, model.awaitCall().number)
        model.feed("phone_observe", """{"reason":"看看设置首页有什么"}""")
        assertEquals(3, model.awaitCall().number)
        assertNotNull(pollUntil(5_000) { DeviceTaskState.frames.value })
        // During the run, the current progress of a line, the vertical line below lists what has been
        // done: action using the model's own words, reading and writing where to look (do not write the
        // purpose of the model to read). At this moment, we are waiting for the third call of the model.
        // The progress line says "Processing". The last step "View settings" is the current step.
        compose.waitUntil(5_000) { compose.onAllNodesWithText("正在处理").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("看了设置").assertExists()
        compose.onNodeWithText(ChatCopy.BACKGROUND_NOTE).assertExists()
        compose.onNodeWithText("先打开系统设置").assertExists()
        compose.onNodeWithText(ChatCopy.placeholder(ComposerMode.Busy, hasHistory = true)).assertExists()
        assertTrue(vm.ui.value.pip.visible)
        shot("03-running-collapsed")

        compose.onNodeWithContentDescription("Mina 的虚拟屏").performClick()
        compose.waitForIdle()
        assertTrue(vm.ui.value.pip.expanded)
        shot("04-running-expanded")
        compose.onNodeWithContentDescription("收起").performClick()
        compose.waitForIdle()

        // The small window is usually in the lower right corner (above the input field): if you throw it
        // to the upper left, it should be sucked to the upper left corner.
        val d = context.resources.displayMetrics.density
        val pip = compose.onNodeWithContentDescription("Mina 的虚拟屏").fetchSemanticsNode().boundsInWindow
        val x = pip.center.x
        val y = pip.center.y
        sh("input swipe ${x.toInt()} ${y.toInt()} ${(x - 250 * d).toInt()} ${(y - 300 * d).toInt()} 120")
        compose.waitForIdle()
        val moved = compose.onNodeWithContentDescription("Mina 的虚拟屏").fetchSemanticsNode().boundsInWindow
        assertTrue("pip did not move up-left: $pip -> $moved", moved.center.x < x && moved.center.y < y)
        shot("05-dragged")

        model.feed("hand_to_user", """{"reason":"captcha","message":"设置里弹出了验证(脚本演示),需要你处理。"}""")
        awaitState { it is TaskState.WaitingTakeover }
        compose.waitForIdle()
        assertTrue(vm.ui.value.pip.expanded)
        compose.onNodeWithText("需要你处理:验证码").assertExists()
        compose.onNodeWithText("已暂停").assertExists()
        shot("06-takeover")

        // The expanded small window and mask cover the card in the conversation: collapse it first and
        // then click the button on the card
        compose.onNodeWithContentDescription("收起").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("取消任务").performClick()
        awaitState { it == TaskState.Cancelled }
        // Accounts made up after the stop (the script model is not written, press the record to spell it
        // directly) are listed under "Stopped."
        compose.onNodeWithText("已停止。").assertExists()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Stopped. Here is what it did before stopping:", substring = true).fetchSemanticsNodes().isNotEmpty() }
        shot("06b-stopped-summary")

        // Then start a new task: the service may still be finishing the previous one, and the interface
        // must wait until it is finished before handing it over.
        compose.runOnUiThread { assertTrue(vm.send("设置里电池是多少", anyway)) }
        model.awaitCall() // The call number of the script model is counted downwards from the previous task.
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""", say = "打开设置")
        model.awaitCall()
        model.answer("脚本演示:电池信息在「设置 › 电池」里。")
        awaitState { it is TaskState.Succeeded }
        compose.waitForIdle()
        compose.onNodeWithText("来源", substring = true).assertExists()
        // The canceled question and the completed question each have a "viewing process"
        assertEquals(2, compose.onAllNodesWithText("查看过程").fetchSemanticsNodes().size)
        shot("07-done")

        // Sidebar: Return to the conversation page (swipe) and open the sidebar first; five entrances
        // and recent conversations
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("drawer").assertIsDisplayed()
        compose.onNodeWithText("定时任务").assertIsDisplayed()
        compose.onNodeWithText("记忆与技能").assertIsDisplayed()
        compose.onNodeWithText("浏览器").assertIsDisplayed()
        compose.onNodeWithText("最近").assertIsDisplayed()
        shot("08-drawer")
    }

    /** The accessory button only appears when the sandbox is not "not supported by this device" (not installed, being installed, and can be installed or repaired if it is broken). */
    @Test
    fun attachButtonHiddenOnlyWhenSandboxUnsupported() {
        val unsupported = SandboxState.get(context).status.value is SandboxStatus.Unsupported
        compose.waitForIdle()
        val nodes = compose.onAllNodesWithContentDescription("添加附件").fetchSemanticsNodes()
        assertEquals("sandbox unsupported=$unsupported", !unsupported, nodes.isNotEmpty())
    }

    /** The file card displays the name and size; click on the text file to enter the view page in the App (no longer leaving it to the system to select the App), where you can see the file content and return to the dialog. */
    @Test
    fun fileCardOpens() {
        val ws = SandboxDirs(context).workspace("card-device-test").apply { mkdirs() }
        val f = File(ws, "card.txt").apply { writeText("hello") }
        val name = "mina-card-${System.currentTimeMillis()}.txt"
        val card = runBlocking { com.meenai.mina.cap.sandbox.AndroidFdSys.gate.openRead(ws, f.name).use { ShareExporter(context).export(it, name) } }
        val store = ConversationStore(context)
        val id = store.newConversation()
        store.append(id, Turn("导出", "好了", "succeeded"), listOf(card))
        try {
            compose.runOnUiThread { vm.openConversation(id) }
            compose.onNodeWithText(card.name).assertExists()
            compose.onNodeWithText(card.name).performClick()
            val shown = pollUntil(5_000) { true.takeIf { onTop(FileViewerActivity::class.java) } }
            assertNotNull("file viewer did not open: ${topResumed(sh("dumpsys activity activities"))}", shown)
            // The view page is another activity, but it is in the same process as the conversation page.
            // Its interface can also be found here: the name is in the top bar, and the content is
            // displayed as it is.
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("viewer.text").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("hello", substring = true, useUnmergedTree = true).assertIsDisplayed()
            shot("file-viewer")
            sh("input keyevent KEYCODE_BACK")
            val back = pollUntil(5_000) { true.takeIf { onTop(MainActivity::class.java) } }
            assertNotNull("back did not return to the chat page", back)
        } finally {
            context.contentResolver.delete(Uri.parse(card.uri), null, null)
            ws.deleteRecursively()
        }
    }

    /** The parallel switch is turned on: each of the two conversations runs a task. There is a small dot on the menu. Click on the other conversation in the "In Progress" sidebar to switch to it. */
    @Test
    fun otherTaskChipSwitches() {
        val flags = FeatureFlags.get(context)
        val prior = flags.speedFlags()
        flags.setSpeed(prior.copy(parallelTasks = true))
        val models = listOf(ScriptedModel().apply { intent = "ACT" }, ScriptedModel().apply { intent = "ACT" })
        // Model Factory not only retrieves the task when the task is started: the title of the new
        // dialogue is also retrieved another time in the background, and the order is uncertain.
        // Rotating by number will allow the first task to get the second model; if the first task
        // actually asks about the model, the first one will be given before, and the second one will be
        // given thereafter (the question about the title will have a fixed answer for which model)
        val firstStarted = AtomicBoolean(false)
        testTasks.modelFactory = { _, _ -> if (firstStarted.get()) models[1] else models[0] }
        try {
            compose.runOnUiThread { assertTrue(vm.send("第一个任务", anyway)) }
            assertEquals(1, models[0].awaitCall().number)
            firstStarted.set(true)
            val first = vm.ui.value.conversationId
            compose.runOnUiThread {
                vm.newConversation()
                assertTrue(vm.send("第二个任务", anyway))
            }
            assertEquals(1, models[1].awaitCall().number)
            val second = vm.ui.value.conversationId
            assertTrue(first != second)

            compose.onNodeWithContentDescription("菜单").performClick()
            compose.waitForIdle()
            // "In progress" is both a segment title and the small print below the line of another
            // conversation: separate recognitions are based on position - the title is at the top, and
            // the line is below (the title and small print are combined in a clickable node)
            val inDrawer = hasAnyAncestor(hasTestTag("drawer"))
            val other = compose.onNode(hasText("第一个任务") and hasText("进行中") and inDrawer)
            other.assertIsDisplayed()
            val header = compose.onNode(hasText("进行中") and !hasText("第一个任务") and inDrawer)
            header.assertIsDisplayed()
            val headerTop = header.fetchSemanticsNode().boundsInWindow.top
            val otherTop = other.fetchSemanticsNode().boundsInWindow.top
            assertTrue("running entry not under the 进行中 header: $headerTop vs $otherTop", otherTop > headerTop)
            other.performClick()
            compose.waitForIdle()
            assertEquals(first, vm.ui.value.conversationId)
            assertEquals("第一个任务", vm.ui.value.exchanges.last().goal)
            shot("parallel-drawer")

            models.forEach { it.answer("done") }
            assertNotNull(pollUntil(20_000) { DeviceTaskState.busy.value.takeIf { !it } })
        } finally {
            DeviceTaskState.runs.value.forEach { it.cancel() }
            pollUntil(15_000) { DeviceTaskState.busy.value.takeIf { !it } }
            flags.setSpeed(prior)
        }
    }
}
