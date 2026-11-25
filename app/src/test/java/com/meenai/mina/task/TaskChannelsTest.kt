package com.meenai.mina.task

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TaskChannelsTest {
    // Fix 1 M4: "Need You" channel (takeover, question, confirm sharing) is set to high priority
    @Test fun attentionChannelCreatedHigh() {
        assertEquals(NotificationManager.IMPORTANCE_HIGH, TaskService.CHANNEL_IMPORTANCE[TaskService.CHANNEL_ATTENTION])
    }

    // The result will pop up once and the progress remains quiet
    @Test fun resultHighProgressQuiet() {
        assertEquals(NotificationManager.IMPORTANCE_HIGH, TaskService.CHANNEL_IMPORTANCE[TaskService.CHANNEL_RESULT])
        assertEquals(NotificationManager.IMPORTANCE_LOW, TaskService.CHANNEL_IMPORTANCE["task_progress"])
    }

    // The old result channel has been built at a lower level and cannot be adjusted higher: change the
    // id to a new one and delete the old id.
    @Test fun resultChannelUsesNewIdAndOldIsDeleted() {
        assertEquals("task_result_v2", TaskService.CHANNEL_RESULT)
        assertEquals("task_result", TaskService.OLD_CHANNEL_RESULT)
        assertNotEquals(TaskService.OLD_CHANNEL_RESULT, TaskService.CHANNEL_RESULT)
        assertFalse(TaskService.CHANNEL_IMPORTANCE.containsKey(TaskService.OLD_CHANNEL_RESULT))
    }
}
