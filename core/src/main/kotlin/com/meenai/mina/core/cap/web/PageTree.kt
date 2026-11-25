package com.meenai.mina.core.cap.web

import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.uistate.UiElement
import com.meenai.mina.core.uistate.normalize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.max
import kotlin.math.roundToInt
import com.meenai.mina.core.uistate.WebInfo

/**
 * Page script output → ScreenSnapshot. The input is in the shape of
 * `{"els":[{"r":"button","n":"search","x":120,"y":40,"w":80,"h":32,"id":"go","c ":1,"e":0,"s":0}],"vw":412,"vh":915,"dpr":2.625,"trunc":false,"mut":17}`,
 * and the coordinates are CSS pixels. Center is replaced by WebView pixels (converted by viewportW / vw
 * first, followed by dpr), and is clamped in the viewport; bounds is normalized to 0-999 according to
 * the viewport, which is the same coordinate system as the mobile phone. There are also optional tags:
 * `ph`=name is the prompt (placeholder) of the input box rather than the filled content, `f`=has input
 * focus; `r` of the file selection control is "file". ref is numbered according to the array position:
 * the broken entry also occupies a number, which matches the numbering table in the page script. Bad
 * input is given to an empty list, no exception is thrown: screen reading failure should not crash the
 * entire round.
 */
object PageTree {
    fun parse(json: String, viewportW: Int, viewportH: Int, snapshotId: String, info: WebInfo): ScreenSnapshot {
        val root = runCatching { Json.parseToJsonElement(json) as? JsonObject }.getOrNull()
        val cssW = root.num("vw")?.takeIf { it > 0 }
        val scale = cssW?.takeIf { viewportW > 0 }?.let { viewportW / it }
            ?: root.num("dpr")?.takeIf { it > 0 }
            ?: 1.0
        val elements = (root?.get("els") as? JsonArray).orEmpty()
            .mapIndexedNotNull { i, e -> (e as? JsonObject)?.let { element(i + 1, it, scale, viewportW, viewportH) } }
        return ScreenSnapshot(
            snapshotId = snapshotId,
            packageName = null,
            elements = elements,
            width = viewportW,
            height = viewportH,
            web = info.copy(truncated = root.flag("trunc")),
        )
    }

    private fun element(n: Int, o: JsonObject, scale: Double, w: Int, h: Int): UiElement {
        val l = px(o.num("x"), scale)
        val t = px(o.num("y"), scale)
        val r = px((o.num("x") ?: 0.0) + (o.num("w") ?: 0.0), scale)
        val b = px((o.num("y") ?: 0.0) + (o.num("h") ?: 0.0), scale)
        val id = o.str("id")?.takeIf { it.isNotBlank() }
        return UiElement(
            ref = "@e$n",
            role = o.str("r") ?: "text",
            name = o.str("n") ?: "",
            centerX = ((l + r) / 2).coerceIn(0, max(0, w - 1)),
            centerY = ((t + b) / 2).coerceIn(0, max(0, h - 1)),
            bounds = listOf(normalize(l, w), normalize(t, h), normalize(r, w), normalize(b, h)),
            resourceId = id?.let { if (it.startsWith("#")) it else "#$it" },
            clickable = o.flag("c"),
            editable = o.flag("e"),
            scrollable = o.flag("s"),
            hint = o.flag("ph"),
            focused = o.flag("f"),
            secret = o.flag("pw"),
            placeholder = o.str("ph")?.takeIf { it.isNotBlank() },
            searchField = o.flag("sr"),
            keyShortcuts = o.str("k")?.split(' ')?.filter(String::isNotBlank)?.take(MAX_SHORTCUTS).orEmpty(),
        )
    }

    /** The number given on the page may be a huge value or infinite: clamp it first and then round it. Don't let the rounding overflow and the center point reach the horizon. */
    private fun px(css: Double?, scale: Double): Int = ((css ?: 0.0) * scale).coerceIn(-PX_LIMIT, PX_LIMIT).roundToInt()

    private const val PX_LIMIT = 1e6
    private const val MAX_SHORTCUTS = 8

    private fun JsonObject?.num(key: String): Double? =
        (this?.get(key) as? JsonPrimitive)?.doubleOrNull?.let { if (it.isFinite()) it.coerceIn(-PX_LIMIT, PX_LIMIT) else 0.0 }

    private fun JsonObject.str(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

    /** Both 0/1 and true/false are recognized. */
    private fun JsonObject?.flag(key: String): Boolean {
        val p = this?.get(key) as? JsonPrimitive ?: return false
        return p.booleanOrNull ?: ((p.intOrNull ?: 0) != 0)
    }
}
