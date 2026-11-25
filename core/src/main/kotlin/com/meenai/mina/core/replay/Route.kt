package com.meenai.mina.core.replay

import com.meenai.mina.core.uistate.ScreenSnapshot
import com.meenai.mina.core.uistate.UiElement
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import com.meenai.mina.core.tool.LegacyNames
import com.meenai.mina.core.tool.ToolIndex
import com.meenai.mina.core.tool.Verb

/**
 * How to identify elements in the route: The number is only valid within one screen reading, and is
 * replaced by the character, name, resource ID, position (0-999) and the serial number of the same type.
 * If [name] is empty, it means not to recognize it by name; if it is in the form of `{1}`, it means that
 * the name is taken from the slot of this original speech. [uniqueAtRecord]: When recording, only one of
 * these features can be found on that screen.
 */
@Serializable
data class Selector(
    val role: String,
    val name: String,
    val resourceId: String?,
    val bounds: List<Int>,
    val ordinal: Int,
    val uniqueAtRecord: Boolean = false,
)

/** Anchor point of one screen: frontend package name + up to [RouteAnchors.MAX] stable text. */
@Serializable
data class Anchors(val packageName: String?, val texts: List<String>)

/** [argsJson] has the number removed, and the slot in the input text is written as `{1}`. */
@Serializable
data class RouteStep(val tool: String, val argsJson: String, val target: Selector?, val before: Anchors, val after: Anchors)

/** A proven practice. [template] is the sentence after replacing the slot value in the original sentence with `{n}`. */
@Serializable
data class Route(
    val id: String,
    val template: String,
    val slots: Int,
    val packages: List<String>,
    val appVersions: Map<String, String>,
    val steps: List<RouteStep>,
    val successes: Int = 0,
    val consecutiveMisses: Int = 0,
    val missStep: Int? = null,
    val disabled: Boolean = false,
    val lastUsedMs: Long,
)

/** The maximum size of the route: number of steps and number of bytes dropped. */
const val MAX_ROUTE_STEPS = 60
const val MAX_ROUTE_BYTES = 64 * 1024

/**
 * The route that was written down before the name was changed: the old tool name in the steps is
 * replaced by the tool that can be recorded in the route now (for verbs that are available on several
 * screens, use the set that can be played back). The current name does not change; the unrecognizable
 * ones remain. During playback, press "Not a replayable action" to stop it.
 */
fun Route.withCurrentToolNames(legacy: LegacyNames, tools: ToolIndex): Route {
    if (steps.all { tools[it.tool] != null }) return this
    return copy(steps = steps.map { st ->
        if (tools[st.tool] != null) st else legacy.resolve(st.tool) { tools[it]?.routable == true }?.let { st.copy(tool = it) } ?: st
    })
}

object RouteCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(r: Route): String = json.encodeToString(Route.serializer(), r)
    fun decode(s: String): Route = json.decodeFromString(Route.serializer(), s)
}

/** Take the selector and anchor point from Reading Screen. */
object RouteAnchors {
    const val MAX = 3
    private const val MAX_LEN = 12
    /** Numbers, time, amounts, etc. may change every time and should not be used as anchor points. */
    private val VOLATILE = Regex("[0-9０-９¥￥$€£%]")
    private val LIST_ROLES = setOf("list", "grid", "recycler", "scroll")

    fun stable(text: String): Boolean {
        val t = text.trim()
        return t.length in 1..MAX_LEN && !VOLATILE.containsMatchIn(t)
    }

    private fun container(e: UiElement) = e.scrollable || e.role.lowercase() in LIST_ROLES

    private fun inside(e: UiElement, c: UiElement) = e !== c && e.bounds.size == 4 && c.bounds.size == 4 &&
        e.bounds[0] >= c.bounds[0] && e.bounds[1] >= c.bounds[1] && e.bounds[2] <= c.bounds[2] && e.bounds[3] <= c.bounds[3]

    /**
     * The text that can be used as an anchor point is deduplicated in the order on the screen, and the
     * text with the resource ID is ranked first. The items in the list and scrollable area are content
     * (contacts, messages, products), and the words in the input box are written by the user, so they
     * are not counted - only the fixed frame of the page (title bar, label, button) is taken.
     */
    fun texts(s: ScreenSnapshot): List<String> {
        val boxes = s.elements.filter(::container)
        return s.elements
            .filter { e -> !e.editable && !container(e) && boxes.none { inside(e, it) } && stable(e.name) }
            .sortedBy { it.resourceId == null }
            .map { it.name.trim() }.distinct()
    }

    fun before(s: ScreenSnapshot?, target: UiElement?): Anchors {
        if (s == null) return Anchors(null, emptyList())
        val self = target?.name?.trim()
        return Anchors(s.packageName, texts(s).filter { it != self }.take(MAX))
    }

    /** New text that appears after the action (that is not available on the screen before the action). */
    fun after(before: ScreenSnapshot?, after: ScreenSnapshot): Anchors {
        val old = before?.let(::texts)?.toSet().orEmpty()
        return Anchors(after.packageName, texts(after).filter { it !in old }.take(MAX))
    }

    /**
     * Name in the selector: Only prompts are left in the input box (if not, it is empty), which means
     * that the interface is not written by the user; the names of other elements must be as stable as
     * anchors (short, without numbers), otherwise the names will not be saved and will only be
     * identified by roles, resource IDs and serial numbers.
     */
    fun storedName(e: UiElement): String = when {
        e.editable -> if (e.hint && stable(e.name)) e.name.trim() else ""
        stable(e.name) -> e.name.trim()
        else -> ""
    }

    fun selector(s: ScreenSnapshot, e: UiElement): Selector {
        val name = storedName(e)
        // Recognize by the saved characteristics: if the name is not saved, it will only be the
        // character and resource ID.
        fun same(x: UiElement) = x.role == e.role && x.resourceId == e.resourceId && (name.isEmpty() || x.name.trim() == e.name.trim())
        val ordinal = s.elements.takeWhile { it !== e }.count(::same)
        return Selector(e.role, name, e.resourceId, e.bounds, ordinal, uniqueAtRecord = s.elements.count(::same) == 1)
    }

    /** The smallest element containing the point (0–999); the energy point is taken first for the same hour. */
    fun elementAt(s: ScreenSnapshot, x: Int, y: Int): UiElement? = s.elements
        .filter { it.bounds.size == 4 && x in it.bounds[0]..it.bounds[2] && y in it.bounds[1]..it.bounds[3] }
        .minWithOrNull(compareBy<UiElement> { (it.bounds[2] - it.bounds[0]).toLong() * (it.bounds[3] - it.bounds[1]) }.thenBy { !it.clickable })
}

/** Effective write operation: ok and the interface has changed, or the input readback is consistent; starting the App and opening the link will be considered successful (the port has waited until the target is in the foreground). */
fun effectiveWrite(verb: Verb?, result: JsonObject): Boolean {
    if (result["ok"] != JsonPrimitive(true)) return false
    return result["changed"] == JsonPrimitive(true) || result["verification"] == JsonPrimitive("match") ||
        (verb in OPENS && result["changed"] != JsonPrimitive(false))
}

/** Open the app, open the link: success means reaching the goal. */
internal val OPENS = setOf(Verb.LaunchApp, Verb.OpenLink)
