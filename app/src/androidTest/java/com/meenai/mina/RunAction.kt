package com.meenai.mina

import com.meenai.mina.core.action.ActionExecutor
import com.meenai.mina.core.action.ActionRecorder
import com.meenai.mina.core.action.Origin
import com.meenai.mina.core.action.execution
import com.meenai.mina.core.guard.OperationGuards
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.record.RunSink
import com.meenai.mina.core.tool.ToolDispatcher
import com.meenai.mina.core.tool.ToolExecution

/** Directly make a tool call in the device test: the same execution entry as the product (no confirmation level, no waiting for lock screen). */
suspend fun ToolDispatcher.runAction(call: Part.ToolCall): ToolExecution =
    ActionExecutor(this, OperationGuards(), null, gate = {}, recorder = ActionRecorder(RunSink.NONE)).run(call, Origin.Model(0)).execution()
