package com.meenai.mina.task

import com.meenai.mina.DeviceTaskState
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.cap.phone.a11y.DeviceSupport
import com.meenai.mina.cap.phone.a11y.DeviceSupport.SETTINGS
import com.meenai.mina.cap.phone.a11y.DeviceSupport.pollUntil
import com.meenai.mina.cap.phone.a11y.DeviceSupport.sh
import com.meenai.mina.core.agent.SpeedFlags
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.settings.FeatureFlags
import com.meenai.mina.shell.IHarnessShell
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real machine test: When the switch parallelTasks is turned on, the two dialogue tasks run at the same
 * time, each occupying a virtual screen. Requires Android 14+, built-in assistant running, Mina
 * accessibility service turned on. The script model is divided into two tasks in starting order.
 */
@RunWith(AndroidJUnit4::class)
class ParallelDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var shell: IHarnessShell
    private val models = listOf(ScriptedModel(), ScriptedModel())
    private val handedOut = AtomicInteger()
    private lateinit var priorFlags: SpeedFlags

    @Before
    fun setUp() {
        assumeTrue("parallel displays need Android 14+", Build.VERSION.SDK_INT >= ParallelCapability.MIN_SDK)
        shell = DeviceSupport.shell()
        DeviceSupport.service()
        sh("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        val flags = FeatureFlags.get(context)
        // The second run of the kill process test: the ending was not run when the process was killed
        // for the first time, and the switch was still on; it needs to be restored to what was written
        // down before the first start, not at this moment.
        priorFlags = killRecord.takeIf { it.exists() }?.let { runCatching { SpeedFlags.parse(JSONObject(it.readText()).getString(FLAGS_KEY)) }.getOrNull() }
            ?: flags.speedFlags()
        flags.setSpeed(priorFlags.copy(parallelTasks = true))
        testTasks.modelFactory = { _, _ -> models[handedOut.getAndIncrement().coerceAtMost(1)] }
        testTasks.lightModelFactory = { _, _ -> null }
        testTasks.visionModelFactory = { _, _ -> null }
    }

    @After
    fun tearDown() {
        if (!::priorFlags.isInitialized) return
        DeviceTaskState.runs.value.forEach { it.cancel() }
        pollUntil(15_000) { DeviceTaskState.busy.value.takeIf { !it } }
        FeatureFlags.get(context).setSpeed(priorFlags)
        testTasks.modelFactory = TaskEnvironment.defaultModelFactory
        testTasks.lightModelFactory = TaskEnvironment.defaultLightModelFactory
        testTasks.visionModelFactory = TaskEnvironment.defaultVisionModelFactory
    }

    private fun clockPackage(): String =
        context.packageManager.resolveActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS), 0)?.activityInfo?.packageName
            ?: error("no clock app handles ACTION_SHOW_ALARMS")

    /** Open a new dialogue task and wait until its model is called for the first time; return the dialogue number. */
    private fun startTask(goal: String, model: ScriptedModel): String {
        val before = DeviceTaskState.conversationId.value
        TaskService.start(context, goal)
        assertEquals(1, model.awaitCall().number)
        return pollUntil(5_000) { DeviceTaskState.conversationId.value?.takeIf { it != before } } ?: error("task did not start")
    }

    private fun displayOf(conversationId: String): Int =
        pollUntil(10_000) { DeviceTaskState.run(conversationId)?.displayId?.value?.takeIf { it >= 0 } } ?: -1

    private fun finish(model: ScriptedModel) = model.answer("done")

    private fun ended(conversationId: String): TaskState? {
        val run = DeviceTaskState.run(conversationId) ?: return TaskState.Cancelled
        return run.state.value.takeIf { it is TaskState.Succeeded || it is TaskState.Failed || it is TaskState.Cancelled }
    }

    /** Two tasks can be completed on two screens at the same time; the user's home screen will not be affected during this period. */
    @Test
    fun twoTasksRunOnTwoDisplays() {
        val mainBefore = DeviceSupport.mainScreenState(shell)
        val (a, b) = models
        val first = startTask("设置版本号", a)
        val second = startTask("在时钟里新建一个带标记的闹钟", b)
        assertEquals(2, DeviceTaskState.runs.value.size)

        a.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        b.feed("phone_launch_app", """{"package":"${clockPackage()}"}""")
        assertEquals(2, a.awaitCall().number)
        assertEquals(2, b.awaitCall().number)
        val displayA = displayOf(first)
        val displayB = displayOf(second)
        assertTrue(displayA > 0 && displayB > 0)
        assertNotEquals(displayA, displayB)
        assertTrue(shell.topTaskId(displayA) >= 0)
        assertTrue(shell.topTaskId(displayB) >= 0)
        // The two screens operate independently, and the focus of the main screen and the top task
        // remain unchanged.
        a.feed("phone_back")
        b.feed("phone_back")
        assertEquals(3, a.awaitCall().number)
        assertEquals(3, b.awaitCall().number)
        assertEquals(mainBefore, DeviceSupport.mainScreenState(shell))

        finish(a)
        finish(b)
        assertNotNull(pollUntil(20_000) { ended(first) })
        assertNotNull(pollUntil(20_000) { ended(second) })
        assertNotNull(pollUntil(15_000) { DeviceTaskState.busy.value.takeIf { !it } })
        assertFalse("Mina display left behind", "mina-agent" in sh("dumpsys display"))
    }

    /** Both tasks need to open settings: the one opened later gets app_in_use_by_other_task, and does not take the settings away from the first task. */
    @Test
    fun sameAppSecondTaskGetsConflictError() {
        val (a, b) = models
        val first = startTask("设置版本号", a)
        a.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        assertEquals(2, a.awaitCall().number)
        val displayA = displayOf(first)
        val settingsTask = shell.topTaskId(displayA)

        startTask("设置里看存储", b)
        b.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        val result = b.awaitCall().messages.last().toString()
        assertTrue(result, "app_in_use_by_other_task" in result)
        assertEquals(settingsTask, shell.topTaskId(displayA))

        finish(a)
        finish(b)
        assertNotNull(pollUntil(15_000) { DeviceTaskState.busy.value.takeIf { !it } })
    }

    /**
     * Review Focus 5: The Mina process was killed while the two tasks were in progress. The PLAY_AUDIO
     * of both packages will be restored next time the two tasks are started, and Mina's virtual screen
     * will no longer exist. Killing the process will end together with the test, so it is run in two
     * times, with the process being killed from outside: the first time, arrange it, leave a record in
     * files/parallel-kill-test.json (two packages include the original PLAY_AUDIO and the switch before
     * starting), and then wait; after seeing the record appear, run `adb shell am force-stop <Mina package name>`
     * on the development machine (this time it reports a crash). The second time (the process is
     * restarted and restored during startup), press the record check and restore the switch to the one
     * in the record (the ending was not run when it was killed for the first time). Note: force-stop
     * will remove Mina from the enabled accessibility service (system behavior), and it must be
     * re-enabled before running it for the second time.
     */
    private val killRecord get() = File(context.filesDir, "parallel-kill-test.json")

    @Ignore("two-run manual procedure: needs an external `adb shell am force-stop` while this test waits; a normal run would sit for 120 s and then fail")
    @Test
    fun killDuringTwoTasksRecovers() {
        val record = killRecord
        if (record.exists()) {
            val saved = JSONObject(record.readText())
            record.delete()
            try {
                for (pkg in saved.keys()) {
                    if (pkg == FLAGS_KEY) continue
                    assertEquals(pkg, saved.getString(pkg), pollUntil(60_000) { shell.getAudioMode(pkg)?.takeIf { it == saved.getString(pkg) } })
                }
                assertFalse("Mina display left behind", "mina-agent" in sh("dumpsys display"))
            } finally {
                // It will also be restored in the ending; it will be restored first here, and the switch
                // will not be left on when the assertion fails.
                FeatureFlags.get(context).setSpeed(priorFlags)
            }
            return
        }
        val clock = clockPackage()
        val prior = JSONObject().put(SETTINGS, shell.getAudioMode(SETTINGS)).put(clock, shell.getAudioMode(clock))
            .put(FLAGS_KEY, priorFlags.describe())
        val (a, b) = models
        startTask("设置版本号", a)
        startTask("在时钟里新建一个带标记的闹钟", b)
        a.feed("phone_launch_app", """{"package":"$SETTINGS"}""")
        b.feed("phone_launch_app", """{"package":"$clock"}""")
        assertEquals(2, a.awaitCall().number)
        assertEquals(2, b.awaitCall().number)
        assertEquals("ignore", pollUntil(5_000) { shell.getAudioMode(SETTINGS)?.takeIf { it == "ignore" } })
        assertEquals("ignore", pollUntil(5_000) { shell.getAudioMode(clock)?.takeIf { it == "ignore" } })
        record.writeText(prior.toString())
        // The suicide command placed in the background within the process will not be executed (ends
        // with the test shell session) and can only be terminated from the outside: after seeing the
        // record file appear, run adb shell am force-stop <package name> on the development machine. If you can't
        // wait, clear the record and end it as failure.
        Thread.sleep(KILL_WAIT_MS)
        record.delete()
        error("Mina was not killed: run `adb shell am force-stop ${context.packageName}` after ${record.name} appears, then rerun this test")
    }

    private companion object {
        const val FLAGS_KEY = "speed_flags"
        const val KILL_WAIT_MS = 120_000L
    }
}
