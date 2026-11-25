package com.meenai.mina.settings

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApprovalStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun unreadableFileFallsBackToAsking() {
        val f = tmp.root.resolve("a.json").apply { writeText("{not json") }
        assertFalse(ApprovalStore(f).autonomous.value)
    }

    @Test fun olderFilesKeepTheirAutonomousSwitch() {
        // The old version of the document also contains approval levels and long-term authorization: no
        // longer recognized, read-only inquiry/autonomous
        val f = tmp.root.resolve("a.json").apply {
            writeText("""{"mode":"AUTO","autonomous":true,"grants":[{"action":"send","app":"com.chat","target":"张三","element":"发送"}]}""")
        }
        assertTrue(ApprovalStore(f).autonomous.value)
        ApprovalStore(f).setAutonomous(false)
        assertFalse("mode" in f.readText() || "grants" in f.readText())
    }

    private fun control() = ApprovalControl(ApprovalStore(tmp.root.resolve("a.json")))

    @Test fun autonomousIsOffByDefaultAndSurvivesARestart() {
        val f = tmp.root.resolve("a.json")
        assertFalse(ApprovalStore(f).autonomous.value)
        ApprovalControl(ApprovalStore(f)).setAutonomous(true)
        val again = ApprovalStore(f)
        assertTrue(again.autonomous.value)
        assertFalse(tmp.root.resolve("a.json.tmp").exists())
        ApprovalControl(again).setAutonomous(false)
        assertFalse(ApprovalStore(f).autonomous.value)
    }

    @Test fun runsInAskHaveAGateAndRunsInAutonomousDoNot() {
        val c = control()
        val ask = c.start()
        assertFalse(ask.startedAutonomous)
        assertFalse(ask.autonomous())
        assertNotNull(ask.gate())
        c.setAutonomous(true)
        val auto = c.start()
        assertTrue(auto.startedAutonomous)
        assertTrue(auto.autonomous())
        assertNull(auto.gate())
    }

    @Test fun remoteRunsAlwaysAskAndIgnoreTheSwitch() {
        val c = control()
        c.setAutonomous(true)
        // The task assigned to the computer by MCP: Even if the switch is on, the computer will start
        // working according to the query, and even if the switch is changed in the middle, it will not
        // follow the instructions.
        val remote = c.start(remote = true)
        assertFalse(remote.startedAutonomous)
        assertFalse(remote.autonomous())
        assertNotNull(remote.gate())
        c.setAutonomous(false)
        c.setAutonomous(true)
        assertFalse(remote.autonomous())
    }

    @Test fun switchingFollowsEveryRunningTask() {
        val c = control()
        val a = c.start()
        val b = c.start()
        c.setAutonomous(true)
        // The running tasks will no longer ask for payment from the next step; they have cards when they
        // start work, this remains unchanged.
        assertTrue(a.autonomous())
        assertTrue(b.autonomous())
        assertFalse(a.startedAutonomous)
        c.setAutonomous(false)
        assertFalse(a.autonomous())
        assertFalse(b.autonomous())
        // Change after completion and never touch that task again; press the new switch to start the
        // next task
        c.end(a)
        c.setAutonomous(true)
        assertFalse(a.autonomous())
        assertTrue(b.autonomous())
        assertTrue(c.start().startedAutonomous)
    }
}
