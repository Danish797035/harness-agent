package com.meenai.mina.about

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun comparesNumericParts() {
        assertTrue(AppVersion.isNewer("0.2.0", "0.1.0"))
        assertTrue(AppVersion.isNewer("0.10.0", "0.9.9"))
        assertTrue(AppVersion.isNewer("1.0", "0.99.99"))
        assertFalse(AppVersion.isNewer("0.1.0", "0.1.0"))
        assertFalse(AppVersion.isNewer("0.1.0", "0.2.0"))
    }

    @Test
    fun ignoresLeadingVAndMissingParts() {
        assertEquals(0, AppVersion.compare("v0.2.0", "0.2.0"))
        assertEquals(0, AppVersion.compare("V1.2", "1.2.0"))
        assertTrue(AppVersion.isNewer("v0.1.1", "0.1"))
        assertEquals("0.2.0", AppVersion.normalize(" v0.2.0 "))
    }

    @Test
    fun releaseBeatsPrerelease() {
        assertTrue(AppVersion.isNewer("0.2.0", "0.2.0-beta.1"))
        assertFalse(AppVersion.isNewer("0.2.0-beta.1", "0.2.0"))
        assertTrue(AppVersion.isNewer("0.2.0-beta.1", "0.1.0"))
        assertTrue(AppVersion.isNewer("0.2.0-beta.2", "0.2.0-beta.1"))
        assertTrue(AppVersion.isNewer("0.2.0-beta.10", "0.2.0-beta.9"))
        assertTrue(AppVersion.isNewer("0.2.0-rc.1", "0.2.0-beta.3"))
        assertTrue(AppVersion.isNewer("0.2.0-beta.1", "0.2.0-beta"))
    }

    @Test
    fun buildMetadataIgnored() {
        assertEquals(0, AppVersion.compare("0.2.0+65fcf92", "0.2.0"))
    }

    @Test
    fun parsesRunNumberFromTag() {
        assertEquals(127L, AppVersion.parseRunNumber("v0.1.48-127"))
        assertEquals(127L, AppVersion.parseRunNumber("0.1.48-127"))
        assertEquals(127L, AppVersion.parseRunNumber("v127"))
        assertEquals(127L, AppVersion.parseRunNumber("127"))
        assertEquals(1L, AppVersion.parseRunNumber("v1.0.0-1"))
        assertNull(AppVersion.parseRunNumber("v0.1.48"))
        assertNull(AppVersion.parseRunNumber("0.1.48"))
        assertNull(AppVersion.parseRunNumber("v0.1.48-beta"))
        assertNull(AppVersion.parseRunNumber("v0.1.48-beta.1"))
        assertNull(AppVersion.parseRunNumber(""))
    }

    @Test
    fun comparesRunNumbers() {
        assertTrue(AppVersion.isNewerRun("v0.1.48-127", 100))
        assertFalse(AppVersion.isNewerRun("v0.1.48-100", 127))
        assertFalse(AppVersion.isNewerRun("v0.1.48-100", 100))
        assertFalse(AppVersion.isNewerRun("v0.1.48", 100))
    }

    @Test
    fun checksUpdateAvailabilityWithRunNumberAndSemver() {
        // Run number higher than current versionCode -> update
        assertTrue(AppVersion.isUpdateAvailable("v0.1.48-127", "0.1.48", 100))
        // Run number lower or equal -> no update
        assertFalse(AppVersion.isUpdateAvailable("v0.1.48-100", "0.1.48", 127))
        assertFalse(AppVersion.isUpdateAvailable("v0.1.48-100", "0.1.48", 100))

        // No run number in tag -> fallback to semver comparison
        assertTrue(AppVersion.isUpdateAvailable("v0.2.0", "0.1.48", 100))
        assertFalse(AppVersion.isUpdateAvailable("v0.1.40", "0.1.48", 100))
        assertFalse(AppVersion.isUpdateAvailable("v0.1.48", "0.1.48", 100))
    }
}
