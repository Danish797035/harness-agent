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
        val maxW = maxOf(0f, screenWidth)
        val maxH = maxOf(0f, screenHeight)
        val l = rect.left.coerceIn(0f, maxW)
        val t = rect.top.coerceIn(0f, maxH)
        val r = rect.right.coerceIn(0f, maxW)
        val b = rect.bottom.coerceIn(0f, maxH)
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
        val minPx = maxOf(0f, minDp * maxOf(0f, density))
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
        val maxW = maxOf(0f, screenWidth)
        val maxH = maxOf(0f, screenHeight)
        val minPx = maxOf(0f, minDp * maxOf(0f, density))
        var l = current.left
        var t = current.top
        var r = current.right
        var b = current.bottom

        when (handle) {
            ResizeHandle.TopLeft -> {
                val maxL = maxOf(0f, minOf(maxW, r - minPx))
                val maxT = maxOf(0f, minOf(maxH, b - minPx))
                l = (l + dx).coerceIn(0f, maxL)
                t = (t + dy).coerceIn(0f, maxT)
            }
            ResizeHandle.TopRight -> {
                val minR = minOf(maxW, maxOf(0f, l + minPx))
                val maxT = maxOf(0f, minOf(maxH, b - minPx))
                r = (r + dx).coerceIn(minR, maxW)
                t = (t + dy).coerceIn(0f, maxT)
            }
            ResizeHandle.BottomLeft -> {
                val maxL = maxOf(0f, minOf(maxW, r - minPx))
                val minB = minOf(maxH, maxOf(0f, t + minPx))
                l = (l + dx).coerceIn(0f, maxL)
                b = (b + dy).coerceIn(minB, maxH)
            }
            ResizeHandle.BottomRight -> {
                val minR = minOf(maxW, maxOf(0f, l + minPx))
                val minB = minOf(maxH, maxOf(0f, t + minPx))
                r = (r + dx).coerceIn(minR, maxW)
                b = (b + dy).coerceIn(minB, maxH)
            }
        }
        return clamp(CropRect(l, t, r, b), maxW, maxH)
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
        val maxW = maxOf(0f, screenWidth)
        val maxH = maxOf(0f, screenHeight)
        val minPx = maxOf(0f, minDp * maxOf(0f, density))
        val clampedX = newX.coerceIn(0f, maxW)
        val clampedY = newY.coerceIn(0f, maxH)

        var l = current.left
        var t = current.top
        var r = current.right
        var b = current.bottom

        when (handle) {
            ResizeHandle.TopLeft -> {
                val maxL = maxOf(0f, minOf(maxW, r - minPx))
                val maxT = maxOf(0f, minOf(maxH, b - minPx))
                l = minOf(clampedX, maxL)
                t = minOf(clampedY, maxT)
            }
            ResizeHandle.TopRight -> {
                val minR = minOf(maxW, maxOf(0f, l + minPx))
                val maxT = maxOf(0f, minOf(maxH, b - minPx))
                r = maxOf(clampedX, minR)
                t = minOf(clampedY, maxT)
            }
            ResizeHandle.BottomLeft -> {
                val maxL = maxOf(0f, minOf(maxW, r - minPx))
                val minB = minOf(maxH, maxOf(0f, t + minPx))
                l = minOf(clampedX, maxL)
                b = maxOf(clampedY, minB)
            }
            ResizeHandle.BottomRight -> {
                val minR = minOf(maxW, maxOf(0f, l + minPx))
                val minB = minOf(maxH, maxOf(0f, t + minPx))
                r = maxOf(clampedX, minR)
                b = maxOf(clampedY, minB)
            }
        }
        return clamp(CropRect(l, t, r, b), maxW, maxH)
    }

    /**
     * Calculates scaled dimensions maintaining aspect ratio, capped at maxLongestSide (1080px).
     */
    fun calculateScaledDimensions(
        width: Int,
        height: Int,
        maxLongestSide: Int = 1080,
    ): Pair<Int, Int> {
        val safeW = maxOf(1, width)
        val safeH = maxOf(1, height)
        val longest = maxOf(safeW, safeH)
        val factor = if (longest > maxLongestSide && maxLongestSide > 0) maxLongestSide.toDouble() / longest else 1.0
        val targetW = (safeW * factor).toInt().coerceAtLeast(1)
        val targetH = (safeH * factor).toInt().coerceAtLeast(1)
        return Pair(targetW, targetH)
    }
}
