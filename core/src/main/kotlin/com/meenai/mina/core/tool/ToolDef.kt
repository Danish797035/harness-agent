package com.meenai.mina.core.tool

import com.meenai.mina.core.model.ToolSpec
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** A tool is given the definition of the model; the parameter list is used both to generate the schema and to reject unknown fields. */
internal class ToolDef(
    val name: String,
    val description: String,
    val props: Map<String, JsonObject> = emptyMap(),
    val required: List<String> = emptyList(),
) {
    val allowed: Set<String> = props.keys + "reason"
    /** The reason in the parameter is the tool's own field (such as the reason for the takeover), not an additional description of the purpose. */
    val ownReason = "reason" in props

    fun spec() = ToolSpec(name, description, buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            props.forEach { (k, v) -> put(k, v) }
            if (!ownReason) put("reason", str(REASON_NOTE))
        })
        put("required", JsonArray(required.map { JsonPrimitive(it) }))
        put("additionalProperties", false)
    }.toString())
}

internal fun str(d: String, enum: List<String>? = null) = buildJsonObject {
    put("type", "string"); put("description", d)
    enum?.let { put("enum", JsonArray(it.map(::JsonPrimitive))) }
}

internal fun int(d: String, min: Int? = null, max: Int? = null) = buildJsonObject {
    put("type", "integer"); put("description", d)
    min?.let { put("minimum", it) }; max?.let { put("maximum", it) }
}

internal fun bool(d: String) = buildJsonObject { put("type", "boolean"); put("description", d) }

internal fun strArray(d: String, max: Int) = buildJsonObject {
    put("type", "array"); put("description", d); put("maxItems", max)
    put("items", buildJsonObject { put("type", "string") })
}

/**
 * The reason (intention statement) attached to each tool: The interface treats it as an explanation of
 * this step for the user, so it is written in the language of the user's most recent sentence just like
 * the words to the user.
 */
internal const val REASON_NOTE = "Optional short note on why you are calling this, in the user's language; shown to the user as the step label."

internal const val COORD = "Normalized 0-999 (0,0 is top-left, 999,999 is bottom-right) over the latest screen."
internal const val REF = "Element ref such as @e3 from the latest read of this screen; must be paired with its snapshotId."
internal const val SNAP = "snapshotId of the screen read that produced the ref. A mismatch returns ref_expired."
