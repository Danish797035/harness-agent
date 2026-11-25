package com.meenai.mina.cap.phone.a11y

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import com.meenai.mina.core.cap.phone.WheelDrags
import com.meenai.mina.core.cap.phone.WheelMath
import com.meenai.mina.core.cap.phone.WheelSteps
import com.meenai.mina.core.uistate.VisibleTextLine
import com.meenai.mina.core.uistate.WheelOutcome
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turn a wheel to a value and do it in one call. Try what the control can do in order (do it if you can,
 * and fall back to the next one if you can't): 1. There is an input box in the scroll wheel (the
 * original digital scroll wheel has it): write the value directly into it, let it lose focus to confirm,
 * and read it back; 2. The scroll wheel can "roll forward/backward" without any hindrance (the original
 * digital scroll wheel clicks one frame): read the current value, press [WheelSteps] to set the
 * direction and count down, scroll in batches, and read back in each batch; 3. None of them work (the
 * scroll wheel drawn by the app itself): read the several items visible in this column (if you can't
 * read it, just recognize the words on the screen), estimate the height of one grid and the current
 * value, drag slowly on this column (don't shake it, so as not to turn your head), read it back and then
 * correct it; if the value does not move, drag it further, change it faster and try again, if it still
 * does not move, it will report "not_moving" and return the model.
 *
 * The number given can be the scroll wheel, or it can be an item it displays (usually the selected
 * item): at this time, the scroll wheel is in the column horizontally aligned with this item, and other
 * scroll wheels (minutes next to hours) side by side in the same block are not read or dragged.
 * Interface functions such as reading, dragging, and so on are all injected by [AndroidScreen]. Here we
 * only focus on the steps.
 */
internal class WheelDriver(
    private val width: Int,
    private val height: Int,
    /** Wait until the interface is continuously quietMs, up to capMs. */
    private val quiet: suspend (quietMs: Long, capMs: Long) -> Unit,
    /** Drag a finger along these points (pixels): When [slow] is true, drag slowly and stop before letting go; when false, drag quickly like a person. */
    private val drag: suspend (points: List<Pair<Int, Int>>, slow: Boolean) -> Boolean,
    /** Identify the words on the screen (coordinates 0–999); null if not. */
    private val ocr: suspend () -> List<VisibleTextLine>?,
    /** There is an input method popping up on this screen. */
    private val imeShown: () -> Boolean,
    /** Click to return (collapse input method). */
    private val back: () -> Boolean,
) {
    private var attempts = 0

    /** One-time reading value: the selected item [current] (cannot be read as null) and the several items visible in this column (from top to bottom), [selected] is the subscript of the selected item. */
    private class Reading(val current: String?, val items: List<WheelMath.Item>, val selected: Int) {
        val texts get() = items.map { it.text }
    }

    /**
     * The column to be transferred: read down from [root], and only recognize the items that fall
     * horizontally in [left]..[right]; [mid] is the center line of the selected cell; [rowHeight] is the
     * retreat when one cell is overestimated (when an item is given, it is its own height).
     */
    private class Column(val root: AccessibilityNodeInfo, val box: Rect, val left: Int, val right: Int, val mid: Int, val rowHeight: Int)

    suspend fun set(start: AccessibilityNodeInfo, value: String): WheelOutcome {
        val startBox = Rect().also(start::getBoundsInScreen)
        if (startBox.isEmpty) return outcome(false, null, null, "none", "not_a_wheel")
        val wheel = scrollableWheel(start, startBox)
        val col = columnOf(start, startBox, wheel)
        var now = read(col)
        val from = now.current
        if (from == null && now.items.size < 2) {
            val scrollable = wheel != null || start.isScrollable
            return outcome(false, null, null, "none", if (scrollable) "unknown_value" else "not_a_wheel")
        }
        if (WheelMath.matches(from, value)) return outcome(true, from, from, "none")

        // 1. Direct input
        editableOf(wheel ?: start)?.let { field ->
            if (typeInto(field, value)) {
                now = settled(col)
                if (WheelMath.matches(now.current, value)) return outcome(true, now.current, from, "text")
            }
        }

        // 2. Barrier-free scrolling
        if (wheel != null && canStep(wheel)) {
            val steps = WheelSteps(value, cap = MAX_STEPS)
            var stopped: String? = null
            var rechecks = 0
            loop@ while (true) {
                when (val s = steps.next(now.current, now.texts)) {
                    WheelSteps.Step.Done -> {
                        // The scroll wheel may still be moving when you read the target: when it stops,
                        // check again. If it is not correct, continue scrolling.
                        now = settled(col)
                        if (WheelMath.matches(now.current, value)) return outcome(true, now.current, from, "actions")
                        if (++rechecks > MAX_RECHECKS) { stopped = "not_reached"; break@loop }
                    }
                    is WheelSteps.Step.Stop -> { stopped = s.reason; break@loop }
                    is WheelSteps.Step.Act -> {
                        val action = if (s.forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        var done = 0
                        repeat(s.count) { if (wheel.performAction(action)) done++ }
                        attempts += s.count
                        quiet(STEP_QUIET_MS, STEP_CAP_MS)
                        wheel.refresh()
                        now = settled(col)
                        // Not rolling at all: This side is over (or this side is not allowed to roll)
                        if (done == 0 && !WheelMath.matches(now.current, value)) { stopped = "at_end"; break@loop }
                    }
                }
            }
            if (stopped == "at_end" || stopped == "unknown_value") return outcome(false, now.current, from, "actions", stopped)
        }

        // 3. Drag
        return dragTo(col, value, from, now)
    }

    private suspend fun dragTo(col: Column, value: String, from: String?, first: Reading): WheelOutcome {
        var now = first
        val plan = WheelDrags(value, rowHeight = col.rowHeight)
        var dragged = 0
        var rechecks = 0
        while (true) {
            when (val s = plan.next(now.texts, now.selected, now.items.map { it.centerY }, dragged)) {
                WheelDrags.Step.Done -> {
                    // Stop and check again (the wheel will still snap to the nearest grid after you let
                    // go)
                    now = settled(col)
                    if (WheelMath.matches(now.current, value)) return outcome(true, now.current, from, "drag")
                    if (++rechecks > MAX_RECHECKS) return outcome(false, now.current, from, "drag", "not_reached")
                }
                is WheelDrags.Step.Stop -> return outcome(false, now.current, from, "drag", s.reason)
                is WheelDrags.Step.Drag -> {
                    dragged = dragSteps(col, now, s.steps, plan.itemHeight, s.boost, s.slow)
                        ?: return outcome(false, now.current, from, "drag", "not_reached")
                    now = settled(col)
                }
            }
        }
    }

    /**
     * Drag [steps] cells on this column (positive number means moving your finger up = forward),
     * multiply [itemHeight] for each cell, and then multiply it by [boost]; return the pixels that the
     * finger actually walked (up means positive), and it will be null if it cannot be dragged out. Place
     * your finger on the horizontal center of the column, drag up to start from the lower part of the
     * column, and drag down to start from the upper part of the column. You can drag out the column (the
     * finger is not lifted, the scroll wheel will still follow), and it will only be clamped in the
     * screen.
     */
    private suspend fun dragSteps(col: Column, r: Reading, steps: Int, itemHeight: Int, boost: Double, slow: Boolean): Int? {
        val h = itemHeight.coerceAtLeast(1)
        val x = r.items.map { it.centerX }.sorted().getOrNull(r.items.size / 2) ?: (col.left + col.right) / 2
        val top = r.items.minOfOrNull { it.centerY - h / 2 } ?: (col.mid - h / 2)
        val bottom = r.items.maxOfOrNull { it.centerY + h / 2 } ?: (col.mid + h / 2)
        val maxDrag = maxOf(bottom - top - h, h * MIN_DRAG_ROWS)
        var dragged = 0
        for (piece in WheelMath.drags(steps, h, maxDrag)) {
            val len = maxOf((abs(piece) * boost).roundToInt(), MIN_DRAG_PX)
            val up = piece > 0
            val y0 = (if (up) bottom - h / 2 else top + h / 2).coerceIn(maxOf(top + 1, 1), minOf(bottom - 1, height - 2).coerceAtLeast(maxOf(top + 1, 1)))
            val y1 = (if (up) y0 - len else y0 + len).coerceIn(1, height - 2)
            if (!drag(listOf(x.coerceIn(0, width - 1) to y0, x.coerceIn(0, width - 1) to y1), slow)) return null
            attempts++
            dragged += y0 - y1
            quiet(DRAG_QUIET_MS, DRAG_CAP_MS)
        }
        return dragged
    }

    /** Write the value into the input box in the scroll wheel and confirm (the original scroll wheel is set according to the written value when the focus is lost); therefore, the pop-up input method is closed. */
    private suspend fun typeInto(field: AccessibilityNodeInfo, value: String): Boolean {
        val imeBefore = imeShown()
        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
        val set = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        attempts++
        field.performAction(AccessibilityNodeInfo.ACTION_CLEAR_FOCUS)
        quiet(STEP_QUIET_MS, STEP_CAP_MS)
        if (!imeBefore && imeShown()) {
            back()
            quiet(STEP_QUIET_MS, STEP_CAP_MS)
        }
        return set
    }

    // ---------- read ----------

    /**
     * Readings after stopping: After scrolling and dragging, the wheel will still rotate for a while and
     * be absorbed to the nearest frame (the animation of one frame of the original digital scroll wheel
     * is about 0.3 seconds), and accessibility events may not necessarily occur. It will stop when the
     * same value is read twice in a row every [SETTLE_MS], and it can be read [MAX_SETTLE_READS] times
     * at most.
     */
    private suspend fun settled(col: Column): Reading {
        var last = read(col)
        repeat(MAX_SETTLE_READS - 1) {
            delay(SETTLE_MS)
            val again = read(col)
            if (again.current == last.current) return again
            last = again
        }
        return last
    }

    private suspend fun read(col: Column): Reading {
        col.root.refresh()
        col.root.getBoundsInScreen(col.box)
        val found = treeItems(col)
        if (found.isNotEmpty()) {
            val h = WheelMath.itemHeight(found.map { it.centerY })
            val anchor = WheelMath.selectedIndex(found, col.mid, h)
            val items = if (anchor >= 0) WheelMath.column(found, anchor) else found
            val i = WheelMath.selectedIndex(items, col.mid, WheelMath.itemHeight(items.map { it.centerY }) ?: h)
            if (i >= 0) return Reading(items[i].text, items, i)
            // No item covers the middle line: it may be that the middle square is not reported (only the
            // upper and lower squares are reported), press the two items immediately above and below to
            // push out the middle square; if it cannot be pushed out, return to the item closest to the
            // middle line.
            val below = items.indexOfFirst { it.centerY > col.mid }
            val mid = (if (below > 0) WheelMath.between(items[below - 1].text, items[below].text) else null) ?: run {
                val j = WheelMath.selected(items.map { it.centerY }, col.mid, WheelMath.itemHeight(items.map { it.centerY }) ?: h)
                return Reading(items.getOrNull(j)?.text, items, j)
            }
            val withMid = items.toMutableList().apply {
                add(below, WheelMath.Item(mid, (items[below - 1].centerX + items[below].centerX) / 2, col.mid, selected = true))
            }
            return Reading(mid, withMid, below)
        }
        // The scroll wheel I drew: recognize the words in this column
        val lines = ocr().orEmpty().mapNotNull { l ->
            val cx = px((l.bounds[0] + l.bounds[2]) / 2, width)
            val cy = px((l.bounds[1] + l.bounds[3]) / 2, height)
            if (!col.box.contains(cx, cy) || cx !in col.left..col.right || l.text.isBlank() || l.text.trim().length > MAX_ITEM_CHARS) null
            else WheelMath.Item(l.text.trim(), cx, cy)
        }.sortedBy { it.centerY }
        val i = WheelMath.selected(lines.map { it.centerY }, col.mid, WheelMath.itemHeight(lines.map { it.centerY }))
        return Reading(lines.getOrNull(i)?.text, lines, i)
    }

    /** Several items with words that can be seen in this column (find a few levels down), from top to bottom; the same item is not repeated. */
    private fun treeItems(col: Column): List<WheelMath.Item> {
        val out = mutableListOf<WheelMath.Item>()
        val r = Rect()
        fun add(n: AccessibilityNodeInfo): Boolean {
            val label = itemLabel(n) ?: return false
            n.getBoundsInScreen(r)
            if (r.isEmpty || !col.box.contains(r.centerX(), r.centerY()) || r.centerX() !in col.left..col.right) return false
            if (out.none { it.text == label && abs(it.centerY - r.centerY()) < 4 }) out += WheelMath.Item(label, r.centerX(), r.centerY(), n.isSelected, r.height())
            return true
        }
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            for (i in 0 until n.childCount) {
                val c = n.getChild(i) ?: continue
                if (!c.isVisibleToUser) continue
                if (!add(c) && depth < MAX_DOWN) walk(c, depth + 1)
            }
        }
        if (col.root.childCount == 0) add(col.root) else walk(col.root, 0)
        return out.sortedBy { it.centerY }
    }

    /** The name of an item; the status (stateDescription, such as "selected") reported separately by the system. If it is spelled into the name, remove it first. Anything that is too long will not count. */
    private fun itemLabel(n: AccessibilityNodeInfo): String? {
        var label = ScreenReader.ownLabel(n) ?: return null
        val state = n.stateDescription?.toString()?.trim()
        if (!state.isNullOrEmpty() && label != state && label.contains(state)) {
            label = label.replace(state, "").trim { it.isWhitespace() || !it.isLetterOrDigit() }.ifEmpty { label }
        }
        return label.takeIf { it.length <= MAX_ITEM_CHARS }
    }

    // ---------- Recognize the scroll wheel ----------

    /**
     * A scroll wheel that can be scrolled without any obstacles: the given node itself, the one that can
     * be scrolled a few levels up (when the given item is an item in the roller), or the one found
     * downward; the one found upward should cover the center of the given item horizontally (the
     * scrolling area of the entire page is covered by several side-by-side rollers, and cannot be used
     * for scrolling). None is null.
     */
    private fun scrollableWheel(start: AccessibilityNodeInfo, startBox: Rect): AccessibilityNodeInfo? {
        if (scrollable(start)) return start
        val r = Rect()
        var p = start.parent
        var depth = 0
        while (p != null && depth++ < MAX_UP) {
            if (scrollable(p)) {
                p.getBoundsInScreen(r)
                return p.takeIf { r.centerX() in startBox.left..startBox.right }
            }
            p = p.parent
        }
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(start) }
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 60) {
            val c = queue.removeFirst()
            if (scrollable(c)) return c
            for (i in 0 until c.childCount) c.getChild(i)?.let(queue::add)
        }
        return null
    }

    /**
     * The column to be read. It's given with a roller (that can roll) or several items inside: that's
     * it. What is given is one item (this is often the case with rollers drawn by myself): Look up for
     * the nearest layer with no less than three items horizontally aligned with it, and only recognize
     * items that are horizontally aligned with it, so the roller next to it is not included.
     */
    private fun columnOf(start: AccessibilityNodeInfo, startBox: Rect, wheel: AccessibilityNodeInfo?): Column {
        val ownHeight = startBox.height().coerceAtLeast(1)
        if (wheel != null && wheel != start && Rect().also(wheel::getBoundsInScreen).let { it.width() <= startBox.width() * 2 }) {
            // What is given is one item in the scroll wheel. The scroll wheel is only one column wide:
            // read the entire scroll wheel.
            val box = Rect().also(wheel::getBoundsInScreen)
            return Column(wheel, box, box.left, box.right, startBox.centerY(), ownHeight)
        }
        if (start.childCount > 0) {
            val own = Column(start, Rect(startBox), startBox.left, startBox.right, startBox.centerY(), startBox.height() / ASSUMED_ROWS)
            if (treeItems(own).size >= 2) return own
        }
        var best: Column? = null
        var p = start.parent
        var depth = 0
        while (p != null && depth++ < MAX_UP) {
            val box = Rect().also(p::getBoundsInScreen)
            val c = Column(p, box, startBox.left, startBox.right, startBox.centerY(), ownHeight)
            val n = treeItems(c).size
            if (n >= 3) return c
            if (n >= 2 && best == null) best = c
            p = p.parent
        }
        return best ?: Column(start, Rect(startBox), startBox.left, startBox.right, startBox.centerY(), ownHeight)
    }

    private fun scrollable(n: AccessibilityNodeInfo): Boolean = n.isScrollable || canStep(n)

    private fun canStep(n: AccessibilityNodeInfo): Boolean = n.actionList.any {
        it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
    }

    /** The scroll wheel itself or the input box on the inner layer (the middle box of the original digital scroll wheel). */
    private fun editableOf(wheel: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (wheel.isEditable) return wheel
        for (i in 0 until wheel.childCount) {
            val c = wheel.getChild(i) ?: continue
            if (c.isEditable && c.isVisibleToUser) return c
        }
        return null
    }

    private fun px(n: Int, size: Int) = (n * (size - 1) / 999.0).roundToInt()

    private fun outcome(reached: Boolean, value: String?, from: String?, method: String, error: String? = null) =
        WheelOutcome(reached, WheelMath.clean(value), WheelMath.clean(from), attempts, method, if (reached) null else error ?: "not_reached")

    private companion object {
        const val MAX_STEPS = 80
        const val MAX_UP = 5
        const val MAX_DOWN = 4
        /** The name of an item often carries a status word ("Selected, 21" "Selected, 21"), which is more relaxed. */
        const val MAX_ITEM_CHARS = 24
        /** If there is more than one grid and the overestimation cannot be calculated, press one screen and five elements. */
        const val ASSUMED_ROWS = 5
        /** You can drag at least this many cells at a time (you can drag even when the list only reads one or two items), and at least this many pixels (drag too short and it will be regarded as a click). */
        const val MIN_DRAG_ROWS = 3
        const val MIN_DRAG_PX = 48
        /** Judgment of stable stop: after such a long time, the value of reading twice in succession is the same; it can be read a few times at most; it stops after reading the target but it is wrong, and it can be continued a few times at most. */
        const val SETTLE_MS = 350L
        const val MAX_SETTLE_READS = 5
        const val MAX_RECHECKS = 2
        const val STEP_QUIET_MS = 150L
        const val STEP_CAP_MS = 700L
        const val DRAG_QUIET_MS = 300L
        const val DRAG_CAP_MS = 1_500L
    }
}
