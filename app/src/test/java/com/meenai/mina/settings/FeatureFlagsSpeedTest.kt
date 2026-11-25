package com.meenai.mina.settings

import com.meenai.mina.core.agent.SpeedFlags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFlagsSpeedTest {
    @Test fun freshInstallTurnsOnDefaultsIncludingReplay() {
        val f = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs()).speedFlags()
        assertTrue(f.inlineScreen && f.batchSteps && f.adaptiveVision && f.replay)
        assertFalse(f.lightSteps)
    }

    @Test fun earlierUpgradeOnlyAddsReplayOnce() {
        val prefs = FeatureFlagsCapsuleTest.MemoryPrefs()
        // The previous version has been upgraded, and the user has turned off continuous operation: this
        // time only playback is added, and nothing else is done.
        prefs.edit().putString("speed_flags", "inlineScreen,adaptiveVision").putBoolean("speed_defaults_applied_v2", true).commit()
        val flags = FeatureFlags(prefs)
        assertEquals(SpeedFlags(inlineScreen = true, adaptiveVision = true, replay = true), flags.speedFlags())
        // After the upgrade, if the user manually turns off playback, it will remain turned off.
        flags.setSpeed(flags.speedFlags().copy(replay = false))
        assertFalse(FeatureFlags(prefs).speedFlags().replay)
    }
}
