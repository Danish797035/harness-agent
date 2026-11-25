package com.meenai.mina.core.tool

import com.meenai.mina.core.model.Part

/**
 * An action in the tool (page script) that does not move the interface is returned to the loop for
 * execution: guards, lock screen levels, irreversible determinations, and running records are all the
 * same as when the model calls this tool alone. When it is stopped and not executed, the error in the
 * result is confirmation_required / guard_stopped / target_unresolved, and executed is false.
 */
fun interface SubActions {
    suspend fun run(call: Part.ToolCall): ToolExecution
}
