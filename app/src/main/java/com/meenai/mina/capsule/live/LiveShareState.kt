package com.meenai.mina.capsule.live

enum class LiveShareStatus {
    Idle,
    RequestingConsent,
    Active,
    Paused,
    Stopped,
}

enum class PauseReason {
    None,
    SensitiveApp,
    BlockedApp,
}

enum class StopReason {
    UserStop,
    DeviceLocked,
    ConsentDenied,
    SystemTerminated,
    Error,
}

data class LiveShareState(
    val status: LiveShareStatus = LiveShareStatus.Idle,
    val sessionId: String? = null,
    val pauseReason: PauseReason = PauseReason.None,
    val stopReason: StopReason? = null,
)
