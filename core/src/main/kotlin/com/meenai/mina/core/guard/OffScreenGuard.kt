package com.meenai.mina.core.guard

import com.meenai.mina.core.model.Part
import kotlinx.serialization.json.JsonObject

/** Guards for tools that do not move the screen (such as sandboxes), one for each task; screen guards do not look at these tools. */
interface OffScreenGuard {
    /** The conclusion of calling a halt; if not called, it is null. */
    val stop: GuardVerdict?

    /** The execution result is replaced when the quota of this type of tool is used up; if it is not used up, it is null. */
    fun exhausted(tool: String): JsonObject?

    /** Adjust the call according to the remaining amount before execution; return as is without adjustment. */
    fun capTimeout(call: Part.ToolCall, args: JsonObject): Part.ToolCall

    /** Conclusion after execution. */
    fun result(tool: String, args: JsonObject, content: JsonObject): List<GuardVerdict>
}
