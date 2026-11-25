package com.meenai.mina.task

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactAlarmGuideTest {
    // If you don’t have the permission for the punctual alarm clock, you will only be reminded once; if
    // you have the permission, you will not be disturbed.
    @Test fun promptsOnceOnlyWhenPermissionMissing() {
        assertTrue(ExactAlarmGuide.shouldPrompt(granted = false, prompted = false))
        assertFalse(ExactAlarmGuide.shouldPrompt(granted = false, prompted = true))
        assertFalse(ExactAlarmGuide.shouldPrompt(granted = true, prompted = false))
    }
}
