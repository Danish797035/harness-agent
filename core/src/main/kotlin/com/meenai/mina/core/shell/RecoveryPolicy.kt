package com.meenai.mina.core.shell

import kotlinx.coroutines.delay

/** Here are a few things to look at when deciding whether to restart Assistant. */
data class RecoveryInputs(
    /** This unit stores the pairing key. */
    val paired: Boolean,
    /** The wireless debugging switch of the system; some mobile phones cannot read it, so it is null. */
    val wirelessDebugging: Boolean?,
    val status: BuiltinShellStatus,
)

enum class StopReason {
    NotPaired,
    /** Wireless debugging is off: wait until it is turned on (retriggered by the party listening to the switch). */
    WirelessDebuggingOff,
    /** The failed category requires the user to take action (re-pairing, connecting to Wi-Fi), and retrying is useless. */
    NeedsUser,
    OutOfAttempts,
}

sealed interface RecoveryDecision {
    data object Healthy : RecoveryDecision
    /** Wait for [delayMs] and then reconnect with the paired key. */
    data class Try(val delayMs: Long) : RecoveryDecision
    /** Someone else is connecting: Wait for [delayMs] to check again, without overlapping and reconnecting. */
    data class Wait(val delayMs: Long) : RecoveryDecision
    data class Stop(val reason: StopReason) : RecoveryDecision
}

data class RecoveryOutcome(val recovered: Boolean, val attempts: Int, val stoppedBecause: StopReason?)

/**
 * When to restart when the assistant is not around, how often, and how many times to try. Unplugging the
 * USB and restarting adbd will take away all processes with shell identity, so there is no distinction
 * between "why it is missing": as long as the pairing is still there, the wireless debugging has not
 * been confirmed to be turned off, and the failure does not require user action, press escape to
 * reconnect. Whether you trust the network or not and cannot read it, always try first. Failure will be
 * determined by the connection result.
 */
object RecoveryPolicy {
    const val MAX_ATTEMPTS = 5
    const val BASE_DELAY_MS = 2_000L
    const val MAX_DELAY_MS = 30_000L

    /** Try it immediately for the first time, then 2, 4, 8, 16 seconds... capped at 30 seconds. */
    fun backoffMs(attempt: Int): Long =
        if (attempt <= 0) 0L else (BASE_DELAY_MS shl (attempt - 1).coerceAtMost(20)).coerceAtMost(MAX_DELAY_MS)

    fun decide(inputs: RecoveryInputs, attempts: Int): RecoveryDecision {
        val s = inputs.status
        return when {
            s == BuiltinShellStatus.Running -> RecoveryDecision.Healthy
            !inputs.paired || s == BuiltinShellStatus.NotPaired -> RecoveryDecision.Stop(StopReason.NotPaired)
            inputs.wirelessDebugging == false -> RecoveryDecision.Stop(StopReason.WirelessDebuggingOff)
            s is BuiltinShellStatus.Failed && needsUser(s.reason) -> RecoveryDecision.Stop(StopReason.NeedsUser)
            attempts >= MAX_ATTEMPTS -> RecoveryDecision.Stop(StopReason.OutOfAttempts)
            s == BuiltinShellStatus.Connecting -> RecoveryDecision.Wait(backoffMs(attempts).coerceAtLeast(BASE_DELAY_MS))
            else -> RecoveryDecision.Try(backoffMs(attempts))
        }
    }

    private fun needsUser(f: BuiltinShellFailure) = f.defaultFix != BuiltinShellFix.Retry

    /**
     * Press [decide] and try until it resumes or it is time to stop. [attempt] is a reconnection (the
     * actual implementation is `BuiltinShellControl.retry()`, which does not throw an exception and the
     * result is written into the status); [inputs] is re-read in each round.
     */
    suspend fun recover(
        inputs: suspend () -> RecoveryInputs,
        attempt: suspend () -> Unit,
        sleep: suspend (Long) -> Unit = { delay(it) },
    ): RecoveryOutcome {
        var attempts = 0
        while (true) {
            when (val d = decide(inputs(), attempts)) {
                RecoveryDecision.Healthy -> return RecoveryOutcome(recovered = true, attempts = attempts, stoppedBecause = null)
                is RecoveryDecision.Stop -> return RecoveryOutcome(recovered = false, attempts = attempts, stoppedBecause = d.reason)
                is RecoveryDecision.Wait -> { sleep(d.delayMs); attempts++ }
                is RecoveryDecision.Try -> { sleep(d.delayMs); attempt(); attempts++ }
            }
        }
    }
}

/** Whether to use "Automatic recovery after restart" when booting up: It depends on whether it has taken effect (it is turned on by default, and it counts if it has been added). It does not depend on whether the user has flipped the switch. */
object BootRestore {
    const val MIN_SDK = 31

    enum class Gate { Act, Skip, PermissionMissing }

    fun gate(sdkInt: Int, consent: RecoveryConsent, holdsPermission: Boolean): Gate = when {
        sdkInt < MIN_SDK || !consent.isApplied(RecoveryCapability.RestoreAfterReboot) -> Gate.Skip
        !holdsPermission -> Gate.PermissionMissing
        else -> Gate.Act
    }
}

/** The results of the automatic recovery from the last boot are displayed truthfully on the settings page. */
enum class RestoreOutcome {
    Restored,
    /** This item has been turned on, but the authorization to modify system settings is no longer available. */
    PermissionMissing,
    /** Writing the wireless debugging switch is rejected by the system or does not take effect: This phone does not support automatic opening at boot. */
    SettingUnavailable,
    /** The switch is on, but the connection is not available (for example, it is not on a trusted Wi-Fi). */
    NotRecovered,
}
