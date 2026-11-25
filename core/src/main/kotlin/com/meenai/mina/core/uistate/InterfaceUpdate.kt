package com.meenai.mina.core.uistate

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** If the difference in text exceeds the full amount, the full amount will be issued: the difference will not save much, and it will be more difficult to read. */
private const val MAX_DIFF_RATIO = 0.5
private const val DEFAULT_MAX_ELEMENTS = 200

/**
 * The difference between the two screens (aligned by stable number), gives the `screen` field of the
 * write operation result:
 * `{"snapshotId","base","currentApp","full":false,"added":[element],"removed":[{ref,name,role}],"changed":[element+"was"],"unchanged":N}`.
 * If there is no base, [baseVisible] is false (the model can no longer see the screen reading that the
 * difference depends on), the front-end App has changed, or the difference text exceeds half of the full
 * amount, the full amount is sent: `{"snapshotId","currentApp","full":true,"elements":[…],"truncated"}`.
 * The difference is calculated based on the complete element list of the two screens. The total number
 * of added/changed elements during output does not exceed [maxElements], and the excess is marked
 * truncated. When the mobile phone snapshot has a window tree, the full result will have tree, and the
 * difference result will have treeChanges; the caller passes the complete number of elements to the
 * mobile phone.
 */
object InterfaceUpdate {
    fun between(base: ModelSnapshot?, current: ModelSnapshot, baseVisible: Boolean, maxElements: Int = DEFAULT_MAX_ELEMENTS): JsonObject {
        val (all, _) = ScreenJson.elements(current.screen, Int.MAX_VALUE)
        fun full() = buildJsonObject {
            put("snapshotId", current.snapshotId)
            put("currentApp", current.port.packageName)
            put("full", true)
            flags(current)
            put("elements", JsonArray(all.take(maxElements)))
            put("truncated", all.size > maxElements)
            if (current.screen.windows.isNotEmpty()) put("tree", UiTreeJson.windows(current.screen))
        }
        if (base == null || !baseVisible || base.port.packageName != current.port.packageName) return full()
        if (current.screen.windows.isNotEmpty() && !UiTreeJson.sameWindows(base.screen, current.screen)) return full()
        val before = ScreenJson.elements(base.screen, Int.MAX_VALUE).first.associateBy(::ref)
        val now = all.associateBy(::ref)
        val added = all.filter { ref(it) !in before }
        val removed = before.filter { it.key !in now }.values.map { e ->
            val o = e as JsonObject
            buildJsonObject { put("ref", ref(e)); o["name"]?.let { put("name", it) }; o["role"]?.let { put("role", it) } }
        }
        val changed = all.mapNotNull { e ->
            val old = before[ref(e)] ?: return@mapNotNull null
            if (old == e) return@mapNotNull null
            val was = (old as JsonObject)["name"]
            if (was != (e as JsonObject)["name"]) JsonObject(e + ("was" to (was ?: JsonPrimitive("")))) else e
        }
        val diffChars = JsonArray(added).toString().length + JsonArray(removed).toString().length + JsonArray(changed).toString().length
        if (diffChars > all.toString().length * MAX_DIFF_RATIO) return full()
        val treeChanges = if (current.screen.windows.isEmpty()) null else UiTreeJson.changes(base.screen, current.screen)
        if (treeChanges != null && treeChanges.toString().length > UiTreeJson.windows(current.screen).toString().length * MAX_DIFF_RATIO) return full()
        val shownAdded = added.take(maxElements)
        val shownChanged = changed.take((maxElements - shownAdded.size).coerceAtLeast(0))
        return buildJsonObject {
            put("snapshotId", current.snapshotId)
            put("base", base.snapshotId)
            put("currentApp", current.port.packageName)
            put("full", false)
            flags(current)
            put("added", JsonArray(shownAdded))
            put("removed", JsonArray(removed))
            put("changed", JsonArray(shownChanged))
            put("unchanged", all.size - added.size - changed.size)
            if (shownAdded.size + shownChanged.size < added.size + changed.size) put("truncated", true)
            treeChanges?.let { put("treeChanges", it) }
        }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.flags(s: ModelSnapshot) {
        if (s.port.changedDuringRead) put("screenChangedDuringRead", true)
    }

    private fun ref(e: JsonElement) = ((e as JsonObject)["ref"] as JsonPrimitive).content
}
