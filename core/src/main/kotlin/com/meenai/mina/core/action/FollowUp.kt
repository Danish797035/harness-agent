package com.meenai.mina.core.action

import com.meenai.mina.core.guard.GuardVerdict
import com.meenai.mina.core.tool.ToolExecution
import kotlinx.serialization.json.JsonObject

/** There are several places that the caller inserts into the execution entry (only for single-stepping of the model): waiting for the user's outcome and final processing before handing it over to the model. */
interface FollowUp {
    /** After execution and before guard verification: wait for the user's outcome (confirmation, questioning, takeover) to be replaced by the final result; returning null means the task is cancelled. */
    suspend fun resolve(exec: ToolExecution): ToolExecution? = exec

    /** After guard checking and before recording: return the final result and the content handed to the model (also recorded in the running record). */
    suspend fun present(exec: ToolExecution, verdicts: List<GuardVerdict>): Presented = Presented(exec, exec.content)

    /** Stopped by the confirmation level: after the instructions are handed over to the model, but before being recorded in the running record. */
    fun refused(content: JsonObject) {}

    companion object {
        val NONE = object : FollowUp {}
    }
}

data class Presented(val exec: ToolExecution, val content: JsonObject)
