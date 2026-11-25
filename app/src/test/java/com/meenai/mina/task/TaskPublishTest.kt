package com.meenai.mina.task

import com.meenai.mina.core.agent.FailKind
import com.meenai.mina.core.agent.TaskState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The final state reported by the loop is written out without publishing: the final state is only
 * written by TaskRun.end after remembering the run number. Otherwise, the interface receives the end
 * first, and the run number obtained is empty, and there is no "view process" on the card.
 */
class TaskPublishTest {
    @Test fun loopTerminalStatesAreNotPublished() {
        assertFalse(TaskService.loopMayPublish(TaskState.Succeeded("ok")))
        assertFalse(TaskService.loopMayPublish(TaskState.Failed("x", FailKind.GaveUp)))
        assertFalse(TaskService.loopMayPublish(TaskState.Cancelled))
        assertFalse(TaskService.loopMayPublish(TaskState.Idle))
        assertTrue(TaskService.loopMayPublish(TaskState.Running(1, "看屏幕", "phone_observe")))
        assertTrue(TaskService.loopMayPublish(TaskState.WaitingInput("哪个?", null)))
    }
}
