package com.meenai.mina

import androidx.test.platform.app.InstrumentationRegistry

/** Device tests read status from the TaskCenter held by the App; TaskService is only responsible for the Android host. */
object DeviceTaskState {
    private val center get() = (InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HarnessApp).taskState
    val state get() = center.state
    val busy get() = center.busy
    val takeoverStage get() = center.takeoverStage
    val conversationId get() = center.conversationId
    val frames get() = center.frames
    val runs get() = center.runs
    val summarizing get() = center.summarizing
    val endedRunIds get() = center.endedRunIds
    fun run(conversationId: String) = center.run(conversationId)
}
