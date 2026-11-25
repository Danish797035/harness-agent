package com.meenai.mina.core.cap.phone

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The algorithm of the wheel selector (which rotates one frame at a time, date, region, etc.) has
 * nothing to do with how to rotate: recognize the value, calculate the direction and number of cells,
 * and estimate the height of one cell. The port goes into place as it does in one tool call, without the
 * need to look at the model frame by frame.
 *
 * "Forward" means that the value on the scroll wheel moves backward (the following item is turned to the
 * middle, the number usually becomes larger); whether it becomes larger is corrected by the value read
 * back.
 */
object WheelMath {
    private val ONLY_NUMBER = Regex("""^-?\d{1,6}$""")
    private val LEADING_NUMBER = Regex("""^\D{0,3}?(\d{1,6})(?!\d)""")
    private val DIGITS = Regex("""(?<!\d)\d{1,6}(?!\d)""")
    /** The punctuation mark that separates the value, status words ("selected", "selected"...) and units in the text of an item. */
    private val SEPARATORS = Regex("""[,，、:：;；|/()（）\[\]【】<>《》·•]+""")

    /** To compare text: remove blanks, replace full-width numbers with half-width numbers, and ignore case. */
    fun normalize(s: String): String = buildString {
        for (c in s.trim()) when {
            c.isWhitespace() -> Unit
            c in '０'..'９' -> append('0' + (c - '０'))
            else -> append(c.lowercaseChar())
        }
    }

    /**
     * The integers in the displayed text: "09" → 9, "9 o'clock" / "9 o'clock" → 9, "2026" → 2026, "Week
     * 3" → 3; Accessible names often have status words before and after the value ("Selected, 21",
     * "Selected, 21", "21 (selected)"). This is it when there is only one number in the entire
     * paragraph. When there are several numbers, the first one near the beginning is taken (if there are
     * more than three characters in the front, it is null); if there are no numbers, it is null.
     */
    fun number(s: String?): Int? {
        val t = s?.let(::normalize)?.ifEmpty { null } ?: return null
        if (ONLY_NUMBER.matches(t)) return t.toIntOrNull()
        DIGITS.findAll(t).toList().singleOrNull()?.let { return it.value.toIntOrNull() }
        return LEADING_NUMBER.find(t)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Several segments cut by punctuation points (processed as [normalize]): "Selected, 21" → [Selected, 21]. */
    fun segments(s: String): List<String> = normalize(s).split(SEPARATORS).filter { it.isNotEmpty() }

    /**
     * Values reported to the model: When there is a status word in the name, only the value segment is
     * left ("Selected, 21" → "21", "Selected, 21" → "21"); if the only segment with numbers cannot be
     * cut out, it will remain as it is (text value does not guess which segment is the status word).
     */
    fun clean(label: String?): String? {
        val t = label?.trim()?.ifEmpty { null } ?: return null
        val parts = t.split(SEPARATORS).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return t
        return parts.filter { p -> p.any { it.isDigit() || it in '０'..'９' } }.singleOrNull() ?: t
    }

    /** The target itself is a number ("9", "09", "2026"), not text with numbers. */
    fun numericTarget(target: String): Int? = normalize(target).takeIf(ONLY_NUMBER::matches)?.toIntOrNull()

    /**
     * Is the [shown] displayed by the wheel at the moment the desired [target]: When the target is a
     * pure number, use the numerical ratio ("09" is "9", "40 minutes" is "40", "Selected, 21" is "21");
     * otherwise, the text is the same, or the displayed (or a segment cut according to punctuation)
     * starts/ends with the target ("Beijing City" vs. "Beijing").
     */
    fun matches(shown: String?, target: String): Boolean {
        val s = shown?.let(::normalize)?.ifEmpty { null } ?: return false
        val t = normalize(target).ifEmpty { return false }
        numericTarget(target)?.let { return number(s) == it }
        fun affix(x: String) = x == t || x.startsWith(t) || x.endsWith(t)
        return affix(s) || segments(s).any(::affix)
    }

    /**
     * Several items can be seen from top to bottom to estimate the length of the loop: "getting smaller"
     * appears once in the number (...58 59 00 01...), the loop length = the maximum value before the
     * breakpoint − the minimum value after the breakpoint + 1; no loop can be seen (all increments, not
     * numbers, too few) is null.
     */
    fun modulus(visible: List<String>): Int? {
        val n = visible.map { number(it) ?: return null }
        if (n.size < 2) return null
        val drops = (1 until n.size).filter { n[it] < n[it - 1] }
        if (drops.size != 1) return null
        val i = drops.single()
        return (n.subList(0, i).max() - n.subList(i, n.size).min() + 1).takeIf { it >= 2 }
    }

    /** From [current] to [target], we need to go to the side with larger numbers (negative numbers to the smaller side): when the cycle length [modulus] is known, take the closest side. */
    fun distance(current: Int, target: Int, modulus: Int?): Int {
        val d = target - current
        if (modulus == null || modulus < 2) return d
        val m = ((d % modulus) + modulus) % modulus
        return if (m <= modulus / 2) m else m - modulus
    }

    /** How many cells does the number go from [before] to [after] (when the loop length is known, 59→0 is +1, not −59); if the number is not null, it is null. */
    fun moved(before: String?, after: String?, modulus: Int?): Int? {
        val b = number(before) ?: return null
        val a = number(after) ?: return null
        return distance(b, a, modulus)
    }

    /** How high is one square: The distance between the centers of adjacent items is the median; if there are less than two items and the distance is too small, it is null. */
    fun itemHeight(centers: List<Int>): Int? {
        val sorted = centers.sorted()
        val gaps = (1 until sorted.size).map { sorted[it] - sorted[it - 1] }.filter { it > 2 }
        if (gaps.isEmpty()) return null
        return gaps.sorted()[gaps.size / 2]
    }

    /**
     * The selected item: There is only one item marked selected ([Item.selected], barrier-free
     * isSelected); when each item has its own height, it is the item whose upper and lower ranges cover
     * the midline [centerY] (not covered is -1: the middle square is not reported, and the upper and
     * lower neighbors are not used to count); otherwise, press [selected] to find the one on the
     * midline.
     */
    fun selectedIndex(items: List<Item>, centerY: Int, itemHeight: Int?): Int {
        items.indices.filter { items[it].selected }.singleOrNull()?.let { return it }
        if (items.isNotEmpty() && items.all { it.height > 0 }) {
            return items.indices.filter { abs(items[it].centerY - centerY) * 2 <= items[it].height }
                .minByOrNull { abs(items[it].centerY - centerY) } ?: -1
        }
        return selected(items.map { it.centerY }, centerY, itemHeight)
    }

    /**
     * The grid on the center line is not reported (some rollers only report the upper and lower grids),
     * and the two items immediately above and below are both numbers, the text in the middle grid: The
     * difference between the upper and lower is 2, which is the number sandwiched in the middle; when
     * the difference is much greater, only the interface that has been rotated once is recognized (59
     * and 1, the middle is 0, 22 and 0, the middle is 23, 12 and 2, the middle is 1, 11 and 1, the
     * middle is 12, 1 and 59 The middle is 0). When the hour hand of 1-12 is not reported on the 12
     * grid, the up and down numbers are 11 and 1. You cannot press "the smaller side minus one" to push
     * it to 0 (0 is not on this roller); if it is arranged backwards (1 is on the top, 11 is on the
     * bottom), it is also 12. The writing method is as above (unit; if there are zeros at the top and
     * bottom, add zeros); if it cannot be deduced, it is null.
     */
    fun between(above: String, below: String): String? {
        val a = number(above) ?: return null
        val b = number(below) ?: return null
        val mid = when {
            abs(b - a) == 2 -> (a + b) / 2
            // 1–12:11 and 1 in between is 12. It must be ranked before "the smaller one minus one",
            // otherwise it will be pushed to 0
            (a == 11 && b == 1) || (a == 1 && b == 11) -> 12
            b < a && b <= 2 -> if (b >= 1) b - 1 else a + 1
            a < b && a <= 2 -> if (a >= 1) a - 1 else b + 1
            else -> return null
        }
        if (mid < 0) return null
        val token = DIGITS.find(above.trim())?.value ?: return null
        val other = DIGITS.find(below.trim())?.value.orEmpty()
        // The zero-padded scroll wheel (one item above and below starts with 0, such as 09, 01) will
        // still be padded with zeros to the same width.
        val padded = listOf(token, other).any { it.length > 1 && it.startsWith('0') }
        return above.trim().replaceFirst(token, if (padded) mid.toString().padStart(token.length, '0') else mid.toString())
    }

    /**
     * Among the several items from top to bottom, the section that is in a row with item [anchor] and
     * has a similar spacing: the titles and buttons above and below the scroll wheel in the same block
     * are not included. The height of a grid is calculated based on the distance between it and the
     * adjacent items above and below (whichever is smaller). If there are less than three items and the
     * height of one block cannot be estimated, return it as it is.
     */
    fun column(items: List<Item>, anchor: Int): List<Item> {
        if (items.size < 3 || anchor !in items.indices) return items
        val near = listOfNotNull(
            items.getOrNull(anchor - 1)?.let { items[anchor].centerY - it.centerY },
            items.getOrNull(anchor + 1)?.let { it.centerY - items[anchor].centerY },
        ).filter { it > 2 }
        val h = near.minOrNull() ?: return items
        fun even(gap: Int) = gap > h * 0.4 && gap < h * 1.8
        var lo = anchor
        var hi = anchor
        while (lo > 0 && even(items[lo].centerY - items[lo - 1].centerY)) lo--
        while (hi < items.lastIndex && even(items[hi + 1].centerY - items[hi].centerY)) hi++
        return items.subList(lo, hi + 1)
    }

    /** The subscript of the item on the center line [centerY]: the center is closest to the center line; the distance is more than half a square (not facing the center line) is -1. */
    fun selected(centers: List<Int>, centerY: Int, itemHeight: Int?): Int {
        val best = centers.indices.minByOrNull { abs(centers[it] - centerY) } ?: return -1
        if (itemHeight != null && abs(centers[best] - centerY) > itemHeight * 0.75) return -1
        return best
    }

    /**
     * How many squares have to be turned forward to arrive (negative number is backward): when the
     * target is within the visible items, the calculation is based on the position difference (the
     * following one is forward); when the target and the selected one are both numbers, the number
     * difference is calculated (if you can see the loop, go to the side you are approaching, if you
     * can't see it in front of you, use the [knownModulus] you saw before; when [increasingForward] is
     * false, the forward direction is getting smaller); None (text list, target is not in front of you)
     * is null.
     */
    fun stepsTo(visible: List<String>, selectedIndex: Int, target: String, increasingForward: Boolean = true, knownModulus: Int? = null): Int? {
        if (selectedIndex !in visible.indices) return null
        visible.indexOfFirst { matches(it, target) }.takeIf { it >= 0 }?.let { return it - selectedIndex }
        val t = numericTarget(target) ?: return null
        val cur = number(visible[selectedIndex]) ?: return null
        val d = distance(cur, t, modulus(visible) ?: knownModulus)
        return if (increasingForward) d else -d
    }

    /**
     * The distance to be dragged to turn [steps] squares (pixels, drag upwards with a positive number =
     * move forward), one square [itemHeight]; dragging cannot exceed [maxDrag] at one time, and it will
     * be divided into several times if it exceeds. Returns the distance of each drag.
     */
    fun drags(steps: Int, itemHeight: Int, maxDrag: Int): List<Int> {
        if (steps == 0 || itemHeight <= 0) return emptyList()
        val perDrag = (maxDrag / itemHeight).coerceAtLeast(1)
        val sign = if (steps > 0) 1 else -1
        val out = mutableListOf<Int>()
        var left = abs(steps)
        while (left > 0) {
            val n = minOf(left, perDrag)
            out += sign * n * itemHeight
            left -= n
        }
        return out
    }

    /** One item that can be seen in the scroll wheel: text and center (pixels); [selected] is marked with barrier-free selection; [height] is how high it is (I don’t know if it is 0). */
    data class Item(val text: String, val centerX: Int, val centerY: Int, val selected: Boolean = false, val height: Int = 0)

    /**
     * If a container that can be rolled vertically looks like a roller, it is better to miss it than to
     * identify it: three to nine items, each of which is a short value (no more than [MAX_ITEM_CHARS]
     * characters), different from each other, all in the middle of the container (less than 15% of the
     * width), and arranged equidistantly from top to bottom (the maximum spacing is less than 1.5 times
     * the minimum). It doesn’t look like the short tabs or chat list on the left side of the settings
     * page.
     */
    fun looksLikeWheel(items: List<Item>, left: Int, right: Int): Boolean {
        if (items.size !in 3..9 || right <= left) return false
        val texts = items.map { normalize(it.text) }
        if (texts.any { it.isEmpty() || it.length > MAX_ITEM_CHARS } || texts.toSet().size != texts.size) return false
        val mid = (left + right) / 2.0
        if (items.any { abs(it.centerX - mid) > (right - left) * 0.15 }) return false
        val ys = items.map { it.centerY }
        if (ys.zipWithNext().any { (a, b) -> b <= a }) return false
        val gaps = ys.zipWithNext { a, b -> b - a }
        return gaps.max() < gaps.min() * 1.5
    }

    private const val MAX_ITEM_CHARS = 8

    /** Dragging [dragged] pixels actually turns [moved] grid: the actual height of one grid; not converted to null. */
    fun calibrated(dragged: Int, moved: Int): Int? =
        if (moved == 0) null else abs((dragged.toDouble() / moved).roundToInt()).takeIf { it > 2 }
}

/**
 * The next step when turning the wheel by pressing the barrier-free "Roll Forward/Back": After each
 * batch, hand in the value read back, and it will tell you which way to roll a few more times, or to
 * stop. What is transferred at one time is not necessarily one cell (the list is scrolled one page at a
 * time), so it is recalculated according to the value read back after each batch:
 * - - The target is a number: determine the direction and count down according to the number difference;
 * when reading back for the first time, see whether "forward" becomes larger or smaller; if the value
 * does not move, it means that this side has reached the end and the target is not within the range,
 * stop;
 * Even if you cross the target back and forth several times (more than one square at a time, you can't
 * stop accurately), you can stop and leave it to dragging for calibration.
 * - - The target is text: when it is in front of you, press the position difference; when it is not
 * there, swipe forward one by one, and when it reaches the end, swipe in reverse until both ends are
 * reached.
 */
class WheelSteps(private val target: String, private val cap: Int = 80, private val burst: Int = 5) {
    sealed interface Step {
        data object Done : Step
        /** Go to [forward] and scroll down [count]. */
        data class Act(val forward: Boolean, val count: Int) : Step
        /**
         * Stop: [reason] is at_end (the end, the target is not on this side), not_moving (the wheel of
         * the cycle has not rolled), cap (it has rolled too many times), overshoot (cannot stop),
         * unknown_value (the value cannot be read).
         */
        data class Stop(val reason: String) : Step
    }

    /** A few rolls so far. */
    var actions: Int = 0
        private set
    private val numeric = WheelMath.numericTarget(target)
    private var increasingForward = true
    private var calibrated = false
    private var modulus: Int? = null
    private var last: String? = null
    private var lastAct: Step.Act? = null
    private var reversals = 0
    private var scanForward = true
    private var scanReversed = false

    /** After the previous batch: the value [current] selected at the moment and several visible items [visible](from top to bottom). It didn’t roll over the first time I adjusted it. */
    fun next(current: String?, visible: List<String> = emptyList()): Step {
        if (WheelMath.matches(current, target)) return Step.Done
        if (current == null) return Step.Stop("unknown_value")
        WheelMath.modulus(visible)?.let { modulus = it }
        val prev = lastAct
        val unchanged = prev != null && current == last
        if (actions >= cap) return Step.Stop("cap")
        if (numeric != null && WheelMath.number(current) != null) {
            if (prev != null) {
                // The value does not move: It can be seen that the circulating wheel has no end. It is
                // not working this time, not the end.
                if (unchanged) return Step.Stop(if (modulus != null) "not_moving" else "at_end")
                val moved = WheelMath.moved(last, current, modulus)
                if (!calibrated && moved != null && moved != 0) {
                    calibrated = true
                    if ((moved > 0) != prev.forward) increasingForward = false
                }
            }
            val d = WheelMath.distance(WheelMath.number(current)!!, numeric, modulus).let { if (increasingForward) it else -it }
            val forward = d > 0
            val turned = prev != null && forward != prev.forward
            if (turned && ++reversals > 3) return Step.Stop("overshoot")
            // Just passed the target and walked back: one step at a time
            val n = if (turned) 1 else minOf(abs(d), burst)
            return act(forward, n.coerceAtMost(cap - actions), current)
        }
        val sel = visible.indexOfFirst { it == current }
        val at = visible.indexOfFirst { WheelMath.matches(it, target) }
        if (sel >= 0 && at >= 0) return act(at > sel, minOf(abs(at - sel), burst, cap - actions), current)
        if (unchanged) {
            if (scanReversed) return Step.Stop("at_end")
            scanReversed = true
            scanForward = !scanForward
        }
        return act(scanForward, 1, current)
    }

    private fun act(forward: Boolean, count: Int, current: String?): Step {
        val a = Step.Act(forward, count.coerceAtLeast(1))
        last = current
        lastAct = a
        actions += a.count
        return a
    }
}

/**
 * The next step when dragging the scroll wheel (drawn by the App itself and cannot be scrolled without
 * any obstacles): after each dragging, hand in the column read back, and it will tell you how many more
 * spaces to drag, how to drag, or to stop.
 * - - Press [WheelMath.stepsTo] to freeze the number (press the difference between the numbers to see
 * the side the loop is approaching, and remember the length of the loop you saw before); when turning
 * for the first time, look at "drag up"
 * Whether it becomes larger or smaller, the height of one grid is calibrated according to the actual
 * number of grids rotated.
 * - - Dragging the value does not mean that it is at the end: it is at the end (at_end) only when the
 * loop cannot be seen and there is no item in the direction you want to go to; otherwise it will not
 * work this time.
 * Next time, change the speed (if the difference is only one frame, drag it further), and it will report
 * not_moving after [maxStill] times.
 * - - The text list and the target are not in front of you: scroll through the few items you can see at
 * one time and swipe to one side, then reverse it when you reach the end, and stop at both ends.
 */
class WheelDrags(private val target: String, rowHeight: Int, private val maxMoves: Int = 5, private val maxStill: Int = 3) {
    sealed interface Step {
        data object Done : Step
        /** Drag across the [steps] grid (positive finger pointing up = move forward), multiply the distance by [boost]; [slow]. Drag slowly without dragging, otherwise swipe quickly. */
        data class Drag(val steps: Int, val boost: Double, val slow: Boolean) : Step
        /** Stop: [reason] is at_end, not_moving, not_reached (it has not been reached after a few rounds, or it has been calculated according to the number but does not have this value), unknown_value. */
        data class Stop(val reason: String) : Step
    }

    /** How high is one grid (in pixels): first estimate based on the several values you can see, and then calibrate based on the actual number of grids rotated. */
    var itemHeight: Int = rowHeight.coerceAtLeast(1)
        private set
    private val numeric = WheelMath.numericTarget(target)
    private var started = false
    private var increasingForward = true
    private var directionKnown = false
    private var modulus: Int? = null
    private var last: String? = null
    private var lastDrag: Step.Drag? = null
    private var lastScanning = false
    private var scanForward = true
    private var scanReversed = false
    private var moves = 0
    private var still = 0

    /**
     * After the drag just now: the several items in this column from top to bottom [items], the vertical
     * coordinate of each center [centers], the subscript of the selected item [selected] (-1 if not
     * found), and the pixels actually passed by the finger [dragged] (up is positive; it has not been
     * dragged the first time, so it is 0).
     */
    fun next(items: List<String>, selected: Int, centers: List<Int> = emptyList(), dragged: Int = 0): Step {
        val current = items.getOrNull(selected)
        if (WheelMath.matches(current, target)) return Step.Done
        if (items.isEmpty()) return Step.Stop("unknown_value")
        if (!started) {
            started = true
            WheelMath.itemHeight(centers)?.let { itemHeight = it }
        }
        WheelMath.modulus(items)?.let { modulus = it }
        val prev = lastDrag
        if (prev != null) {
            if (current != last) {
                still = 0
                moves++
                val moved = WheelMath.moved(last, current, modulus)
                if (moved != null && moved != 0) {
                    if (!directionKnown && numeric != null) {
                        directionKnown = true
                        if ((moved > 0) != (dragged > 0)) increasingForward = false
                    }
                    WheelMath.calibrated(dragged, moved)?.let { itemHeight = it }
                }
            } else if (atEnd(items, selected, forward = prev.steps > 0)) {
                if (!lastScanning || scanReversed) return Step.Stop("at_end")
                scanReversed = true
                scanForward = !scanForward
            } else {
                still++
            }
        }
        if (still >= maxStill) return Step.Stop("not_moving")
        if (moves >= maxMoves) return Step.Stop("not_reached")
        val planned = WheelMath.stepsTo(items, selected, target, increasingForward, modulus)
        // The numerical calculation has arrived but it doesn’t match: the value is not on the scroll
        // wheel (for example, find 75 in 0–59)
        if (planned == 0) return Step.Stop("not_reached")
        lastScanning = planned == null
        val steps = planned ?: ((items.size - 1).coerceAtLeast(1) * if (scanForward) 1 else -1)
        last = current
        // The previous one didn't work: change the speed and try again; drag the part that is only one
        // space short, and then drag it farther (don't drag it if it is too much, so as not to turn your
        // head and bias the calibration)
        val boost = if (abs(steps) <= 1) 1.0 + BOOST * still else 1.0
        return Step.Drag(steps, boost, slow = still % 2 == 0).also { lastDrag = it }
    }

    /** You can't see the loop, there are no items in the direction you want to go (forward is below). */
    private fun atEnd(items: List<String>, selected: Int, forward: Boolean): Boolean {
        if (modulus != null || items.size < 2 || selected !in items.indices) return false
        return if (forward) selected == items.lastIndex else selected == 0
    }

    private companion object {
        const val BOOST = 0.6
    }
}
