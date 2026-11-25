package com.meenai.mina.core.guard

import com.meenai.mina.core.prompt.Feedback
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.floor

enum class Severity(val wire: String) { Warn("warn"), Stop("stop") }

/**
 * One conclusion from the guard. Reminder ([Severity.Warn]) with [nudge]: a paragraph explaining where
 * the jam is and providing several ways out (sent to the model); there is no stoppage.
 */
data class GuardVerdict(val code: String, val severity: Severity, val evidence: JsonObject, val nudge: String? = null) {
    fun toJson(): JsonObject = buildJsonObject {
        put("code", code); put("severity", severity.wire); put("evidence", evidence)
        nudge?.let { put("nudge", it) }
    }
}

/** Stable serialization after key sorting so that parameter order does not affect the signature. */
private fun stable(e: JsonElement): String = when (e) {
    is JsonObject -> e.keys.sorted().joinToString(",", "{", "}") { "${JsonPrimitive(it)}:${stable(e[it]!!)}" }
    is JsonArray -> e.joinToString(",", "[", "]") { stable(it) }
    else -> e.toString()
}

private fun num(e: JsonElement?): Double? = (e as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

private val SWIPE_KEYS = listOf("startX", "startY", "endX", "endY")

/** Action signature: Ignore reason, coordinates are rounded to 50, and only the direction of sliding is recorded. */
fun actionSignature(tool: String, args: JsonObject): String {
    val n = args.toMutableMap()
    n.remove("reason")
    val x = num(n["x"]); val y = num(n["y"])
    if (x != null && y != null) {
        n["x"] = JsonPrimitive(floor(x / 50).toLong())
        n["y"] = JsonPrimitive(floor(y / 50).toLong())
    }
    val s = SWIPE_KEYS.map { num(n[it]) }
    if (s.all { it != null }) {
        val dx = s[2]!! - s[0]!!; val dy = s[3]!! - s[1]!!
        val vertical = abs(dy) >= abs(dx)
        n["swipeDirection"] = JsonPrimitive("${if (vertical) "V" else "H"}${if ((if (vertical) dy else dx) < 0) "-" else "+"}")
        SWIPE_KEYS.forEach { n.remove(it) }
    }
    return "$tool|${stable(JsonObject(n))}"
}

/**
 * One operational guard per mission. Only when the action really has no effect, repeated reading without
 * new content, or the tool fails continuously will it be reminded and finally stopped. Identical
 * clicks/scrolls and already-seen interface fingerprints do not in themselves indicate stagnation; new
 * pages or reads clear the no-progress count.
 */
class OperationGuards(private val stepBudget: Int? = null) {

    private var frameId: String? = null
    private var frameHash: Long? = null
    private var acted = false
    /** The previous action has been demerited according to the actual effect it reported. "Does it have any effect?": the next interface will not be repeated according to the fingerprint. */
    private var effectNoted = false

    /** The number of consecutive actions that have no effect. */
    private var unchanged = 0
    private var unchangedWarnedAt = 0

    /** Each interface state visited (element table fingerprint). */
    private val seenStates = HashSet<Long>()
    /** The number of times you have returned to each state since the last time you visited the new state and the threshold that has been reminded. */
    private val visits = HashMap<Long, Int>()
    private val visitsWarnedAt = HashMap<Long, Int>()
    /** Each status seen one after another (adjacent ones are different), the most recent ones. */
    private val trail = ArrayDeque<Long>()
    private var cycleWarnedAt = 0
    /** Several actions have been taken since the last time the new state was reached. */
    private var sinceNewState = 0
    private var sinceNewWarnedAt = 0

    /** The count of tasks; [epoch] increases by one every time the screen changes, and the count data read in each segment is invalid. */
    private val reads = GuardScope(canStop = true)
    /** Remember what you read across pages; don’t treat old content as new progress on a task when you return to the same page. */
    private val taskReads = HashSet<Long>()
    private var epoch = 0

    private var consecutiveFailures = 0
    private var unrecoverableFailures = 0
    private val recentFailures = ArrayDeque<JsonObject>()

    var stop: GuardVerdict? = null
        private set
    val unchangedFrames: Int get() = unchanged
    var progressVersion: Long = 0
        private set

    /** The reminder is attached where the card is (guard.<code>, fill in [count]) and the way out; the stop is not attached. */
    private fun verdict(code: String, severity: Severity, evidence: JsonObject, count: Int, waysOut: Boolean = true, period: Int? = null): List<GuardVerdict> {
        val nudge = if (severity == Severity.Warn) listOfNotNull(
            if (period != null) Feedback.format(LEADS.getValue(code), "count" to count, "states" to period) else Feedback.format(LEADS.getValue(code), "count" to count),
            Feedback["guard.ways_out"].takeIf { waysOut },
        ).joinToString(" ") else null
        val v = GuardVerdict(code, severity, evidence, nudge)
        if (severity == Severity.Stop) stop = v
        return listOf(v)
    }

    private fun <T> ArrayDeque<T>.pushCapped(v: T, cap: Int = 10) { addLast(v); if (size > cap) removeFirst() }

    /** The call entry is retained; the action signature does not participate in the stagnation determination. */
    fun beforeAction(tool: String, args: JsonObject, diagnostic: Boolean = false, scope: GuardScope = reads): List<GuardVerdict> {
        // The same action can read new content page by page; the action signature alone cannot determine
        // that the task is stalled.
        return emptyList()
    }

    /** An action that will change the interface is done. */
    fun noteAction() {
        acted = true
        effectNoted = false
        sinceNewState += 1
    }

    /**
     * The actual effect reported by the action (kind of effect, see core.uistate.EffectKind): no_effect
     * is recorded once as "no effect". If it has obviously changed, changed the app or changed the page,
     * it will be calculated from scratch; small local changes will not be counted.
     */
    fun effect(kind: String): List<GuardVerdict> {
        effectNoted = true
        when (kind) {
            NO_EFFECT -> unchanged += 1
            else -> { unchanged = 0; unchangedWarnedAt = 0; return emptyList() }
        }
        return noProgress()
    }

    private fun noProgress(): List<GuardVerdict> {
        val rung = GuardRules.climb(unchanged, GuardRules.NO_EFFECT_LADDER, unchangedWarnedAt, canStop = true) ?: return emptyList()
        unchangedWarnedAt = rung.threshold
        return verdict("no_progress", rung.severity, buildJsonObject { put("unchanged", unchanged) }, unchanged)
    }

    /**
     * The model gets a copy of the interface (read interface, or included in the action result), and
     * [hash] is its fingerprint. Only if "the interface is still the same after the action" is
     * considered to have no effect (actions that have reported effects will not be repeated); simply
     * watching it repeatedly does not count. Which state is reached after the action is also used to
     * check back to the same state, go around in circles, and never reach a new state.
     */
    fun frame(id: String, hash: Long): List<GuardVerdict> {
        if (frameId == id) return emptyList()
        val same = frameHash == hash
        val didAct = acted
        val noted = effectNoted
        acted = false
        effectNoted = false
        frameId = id
        frameHash = hash
        val out = mutableListOf<GuardVerdict>()
        if (!same) {
            pageChanged()
            if (seenStates.add(hash)) progress()
        }
        if (didAct && !noted) {
            if (same) { unchanged += 1; out += noProgress() } else { unchanged = 0; unchangedWarnedAt = 0 }
        }
        // The old interface itself does not prove failure, it only reminds the model to evaluate whether
        // there is new content or a change of route.
        if (didAct) out += noNewState()
        return out
    }

    /** After the action, return to a state that has been reached. It was said by [cycle] while going around in circles, so I won’t repeat it here. */
    private fun arrived(hash: Long): List<GuardVerdict> {
        val n = (visits[hash] ?: 1) + 1
        visits[hash] = n
        if (GuardRules.cycle(trail.toList()) != null) return emptyList()
        val rung = GuardRules.climb(n, GuardRules.sameStateLadder(stepBudget), visitsWarnedAt[hash] ?: 0, canStop = true) ?: return emptyList()
        visitsWarnedAt[hash] = rung.threshold
        return verdict("same_state", rung.severity, buildJsonObject { put("visits", n) }, n)
    }

    private fun cycle(): List<GuardVerdict> {
        val c = GuardRules.cycle(trail.toList())
        if (c == null) { cycleWarnedAt = 0; return emptyList() }
        val rung = GuardRules.climb(c.rounds, GuardRules.cycleLadder(stepBudget), cycleWarnedAt, canStop = true) ?: return emptyList()
        cycleWarnedAt = rung.threshold
        return verdict("state_cycle", rung.severity, buildJsonObject { put("states", c.period); put("rounds", c.rounds) }, c.rounds, period = c.period)
    }

    private fun noNewState(): List<GuardVerdict> {
        val rung = GuardRules.climb(sinceNewState, GuardRules.noNewStateLadder(stepBudget), sinceNewWarnedAt, canStop = false) ?: return emptyList()
        sinceNewWarnedAt = rung.threshold
        return verdict("no_new_state", rung.severity, buildJsonObject { put("actions", sinceNewState) }, sinceNewState)
    }

    /**
     * Reading the screen/reading the webpage/webpage script at one time: reading repeatedly when the
     * screen does not change and no effective operations are performed, and no new things can be read
     * (the thing you are looking for is not on this page). [fresh] is true, which means that you have
     * read content that you have not read before (such as reading the whole page and changing the
     * starting point), and it does not count as re-reading. It doesn't matter if you change tools or
     * scripts to read: the page has not changed, and the answer is what is existing on the page.
     * [window] gives a separate count in it (reading in one call of the page script), and does not move
     * the count of the task.
     */
    fun read(tool: String, fresh: Boolean, window: GuardScope = reads, fingerprint: Long? = null): List<GuardVerdict> {
        if (fresh) {
            if (fingerprint == null || taskReads.add(fingerprint)) progress()
            return emptyList()
        }
        window.sync(epoch)
        window.rereads += 1
        val n = window.rereads
        val rung = GuardRules.climb(n, GuardRules.REREAD_LADDER, window.warnedAt, window.canStop) ?: return emptyList()
        window.warnedAt = rung.threshold
        return verdict("read_loop", rung.severity, buildJsonObject {
            put("tool", tool); put("rereadsOfUnchangedPage", n)
        }, n)
    }

    /** This reading is the first time it has been read since the last time the screen changed. */
    fun firstSeen(fingerprint: Long, window: GuardScope = reads): Boolean {
        window.sync(epoch)
        return window.seen.add(fingerprint)
    }

    /** The screen has changed (or a valid operation has been performed): previously read counts are no longer counted (all read counts are invalidated together). */
    fun pageChanged() {
        epoch += 1
    }

    private fun progress() {
        progressVersion += 1
        unchanged = 0
        unchangedWarnedAt = 0
        sinceNewState = 0
        sinceNewWarnedAt = 0
        if (stop?.code == "no_progress" || stop?.code == "read_loop") stop = null
    }

    fun result(tool: String, content: JsonObject, neutral: Boolean = false): List<GuardVerdict> {
        val ok = (content["ok"] as? JsonPrimitive)?.booleanOrNull
        val countsAsFailure = (content["counts_as_failure"] as? JsonPrimitive)?.booleanOrNull
        if (neutral || countsAsFailure == false || ok != false) {
            consecutiveFailures = 0
            unrecoverableFailures = 0
            return emptyList()
        }
        val recoverable = (content["recoverable"] as? JsonPrimitive)?.booleanOrNull != false
        consecutiveFailures += 1
        if (!recoverable) unrecoverableFailures += 1
        recentFailures.pushCapped(buildJsonObject {
            put("tool", tool); put("recoverable", recoverable)
            (content["error"] as? JsonPrimitive)?.takeIf { it.isString }?.let { put("error", it.content) }
        })
        val severity = GuardRules.failures(consecutiveFailures, unrecoverableFailures) ?: return emptyList()
        return verdict("too_many_failures", severity, buildJsonObject {
            put("consecutive", consecutiveFailures)
            put("unrecoverable", unrecoverableFailures)
            put("recent", JsonArray(recentFailures.toList().takeLast(5)))
        }, consecutiveFailures, waysOut = consecutiveFailures >= GuardRules.FAILURE_WAYS_OUT || unrecoverableFailures > 0)
    }

    private companion object {
        const val NO_EFFECT = "no_effect"
        /** Remember the recent status (enough to see the longest circle safety net). */
        const val TRAIL_KEEP = 64

        /** The sentence at the beginning of each reminder says where the card is (prompts/feedback/tools.md). */
        val LEADS = mapOf(
            "repeating_action" to "guard.repeating_action", "no_progress" to "guard.no_progress", "same_state" to "guard.same_state",
            "state_cycle" to "guard.state_cycle", "no_new_state" to "guard.no_new_state", "read_loop" to "guard.read_loop",
            "too_many_failures" to "guard.too_many_failures",
        )
    }
}

/**
 * A section count: the latest action signature (repeated operation), the fingerprint of the result that
 * has been read, and the number of times that the unchanged page has been read again. When [canStop] is
 * false, the maximum number of reminders is that the task will not be stopped. The read count will be
 * invalidated and recalculated when the screen changes ([OperationGuards.pageChanged]).
 */
class GuardScope(val canStop: Boolean) {
    internal val loop = ArrayDeque<String>()
    internal var loopWarnedAt = 0
    internal val seen = HashSet<Long>()
    internal var rereads = 0
    internal var warnedAt = 0
    private var epoch = 0

    internal fun sync(now: Int) {
        if (now == epoch) return
        epoch = now
        seen.clear(); rereads = 0; warnedAt = 0
    }
}
