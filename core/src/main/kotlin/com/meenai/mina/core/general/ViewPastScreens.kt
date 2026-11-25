package com.meenai.mina.core.general

import com.meenai.mina.core.prompt.Feedback
import com.meenai.mina.core.prompt.Prompts
import com.meenai.mina.core.tool.AgentTool
import com.meenai.mina.core.tool.Args
import com.meenai.mina.core.tool.Concurrency
import com.meenai.mina.core.tool.RECALL_KEEP
import com.meenai.mina.core.tool.RECALL_MAX_IDS
import com.meenai.mina.core.tool.Risk
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.tool.ToolDef
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.Verb
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Look back at the previous screen reading (any screen will work): the main model that can see the picture will get the screenshot at that time, and the rest will be the element text at that time. */
internal object ViewPastScreens : AgentTool {
    override val verb = Verb.ViewPastScreens
    override val risk = Risk.Read
    override val withScreens = true
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef("view_past_screens",
        Prompts.tool("view_past_screens", "keep" to RECALL_KEEP),
        mapOf("ids" to buildJsonObject {
            put("type", "array"); put("description", "1-$RECALL_MAX_IDS screenshotIds from earlier screen reads.")
            put("minItems", 1); put("maxItems", RECALL_MAX_IDS)
            put("items", buildJsonObject { put("type", "integer"); put("minimum", 1) })
        }), listOf("ids"))

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val ids = a.intList("ids", 1, RECALL_MAX_IDS)!!.distinct()
        val images = mutableListOf<ByteArray>()
        val imageIds = mutableListOf<Int?>()
        val recalls = ctx.shots.recalls
        val found = ids.mapNotNull { id -> recalls.firstOrNull { it.id == id } }
        val missing = ids.filter { id -> found.none { it.id == id } }
        val content = buildJsonObject {
            put("ok", missing.isEmpty())
            put("screenshots", JsonArray(found.map { r ->
                buildJsonObject {
                    put("id", r.id); put("step", r.step); put("app", r.app)
                    if (r.png != null) { images += r.png; imageIds += r.id; put("imageAttached", true) } else put("elementsText", r.elementsText)
                }
            }))
            if (missing.isNotEmpty()) {
                put("missing", JsonArray(missing.map(::JsonPrimitive)))
                put("error", "screenshot_not_found")
                put("message", Feedback.format("recall_not_found", "keep" to RECALL_KEEP))
                put("recoverable", true)
                // What I can’t find is the old screen, not the phone operation failure.
                put("counts_as_failure", false)
            }
        }
        return ToolExecution(content, images = images, imageIds = imageIds)
    }
}
