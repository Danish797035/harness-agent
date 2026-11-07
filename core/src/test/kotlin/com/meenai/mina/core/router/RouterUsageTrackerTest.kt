package com.meenai.mina.core.router

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.time.ZonedDateTime

class RouterUsageTrackerTest {

    @Before
    fun setUp() {
        RouterUsageTracker.clear()
    }

    @Test
    fun istMidnightResetTest() {
        // 2026-05-10 23:59:50 IST
        val tBeforeMidnight = ZonedDateTime.of(
            2026, 5, 10, 23, 59, 50, 0,
            RouterUsageTracker.IST_ZONE,
        ).toInstant().toEpochMilli()

        // 2026-05-11 00:00:10 IST (crossed midnight in Asia/Kolkata)
        val tAfterMidnight = ZonedDateTime.of(
            2026, 5, 11, 0, 0, 10, 0,
            RouterUsageTracker.IST_ZONE,
        ).toInstant().toEpochMilli()

        RouterUsageTracker.recordCall("Qwen/Qwen2.5-72B-Instruct", "modelscope", nowMs = tBeforeMidnight)
        RouterUsageTracker.recordCall("Qwen/Qwen2.5-72B-Instruct", "modelscope", nowMs = tBeforeMidnight)

        assertEquals(2, RouterUsageTracker.getTodayCalls("Qwen/Qwen2.5-72B-Instruct", nowMs = tBeforeMidnight))
        assertEquals(2, RouterUsageTracker.getTodayProviderCalls("modelscope", nowMs = tBeforeMidnight))

        // Across IST midnight, counter for today must be 0
        assertEquals(0, RouterUsageTracker.getTodayCalls("Qwen/Qwen2.5-72B-Instruct", nowMs = tAfterMidnight))
        assertEquals(0, RouterUsageTracker.getTodayProviderCalls("modelscope", nowMs = tAfterMidnight))

        // Recording on new day increments new day's counter
        RouterUsageTracker.recordCall("Qwen/Qwen2.5-72B-Instruct", "modelscope", nowMs = tAfterMidnight)
        assertEquals(1, RouterUsageTracker.getTodayCalls("Qwen/Qwen2.5-72B-Instruct", nowMs = tAfterMidnight))

        // Does not reset at UTC midnight (05:30 IST)
        val tUtcMidnight = ZonedDateTime.of(
            2026, 5, 11, 5, 30, 0, 0,
            RouterUsageTracker.IST_ZONE,
        ).toInstant().toEpochMilli()
        assertEquals(1, RouterUsageTracker.getTodayCalls("Qwen/Qwen2.5-72B-Instruct", nowMs = tUtcMidnight))
    }
}
