package com.meenai.mina.core.cap.phone

import com.meenai.mina.core.assembly.toolDispatcher
import com.meenai.mina.core.prompt.Feedback
import com.meenai.mina.core.tool.Args
import com.meenai.mina.core.tool.FakeScreen
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.ToolDispatcher
import com.meenai.mina.core.uistate.ScreenPort
import com.meenai.mina.core.uistate.SystemCommandResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandSteerTest {
    @Test fun recognisesCommandsThatEmulateDedicatedTools() {
        assertEquals(CommandSteer.Purpose.Gesture, CommandSteer.purpose("input -d \"\$MINA_DISPLAY_ID\" tap 500 800"))
        assertEquals(CommandSteer.Purpose.Gesture, CommandSteer.purpose("input touchscreen -d 7 swipe 1 2 3 4 300"))
        assertEquals(CommandSteer.Purpose.Type, CommandSteer.purpose("input -d 7 tap 500 900; input -d 7 text hello"))
        assertEquals(CommandSteer.Purpose.Key, CommandSteer.purpose("sleep 1 && input -d 7 keyevent KEYCODE_ENTER"))
        assertEquals(CommandSteer.Purpose.Launch, CommandSteer.purpose("am start --display 7 -n com.example/.Main"))
        assertEquals(CommandSteer.Purpose.Launch, CommandSteer.purpose("monkey -p com.example -c android.intent.category.LAUNCHER 1"))
        assertEquals(CommandSteer.Purpose.Launch, CommandSteer.purpose("cmd activity start-activity -n com.example/.Main"))
        // Share text: Actions written in full or abbreviated are accepted; shared files are calculated
        // as normal startup
        assertEquals(CommandSteer.Purpose.Share, CommandSteer.purpose("am start --display 7 -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT 'hi' -p com.example.chat"))
        assertEquals(CommandSteer.Purpose.Share, CommandSteer.purpose("am start -a SEND -t text/plain --es android.intent.extra.TEXT hi"))
        assertEquals(CommandSteer.Purpose.Launch, CommandSteer.purpose("am start -a android.intent.action.SENDTO -d smsto:10086"))
        assertEquals(CommandSteer.Purpose.Launch, CommandSteer.purpose("am start -a android.intent.action.SEND -t image/png --eu android.intent.extra.STREAM content://x/1"))
        // Query commands are as usual
        assertNull(CommandSteer.purpose("pm list packages"))
        assertNull(CommandSteer.purpose("dumpsys input_method | grep mInputShown"))
        assertNull(CommandSteer.purpose("settings get system screen_off_timeout"))
    }

    private fun screen(vararg codes: Int) = object : ScreenPort by FakeScreen() {
        private val queue = ArrayDeque(codes.toList())
        override suspend fun systemCommand(command: String) = SystemCommandResult(queue.removeFirstOrNull() ?: 0, "out")
    }

    private suspend fun ToolDispatcher.run(command: String): ToolExecution =
        PhoneCommand.execute(Args(PhoneCommand.def, buildJsonObject { put("command", command) }), ctx)

    private fun ToolExecution.hint() = content["hint"]?.jsonPrimitive?.content

    private class Counting : ScreenPort by FakeScreen() {
        val ran = mutableListOf<String>()
        override suspend fun systemCommand(command: String): SystemCommandResult { ran += command; return SystemCommandResult(0, "out") }
    }

    private fun ToolExecution.ok() = content["ok"]!!.jsonPrimitive.content == "true"
    private fun ToolExecution.error() = content["error"]?.jsonPrimitive?.content

    @Test fun emulatingCommandsAreSteeredButStillRun() = runTest {
        val d = toolDispatcher(screen(0))
        val r = d.run("input -d 7 text hi")
        assertEquals("true", r.content["ok"]!!.jsonPrimitive.content)
        assertEquals(Feedback["system_command.use_type"], r.hint())
        assertNull(d.run("pm list packages").hint())
    }

    @Test fun afterTheFirstHintEmulatingCommandsAreRefused() = runTest {
        val screen = Counting()
        val d = toolDispatcher(screen)
        assertTrue(d.run("input -d 7 tap 500 900").ok())
        // Already reminded: I won’t execute another type of imitated command, I will just tell you which
        // tool to use.
        for ((command, key) in listOf(
            "input -d 7 text hi" to "system_command.use_type",
            "input -d 7 keyevent 66" to "system_command.use_key",
            "am start --display 7 -n com.example/.Main" to "system_command.use_launch",
        )) {
            val r = d.run(command)
            assertFalse(command, r.ok())
            assertEquals("use_phone_tool", r.error())
            assertEquals(Feedback.format("system_command.refused", "use" to Feedback[key]), r.hint())
        }
        // The commands to check status and read settings are as usual.
        assertTrue(d.run("settings get system screen_off_timeout").ok())
        assertTrue(d.run("dumpsys window | grep mCurrentFocus").ok())
        assertEquals(listOf("input -d 7 tap 500 900", "settings get system screen_off_timeout", "dumpsys window | grep mCurrentFocus"), screen.ran)
    }

    @Test fun textSharesAreAlwaysPointedToTheShareTool() = runTest {
        val screen = Counting()
        val r = toolDispatcher(screen).run("am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT hi -p com.example.chat")
        assertFalse(r.ok())
        assertEquals("use_phone_tool", r.error())
        val hint = r.hint()!!
        assertTrue(hint, Feedback["system_command.use_share"] in hint && "phone_share_text" in hint)
        assertTrue(screen.ran.isEmpty())
    }

    @Test fun refusalStateIsPerRun() = runTest {
        toolDispatcher(Counting()).run("input -d 7 tap 1 1")
        // New task with new count: execute as usual for the first time, only remind
        val fresh = toolDispatcher(Counting()).run("input -d 7 tap 1 1")
        assertTrue(fresh.ok())
        assertEquals(Feedback["system_command.use_tap"], fresh.hint())
    }

    @Test fun repeatedFailuresAreSteeredUntilOneSucceeds() = runTest {
        val d = toolDispatcher(screen(1, 2, 0, 1))
        assertNull(d.run("pm path x").hint())
        assertEquals(Feedback["system_command.failed_again"], d.run("pm path y").hint())
        // Clear once successfully
        assertNull(d.run("pm path z").hint())
        assertNull(d.run("pm path w").hint())
        // Imitation of specialized tools failed again: both sentences were said (executed the first
        // time; not executed after that)
        val d2 = toolDispatcher(screen(1, 1))
        d2.run("pm path x")
        val both = d2.run("input -d 7 keyevent 66").hint()!!
        assertTrue(both, Feedback["system_command.use_key"] in both && Feedback["system_command.failed_again"] in both)
        assertFalse(Feedback["system_command.use_type"] in both)
    }
}
