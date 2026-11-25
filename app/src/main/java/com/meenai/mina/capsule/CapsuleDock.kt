package com.meenai.mina.capsule

import com.meenai.mina.cap.phone.a11y.OverlayArea
import kotlin.math.roundToInt

enum class DockSide { Left, Right }

/** Where does the capsule stop: [side], which side is attached to it, [fraction] is the ratio of the height within the swingable range (0 is the top, 1 is the bottom), record it in proportion, and it will still return to the original place after the screen is turned. */
data class CapsuleDock(val side: DockSide = DockSide.Right, val fraction: Float = 0f)

/**
 * The range that the capsule window can be placed is the window coordinates (pixels, the window includes
 * the edges left for shadow): when docked, the left side of the window is at [left], or the right side
 * is at [right]; the top and bottom are between [top]..[bottom] (avoiding the status bar, bangs and
 * bottom gesture bar). When dragging, you can drag left and right to [edgeLeft]..[edgeRight] (the
 * visible capsule is attached to the edge of the screen).
 */
data class DockArea(
    val width: Int, val left: Int, val top: Int, val right: Int, val bottom: Int,
    val edgeLeft: Int, val edgeRight: Int,
) {
    /**
     * When the input method pops up (the top is at [imeTop], screen coordinates), the swingable range is
     * above it, and the visible part is separated from it by [gap]; [room] is the shadow edge. It
     * remains unchanged when there is no pop-up ([imeTop] is null).
     */
    fun above(imeTop: Int?, gap: Int, room: Int): DockArea =
        if (imeTop == null) this else copy(bottom = minOf(bottom, imeTop - gap + room).coerceAtLeast(top))

    companion object {
        /**
         * Calculated from the screen and system bar: the visible capsule when docked is [margin] away
         * from the left and right edges of the screen, and [gap] away from the status bar and bottom
         * gesture bar; [room] is the circle left for shadow in the window.
         */
        fun of(area: OverlayArea, margin: Int, gap: Int, room: Int) = DockArea(
            width = area.width,
            left = area.insetLeft + margin - room,
            top = area.insetTop + gap - room,
            right = area.width - area.insetRight - margin + room,
            bottom = area.height - area.insetBottom - gap + room,
            edgeLeft = area.insetLeft - room,
            edgeRight = area.width - area.insetRight + room,
        )
    }
}

/** Algorithms for dragging, welting, and proportional height placement (pure functions, pixels). */
object CapsuleDocking {
    /** The top of the window with a height of [h] is swung by [fraction]. */
    fun y(fraction: Float, area: DockArea, h: Int): Int = area.top + (fraction.coerceIn(0f, 1f) * range(area, h)).roundToInt()

    /** The height ratio of the top when it is at [y]. */
    fun fraction(y: Int, area: DockArea, h: Int): Float {
        val r = range(area, h)
        return if (r == 0) 0f else ((y - area.top).toFloat() / r).coerceIn(0f, 1f)
    }

    /** The window with a height of [h] (the card is higher than the capsule) moves up from [y] when it cannot be placed down, without crossing the status bar and bottom. */
    fun fitY(y: Int, area: DockArea, h: Int): Int = y.coerceIn(area.top, maxOf(area.top, area.bottom - h))

    /** Drag the left side of the window: At most, visible capsules can be attached to the edge of the screen. */
    fun dragX(x: Int, area: DockArea, w: Int): Int = x.coerceIn(area.edgeLeft, maxOf(area.edgeLeft, area.edgeRight - w))

    /** The left side of the window when docked at [side]. */
    fun dockedX(side: DockSide, area: DockArea, w: Int): Int = if (side == DockSide.Left) area.left else area.right - w

    /** The distance from that edge when docked at [side] (x when the window is aligned to the left or right). */
    fun edgeOffset(side: DockSide, area: DockArea): Int = if (side == DockSide.Left) area.left else area.width - area.right

    /**
     * Let go: [x] is the left side of the window, [y] is the top side of the window. The center line is
     * attached to the left side of the left half of the screen, otherwise it is attached to the right
     * side; the height is recorded in proportion.
     */
    fun release(x: Int, y: Int, w: Int, h: Int, area: DockArea): CapsuleDock {
        val side = if (x + w / 2 < area.width / 2) DockSide.Left else DockSide.Right
        return CapsuleDock(side, fraction(fitY(y, area, h), area, h))
    }

    private fun range(area: DockArea, h: Int) = maxOf(0, area.bottom - area.top - h)
}

/**
 * Where and how high the expanded card is placed (pure function, pixels): The card is very wide,
 * centered on the left and right, and only dragged up and down. The height of dialogue cards is fixed at
 * [maxHeight]; the height of task cards is based on the content, and the maximum is [maxHeight]. If it
 * exceeds, the height will be rolled inside. The position is recorded according to the top of the card:
 * the proportion of the top in the range that can be placed ([release]) is recorded separately from the
 * capsule; the range that can be placed in the top is calculated based on the tallest card (window
 * height [full], that is, [maxHeight] plus the lower shadow edge), so cards with different heights are
 * aligned at the top in the same proportion. When changing cards, the top does not move, grows or
 * shrinks down; when it cannot be placed down (gesture bar at the bottom, input method), move up until
 * it is just right. After rotating the screen, it will swing back proportionally.
 */
object CardDocking {
    /** The maximum ratio of the card to the screen height. */
    const val SHARE = 0.58f
    /** If I don’t remember the ratio of the top of the cards: the tallest card is placed in the middle of the range that can be placed. */
    const val DEFAULT_FRACTION = 0.5f

    /**
     * The maximum height of the visible card (the dialogue card is this high): the screen height is
     * [screenHeight] to [SHARE], but not more than what can fit in [area] (the upper and lower sides
     * give way to the shadow edge [room]); when the input method pops up, [area] has been received above
     * it, the card becomes shorter, and the middle part shrinks, but the input box is still visible.
     */
    fun maxHeight(screenHeight: Int, area: DockArea, room: Int): Int =
        minOf((screenHeight * SHARE).roundToInt(), area.bottom - area.top - 2 * room).coerceAtLeast(0)

    /**
     * Place the top of the card window with the proportion [fraction] and the height [h]: First, click
     * the highest card (window height [full]) in the range [base] without input method to set the top,
     * and then move it to the current range [area].
     */
    fun y(fraction: Float, base: DockArea, area: DockArea, full: Int, h: Int): Int =
        CapsuleDocking.fitY(base.top + (fraction.coerceIn(0f, 1f) * topRange(base, full)).roundToInt(), area, h)

    /** When dragging the title bar, the upper part of the window: if the finger moves [dy], the upper part cannot be moved out of the range that can be placed in [base], and the entire frame cannot be moved out of [area]. */
    fun dragY(startY: Int, dy: Float, base: DockArea, area: DockArea, full: Int, h: Int): Int =
        CapsuleDocking.fitY(minOf(startY + dy.roundToInt(), base.top + topRange(base, full)), area, h)

    /** Note the upper proportion when you let go (calculated according to the range of the input method [base], the input method will still be in the same place after being folded). */
    fun release(y: Int, base: DockArea, full: Int): Float {
        val r = topRange(base, full)
        return if (r == 0) 0f else ((y - base.top).toFloat() / r).coerceIn(0f, 1f)
    }

    /** How far can the top move: The tallest card from top to bottom. */
    private fun topRange(base: DockArea, full: Int) = maxOf(0, base.bottom - base.top - full)
}
