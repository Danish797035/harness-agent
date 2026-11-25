package com.meenai.mina.task

import org.junit.Rule
import com.meenai.mina.DeviceTaskState
import android.content.Context
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.cap.phone.a11y.DeviceSupport
import com.meenai.mina.cap.phone.a11y.DeviceSupport.SETTINGS
import com.meenai.mina.cap.phone.a11y.DeviceSupport.pollUntil
import com.meenai.mina.cap.phone.a11y.DeviceSupport.sh
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.StopReason
import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.core.verify.Verifier
import com.meenai.mina.shell.IHarnessShell
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real machine test: Use the script model to drive the TaskService, open the system settings on the
 * virtual screen, and check whether it is always on, muted, removed, paused, and locked. Requires the
 * built-in assistant to be running and the Mina accessibility service to be turned on.
 */
@RunWith(AndroidJUnit4::class)
class TaskServiceTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var shell: IHarnessShell
    /**
     * One script model for each task: the call number of the model and the number of verification times
     * for each task. When sharing one, the calls (verification, closing) that were not taken away by the
     * previous task will be taken by the wait of the next task, and the test will run in front of the
     * task. In a task, it’s not just the task itself that requires the model from the factory: it takes
     * one time to create a title for a new conversation and one time to write an explanation after it’s
     * finished, and it’s concurrent with the task, so there’s no telling who wants it first. Therefore,
     * the factory does not issue models according to "how many times they are requested", but only the
     * model for the most recently started mission (see [model]).
     */
    private val models = List(MAX_TASKS) { ScriptedModel() }
    /** The factory reads it on the task service's thread. */
    @Volatile private var started = 0
    /** The model of the task started by the most recent [start] (it is used for the first task before the task is started). */
    private val model: ScriptedModel get() = models[maxOf(0, started - 1)]

    private fun start(goal: String) {
        started++
        TaskService.start(context, goal)
    }
    private var settingsTask = -1
    private lateinit var priorAudio: String

    @Before
    fun setUp() {
        shell = DeviceSupport.shell()
        DeviceSupport.service()
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        priorAudio = shell.getAudioMode(SETTINGS)!!
        // The tasks here are all initiated from the interface. It is recommended that when the first
        // tool is called, the intention will be asked again; always answer "want to operate" and do not
        // occupy the script.
        models.forEach { it.intent = "ACT" }
        testTasks.modelFactory = { _, _ -> model }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
    }

    @After
    fun tearDown() {
        TaskService.cancel(context)
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }
        // When the test fails in the middle, the settings may still remain on the home screen: only end
        // the test and move the tasks to it.
        if (settingsTask >= 0 && shell.topTaskId(Display.DEFAULT_DISPLAY) == settingsTask) sh("am force-stop $SETTINGS")
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
    }

    private fun TaskState.running() = this !is TaskState.Succeeded && this !is TaskState.Failed && this !is TaskState.Cancelled

    /** Open the task and let it open the settings on the virtual screen and return the virtual screen number; at this time the model is waiting for the second step. */
    private fun startWithSettingsOpen(): Int {
        start("测试任务")
        assertEquals(1, model.awaitCall().number)
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(2, model.awaitCall().number)
        val displayId = testTasks.agentDisplayId.value
        assertTrue(displayId > 0)
        settingsTask = shell.topTaskId(displayId)
        assertTrue(settingsTask >= 0)
        return displayId
    }

    /** Submit an answer without verifiable value and wait for the task to end: there is nothing to be correct in the literal verification, so no additional verification is required, and the result is completed. [appOpened] is only for legibility at the call site. */
    private fun finishAndAwait(@Suppress("UNUSED_PARAMETER") appOpened: Boolean = true) {
        model.answer("done")
        val end = pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        assertTrue("$end", end is TaskState.Succeeded && end.answer == "done" && !end.doubtful)
        // The status changes to finished first, and the sound is restored. After the finishing is
        // completed, busy changes back to false.
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }
    }

    private fun awaitState(predicate: (TaskState) -> Boolean): TaskState =
        pollUntil(10_000) { DeviceTaskState.state.value.takeIf(predicate) } ?: error("state never matched: ${DeviceTaskState.state.value}")

    private fun minaScreenLock(): String? =
        sh("dumpsys power").lines().map { it.trim() }.firstOrNull { "'Mina:task'" in it }

    /**
     * The answer on the product path does not require additional verification (the task is run with
     * reviewFinalAnswer = false): the answer given by the model is handed over as it is, and the outcome
     * is completed and unverified; neither the main model nor the lightweight model receives
     * verification requests. The rules for verification itself are managed by core unit tests.
     */
    @Test
    fun answerHandedOverWithoutReview() {
        val lightChecks = AtomicInteger()
        testTasks.lightModelFactory = { _, _ ->
            object : ChatModel {
                override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
                override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                    // When equipped with a lightweight model, judge the intention and ask it first; the
                    // rest can only be verification and counting.
                    if (system == INTENT) return ModelReply(listOf(Part.Text("ACT")), StopReason.EndTurn, null)
                    if (system == TITLE) return ModelReply(listOf(Part.Text("测试对话")), StopReason.EndTurn, null)
                    if (system == Verifier.PROMPT) lightChecks.incrementAndGet()
                    return ModelReply(listOf(Part.Text(ScriptedModel.CANNOT_VERIFY)), StopReason.EndTurn, null)
                }
            }
        }
        model.verdict = ScriptedModel.CANNOT_VERIFY
        start("测试任务")
        assertEquals(1, model.awaitCall().number)
        model.feed("phone_observe")
        assertEquals(2, model.awaitCall().number)
        model.answer("电量是 9731 毫安时")
        val end = pollUntil(15_000) { DeviceTaskState.state.value.takeIf { !it.running() } }
        assertTrue("$end", end is TaskState.Succeeded && end.answer == "电量是 9731 毫安时" && !end.reviewed && end.unconfirmed.isEmpty())
        assertEquals(0, lightChecks.get())
        assertEquals(0, model.verifierRequests.get())
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }
    }

    /** Tasks that do not open the App do not create a virtual screen: the reading screen is empty and prompts phone_launch_app first, the writing operation fails no_app_open, and there is no screen. */
    @Test
    fun noDisplayUntilLaunchApp() {
        start("你好")
        assertEquals(1, model.awaitCall().number)
        model.feed("phone_observe")
        val second = model.awaitCall()
        assertTrue(second.messages.last().toString(), "Start with phone_launch_app" in second.messages.last().toString())
        model.feed("phone_tap", """{"x":500,"y":500}""")
        val third = model.awaitCall()
        assertTrue(third.messages.last().toString(), "no_app_open" in third.messages.last().toString())
        assertEquals(-1, testTasks.agentDisplayId.value)
        assertEquals(null, DeviceTaskState.frames.value)
        finishAndAwait(appOpened = false)
        assertEquals(-1, testTasks.agentDisplayId.value)

        // The first time phone_launch_app is used to build a screen, and only one screen is built. The
        // second task has its own script model, called starting from 1
        start("测试任务")
        assertEquals(1, model.awaitCall().number)
        assertEquals(-1, testTasks.agentDisplayId.value)
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(2, model.awaitCall().number)
        val first = testTasks.agentDisplayId.value
        assertTrue(first > 0)
        settingsTask = shell.topTaskId(first)
        model.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(3, model.awaitCall().number)
        assertEquals(first, testTasks.agentDisplayId.value)
        assertNotNull(pollUntil(5_000) { DeviceTaskState.frames.value })
        finishAndAwait()
        assertEquals(-1, testTasks.agentDisplayId.value)
    }

    @Test
    fun openingSameAppPauses() {
        val displayId = startWithSettingsOpen()
        // Mute follows the open app in the bypass coroutine, wait a moment
        assertEquals("ignore", pollUntil(5_000) { shell.getAudioMode(SETTINGS)?.takeIf { it == "ignore" } } ?: shell.getAudioMode(SETTINGS))

        // Simulate the user opening the same App on the home screen: the task is moved to the home
        // screen
        assertTrue(shell.moveTask(settingsTask, Display.DEFAULT_DISPLAY))
        // The pause should be displayed immediately when the model is still "inferring" (before it
        // reaches the gate).
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.MOVED_AWAY)), awaitState { it is TaskState.Held })
        model.feed("phone_back")
        assertEquals(TaskState.Held(TaskGate.paused(TaskGate.MOVED_AWAY)), awaitState { it is TaskState.Held })
        // Does not automatically move back during pause
        Thread.sleep(1_500)
        assertEquals(settingsTask, shell.topTaskId(Display.DEFAULT_DISPLAY))

        TaskService.resume(context)
        assertEquals(3, model.awaitCall().number)
        assertEquals(settingsTask, pollUntil(5_000) { shell.topTaskId(displayId).takeIf { it == settingsTask } })
        assertTrue(shell.topTaskId(Display.DEFAULT_DISPLAY) != settingsTask)

        finishAndAwait()
        assertEquals(priorAudio, shell.getAudioMode(SETTINGS))
    }

    /**
     * Just look at dumpsys power without changing any global settings: temporarily turn off "Always on
     * when plugged into USB" to wait for the real screen off timeout. If the last user operation has
     * been for a long time, the main screen will be immediately turned off and locked (actually tested),
     * which is unacceptable when unattended. When the lock is disabled by the system (such as background
     * restrictions), dumpsys will be marked DISABLED, which is excluded here.
     */
    @Test
    fun keepsScreenOnDuringTask() {
        startWithSettingsOpen()
        val line = minaScreenLock()
        assertNotNull("Mina holds no screen wake lock while running", line)
        // Some systems add a lock object number (mLock: <id>) before the type, and only recognize the
        // type. They only keep the screen bright, allow the system to dim, and do not lock it at full
        // brightness.
        assertTrue(line!!, "SCREEN_DIM_WAKE_LOCK" in line)
        assertFalse(line, "DISABLED" in line)
        val summary = sh("dumpsys power").lines().map { it.trim() }.first { it.startsWith("mWakeLockSummary=") }
        // The summary is a bit mask (0x..), AOSP's WAKE_LOCK_SCREEN_DIM = 1 shl 2; some versions are
        // printed as text
        val bits = Regex("0x([0-9a-fA-F]+)").find(summary)?.groupValues?.get(1)?.toInt(16)
        assertTrue(summary, if (bits != null) bits and (1 shl 2) != 0 else "SCREEN_DIM" in summary)

        finishAndAwait()
        val released = pollUntil(3_000) { if (minaScreenLock() == null) true else null }
        assertTrue("screen wake lock still held after the task: ${minaScreenLock()}", released == true)
    }

    /**
     * Stop: The interface is stopped on the spot, and then a paragraph of explanation is added as the
     * answer of this round (the ending is still cancelled). Please tell me to write the lightweight
     * model without giving tools; when the lightweight model makes an error, change to the main model.
     * If the main model cannot be written, just press the record and spell it out directly.
     */
    @Test
    fun stopLeavesASummaryAsTheAnswer() {
        val asked = AtomicInteger()
        val gate = java.util.concurrent.CountDownLatch(1)
        testTasks.lightModelFactory = { _, _ ->
            object : ChatModel {
                override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
                override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                    // The question and answer about the intention of starting the game "requires
                    // operation". For other lightweight calls, the only way to explain it is to write it
                    // this time.
                    if (system == INTENT) return ModelReply(listOf(Part.Text("ACT")), StopReason.EndTurn, null)
                    if (system != SUMMARY) throw IllegalStateException("not for this test")
                    assertTrue(tools.isEmpty())
                    asked.incrementAndGet()
                    // Don’t finish writing yet: you can see “organizing”
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { gate.await(10, java.util.concurrent.TimeUnit.SECONDS) }
                    return ModelReply(listOf(Part.Text("停止前打开了设置,还没看任何页面。")), StopReason.EndTurn, null)
                }
            }
        }
        startWithSettingsOpen()
        val conversation = DeviceTaskState.conversationId.value!!
        TaskService.cancel(context)
        assertEquals(TaskState.Cancelled, awaitState { !it.running() })
        val runId = pollUntil(5_000) { DeviceTaskState.endedRunIds.value[conversation] }!!
        assertTrue(runId in pollUntil(5_000) { DeviceTaskState.summarizing.value.takeIf { runId in it } }.orEmpty())
        val store = com.meenai.mina.chat.ConversationStore.shared(context)
        // Write down "cancelled" first, stop waiting for explanation
        val placeholder = pollUntil(5_000) { store.turns(conversation).lastOrNull { it.runId == runId } }!!
        assertEquals("cancelled" to "已取消", placeholder.status to placeholder.outcome)
        gate.countDown()
        val turn = pollUntil(15_000) { store.turns(conversation).lastOrNull { it.runId == runId && it.outcome != "已取消" } }!!
        assertEquals("停止前打开了设置,还没看任何页面。", turn.outcome)
        assertEquals("cancelled", turn.status)
        assertEquals(1, asked.get())
        assertNotNull(pollUntil(5_000) { DeviceTaskState.summarizing.value.takeIf { runId !in it } })
        pollUntil(10_000) { DeviceTaskState.busy.value.takeIf { !it } }

        // There is an error in the lightweight model and the main model is in vain: press the record to
        // spell it directly
        testTasks.lightModelFactory = { _, _ ->
            object : ChatModel {
                override val capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 8_000, reasoning = false)
                override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
                    if (system == INTENT) ModelReply(listOf(Part.Text("ACT")), StopReason.EndTurn, null) else throw IllegalStateException("down")
            }
        }
        startWithSettingsOpen()
        val second = DeviceTaskState.conversationId.value!!
        val secondRun = pollUntil(5_000) { DeviceTaskState.run(second)?.runId?.value }!!
        TaskService.cancel(context)
        assertEquals(TaskState.Cancelled, awaitState { !it.running() })
        val plain = pollUntil(20_000) { store.turns(second).lastOrNull { it.runId == secondRun && it.outcome != "已取消" } }!!
        assertTrue(plain.outcome, plain.outcome.startsWith("Stopped. Here is what it did before stopping:"))
        assertEquals(1, model.summaryRequests.get())
    }

    // It must not be enabled when unattended: it will send KEYCODE_SLEEP to turn off the screen and lock
    // the phone, and only someone can enter the password to unlock it. Only manually remove @Ignore and
    // run when the user is present and ready to unlock.
    @Ignore("needs user to unlock: pressing power locks the phone and only a person can unlock it")
    @Test
    fun lockPausesAndUnlockResumes() {
        startWithSettingsOpen()
        sh("input keyevent KEYCODE_SLEEP")
        model.feed("phone_back")
        assertEquals(TaskState.Held(TaskGate.LOCKED), awaitState { it == TaskState.Held(TaskGate.LOCKED) })
        // Unlocked by someone; it should continue automatically after unlocking, no need to click
        // continue
        val resumed = pollUntil(120_000) { DeviceTaskState.state.value.takeIf { it is TaskState.Running } }
        assertNotNull(resumed)
        assertEquals(3, model.awaitCall().number)
        finishAndAwait()
    }

    private companion object {
        const val MAX_TASKS = 4
        val INTENT by lazy { com.meenai.mina.core.prompt.Prompts.role("intent_gate") }
        val SUMMARY by lazy { com.meenai.mina.core.prompt.Prompts.role("stop_summary") }
        val TITLE by lazy { com.meenai.mina.core.prompt.Prompts.role("conversation_title") }
    }
}
