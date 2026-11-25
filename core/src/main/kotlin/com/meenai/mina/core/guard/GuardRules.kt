package com.meenai.mina.core.guard

/**
 * Judgment rules for operating guards. Each rule looks at the same fact: what action was done, which
 * target it acted on (the combined action signature of the two is [actionSignature]), the actual effect
 * of the action (effect), and which state the interface reaches afterward; [OperationGuards] counts
 * according to these facts, here only the count is changed to the given level. Pure function.
 *
 * When reaching a dead end, first remind (nudge): explain where the stuck is and give three ways out
 * (change to a clearly different path, ask the user, hand over what you have and explain what is
 * missing). The last step of each ladder is stopped. It is only the final safety net, and the threshold
 * is far behind the reminder; the steps that slowly circle are limited by the upper limit of the number
 * of steps in this task (see [lateStop]):
 *
 * | | Signal | Reminder | Stop (Safety Net) |
 * |-|---|---|---|
 * | | Same action + same target repeated (last [LOOP_WINDOW] times) | 3, 6 | 12 |
 * | | Same as above, diagnostic interface tools (calculated by tool) | 6, 9 | 15 |
 * | | The action has no effect (effect is no_effect, or the interface remains unchanged after moving),
 * continuous | 3, 6, 9 | 12 |
 * | | I read it again when the page remained unchanged, but no new content was read | 5, 8 | 12 |
 * | | Continuous failure (the 4th time to attach a way out) | 2, 4 | 8 |
 * | | Unrecoverable failure | 1 | 3 |
 * | | Return to the same state for the Nth time without reaching the new state | 3, 6 | 1/4 of the upper
 * limit of steps (at least 10) |
 * | | Circle between two or three states (full number of circles) | 2, 4 | 1/5 of the upper limit of
 * steps (at least 6) |
 * | | N consecutive actions without reaching any new state | 8, 16 | 1/2 of the upper limit of steps (at
 * least 24) |
 *
 * If you don’t know the upper limit of steps, press [DEFAULT_BUDGET].
 */
internal object GuardRules {
    /** Which level of the ladder is counted: [threshold] is the threshold of this level. */
    data class Rung(val threshold: Int, val severity: Severity)

    /** Repeat action to see how many recent actions it has taken. */
    const val LOOP_WINDOW = 16

    /** I don’t know the upper limit of the number of steps in the task, so I will count them based on this number of steps. */
    const val DEFAULT_BUDGET = 60

    /** The ladder of repetitive actions: remind, remind again, stop. The diagnostic interface is often repetitive and the threshold is higher. */
    fun repeatLadder(diagnostic: Boolean): List<Int> = if (diagnostic) listOf(6, 9, 15) else listOf(3, 6, 12)

    /** Ladder of action without effect. */
    val NO_EFFECT_LADDER = listOf(3, 6, 9, 12)

    /** Steps for rereading when the page has not changed. */
    val REREAD_LADDER = listOf(5, 8, 12)

    /** Stairs for consecutive failures; for unrecoverable failures, press [UNRECOVERABLE_LADDER]. */
    val FAILURE_LADDER = listOf(2, 4, 8)
    val UNRECOVERABLE_LADDER = listOf(1, 3)

    /** If you fail continuously until this time, a way out will be attached in the reminder. */
    const val FAILURE_WAYS_OUT = 4

    /** You can go around in circles to see at most a few states. */
    const val MAX_CYCLE = 3

    /** The ladder to return to the same state ([budget] is the upper limit of the number of steps of the task). */
    fun sameStateLadder(budget: Int?) = listOf(3, 6, lateStop(budget, 4, 10))

    /** Circular steps (number of complete turns). */
    fun cycleLadder(budget: Int?) = listOf(2, 4, lateStop(budget, 5, 6))

    /** The number of actions that have not reached a new state in a row. */
    fun noNewStateLadder(budget: Int?) = listOf(8, 16, lateStop(budget, 2, 24))

    /** The threshold of the safety net: 1/[divisor] of the upper limit of steps, at least [floor](far after the reminder). */
    fun lateStop(budget: Int?, divisor: Int, floor: Int): Int = maxOf(floor, (budget ?: DEFAULT_BUDGET) / divisor)

    /**
     * Count which level [count] has reached on the ladder [ladder](ascending order, stop at the last level). If it has not reached the
     * first level, or if this level ([warnedAt] is the threshold that has been reminded) has been
     * reminded, it is null; when it reaches the highest level and [canStop], it will stop, otherwise it
     * will remind.
     */
    fun climb(count: Int, ladder: List<Int>, warnedAt: Int, canStop: Boolean): Rung? {
        val threshold = ladder.lastOrNull { count >= it } ?: return null
        if (threshold <= warnedAt) return null
        return Rung(threshold, if (threshold == ladder.last() && canStop) Severity.Stop else Severity.Warn)
    }

    /** At the end, it circles between [period] different states: [length] is the length of the end of this circle, and [rounds] is the number of complete circles. */
    data class Cycle(val period: Int, val length: Int) {
        val rounds: Int get() = length / period
    }

    /**
     * Do the states that you have visited successively (different adjacent ones) end up in circles
     * between 2-[MAX_CYCLE] states: A B A B is 2 circles of 2 circles, and A B C A B C is 2 circles of 3
     * circles. The one that does not repeat at least one step is null; the longest one is taken when
     * several loop lengths are suitable.
     */
    fun cycle(trail: List<Long>): Cycle? {
        var best: Cycle? = null
        for (p in 2..MAX_CYCLE) {
            if (trail.size < p + 1) continue
            if (trail.takeLast(p).toSet().size != p) continue
            var len = p
            var i = trail.size - 1
            while (i - p >= 0 && trail[i] == trail[i - p]) { len++; i-- }
            if (len > p && (best == null || len > best.length)) best = Cycle(p, len)
        }
        return best
    }

    /** The level to be given when there are [consecutive] consecutive failures, of which [unrecoverable] times are unrecoverable; if not, it is null. */
    fun failures(consecutive: Int, unrecoverable: Int): Severity? = when {
        consecutive >= FAILURE_LADDER.last() || unrecoverable >= UNRECOVERABLE_LADDER.last() -> Severity.Stop
        consecutive >= FAILURE_LADDER.first() || unrecoverable >= UNRECOVERABLE_LADDER.first() -> Severity.Warn
        else -> null
    }
}
