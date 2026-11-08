package com.meenai.mina.core.router

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelHealthTrackerTest {

    @Test
    fun callOutcome_untestedIsNone_successIsSuccess_failureIsFailure() {
        val tracker = ModelHealthTracker()
        val model = "Qwen/Qwen2.5-72B-Instruct"

        // Initially untested -> None
        assertEquals(ModelHealthTracker.CallOutcome.None, tracker.getLastCallOutcome(model))

        // Success recorded -> Success
        tracker.recordSuccess(model, 1200L)
        assertEquals(ModelHealthTracker.CallOutcome.Success, tracker.getLastCallOutcome(model))
        // Case insensitive lookup
        assertEquals(ModelHealthTracker.CallOutcome.Success, tracker.getLastCallOutcome(model.lowercase()))

        // Failure recorded -> Failure
        tracker.recordFailure(model)
        assertEquals(ModelHealthTracker.CallOutcome.Failure, tracker.getLastCallOutcome(model))

        // Reset clears outcome -> None
        tracker.reset(model)
        assertEquals(ModelHealthTracker.CallOutcome.None, tracker.getLastCallOutcome(model))
    }
}
