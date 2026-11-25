package com.meenai.mina.settings

import com.meenai.mina.capsule.CapsuleMode
import com.meenai.mina.core.cap.phone.OperatingPace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The whitelist of settings_list / settings_set: only gives preferences that can be changed back easily; models, keys, and deleted data are not included. */
class SelfConfigImplTest {
    private fun flags() = FeatureFlags(FeatureFlagsCapsuleTest.MemoryPrefs())

    @Test fun offersOnlyPreferencesAvailableToday() {
        val keys = SelfConfigImpl(flags()).options().map { it.key }.toSet()
        assertEquals(setOf("operating_pace", "mute_apps", "appearance", "capsule", "keep_screen_on"), keys)
    }

    @Test fun modelsKeysAndAnythingThatDeletesDataAreNotOffered() {
        val keys = SelfConfigImpl(flags()).options().map { it.key }
        assertTrue(keys.none { k -> listOf("model", "key", "delete", "reset", "clear").any { it in k } })
        // The networking and proxying of the container will allow the data to leave the phone, and the
        // user must decide in the settings.
        assertTrue("container_network" !in keys)
    }

    @Test fun changingAPreferenceTakesEffect() {
        val f = flags()
        val s = SelfConfigImpl(f)
        assertNull(s.set("operating_pace", "fast"))
        assertEquals(OperatingPace.FAST, f.operatingPace())
        assertNull(s.set("mute_apps", "off"))
        assertEquals(false, f.muteTarget())
        assertNull(s.set("appearance", "dark"))
        assertEquals(ThemeMode.Dark, f.themeMode())
        assertNull(s.set("capsule", "off"))
        assertEquals(CapsuleMode.Off, f.capsuleMode())
        assertNull(s.set("keep_screen_on", "off"))
        assertEquals(false, f.keepScreenOn())
    }

    @Test fun badValuesAndUnknownKeysAreReported() {
        val s = SelfConfigImpl(flags())
        assertNotNull(s.set("operating_pace", "turbo"))
        assertNotNull(s.set("appearance", "sepia"))
        assertNotNull(s.set("mute_apps", "maybe"))
        assertEquals("there is no such setting", s.set("api_key", "sk-123"))
        assertEquals("there is no such setting", s.set("container_network", "on"))
    }
}
