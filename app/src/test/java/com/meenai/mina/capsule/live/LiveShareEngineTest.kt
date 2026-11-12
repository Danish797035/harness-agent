package com.meenai.mina.capsule.live

import com.meenai.mina.core.access.AppAccessState
import com.meenai.mina.core.access.AppCategory
import com.meenai.mina.core.access.AppClassification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2a tests:
 * 1. Lock event par share stop ("Phone lock hote hi share band, unlock pe apne aap shuru nahi").
 * 2. Blocked app par pause ("Blocked apps, Aadhaar, bank aur UPI apps khulte hi share pause").
 * 3. Nayi session mein consent dobara maangi jaati hai ("MediaProjection har session mein nayi permission maange, purani reuse nahi").
 */
class LiveShareEngineTest {

    @Test
    fun lockEventStopsLiveShareAndUnlockDoesNotResume() {
        val requestedConsents = mutableListOf<String>()
        val engine = LiveShareEngine { requestedConsents.add(it) }

        val sessionId = engine.startSession()
        assertEquals(1, requestedConsents.size)
        assertEquals(LiveShareStatus.RequestingConsent, engine.state.value.status)

        // Consent granted -> Active
        engine.onConsentGranted(sessionId)
        assertEquals(LiveShareStatus.Active, engine.state.value.status)
        assertTrue(engine.isSharing)

        // Lock event occurs: share must stop immediately
        engine.onDeviceLockChanged(locked = true)
        assertEquals(LiveShareStatus.Stopped, engine.state.value.status)
        assertEquals(StopReason.DeviceLocked, engine.state.value.stopReason)
        assertFalse(engine.isSharing)

        // Device unlocked: must NOT auto-resume
        engine.onDeviceLockChanged(locked = false)
        assertFalse("Live share must not auto-resume on device unlock", engine.isSharing)
        assertNotEquals(LiveShareStatus.Active, engine.state.value.status)
    }

    @Test
    fun blockedOrSensitiveAppPausesLiveShareAndResumesOnSafeApp() {
        val engine = LiveShareEngine { }
        val sessionId = engine.startSession()
        engine.onConsentGranted(sessionId)
        assertEquals(LiveShareStatus.Active, engine.state.value.status)

        val accessState = AppAccessState(
            blocked = setOf("com.blocked.browser"),
            classifications = mapOf(
                "com.custom.bank" to AppClassification(AppCategory.Finance)
            )
        )

        // 1. User opens user-blocked app -> Pauses
        engine.onForegroundPackagesChanged(listOf("com.blocked.browser"), accessState)
        assertEquals(LiveShareStatus.Paused, engine.state.value.status)
        assertEquals(PauseReason.SensitiveApp, engine.state.value.pauseReason)

        // 2. User switches to Finance category app -> Remains paused
        engine.onForegroundPackagesChanged(listOf("com.custom.bank"), accessState)
        assertEquals(LiveShareStatus.Paused, engine.state.value.status)

        // 3. User switches to Aadhaar / UPI apps -> Remains paused
        engine.onForegroundPackagesChanged(listOf("in.gov.uidai.maadhaar"), accessState)
        assertEquals(LiveShareStatus.Paused, engine.state.value.status)

        engine.onForegroundPackagesChanged(listOf("com.phonepe.app"), accessState)
        assertEquals(LiveShareStatus.Paused, engine.state.value.status)

        // 4. User navigates back to safe unblocked app -> Resumes to Active
        engine.onForegroundPackagesChanged(listOf("com.example.notes"), accessState)
        assertEquals(LiveShareStatus.Active, engine.state.value.status)
        assertEquals(PauseReason.None, engine.state.value.pauseReason)
        assertTrue(engine.isSharing)
    }

    @Test
    fun newSessionRequestsConsentAnewAndNeverReusesOldConsent() {
        val requestedSessionIds = mutableListOf<String>()
        val engine = LiveShareEngine { requestedSessionIds.add(it) }

        // Session 1 started
        val firstSessionId = engine.startSession()
        assertEquals(1, requestedSessionIds.size)
        assertEquals(firstSessionId, requestedSessionIds[0])

        engine.onConsentGranted(firstSessionId)
        assertEquals(LiveShareStatus.Active, engine.state.value.status)

        // Session 1 ends
        engine.stopSession(StopReason.UserStop)
        assertEquals(LiveShareStatus.Stopped, engine.state.value.status)
        assertFalse(engine.isSharing)

        // Session 2 started: must request consent again with a brand new session ID
        val secondSessionId = engine.startSession()
        assertEquals(2, requestedSessionIds.size)
        assertEquals(secondSessionId, requestedSessionIds[1])
        assertNotEquals("Session ID must be unique across sessions", firstSessionId, secondSessionId)
        assertEquals(LiveShareStatus.RequestingConsent, engine.state.value.status)

        // Old consent from session 1 cannot activate session 2
        engine.onConsentGranted(firstSessionId)
        assertEquals("Old session consent must be ignored", LiveShareStatus.RequestingConsent, engine.state.value.status)

        // New consent activates session 2
        engine.onConsentGranted(secondSessionId)
        assertEquals(LiveShareStatus.Active, engine.state.value.status)
        assertTrue(engine.isSharing)
    }

    @Test
    fun deviceLockCheckerBlocksSafeAppWhenDeviceLocked() {
        var locked = false
        val engine = LiveShareEngine { }.apply {
            deviceLockChecker = { locked }
        }

        val sessionId = engine.startSession()
        engine.onConsentGranted(sessionId)
        assertEquals(LiveShareStatus.Active, engine.state.value.status)

        val safeState = AppAccessState(available = true)
        engine.onForegroundPackagesChanged(listOf("com.android.chrome"), safeState)
        assertEquals(LiveShareStatus.Active, engine.state.value.status)

        // Now device is locked -> CapsuleSecurityPolicy must disallow screen action
        locked = true
        engine.onForegroundPackagesChanged(listOf("com.android.chrome"), safeState)
        assertEquals(LiveShareStatus.Paused, engine.state.value.status)
        assertEquals(PauseReason.SensitiveApp, engine.state.value.pauseReason)
    }
}
