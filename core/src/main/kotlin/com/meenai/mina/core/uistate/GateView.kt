package com.meenai.mina.core.uistate

import com.meenai.mina.core.replay.Anchors
import com.meenai.mina.core.replay.RouteAnchors
import com.meenai.mina.core.replay.Selector
import com.meenai.mina.core.tool.LOCATED_REF
import com.meenai.mina.core.tool.Verb
import com.meenai.mina.core.tool.argStr
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * The target of a call for the execution entry (irreversible judgment, running record, route): which
 * screen to judge, what coordinates @vN is changed to, what is the port number, and the target element
 * and anchor point before the write operation. All are viewed according to the screen reading status
 * ([session]) of the screen to which the call belongs; it is read-only and does not perform any action.
 */
internal class GateView {
    fun snapshotFor(session: ScreenSession, args: JsonObject): ScreenSnapshot? {
        val id = args.argStr("snapshotId")
        // The number of the old screen is still in the latest screen: if the action falls on the latest
        // screen, it will be judged according to the latest screen (the number is the same, the
        // snapshotId is given by the model)
        val ref = args.argStr("ref")
        val stabilizer = session.stabilizer
        if (stabilizer != null && id != null && ref != null && !LOCATED_REF.matches(ref)) {
            val r = stabilizer.resolve(ref, id)
            if (r is Resolved.Current && r.relocated) stabilizer.latest?.let { return it.screen.copy(snapshotId = id) }
        }
        if (id != null) return session.recentSnapshots.lastOrNull { it.snapshotId == id }
        return session.lastSnapshot
    }

    fun portRecord(session: ScreenSession, args: JsonObject): JsonObject {
        val st = session.stabilizer ?: return JsonObject(emptyMap())
        val ref = args.argStr("ref")
        val id = args.argStr("snapshotId")
        if (ref == null || id == null || LOCATED_REF.matches(ref)) return JsonObject(emptyMap())
        val r = st.resolve(ref, id) as? Resolved.Current ?: return JsonObject(emptyMap())
        return buildJsonObject { put("portRef", r.portRef); put("portSnapshotId", r.portSnapshotId) }
    }

    /**
     * When clicking @vN, change it to its registered coordinates, together with what you are looking for
     * (target), the original text (text), and the word seen by the visual model (seen), and click on the
     * coordinates to judge; when typing on @vN and clearing it, change it to the coordinates, and leave
     * the other parameters (Enter) as they are. The rest will be returned as is (unknown @vN will be
     * ref_expired when executed and will not be clicked).
     */
    fun gateArgs(session: ScreenSession, verb: Verb?, args: JsonObject): JsonObject {
        val ref = locatedRef(verb, args) ?: return args
        val (p, _) = session.located.get(ref.first, ref.second) ?: return args
        val info = session.locatedInfo[ref.first]
        return buildJsonObject {
            if (verb != Verb.Tap) args.forEach { (k, v) -> if (k != "ref" && k != "snapshotId") put(k, v) }
            put("x", p.x); put("y", p.y)
            (info?.target ?: (args["target"] as? JsonPrimitive)?.content)?.let { put("target", it) }
            info?.text?.let { put("text", it) }
            info?.seen?.let { put("seen", it) }
        }
    }

    /**
     * Registered @vN, or click on the coordinates with target after viewing describe (those without
     * target will be returned by parameter verification). This type of click is done by the division of
     * labor between the large and small models and the main model (see
     * [com.meenai.mina.core.action.ConfirmDecision.mayBeIrreversible]).
     */
    fun visionSourced(session: ScreenSession, verb: Verb?, args: JsonObject): Boolean {
        if (verb != Verb.Tap) return false
        locatedRef(verb, args)?.let { return session.located.get(it.first, it.second) != null }
        val target = args.argStr("target")
        return session.described && args["ref"] == null && args["x"] != null && !target.isNullOrBlank()
    }

    fun confirmedSeen(session: ScreenSession, verb: Verb?, args: JsonObject): String? {
        val ref = locatedRef(verb, args) ?: return null
        if (session.located.get(ref.first, ref.second) == null) return null
        return session.locatedInfo[ref.first]?.seen?.takeIf { it.isNotBlank() }
    }

    /** Click, type and clear to accept @vN. */
    private fun locatedRef(verb: Verb?, args: JsonObject): Pair<String, String>? {
        if (verb != Verb.Tap && verb != Verb.TypeText && verb != Verb.ClearText) return null
        val ref = args.argStr("ref")?.takeIf(LOCATED_REF::matches) ?: return null
        val sid = args.argStr("snapshotId") ?: return null
        return ref to sid
    }

    /** The route uses: the target element, anchor point and that screen taken from the current read screen before the write operation is executed. */
    class Capture(val target: Selector?, val before: Anchors, val snapshot: ScreenSnapshot?)
    var capture: Capture? = null

    fun anchorsAfter(after: ScreenSnapshot): Anchors? = capture?.let { RouteAnchors.after(it.snapshot, after) }

    fun captureTarget(session: ScreenSession, verb: Verb, o: JsonObject): Capture {
        // @vN According to the registered coordinates
        val checked = gateArgs(session, verb, o)
        val ref = checked.argStr("ref")
        val own = snapshotFor(session, checked)
        val s = own ?: session.lastSnapshot
        fun coord(k: String): Int? = when (val v = checked[k]) {
            is JsonPrimitive -> v.content.toDoubleOrNull()?.roundToInt()
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }.takeIf { it.size == 2 }?.average()?.roundToInt()
            else -> null
        }
        val x = coord("x"); val y = coord("y")
        val el = when {
            ref != null -> own?.elements?.firstOrNull { it.ref == ref }
            verb == Verb.Tap && x != null && y != null -> s?.let { RouteAnchors.elementAt(it, x, y) }
            // Input and clear functions on the focused input box
            verb == Verb.TypeText || verb == Verb.ClearText -> s?.elements?.firstOrNull { it.focused && it.editable }
            else -> null
        }
        return Capture(el?.let { RouteAnchors.selector(s!!, it) }, RouteAnchors.before(s, el), s)
    }
}
