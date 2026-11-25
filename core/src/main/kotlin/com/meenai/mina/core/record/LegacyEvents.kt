package com.meenai.mina.core.record

import com.meenai.mina.core.observation.ObsKind
import com.meenai.mina.core.observation.PlaceKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The renamed events in the old running records should be changed to the current writing before reading
 * (only changed during reading, the file will not be moved):
 * - - screen_text (element text given to the model by screen reading) → observed: an observation read,
 * the location is the site or App at that time, numbered 0;
 * - - screen_saved (save the next frame of screen reading) → media.
 */
internal object LegacyEvents {
    fun upgrade(e: JsonElement): JsonElement {
        val o = e as? JsonObject ?: return e
        return when ((o["type"] as? JsonPrimitive)?.contentOrNull) {
            "screen_text" -> buildJsonObject {
                put("type", "observed")
                o["atMs"]?.let { put("atMs", it) }
                o["step"]?.let { put("step", it) }
                put("kind", ObsKind.Read.wire)
                val site = o.str("site")
                val app = o.str("app")
                // At that time, the website was reported to the website and the app was reported to the
                // mobile phone.
                (site?.let { PlaceKind.Site to it } ?: app?.let { PlaceKind.App to it })?.let { (k, n) ->
                    put("place", buildJsonObject { put("kind", k.wire); put("name", n) })
                    put("source", if (k == PlaceKind.Site) "web" else "phone")
                }
                put("text", (o["names"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty().joinToString("\n"))
                o["inputs"]?.let { put("inputs", it) }
                o["focused"]?.let { put("focused", it) }
            }
            "screen_saved" -> buildJsonObject {
                put("type", "media")
                o["atMs"]?.let { put("atMs", it) }
                o["screenshotId"]?.let { put("mediaId", it) }
                o["step"]?.let { put("step", it) }
                o["app"]?.let { put("place", it) }
            }
            else -> o
        }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
}
