package com.meenai.mina.capsule.circle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for HA-029: CircleCropGeometry crash prevention and boundary clamping.
 * Verifies all 4 resize handles under boundary conditions, minimum size constraints,
 * and invalid geometry inputs to ensure no coerceIn() call can crash with min > max.
 */
class CircleCropGeometryTest {

    private val screenWidth = 1080f
    private val screenHeight = 1920f
    private val density = 2.0f // 24dp = 48px

    // --- Boundary Edge Cases for all 4 resize directions (HA-029 Regression Tests) ---

    @Test
    fun boundaryDragTopLeftWhenRightIsLessThanMinSizeDoesNotCrash() {
        // right = 20px, while minPx = 48px -> (r - minPx) = -28px (< 0f)
        val rectNearLeftEdge = CropRect(left = 5f, top = 5f, right = 20f, bottom = 20f)
        val result = CircleCropGeometry.resizeByDelta(
            current = rectNearLeftEdge,
            handle = ResizeHandle.TopLeft,
            dx = -50f,
            dy = -50f,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            density = density,
        )

        assertTrue("Resulting left must be >= 0", result.left >= 0f)
        assertTrue("Resulting top must be >= 0", result.top >= 0f)
        assertTrue("Left must not exceed right", result.left <= result.right)
        assertTrue("Top must not exceed bottom", result.top <= result.bottom)
    }

    @Test
    fun boundaryDragTopRightWhenLeftIsNearScreenWidthDoesNotCrash() {
        // left = 1060px, while minPx = 48px -> (l + minPx) = 1108px (> screenWidth = 1080f)
        val rectNearRightEdge = CropRect(left = 1060f, top = 10f, right = 1075f, bottom = 60f)
        val result = CircleCropGeometry.resizeByDelta(
            current = rectNearRightEdge,
            handle = ResizeHandle.TopRight,
            dx = 50f,
            dy = -50f,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            density = density,
        )

        assertTrue("Resulting right must not exceed screenWidth", result.right <= screenWidth)
        assertTrue("Left must not exceed right", result.left <= result.right)
        assertTrue("Top must not exceed bottom", result.top <= result.bottom)
    }

    @Test
    fun boundaryDragBottomLeftWhenBottomIsNearScreenHeightDoesNotCrash() {
        // top = 1900px, while minPx = 48px -> (t + minPx) = 1948px (> screenHeight = 1920f)
        // right = 20px -> (r - minPx) = -28px (< 0f)
        val rectNearBottomLeft = CropRect(left = 10f, top = 1900f, right = 20f, bottom = 1915f)
        val result = CircleCropGeometry.resizeByDelta(
            current = rectNearBottomLeft,
            handle = ResizeHandle.BottomLeft,
            dx = -50f,
            dy = 50f,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            density = density,
        )

        assertTrue("Resulting left must be >= 0", result.left >= 0f)
        assertTrue("Resulting bottom must not exceed screenHeight", result.bottom <= screenHeight)
        assertTrue("Left must not exceed right", result.left <= result.right)
        assertTrue("Top must not exceed bottom", result.top <= result.bottom)
    }

    @Test
    fun boundaryDragBottomRightWhenBothEdgesExceedBoundsDoesNotCrash() {
        // left = 1070px, top = 1910px -> both lower bounds exceed screen boundaries
        val rectNearBottomRight = CropRect(left = 1070f, top = 1910f, right = 1075f, bottom = 1915f)
        val result = CircleCropGeometry.resizeByDelta(
            current = rectNearBottomRight,
            handle = ResizeHandle.BottomRight,
            dx = 500f,
            dy = 500f,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            density = density,
        )

        assertTrue("Resulting right must be <= screenWidth", result.right <= screenWidth)
        assertTrue("Resulting bottom must be <= screenHeight", result.bottom <= screenHeight)
        assertTrue("Left must not exceed right", result.left <= result.right)
        assertTrue("Top must not exceed bottom", result.top <= result.bottom)
    }

    // --- Minimum Size Constraints & Normal Behavior Preservation ---

    @Test
    fun normalResizeMaintainsMinimumSizeConstraint() {
        val initial = CropRect(left = 200f, top = 200f, right = 400f, bottom = 400f)
        val minPx = 24f * density // 48px

        // Drag TopLeft inward to shrink beyond minimum size
        val shrunkTL = CircleCropGeometry.resizeByDelta(
            current = initial,
            handle = ResizeHandle.TopLeft,
            dx = 190f,
            dy = 190f,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            density = density,
        )
        // With initial.right = 400, maxL is 400 - 48 = 352
        assertEquals(352f, shrunkTL.left, 0.001f)
        assertEquals(352f, shrunkTL.top, 0.001f)
        assertTrue("Width must be at least minPx", shrunkTL.width >= minPx - 0.001f)
        assertTrue("Height must be at least minPx", shrunkTL.height >= minPx - 0.001f)

        // Drag BottomRight inward to shrink beyond minimum size
        val shrunkBR = CircleCropGeometry.resizeByDelta(
            current = initial,
            handle = ResizeHandle.BottomRight,
            dx = -190f,
            dy = -190f,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            density = density,
        )
        // With initial.left = 200, minR is 200 + 48 = 248
        assertEquals(248f, shrunkBR.right, 0.001f)
        assertEquals(248f, shrunkBR.bottom, 0.001f)
        assertTrue("Width must be at least minPx", shrunkBR.width >= minPx - 0.001f)
        assertTrue("Height must be at least minPx", shrunkBR.height >= minPx - 0.001f)
    }

    // --- Invalid Geometry & Extreme Input Handling ---

    @Test
    fun clampHandlesInvertedAndNegativeDimensions() {
        // Negative screen dimensions
        val rect = CropRect(left = -50f, top = -50f, right = 500f, bottom = 500f)
        val clampedNegative = CircleCropGeometry.clamp(rect, -100f, -100f)
        assertEquals(0f, clampedNegative.left, 0.001f)
        assertEquals(0f, clampedNegative.top, 0.001f)
        assertEquals(0f, clampedNegative.right, 0.001f)
        assertEquals(0f, clampedNegative.bottom, 0.001f)

        // Inverted rect: left > right, top > bottom
        val inverted = CropRect(left = 800f, top = 900f, right = 200f, bottom = 300f)
        val clampedInverted = CircleCropGeometry.clamp(inverted, screenWidth, screenHeight)
        assertTrue("Left must be <= right", clampedInverted.left <= clampedInverted.right)
        assertTrue("Top must be <= bottom", clampedInverted.top <= clampedInverted.bottom)
        assertEquals(200f, clampedInverted.left, 0.001f)
        assertEquals(800f, clampedInverted.right, 0.001f)
    }

    @Test
    fun resizeToPointHandlesExtremeCoordinatesWithoutCrashing() {
        val initial = CropRect(left = 200f, top = 200f, right = 400f, bottom = 400f)

        // Drag handles to extreme negative and extreme positive coordinates
        val toFarLeft = CircleCropGeometry.resizeToPoint(
            current = initial,
            handle = ResizeHandle.TopLeft,
            newX = -9999f,
            newY = -9999f,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            density = density,
        )
        assertEquals(0f, toFarLeft.left, 0.001f)
        assertEquals(0f, toFarLeft.top, 0.001f)

        val toFarRight = CircleCropGeometry.resizeToPoint(
            current = initial,
            handle = ResizeHandle.BottomRight,
            newX = 99999f,
            newY = 99999f,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            density = density,
        )
        assertEquals(screenWidth, toFarRight.right, 0.001f)
        assertEquals(screenHeight, toFarRight.bottom, 0.001f)
    }

    @Test
    fun calculateScaledDimensionsHandlesEdgeCases() {
        // Zero and negative dimensions
        val (w0, h0) = CircleCropGeometry.calculateScaledDimensions(0, 0, 1080)
        assertEquals(1, w0)
        assertEquals(1, h0)

        val (wNeg, hNeg) = CircleCropGeometry.calculateScaledDimensions(-50, -100, 1080)
        assertEquals(1, wNeg)
        assertEquals(1, hNeg)

        // Normal scaling capping at maxLongestSide
        val (wHigh, hHigh) = CircleCropGeometry.calculateScaledDimensions(2160, 3840, 1080)
        assertEquals(1080, maxOf(wHigh, hHigh))
        assertTrue("Aspect ratio should be preserved roughly", (2160.0 / 3840.0 - wHigh.toDouble() / hHigh) < 0.01)
    }

    @Test
    fun isValidSelectionHandlesZeroAndNegativeDensityGracefully() {
        val rect = CropRect(left = 0f, top = 0f, right = 100f, bottom = 100f)
        assertTrue(CircleCropGeometry.isValidSelection(rect, density = 0f))
        assertTrue(CircleCropGeometry.isValidSelection(rect, density = -2f))
    }
}
