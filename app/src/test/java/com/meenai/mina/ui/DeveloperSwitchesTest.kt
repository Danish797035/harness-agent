package com.meenai.mina.ui

import org.junit.Rule
import com.meenai.mina.i18n.ChineseTexts
import com.meenai.mina.core.agent.SpeedFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperSwitchesTest {
    @get:Rule val texts = ChineseTexts()

    private val speed by lazy { DeveloperSwitches.speed(SpeedFlags.OFF) }
    private val experimental by lazy { DeveloperSwitches.experimental(SpeedFlags.OFF) }

    /** Each of the eight switches under the original "Speed" only appears once in a group, and the IDs are not repeated. */
    @Test
    fun eightSwitchesInTwoGroups() {
        assertEquals(
            listOf("adaptive-vision", "inline-screen", "batch-steps", "deep-links", "light-steps"),
            speed.map { it.id },
        )
        assertEquals(listOf("replay", "parallel-tasks", "structured-compaction"), experimental.map { it.id })
        assertEquals(8, (speed + experimental).map { it.id }.toSet().size)
    }

    /** Turning a switch only changes its own item, and the others remain at their original values. */
    @Test
    fun toggleChangesOnlyItsOwnFlag() {
        val base = SpeedFlags(replay = true, deepLinks = true)
        (DeveloperSwitches.speed(base) + DeveloperSwitches.experimental(base)).forEach { sw ->
            val after = sw.change(!sw.checked)
            val changed = listOf(
                base.adaptiveVision != after.adaptiveVision, base.inlineScreen != after.inlineScreen,
                base.batchSteps != after.batchSteps, base.deepLinks != after.deepLinks, base.lightSteps != after.lightSteps,
                base.replay != after.replay, base.parallelTasks != after.parallelTasks,
                base.structuredCompaction != after.structuredCompaction,
            )
            assertEquals("${sw.id} should change exactly one flag", 1, changed.count { it })
        }
    }

    @Test
    fun checkedFollowsFlags() {
        assertTrue(DeveloperSwitches.experimental(SpeedFlags(replay = true)).first { it.id == "replay" }.checked)
        assertFalse(DeveloperSwitches.experimental(SpeedFlags.OFF).any { it.checked })
        assertFalse(DeveloperSwitches.speed(SpeedFlags.OFF).any { it.checked })
    }
}
