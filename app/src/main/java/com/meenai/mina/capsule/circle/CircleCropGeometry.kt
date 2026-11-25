package com.meenai.mina.capsule.circle

/**
 * Geometric model for crop rectangle selection on the screen.
 *
 * Coordinates are represented as pure Kotlin floats to allow JVM unit testing
 * without requiring Android framework mocks.
 */
data class CropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = maxOf(0f, right - left)
    val height: Float get() = maxOf(0f, bottom - top)
}

enum class ResizeHandle {
    TopLeft,
    TopRight,
    BottomLeft,
    BottomRight,
}

object CircleCropGeometry {
    const val MIN_SIZE_DP = 24f

    /**
     * Clamps a rectangle strictly within the screen boundary [0..screenWidth, 0..screenHeight].
     * Ensures left <= right and top <= bottom.
     */
    fun clamp(
        rect: CropRect,
        screenWidth: Float,
        screenHeight: Float,
    ): CropRect {
        val l = rect.left.coerceIn(0f, screenWidth)
        val t = rect.top.coerceIn(0f, screenHeight)
        val r = rect.right.coerceIn(0f, screenWidth)
        val b = rect.bottom.coerceIn(0f, screenHeight)
        return CropRect(
            left = minOf(l, r),
            top = minOf(t, b),
            right = maxOf(l, r),
            bottom = maxOf(t, b),
        )
    }

    /**
     * Checks if a selection is large enough (min 24dp in both width and height).
     * Rejects circles or selections smaller than 24dp.
     */
    fun isValidSelection(
        rect: CropRect,
        density: Float,
        minDp: Float = MIN_SIZE_DP,
    ): Boolean {
        val minPx = minDp * density
        return rect.width >= minPx && rect.height >= minPx
    }

    /**
     * Resizes a crop rectangle by moving a handle with an incremental delta (dx, dy).
     * Enforces screen clamping and minimum size constraints so the rect never flips or exceeds screen bounds.
     */
    fun resizeByDelta(
        current: CropRect,
        handle: ResizeHandle,
        dx: Float,
        dy: Float,
        screenWidth: Float,
        screenHeight: Float,
        density: Float,
        minDp: Float = MIN_SIZE_DP,
    ): CropRect {
        val minPx = minDp * density
        var l = current.left
        var t = current.top
        var r = current.right
        var b = current.bottom

        when (handle) {
            ResizeHandle.TopLeft -> {
                l = (l + dx).coerceIn(0f, r - minPx)
                t = (t + dy).coerceIn(0f, b - minPx)
            }
            ResizeHandle.TopRight -> {
                r = (r + dx).coerceIn(l + minPx, screenWidth)
                t = (t + dy).coerceIn(0f, b - minPx)
            }
            ResizeHandle.BottomLeft -> {
                l = (l + dx).coerceIn(0f, r - minPx)
                b = (b + dy).coerceIn(t + minPx, screenHeight)
            }
            ResizeHandle.BottomRight -> {
                r = (r + dx).coerceIn(l + minPx, screenWidth)
                b = (b + dy).coerceIn(t + minPx, screenHeight)
            }
        }
        return clamp(CropRect(l, t, r, b), screenWidth, screenHeight)
    }

    /**
     * Resizes a crop rectangle to a specific target point (newX, newY), clamped to screen bounds and min size.
     */
    fun resizeToPoint(
        current: CropRect,
        handle: ResizeHandle,
        newX: Float,
        newY: Float,
        screenWidth: Float,
        screenHeight: Float,
        density: Float,
        minDp: Float = MIN_SIZE_DP,
    ): CropRect {
        val minPx = minDp * density
        val clampedX = newX.coerceIn(0f, screenWidth)
        val clampedY = newY.coerceIn(0f, screenHeight)

        var l = current.left
        var t = current.top
        var r = current.right
        var b = current.bottom

        when (handle) {
            ResizeHandle.TopLeft -> {
                l = minOf(clampedX, r - minPx)
                t = minOf(clampedY, b - minPx)
            }
            ResizeHandle.TopRight -> {
                r = maxOf(clampedX, l + minPx)
                t = minOf(clampedY, b - minPx)
            }
            ResizeHandle.BottomLeft -> {
                l = minOf(clampedX, r - minPx)
                b = maxOf(clampedY, t + minPx)
            }
            ResizeHandle.BottomRight -> {
                r = maxOf(clampedX, l + minPx)
                b = maxOf(clampedY, t + minPx)
            }
        }
        return clamp(CropRect(l, t, r, b), screenWidth, screenHeight)
    }

    /**
     * Expands [current] bounding box to include point ([x], [y]), clamped within screen bounds.
     * Ensures freehand circles/lassos never collapse back to zero when the stroke loops toward start.
     */
    fun expandBounds(
        current: CropRect,
        x: Float,
        y: Float,
        screenWidth: Float,
        screenHeight: Float,
    ): CropRect {
        val raw = CropRect(
            left = minOf(current.left, x),
            top = minOf(current.top, y),
            right = maxOf(current.right, x),
            bottom = maxOf(current.bottom, y),
        )
        return clamp(raw, screenWidth, screenHeight)
    }

    /**
     * Returns the nearest corner [ResizeHandle] within [hitRadiusPx] of ([x], [y]), or null.
     */
    fun hitTestHandle(
        rect: CropRect,
        x: Float,
        y: Float,
        hitRadiusPx: Float,
    ): ResizeHandle? {
        val corners = listOf(
            ResizeHandle.TopLeft to Pair(rect.left, rect.top),
            ResizeHandle.TopRight to Pair(rect.right, rect.top),
            ResizeHandle.BottomLeft to Pair(rect.left, rect.bottom),
            ResizeHandle.BottomRight to Pair(rect.right, rect.bottom),
        )
        var best: ResizeHandle? = null
        var bestDistSq = hitRadiusPx * hitRadiusPx
        for ((handle, pt) in corners) {
            val dx = x - pt.first
            val dy = y - pt.second
            val d2 = dx * dx + dy * dy
            if (d2 <= bestDistSq) {
                bestDistSq = d2
                best = handle
            }
        }
        return best
    }

    /**
     * Scales a [CropRect] from overlay view coordinates to target bitmap pixel coordinates.
     */
    fun mapRectToBitmap(
        rect: CropRect,
        viewWidth: Float,
        viewHeight: Float,
        bitmapWidth: Int,
        bitmapHeight: Int,
    ): CropRect {
        if (viewWidth <= 0f || viewHeight <= 0f || bitmapWidth <= 0 || bitmapHeight <= 0) return rect
        val sx = bitmapWidth.toFloat() / viewWidth
        val sy = bitmapHeight.toFloat() / viewHeight
        return clamp(
            CropRect(
                left = rect.left * sx,
                top = rect.top * sy,
                right = rect.right * sx,
                bottom = rect.bottom * sy,
            ),
            bitmapWidth.toFloat(),
            bitmapHeight.toFloat(),
        )
    }

    /**
     * Calculates scaled dimensions maintaining aspect ratio, capped at maxLongestSide (1080px).
     */
    fun calculateScaledDimensions(
        width: Int,
        height: Int,
        maxLongestSide: Int = 1080,
    ): Pair<Int, Int> {
        val longest = maxOf(width, height)
        val factor = if (longest > maxLongestSide) maxLongestSide.toDouble() / longest else 1.0
        val targetW = (width * factor).toInt().coerceAtLeast(1)
        val targetH = (height * factor).toInt().coerceAtLeast(1)
        return Pair(targetW, targetH)
    }
}
