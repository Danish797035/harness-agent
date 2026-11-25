package com.meenai.mina.core.shell

import kotlinx.coroutines.flow.StateFlow

/**
 * The current status of the built-in assistant (a privileged process launched as a shell through the
 * local "wireless debugging"). The interface, setting page and task entry only look at it; the
 * implementation of pairing, connection and watchdog each write the results as one of these types.
 */
sealed interface BuiltinShellStatus {
    /** Not paired yet (or unpaired): You need to go through the guidance. */
    data object NotPaired : BuiltinShellStatus

    /** Paired, but wireless debugging is turned off: it will connect automatically after turning it on. */
    data object WirelessDebuggingOff : BuiltinShellStatus

    /** Pairing, connecting, or launching assistant. */
    data object Connecting : BuiltinShellStatus

    data object Running : BuiltinShellStatus

    /** An error occurred: [reason] indicates which category it is, and [fix] indicates which path to take for one-click repair. */
    data class Failed(val reason: BuiltinShellFailure, val fix: BuiltinShellFix = reason.defaultFix) : BuiltinShellStatus
}

/** Failure category. The copywriting is at the interface level; here only different situations of "what the user should do" are distinguished. */
enum class BuiltinShellFailure(val defaultFix: BuiltinShellFix, val duringPairing: Boolean) {
    /** The pairing code is incorrect or expired. */
    WrongCode(BuiltinShellFix.RePair, duringPairing = true),
    /** The pairing window was closed before filling in the code (the pairing port could not be found). */
    PairingWindowClosed(BuiltinShellFix.RePair, duringPairing = true),
    /** Not connected to Wi-Fi: Wireless debugging cannot be turned on or cannot be connected. */
    NoWifi(BuiltinShellFix.OpenWifi, duringPairing = false),
    /** Mina turned on wireless debugging for the user, but the system immediately turned it off again: this Wi-Fi has not yet been allowed for wireless debugging, and the user needs to turn it on and allow it. */
    WifiNotAllowed(BuiltinShellFix.OpenWirelessDebugging, duringPairing = false),
    /** Unable to connect to the wireless debugging of this machine. */
    ConnectFailed(BuiltinShellFix.Retry, duringPairing = false),
    /** The paired authorization is not recognized (it has been revoked by the system if it has not been used for too long, or the user deleted it in the system). */
    AuthorizationRevoked(BuiltinShellFix.RePair, duringPairing = false),
    /** The key used for pairing is stored but cannot be read: the device only recognizes that one and cannot change it quietly. It needs to be paired again (a new key is required only when paired). */
    KeyUnreadable(BuiltinShellFix.RePair, duringPairing = false),
    /** It was connected, but the assistant failed to start or handover. */
    HelperStartFailed(BuiltinShellFix.Retry, duringPairing = false),
    /**
     * The assistant started by the USB startup command has stopped (mostly because the phone has been
     * restarted), and it has not been paired for wireless debugging. Mina cannot get up by itself: run
     * the startup command again. Do not refer to wireless debugging: Some mobile phones do not have
     * wireless debugging available at all.
     */
    UsbHelperStopped(BuiltinShellFix.RunUsbCommand, duringPairing = false),
    Unknown(BuiltinShellFix.Retry, duringPairing = false),
}

enum class BuiltinShellFix {
    /** Connect again with the paired key. */
    Retry,
    /** Go through the pairing step again. */
    RePair,
    /** Go to your system's Wi-Fi settings. */
    OpenWifi,
    /** Go to developer options and turn on wireless debugging yourself. */
    OpenWirelessDebugging,
    /** Run the USB boot command again (the interface gives the command and copy button). */
    RunUsbCommand,
}

/**
 * What the assistant relies on to get up again after it stops depends on how it got up last time. It all
 * depends on where to redirect users when stopped, what to say about the success page and settings page.
 */
enum class HelperRestart {
    /** Haven't set it up yet. */
    None,
    /** Paired wireless debugging: Mina can restart it by itself; just turn on wireless debugging after restarting the phone (you don’t even need this step if it can be turned on for the user). */
    Wireless,
    /**
     * Only using the USB startup command and no pairing for wireless debugging: Mina cannot get up by
     * itself, and if it stops (for example, after the phone is restarted), the command must be run
     * again. Some mobile phones do not have wireless debugging available and can only take this route,
     * so they are not prompted to turn on wireless debugging at this time.
     */
    UsbCommand,
}

/** Look for results for the pairing port (native mDNS) published in the "Pair a device with a pairing code" window. */
sealed interface PairingDiscovery {
    data object Idle : PairingDiscovery
    data object Searching : PairingDiscovery
    data class Found(val port: Int) : PairingDiscovery
}

/**
 * Built-in assistant interface to the interface. The implementation is responsible for pairing (mDNS
 * port search, 6-digit code), connecting to the local adbd, pulling up and watching the assistant; all
 * results are reflected through [status], and the action itself does not throw an exception.
 */
interface BuiltinShellControl {
    val status: StateFlow<BuiltinShellStatus>
    val pairingDiscovery: StateFlow<PairingDiscovery>

    /** Paired with wireless debugging (Mina can start the assistant and run commands through it by itself). The assistant may also be connected to the computer via USB, so running it does not mean it has been paired. */
    val paired: Boolean get() = status.value != BuiltinShellStatus.NotPaired

    /** What does the assistant rely on to get up again after it stops (see [HelperRestart]). */
    val restart: HelperRestart get() = when {
        status.value == BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped) -> HelperRestart.UsbCommand
        paired -> HelperRestart.Wireless
        else -> HelperRestart.None
    }

    /** Start looking for the pairing port (the user is opening the pairing window). There is no harm in calling it repeatedly: if you are still looking, just restart the timer from this point on. */
    fun startDiscovery()
    fun stopDiscovery()

    /** Use the 6-digit pairing code to pair, then connect and start the assistant after success. When [port] is empty, the found paired port is used. */
    suspend fun startPairing(code: String, port: Int? = null)

    /** Reconnect with the paired key and start the assistant. */
    suspend fun retry()

    /** Delete the pairing key, stop the assistant, and revoke the assistant's authorization to Mina; the subsequent status is [BuiltinShellStatus.NotPaired]. */
    suspend fun unpair()
}

/** Pairing code: 6 digits. Users may include spaces, dashes or full-width numbers, which will be normalized; if the number is not 6 digits, null will be returned. */
object PairingCode {
    const val LENGTH = 6

    /** Various horizontal lines that may be typed by the input method: half-width, full-width, hyphen, short horizontal line, long horizontal line. */
    private const val DASHES = "-－‐‑–—"

    fun normalize(input: String): String? {
        val digits = input.filterNot { it.isWhitespace() || it in DASHES }
            .map { c -> if (c in '０'..'９') '0' + (c - '０') else c }
            .joinToString("")
        return digits.takeIf { it.length == LENGTH && it.all { c -> c in '0'..'9' } }
    }

    /** Manually entered port: 1–65535. */
    fun port(input: String): Int? = input.trim().toIntOrNull()?.takeIf { it in 1..65535 }
}
