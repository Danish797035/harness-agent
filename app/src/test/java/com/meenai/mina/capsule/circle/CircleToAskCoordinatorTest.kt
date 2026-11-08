package com.meenai.mina.capsule.circle

import com.meenai.mina.core.access.AppAccessState
import com.meenai.mina.core.access.AppCategory
import com.meenai.mina.core.access.PackageClassification
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 3 unit tests:
 * 1. crop rectangle screen ke bahar nahi jata (clamp)
 * 2. bahut chhota gola (24dp se kam) reject
 * 3. blocked app par circle nahi khulta
 * 4. Vision model na ho to kuch nahi bheja jata
 */
class CircleToAskCoordinatorTest {

    @Test
    fun cropRectangleScreenKeBaharNahiJata() {
        val screenWidth = 1080f
        val screenHeight = 1920f

        // Out-of-bounds rect on all 4 sides
        val outOfBounds = CropRect(left = -150f, top = -80f, right = 2000f, bottom = 3000f)
        val clamped = CircleCropGeometry.clamp(outOfBounds, screenWidth, screenHeight)

        assertEquals("Left must be clamped to 0", 0f, clamped.left, 0.001f)
        assertEquals("Top must be clamped to 0", 0f, clamped.top, 0.001f)
        assertEquals("Right must be clamped to screenWidth", screenWidth, clamped.right, 0.001f)
        assertEquals("Bottom must be clamped to screenHeight", screenHeight, clamped.bottom, 0.001f)

        // Resizing TopLeft handle with huge negative delta
        val initial = CropRect(left = 100f, top = 100f, right = 500f, bottom = 500f)
        val resizedTL = CircleCropGeometry.resizeByDelta(
            current = initial,
            handle = ResizeHandle.TopLeft,
            dx = -500f,
            dy = -500f,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            density = 2f,
        )
        assertTrue("Resized left must not be negative", resizedTL.left >= 0f)
        assertTrue("Resized top must not be negative", resizedTL.top >= 0f)

        // Resizing BottomRight handle with huge positive delta
        val resizedBR = CircleCropGeometry.resizeByDelta(
            current = initial,
            handle = ResizeHandle.BottomRight,
            dx = 5000f,
            dy = 5000f,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            density = 2f,
        )
        assertTrue("Resized right must not exceed screenWidth", resizedBR.right <= screenWidth)
        assertTrue("Resized bottom must not exceed screenHeight", resizedBR.bottom <= screenHeight)
    }

    @Test
    fun bahutChhotaGolaReject() = runBlocking {
        val density = 2.0f // 24dp = 48px

        // Selections smaller than 24dp
        val tooNarrow = CropRect(left = 100f, top = 100f, right = 140f, bottom = 200f) // width = 40px (< 48px)
        val tooShort = CropRect(left = 100f, top = 100f, right = 200f, bottom = 140f) // height = 40px (< 48px)
        val tiny = CropRect(left = 100f, top = 100f, right = 120f, bottom = 120f) // 20px x 20px (< 24dp)

        assertFalse("Selection narrower than 24dp must be rejected", CircleCropGeometry.isValidSelection(tooNarrow, density))
        assertFalse("Selection shorter than 24dp must be rejected", CircleCropGeometry.isValidSelection(tooShort, density))
        assertFalse("Selection smaller than 24dp must be rejected", CircleCropGeometry.isValidSelection(tiny, density))

        // Valid selection (>= 24dp)
        val valid = CropRect(left = 100f, top = 100f, right = 150f, bottom = 160f) // width=50, height=60 (>= 48px)
        assertTrue("Selection of 24dp or larger must be accepted", CircleCropGeometry.isValidSelection(valid, density))

        // Coordinator execution rejects too small selection
        var queryCalls = 0
        val coordinator = CircleToAskCoordinator(
            visionModelProvider = { null },
            sendQuery = { _, _, _, _ ->
                queryCalls++
                "dummy"
            },
            onWarning = {}
        )

        val result = coordinator.executeAsk(
            rect = tiny,
            density = density,
            croppedJpeg = byteArrayOf(1, 2, 3),
            width = 20,
            height = 20,
            userQuery = "What is this?"
        )
        assertEquals(CircleToAskCoordinator.AskResult.TooSmall, result)
        assertEquals("No query should be dispatched when selection is rejected", 0, queryCalls)
    }

    @Test
    fun blockedAppParCircleNahiKhulta() {
        val coordinator = CircleToAskCoordinator(
            visionModelProvider = { null },
            sendQuery = { _, _, _, _ -> "dummy" },
            onWarning = {}
        )

        val defaultState = AppAccessState(
            available = true,
            blocked = setOf("com.secret.vault"),
            canShareAll = true,
            classifications = mapOf(
                "com.mybank.app" to PackageClassification("com.mybank.app", AppCategory.Finance)
            )
        )

        // Blocked app
        val blockedAllowed = coordinator.canOpenCircleToAsk(listOf("com.secret.vault"), defaultState)
        assertFalse("Circle must not open on user-blocked app", blockedAllowed)

        // Aadhaar app
        val aadhaarAllowed = coordinator.canOpenCircleToAsk(listOf("in.gov.uidai.myaadhaar"), defaultState)
        assertFalse("Circle must not open on Aadhaar app", aadhaarAllowed)

        // UPI app
        val upiAllowed = coordinator.canOpenCircleToAsk(listOf("com.phonepe.app"), defaultState)
        assertFalse("Circle must not open on UPI app", upiAllowed)

        // Banking / Finance app
        val bankAllowed = coordinator.canOpenCircleToAsk(listOf("com.mybank.app"), defaultState)
        assertFalse("Circle must not open on banking / finance app", bankAllowed)

        // Normal safe app
        val safeAllowed = coordinator.canOpenCircleToAsk(listOf("com.android.chrome"), defaultState)
        assertTrue("Circle should open on safe unblocked app", safeAllowed)
    }

    @Test
    fun visionModelNaHoToKuchNahiBhejaJata() = runBlocking {
        var dispatchedCalls = 0
        val warnings = mutableListOf<String>()

        val coordinator = CircleToAskCoordinator(
            visionModelProvider = { null }, // No Vision model set
            sendQuery = { _, _, _, _ ->
                dispatchedCalls++
                "answer"
            },
            onWarning = { warnings.add(it) }
        )

        val validRect = CropRect(100f, 100f, 300f, 300f)
        val result = coordinator.executeAsk(
            rect = validRect,
            density = 2f,
            croppedJpeg = byteArrayOf(10, 20, 30),
            width = 200,
            height = 200,
            userQuery = "Explain this part"
        )

        assertEquals(CircleToAskCoordinator.AskResult.NoVisionModel, result)
        assertEquals("Vision model na ho to zero queries must be sent", 0, dispatchedCalls)
        assertEquals(1, warnings.size)
        assertTrue(warnings[0].contains("Screen dekhne wala model set nahi hai"))
    }
}
