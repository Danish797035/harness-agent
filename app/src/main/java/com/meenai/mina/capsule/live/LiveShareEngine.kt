package com.meenai.mina.capsule.live

import com.meenai.mina.capsule.CapsuleSecurityPolicy
import com.meenai.mina.core.access.AppAccessState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

fun interface ConsentRequester {
    fun requestConsent(sessionId: String)
}

/**
 * State machine managing Live Screen Share lifecycle, policy gating, and session isolation.
 * Guarantees:
 * 1. Consent is never persisted across sessions ("MediaProjection har session mein nayi permission maange").
 * 2. Device lock immediately stops the session permanently ("Phone lock hote hi share band, unlock pe apne aap shuru nahi").
 * 3. Sensitive / blocked apps automatically pause the session, resuming only when leaving them.
 */
class LiveShareEngine(
    private val consentRequester: ConsentRequester,
) {
    private val _state = MutableStateFlow(LiveShareState())
    val state: StateFlow<LiveShareState> = _state.asStateFlow()

    // Holds no reusable token or intent. Each session has a fresh unique ID.
    private var activeSessionId: String? = null

    val isSharing: Boolean
        get() = _state.value.status == LiveShareStatus.Active || _state.value.status == LiveShareStatus.Paused

    fun startSession(): String {
        // Stop any active or pending session
        if (isSharing || _state.value.status == LiveShareStatus.RequestingConsent) {
            stopSession(StopReason.UserStop)
        }

        val newSessionId = UUID.randomUUID().toString()
        activeSessionId = newSessionId
        _state.value = LiveShareState(
            status = LiveShareStatus.RequestingConsent,
            sessionId = newSessionId,
            pauseReason = PauseReason.None,
            stopReason = null,
        )

        // Always request new consent; never reuse previous consent
        consentRequester.requestConsent(newSessionId)
        return newSessionId
    }

    fun onConsentGranted(sessionId: String) {
        if (activeSessionId != sessionId || _state.value.status != LiveShareStatus.RequestingConsent) {
            return
        }
        _state.value = _state.value.copy(
            status = LiveShareStatus.Active,
            pauseReason = PauseReason.None,
            stopReason = null,
        )
    }

    fun onConsentDenied(sessionId: String) {
        if (activeSessionId != sessionId) return
        stopSession(StopReason.ConsentDenied)
    }

    fun onDeviceLockChanged(locked: Boolean) {
        if (locked && (isSharing || _state.value.status == LiveShareStatus.RequestingConsent)) {
            // Lock event permanently stops the live share session
            stopSession(StopReason.DeviceLocked)
        }
    }

    fun onForegroundPackagesChanged(packages: Collection<String>?, appAccessState: AppAccessState) {
        val currentStatus = _state.value.status
        if (currentStatus != LiveShareStatus.Active && currentStatus != LiveShareStatus.Paused) {
            return
        }

        val isSensitive = packages.isNullOrEmpty() || !CapsuleSecurityPolicy.canPerformScreenAction(packages, false, appAccessState)
        if (isSensitive) {
            if (currentStatus == LiveShareStatus.Active) {
                _state.value = _state.value.copy(
                    status = LiveShareStatus.Paused,
                    pauseReason = PauseReason.SensitiveApp,
                )
            }
        } else {
            if (currentStatus == LiveShareStatus.Paused) {
                _state.value = _state.value.copy(
                    status = LiveShareStatus.Active,
                    pauseReason = PauseReason.None,
                )
            }
        }
    }

    fun stopSession(reason: StopReason = StopReason.UserStop) {
        val prevSession = activeSessionId
        activeSessionId = null
        _state.value = LiveShareState(
            status = LiveShareStatus.Stopped,
            sessionId = prevSession,
            pauseReason = PauseReason.None,
            stopReason = reason,
        )
    }

    fun resetToIdle() {
        if (_state.value.status == LiveShareStatus.Stopped) {
            _state.value = LiveShareState(status = LiveShareStatus.Idle)
        }
    }
}
