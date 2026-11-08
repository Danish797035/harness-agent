package com.meenai.mina.capsule.live

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2b unit tests:
 * 1. same screen par frame dobara nahi jata (unchanged screen frame is not resent).
 * 2. 3 second se pehle dusra frame nahi (minimum 3-second interval enforced).
 * 3. paused state mein zero frames (no frames captured or sent while in Paused state).
 * 4. Vision model na ho to frame nahi jata (and single warning issued: "Screen dekhne wala model set nahi hai").
 */
class LiveShareFrameCoordinatorTest {

    @Test
    fun sameScreenParFrameDobaraNahiJata() = runBlocking {
        var isPaused = false
        var hasVision = true
        var currentTask: String? = "Order groceries"
        val sentFrames = mutableListOf<ByteArray>()
        val warnings = mutableListOf<String>()

        val coordinator = LiveShareFrameCoordinator(
            minIntervalMs = 3000L,
            maxLongestSide = 1080,
            isPaused = { isPaused },
            hasVisionModel = { hasVision },
            currentTaskProvider = { currentTask },
            onSendFrame = { jpeg, _, _, _ -> sentFrames.add(jpeg) },
            onWarning = { warnings.add(it) }
        )

        val frameA = byteArrayOf(1, 2, 3, 4)
        val hashA = 0x12345678L

        // First frame dispatched at t = 0
        val r1 = coordinator.onFrameAvailable(frameA, 1080, 1920, hashA, currentTimeMs = 0L)
        assertEquals(LiveShareFrameCoordinator.ProcessResult.Dispatched, r1)
        assertEquals(1, sentFrames.size)

        // Same screen at t = 4000 (> 3 seconds later): must NOT be sent again
        val r2 = coordinator.onFrameAvailable(frameA, 1080, 1920, hashA, currentTimeMs = 4000L)
        assertEquals(LiveShareFrameCoordinator.ProcessResult.SkippedUnchanged, r2)
        assertEquals("Same screen frame must not be sent again", 1, sentFrames.size)

        // Different screen at t = 5000: must be dispatched
        val frameB = byteArrayOf(5, 6, 7, 8)
        val hashB = 0x87654321L
        val r3 = coordinator.onFrameAvailable(frameB, 1080, 1920, hashB, currentTimeMs = 5000L)
        assertEquals(LiveShareFrameCoordinator.ProcessResult.Dispatched, r3)
        assertEquals(2, sentFrames.size)
    }

    @Test
    fun threeSecondSePehleDusraFrameNahi() = runBlocking {
        val sentFrames = mutableListOf<ByteArray>()

        val coordinator = LiveShareFrameCoordinator(
            minIntervalMs = 3000L,
            maxLongestSide = 1080,
            isPaused = { false },
            hasVisionModel = { true },
            currentTaskProvider = { "Check weather" },
            onSendFrame = { jpeg, _, _, _ -> sentFrames.add(jpeg) },
            onWarning = { }
        )

        val frame1 = byteArrayOf(1, 1, 1)
        val hash1 = 100L

        // Frame 1 sent at t = 1000
        val r1 = coordinator.onFrameAvailable(frame1, 720, 1280, hash1, currentTimeMs = 1000L)
        assertEquals(LiveShareFrameCoordinator.ProcessResult.Dispatched, r1)
        assertEquals(1, sentFrames.size)

        // Different Frame 2 arrived at t = 2500 (only 1.5s later, < 3s): must be skipped
        val frame2 = byteArrayOf(2, 2, 2)
        val hash2 = 200L
        val r2 = coordinator.onFrameAvailable(frame2, 720, 1280, hash2, currentTimeMs = 2500L)
        assertEquals(LiveShareFrameCoordinator.ProcessResult.SkippedTooSoon, r2)
        assertEquals("Frame before 3 seconds must not be sent", 1, sentFrames.size)

        // Different Frame 2 arrived at t = 4001 (>= 3s later): must be dispatched
        val r3 = coordinator.onFrameAvailable(frame2, 720, 1280, hash2, currentTimeMs = 4001L)
        assertEquals(LiveShareFrameCoordinator.ProcessResult.Dispatched, r3)
        assertEquals(2, sentFrames.size)
    }

    @Test
    fun pausedStateMeinZeroFrames() = runBlocking {
        var isPaused = true
        val sentFrames = mutableListOf<ByteArray>()

        val coordinator = LiveShareFrameCoordinator(
            minIntervalMs = 3000L,
            maxLongestSide = 1080,
            isPaused = { isPaused },
            hasVisionModel = { true },
            currentTaskProvider = { "Sensitive bank task" },
            onSendFrame = { jpeg, _, _, _ -> sentFrames.add(jpeg) },
            onWarning = { }
        )

        // When paused (blocked or financial app open)
        val r1 = coordinator.onFrameAvailable(byteArrayOf(1), 1080, 1920, 1L, currentTimeMs = 0L)
        assertEquals(LiveShareFrameCoordinator.ProcessResult.SkippedPaused, r1)

        val r2 = coordinator.onFrameAvailable(byteArrayOf(2), 1080, 1920, 2L, currentTimeMs = 5000L)
        assertEquals(LiveShareFrameCoordinator.ProcessResult.SkippedPaused, r2)

        val r3 = coordinator.onFrameAvailable(byteArrayOf(3), 1080, 1920, 3L, currentTimeMs = 10000L)
        assertEquals(LiveShareFrameCoordinator.ProcessResult.SkippedPaused, r3)

        assertEquals("In paused state exactly zero frames must be dispatched", 0, sentFrames.size)
        assertEquals(0, coordinator.framesDispatchedCount)
    }

    @Test
    fun visionModelNaHoToFrameNahiJata() = runBlocking {
        var hasVision = false
        val sentFrames = mutableListOf<ByteArray>()
        val warnings = mutableListOf<String>()

        val coordinator = LiveShareFrameCoordinator(
            minIntervalMs = 3000L,
            maxLongestSide = 1080,
            isPaused = { false },
            hasVisionModel = { hasVision },
            currentTaskProvider = { "General task" },
            onSendFrame = { jpeg, _, _, _ -> sentFrames.add(jpeg) },
            onWarning = { warnings.add(it) }
        )

        // Frame at t = 0 without vision model
        val r1 = coordinator.onFrameAvailable(byteArrayOf(1), 1080, 1920, 1L, currentTimeMs = 0L)
        assertEquals(LiveShareFrameCoordinator.ProcessResult.SkippedNoVisionModel, r1)
        assertEquals("Warning must be shown when vision model is missing", 1, warnings.size)
        assertTrue(warnings[0].contains("Screen dekhne wala model set nahi hai"))

        // Frame at t = 5000 still without vision model: warning must NOT be repeated
        val r2 = coordinator.onFrameAvailable(byteArrayOf(2), 1080, 1920, 2L, currentTimeMs = 5000L)
        assertEquals(LiveShareFrameCoordinator.ProcessResult.SkippedNoVisionModel, r2)
        assertEquals("Warning must only be shown once", 1, warnings.size)

        assertEquals("No frames should be dispatched if vision model is missing", 0, sentFrames.size)
    }
}
