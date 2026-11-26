package com.meenai.mina.capsule.circle

import org.junit.Assert.assertTrue
import org.junit.Test

class CircleCropGeometryTest {

    @Test
    fun resizeByDelta_smallRectAndEdgeSizesNeverThrowIllegalArgumentException() {
        val tinyRect = CropRect(left = 10f, top = 10f, right = 30f, bottom = 30f)

        // Even when minPx (72f) exceeds rect size (20f) and screenWidth (50f), every handle stays safe
        for (handle in ResizeHandle.entries) {
            for (delta in listOf(-200f, -10f, 10f, 200f)) {
                val edge = CircleCropGeometry.resizeByDelta(
                    current = tinyRect,
                    handle = handle,
                    dx = delta,
                    dy = delta,
                    screenWidth = 50f,
                    screenHeight = 50f,
                    density = 3f,
                )
                assertTrue(edge.left in 0f..50f)
                assertTrue(edge.right in 0f..50f)
                assertTrue(edge.top in 0f..50f)
                assertTrue(edge.bottom in 0f..50f)
                assertTrue(edge.left <= edge.right)
                assertTrue(edge.top <= edge.bottom)

                val zero = CircleCropGeometry.resizeByDelta(
                    current = tinyRect,
                    handle = handle,
                    dx = delta,
                    dy = delta,
                    screenWidth = 0f,
                    screenHeight = 0f,
                    density = 3f,
                )
                assertTrue(zero.left == 0f && zero.right == 0f)
                assertTrue(zero.top == 0f && zero.bottom == 0f)
            }
        }
    }
}
