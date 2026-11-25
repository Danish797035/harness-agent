package com.meenai.mina.core.userscreen

import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.uistate.TreeAdequacy
import com.meenai.mina.core.uistate.TreeQuality
import com.meenai.mina.core.uistate.VisibleText
import com.meenai.mina.core.uistate.VisibleTextLine
import com.meenai.mina.core.uistate.UiElement
import com.meenai.mina.core.uistate.normalize
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Where do the words on the user's screen come from? First, use the accessibility tree of the front-end
 * app; when the tree is not enough (the same set of judgments as the task screen reading,
 * [TreeAdequacy]), then recognize the words on the screen (OCR), only fill in the words that are not in
 * the tree, and remove the misrecognized lines. Each line has a center point, and the model that cannot
 * see the picture can also distinguish the left and right sides (the bubbles between the other party and
 * itself in the chat).
 */
object UserScreenText {
    /** There can be at most this many lines and this many words per line on one screen: it is enough to see the screen clearly without overwhelming the context. */
    const val MAX_LINES = 120
    const val MAX_CHARS = 200

    /** The tree is not obviously enough: you have to read the words on the screen again. The screen of the protected page is black and cannot be recognized. */
    fun needsOcr(s: ScreenSnapshot): Boolean = TreeAdequacy.ofTree(s).quality.let { it != TreeQuality.Adequate && it != TreeQuality.Protected }

    /**
     * The words on the screen are from top to bottom and from left to right on the same line. The
     * elements with names in the tree (password boxes are not counted) are combined with the rows in
     * [ocr] that do not exist in the tree; other than [top]/[bottom] (0–999) are the status bar and
     * navigation bar, and the words recognized there (time, battery) are not required. The second value
     * is whether any recognized words are used.
     */
    fun lines(s: ScreenSnapshot, ocr: List<VisibleTextLine>?, top: Int = 0, bottom: Int = 999): Pair<List<ScreenLine>, Boolean> {
        val named = s.elements.filter { !it.secret && it.name.isNotBlank() }
        // The center of the element is a pixel, and the bounds is the same as the recognized word:
        // 0-999: replace them with 0-999, combine and rearrange
        val tree = named.filterNot { joinsChildren(it, named) }
            .map { ScreenLine(clip(it.name), normalize(it.centerX, s.width), normalize(it.centerY, s.height)) }
        val extra = ocr?.let { VisibleText.missing(s, VisibleText.legible(it)) }.orEmpty()
            .map { ScreenLine(clip(it.text), (it.bounds[0] + it.bounds[2]) / 2, (it.bounds[1] + it.bounds[3]) / 2) }
            .filter { it.y in top..bottom }
        val all = (tree + extra).distinctBy { Triple(it.text, it.x / 20, it.y / 20) }
            .sortedWith(compareBy({ it.y / ROW }, { it.x }))
            .take(MAX_LINES)
        return all to extra.any { it in all }
    }

    /**
     * [e] just connects the paragraphs framed in it (a row of setting items, the entire block of a
     * message): no words are left after removing these paragraphs in its name. At this time, leave the
     * few paragraphs inside, each with its own place; if there are words of your own in the name, leave
     * them as well.
     */
    private fun joinsChildren(e: UiElement, all: List<UiElement>): Boolean {
        val inner = all.filter { it !== e && inside(it.bounds, e.bounds) && area(it.bounds) < area(e.bounds) }
        if (inner.isEmpty()) return false
        var rest = e.name
        inner.map { it.name.trim() }.sortedByDescending { it.length }.forEach { rest = rest.replace(it, "") }
        return rest.none { it.isLetterOrDigit() }
    }

    private fun inside(a: List<Int>, b: List<Int>) = a.size == 4 && b.size == 4 && a[0] >= b[0] && a[1] >= b[1] && a[2] <= b[2] && a[3] <= b[3]
    private fun area(b: List<Int>) = if (b.size == 4) (b[2] - b[0]).toLong() * (b[3] - b[1]) else 0L

    /** Tolerance of the same row (0–999): If the center height difference is less than this much, it is considered the same row, and the rows are arranged according to the left and right. */
    private const val ROW = 12

    private fun clip(t: String): String {
        val one = t.replace('\n', ' ').trim()
        return if (one.length <= MAX_CHARS) one else one.take(MAX_CHARS) + "…"
    }
}

/** The one-screen description is saved as JSON (put together with the screenshot); if it cannot be read, null is returned. */
object UserScreenJson {
    fun encode(s: UserScreen): String = buildJsonObject {
        put("id", s.id); put("takenAt", s.takenAt)
        s.packageName?.let { put("package", it) }; s.appLabel?.let { put("label", it) }
        if (s.blocked) put("blocked", true)
        if (s.fromOcr) put("ocr", true)
        put("lines", JsonArray(s.lines.map { l -> buildJsonObject { put("t", l.text); put("x", l.x); put("y", l.y) } }))
    }.toString()

    fun decode(text: String): UserScreen? = runCatching {
        val o = Json.parseToJsonElement(text) as JsonObject
        fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        UserScreen(
            id = str("id") ?: return null,
            takenAt = (o["takenAt"] as? JsonPrimitive)?.longOrNull ?: return null,
            packageName = str("package"), appLabel = str("label"),
            blocked = (o["blocked"] as? JsonPrimitive)?.booleanOrNull == true,
            lines = (o["lines"] as? JsonArray).orEmpty().mapNotNull { e ->
                val l = e as? JsonObject ?: return@mapNotNull null
                ScreenLine((l["t"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null,
                    (l["x"] as? JsonPrimitive)?.intOrNull ?: 0, (l["y"] as? JsonPrimitive)?.intOrNull ?: 0)
            },
            fromOcr = (o["ocr"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }.getOrNull()
}
