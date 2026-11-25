package com.meenai.mina.core.uistate

import com.meenai.mina.core.visual.FrameChange
import com.meenai.mina.core.prompt.Feedback
import com.meenai.mina.core.tool.Verb
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/** The actual effect of the action on the interface state of the app or web page: judged by the interface state (element table, control state, input focus, where) before and after the action. The difference in the screen is only for circumstantial evidence; [wire] is how effect.kind is written in the result. */
enum class EffectKind(val wire: String) {
    /** The interface has obviously changed to match this action. */
    Done("done"),
    /** Only small local changes (focus, switches, one or two elements), or maybe still loading: take a look at the results. */
    SmallChange("small_change"),
    /** Nothing has changed: do it differently, don’t do it the same way. */
    NoEffect("no_effect"),
    /** The place has been changed: the front desk on the mobile phone has been changed to another App, and the webpage has changed to a different page (the address or label has changed); see [NewPlace] in the new place. */
    PlaceChanged("place_changed");

    companion object {
        fun of(wire: String?): EffectKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * The structural difference of the interface state (element table) before and after the action. The
 * elements are aligned according to the content (role + name + resource ID + prompt or not), without
 * looking at the number (the number is not universal for each screen reading): the same content, the
 * same position and the same state count as unchanged, but the control state ([UiElement.states]) has
 * changed [toggled], the same content has changed the position [moved] (scrolling, rearrangement), the
 * words in the same input box have changed [edited], the rest are [added]/[removed]. [focusMoved]: The
 * element with input focus has changed (or the focus appears or disappears). Only look at informative
 * elements ([UiElement.meaningful]).
 */
data class InterfaceDiff(
    val added: Int,
    val removed: Int,
    val moved: Int,
    val edited: Int,
    val toggled: Int,
    val focusMoved: Boolean,
    val total: Int,
) {
    val changes: Int get() = added + removed + moved + edited + toggled
    val none: Boolean get() = changes == 0 && !focusMoved

    /** There are enough changes (see [EffectTuning] for threshold). */
    val major: Boolean get() = changes >= max(EffectTuning.MAJOR_MIN, ceil(total * EffectTuning.MAJOR_SHARE).toInt())

    companion object {
        fun between(before: ScreenSnapshot, after: ScreenSnapshot): InterfaceDiff {
            val a = before.elements.filter { it.meaningful }
            val b = after.elements.filter { it.meaningful }
            // First match the one that is identical, then match the one with only a different status
            // (the switch is flipped), then match the one that has been moved, and then match the one
            // with the words changed in the same input box.
            val leftA = a.toMutableList()
            val leftB = b.toMutableList()
            pairOff(leftA, leftB) { x, y -> key(x) == key(y) && x.bounds == y.bounds && x.states == y.states }
            val toggled = pairOff(leftA, leftB) { x, y -> key(x) == key(y) && x.bounds == y.bounds }
            val moved = pairOff(leftA, leftB) { x, y -> key(x) == key(y) }
            val edited = pairOff(leftA, leftB) { x, y -> x.editable && y.editable && x.role == y.role && x.resourceId == y.resourceId && near(x, y) }
            val focusA = a.firstOrNull { it.focused }?.let(::place)
            val focusB = b.firstOrNull { it.focused }?.let(::place)
            return InterfaceDiff(leftB.size, leftA.size, moved, edited, toggled, focusA != focusB, max(a.size, b.size))
        }

        /** Press [match] to pair two and remove them from both sides to return the matched pair. */
        private fun pairOff(a: MutableList<UiElement>, b: MutableList<UiElement>, match: (UiElement, UiElement) -> Boolean): Int {
            var n = 0
            val it = a.iterator()
            while (it.hasNext()) {
                val x = it.next()
                val j = b.indexOfFirst { y -> match(x, y) }
                if (j >= 0) { b.removeAt(j); it.remove(); n++ }
            }
            return n
        }

        private fun key(e: UiElement) = listOf(e.role, e.name, e.resourceId.orEmpty(), e.hint.toString())
        private fun place(e: UiElement) = listOf(e.role, e.resourceId.orEmpty(), e.bounds.toString())

        private fun near(x: UiElement, y: UiElement): Boolean {
            if (x.bounds.size != 4 || y.bounds.size != 4) return x.resourceId != null
            return distance(x, y) <= EffectTuning.NEAR
        }
    }
}

/**
 * The new place reached after the action: [name] is a general term (App package name, website host name,
 * consistent with "where" when reading the interface), [kind] and [detail] are the details of this
 * interface (mobile phone "app": {package}; web page "web": {url, title, tabId}).
 */
data class NewPlace(val name: String, val kind: String, val detail: JsonObject)

/**
 * The input box read back after input: [text] is the word in the box at the moment (when the prompt is
 * displayed, it is an empty string; the password box is null and is not read out), whether [matches] is
 * consistent with what is to be entered (remove the blank space when comparing: some boxes will add
 * spaces to number groups).
 */
data class Readback(val text: String?, val secret: Boolean, val matches: Boolean)

/**
 * Facts from the port used to judge the effect: The [changed] port verified in the action that the
 * change has not changed; the [switchedTo] port sees the App changed to the front desk; the [verified]
 * port reads the same after the input is written; the [loading] port says that the page has not quieted
 * down (the network and navigation are still in progress); the [onUserScreen] action makes this App
 * After opening it on the user's home screen and leaving it there (the user is currently using it),
 * there is no change visible on Mina's screen, nor is it not clicked.
 */
data class PortFacts(
    val changed: Boolean? = null,
    val switchedTo: String? = null,
    val verified: Boolean = false,
    val loading: Boolean = false,
    val onUserScreen: String? = null,
)

/**
 * The actual effect of an action: kind, destination (when changing places), differences in interface
 * structure and screen, readback of input, and possibly still loading. [treeBlind]: no visible change
 * was observed (kind is still [EffectKind.NoEffect]), yet the screen's tree cannot read the tapped
 * spot (unusable tree, or missing region where the tap is on no element) — taps that only change focus
 * or hidden state look the same and cannot be treated as misses.
 */
data class ActionEffect(
    val kind: EffectKind,
    val place: NewPlace? = null,
    val diff: InterfaceDiff? = null,
    val frame: FrameChange? = null,
    val readback: Readback? = null,
    val loading: Boolean = false,
    val treeBlind: Boolean = false,
) {
    fun toJson(): JsonObject = buildJsonObject {
        put("kind", kind.wire)
        place?.let { put("to", it.name); put(it.kind, it.detail) }
        diff?.let { t ->
            if (t.added > 0) put("added", t.added)
            if (t.removed > 0) put("removed", t.removed)
            if (t.moved > 0) put("moved", t.moved)
            if (t.edited > 0) put("edited", t.edited)
            if (t.toggled > 0) put("toggled", t.toggled)
            if (t.focusMoved) put("focusMoved", true)
        }
        frame?.let { put("frameChanged", (it.whole * 100).roundToInt() / 100.0) }
        readback?.let { r ->
            put("readback", if (r.secret) HIDDEN else r.text?.take(READBACK_MAX))
            put("readbackMatches", r.matches)
        }
        if (loading) put("mayStillBeLoading", true)
        if (treeBlind) put("treeBlind", true)
    }

    companion object {
        const val HIDDEN = "(hidden)"
        private const val READBACK_MAX = 200

        /** You can’t see any changes, and you can’t say that there are no click actions: clicks and gestures (only the focus is changed, and the tree and screen may not move when in the hidden state). */
        private val BLIND_VERBS = setOf(Verb.Tap, Verb.PhoneGesture)

        /**
         * The tree on this screen cannot tell where this action works: the tree cannot be used, or a
         * piece is missing and the point (the center of the screen area [region]) is not on any
         * informative element. Points by elements in the tree ([target] is not null) are only counted
         * when the tree cannot be used. [quality] is the last tree judged on this screen to determine
         * whether it is enough. I don’t know if it is null.
         */
        internal fun treeBlind(verb: Verb, quality: TreeQuality?, before: ScreenSnapshot?, target: UiElement?, region: List<Int>?): Boolean {
            if (verb !in BLIND_VERBS) return false
            return when (quality) {
                TreeQuality.Unusable -> true
                TreeQuality.Partial -> {
                    if (target != null || region == null || region.size != 4) return false
                    val x = (region[0] + region[2]) / 2
                    val y = (region[1] + region[3]) / 2
                    before?.elements.orEmpty().none { e -> e.meaningful && e.bounds.size == 4 && x in e.bounds[0]..e.bounds[2] && y in e.bounds[1]..e.bounds[3] }
                }
                else -> false
            }
        }

        /**
         * The effect is judged according to the interface status before and after the action. When
         * [before]/[after] cannot be read, only the port fact and the screen are judged; if neither is
         * null (no effect is reported). [target] is the target element of the action (the one read by
         * the model, used to find the input box). When inputting ([Verb.TypeText]) with [typed] (word to
         * be input), [pressedEnter], it is not read back (the box may be cleared or the page has been
         * changed after pressing Enter).
         *
         * If you are unsure and don't tell it in time, it will have no effect: if the port says it has
         * changed, it is at least a small change; if the interface is moving or the port says it is
         * still loading, it is only a small change (it may still be loading).
         */
        fun classify(
            verb: Verb,
            before: ScreenSnapshot?,
            after: ScreenSnapshot?,
            port: PortFacts,
            frame: FrameChange?,
            target: UiElement? = null,
            typed: String? = null,
            pressedEnter: Boolean = false,
            secret: Boolean = false,
        ): ActionEffect? {
            // The port itself sees that the front desk has changed the App: it shall prevail (the two
            // readings may be before or after the switch)
            port.switchedTo?.let { return ActionEffect(EffectKind.PlaceChanged, NewPlace(it, "app", buildJsonObject { put("package", it) }), frame = frame) }
            // Opened to the user's home screen: changed location, just not on Mina's screen
            port.onUserScreen?.let {
                return ActionEffect(EffectKind.PlaceChanged, NewPlace(it, "app", buildJsonObject { put("package", it); put("onUserScreen", true) }), frame = frame)
            }
            val raw = judge(verb, before, after, port, frame, target, typed, pressedEnter, secret) ?: return null
            val loading = port.loading || after?.changedDuringRead == true || after?.let(::showsProgress) == true
            return when {
                raw.kind != EffectKind.NoEffect -> raw.copy(loading = loading && raw.kind == EffectKind.SmallChange)
                loading -> raw.copy(kind = EffectKind.SmallChange, loading = true)
                port.changed == true -> raw.copy(kind = EffectKind.SmallChange)
                else -> raw
            }
        }

        private fun judge(
            verb: Verb, before: ScreenSnapshot?, after: ScreenSnapshot?, port: PortFacts, frame: FrameChange?,
            target: UiElement?, typed: String?, pressedEnter: Boolean, secret: Boolean,
        ): ActionEffect? {
            if (before == null || after == null) {
                return when {
                    port.changed == true || frame?.visible == true -> ActionEffect(EffectKind.SmallChange, frame = frame)
                    port.changed == false -> ActionEffect(EffectKind.NoEffect, frame = frame)
                    else -> null
                }
            }
            moved(before, after)?.let { return ActionEffect(EffectKind.PlaceChanged, it, frame = frame) }
            val diff = InterfaceDiff.between(before, after)
            val readback = if ((verb == Verb.TypeText || verb == Verb.ClearText) && !pressedEnter) {
                readback(after, target, if (verb == Verb.ClearText) "" else typed, secret)
            } else null
            val kind = when {
                readback != null && !readback.secret && readback.matches -> EffectKind.Done
                // After the port was written, it was read back on the spot and it was consistent, but
                // the box (or password box) could not be found in this interface: check based on the
                // port
                port.verified && (readback == null || readback.secret) && !pressedEnter -> EffectKind.Done
                readback != null && !readback.secret -> if (diff.none && frame?.visible != true) EffectKind.NoEffect else EffectKind.SmallChange
                diff.major -> EffectKind.Done
                diff.none -> when {
                    frame?.large == true && max(before.elements.count { it.meaningful }, after.elements.count { it.meaningful }) < EffectTuning.FEW_ELEMENTS -> EffectKind.Done
                    frame?.visible == true -> EffectKind.SmallChange
                    else -> EffectKind.NoEffect
                }
                else -> EffectKind.SmallChange
            }
            return ActionEffect(kind, diff = diff, frame = frame, readback = readback)
        }

        /** There are controls such as progress bars on the interface (loading). */
        private fun showsProgress(s: ScreenSnapshot) = s.elements.any { it.role.contains("progress", ignoreCase = true) && UiStates.DISABLED !in it.states }

        /** After changing the app or changing the page: look at the address (not counting after #) and tags on the web page, and look at the front-end package name on the mobile phone. None are replaced with null. */
        private fun moved(before: ScreenSnapshot, after: ScreenSnapshot): NewPlace? {
            val wb = before.web; val wa = after.web
            if (wb != null || wa != null) {
                if (wb == null || wa == null) return null
                if (wb.tabId != wa.tabId || wb.url.substringBefore('#') != wa.url.substringBefore('#')) {
                    return NewPlace(after.packageName ?: wa.url, "web", buildJsonObject { put("url", wa.url); put("title", wa.title); put("tabId", wa.tabId) })
                }
                return null
            }
            val pa = after.packageName ?: return null
            return if (before.packageName != null && before.packageName != pa) NewPlace(pa, "app", buildJsonObject { put("package", pa) }) else null
        }

        /**
         * The words in the input box at this moment: the same box (same role, same resource ID, similar
         * location) is given priority, followed by the input box with focus; if not found, it is null
         * (no readback is reported). [want] is the expected word (empty string when cleared); it is not
         * read back when it is null.
         */
        private fun readback(after: ScreenSnapshot, target: UiElement?, want: String?, secret: Boolean): Readback? {
            want ?: return null
            val fields = after.elements.filter { it.editable }
            val field = target?.let { t ->
                fields.filter { it.role == t.role && it.resourceId == t.resourceId }.minByOrNull { distance(it, t) }
                    ?.takeIf { distance(it, t) <= EffectTuning.SAME_FIELD }
            } ?: fields.firstOrNull { it.focused } ?: return null
            if (secret || field.secret) return Readback(null, secret = true, matches = false)
            val text = if (field.hint) "" else field.name
            return Readback(text, secret = false, matches = norm(text) == norm(want))
        }

        private fun norm(s: String) = s.filterNot { it.isWhitespace() }
    }
}

/** The distance between the centers of two elements (0–999 miles); the missing range is 0. */
private fun distance(a: UiElement, b: UiElement): Int {
    if (a.bounds.size != 4 || b.bounds.size != 4) return 0
    val dx = (a.bounds[0] + a.bounds[2]) / 2 - (b.bounds[0] + b.bounds[2]) / 2
    val dy = (a.bounds[1] + a.bounds[3]) / 2 - (b.bounds[1] + b.bounds[3]) / 2
    return kotlin.math.sqrt((dx * dx + dy * dy).toDouble()).toInt()
}

/** A sentence given to the model according to the effect (effect.* in prompts/feedback/tools.md); there is no need to say anything else if it has obviously changed. */
internal object EffectFeedback {
    /** [portSwitched]: The port has been reported to the front desk and has been switched away (that explanation is already in the result), so it will not be repeated. */
    fun hint(e: ActionEffect, verb: Verb, portSwitched: Boolean): String? {
        val r = e.readback?.takeIf { !it.secret && !it.matches }
        val differs = r?.let { Feedback.format("effect.readback_differs", "readback" to JsonPrimitive(it.text.orEmpty()).toString()) }
        return when (e.kind) {
            EffectKind.Done -> null
            EffectKind.PlaceChanged -> if (portSwitched) null else Feedback.format("effect.place_changed", "to" to e.place?.name)
            EffectKind.SmallChange -> differs ?: Feedback[if (e.loading) "effect.loading" else "effect.small_change"]
            EffectKind.NoEffect -> listOfNotNull(
                differs,
                Feedback[when {
                    e.treeBlind -> "effect.no_visible_change"
                    verb == Verb.Back -> "effect.no_effect.back"
                    verb == Verb.Scroll || verb == Verb.Swipe -> "effect.no_effect.scroll"
                    else -> "effect.no_effect"
                }],
            ).joinToString(" ")
        }
    }
}
