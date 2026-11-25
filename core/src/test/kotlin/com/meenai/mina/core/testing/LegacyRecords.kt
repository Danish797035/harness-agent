package com.meenai.mina.core.testing

import com.meenai.mina.core.record.RunEvent
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * For testing: a screen_text in the old running record (the element text given to the model by reading
 * the screen), read it in the same way as reading the old record (it reads observed number 0).
 */
fun legacyScreenText(
    atMs: Long, step: Int, app: String?, names: List<String>, site: String? = null, inputs: List<String>? = null, focused: String? = null,
): RunEvent.Observed = RunEvent.decode(buildJsonObject {
    put("type", "screen_text"); put("atMs", atMs); put("step", step)
    app?.let { put("app", it) }
    put("names", JsonArray(names.map(::JsonPrimitive)))
    site?.let { put("site", it) }
    inputs?.let { put("inputs", JsonArray(it.map(::JsonPrimitive))) }
    focused?.let { put("focused", it) }
}.toString()) as RunEvent.Observed
