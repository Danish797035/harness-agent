package com.meenai.mina.core.visual

import com.meenai.mina.core.prompt.Feedback
import com.meenai.mina.core.tool.RECALL_KEEP
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.fail
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The last [RECALL_KEEP] screen readings are for view_past_screens to review; no pictures are left on protected pages and payment pages. */
internal class Recall(val id: Int, val step: Int, val app: String?, val png: ByteArray?, val elementsText: String)

/**
 * The screen number and review:screenshotId shared by several screens are counted upwards in a task, and
 * view_past_screens can be found no matter which screen it is. [step] is the round of this call (the
 * "Nth step" of the cycle), look back at the step in the record.
 */
internal class Shots {
    var step = 0
    var lastId = 0
        private set
    val recalls = ArrayDeque<Recall>()

    fun next(): Int = ++lastId

    fun remember(r: Recall) {
        recalls.addLast(r)
        while (recalls.size > RECALL_KEEP) recalls.removeFirst()
    }

    /**
     * The screenshotId of the screen is regarded as snapshotId (this is often done when looking at
     * pictures): the screen number is only used for review, not for clicking. The screen reading number
     * is never a pure number. It is a mistake to use a pure number that does not exceed the screen
     * number that has been sent. Please tell the truth which one should be used.
     */
    fun idAsSnapshot(o: JsonObject): ToolExecution? {
        val p = o["snapshotId"] as? JsonPrimitive ?: return null
        val n = p.content.trim().toIntOrNull() ?: return null
        if (n < 1 || n > lastId) return null
        return fail("ref_expired", Feedback.format("screenshot_id_as_snapshot", "id" to n))
    }
}
