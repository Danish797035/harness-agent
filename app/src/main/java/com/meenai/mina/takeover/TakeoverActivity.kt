package com.meenai.mina.takeover

import android.app.Activity
import android.os.Bundle
import com.meenai.mina.task.TaskService

/** Do not display the interface: Transfer the takeover button clicked in the notification to the task service and end it immediately. */
class TakeoverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent?.getIntExtra(TaskService.EXTRA_WAIT_KEY, -1)?.takeIf { it >= 0 }
        // Which task does the button belong to? When several tasks are waiting to be taken over at the
        // same time, they cannot fall on other tasks.
        val run = intent?.getStringExtra(TaskService.EXTRA_RUN)
        when (intent?.action) {
            TaskService.ACTION_TAKE_OVER -> TaskService.takeOver(this, key, run)
            TaskService.ACTION_HAND_BACK -> TaskService.handBack(this, key, run)
            TaskService.ACTION_FINISHED_MYSELF -> TaskService.finishedMyself(this, key, run)
            TaskService.ACTION_SKIP -> TaskService.skip(this, key, run)
        }
        finish()
    }
}
