package com.meenai.mina.core.shell

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The reason why wireless debugging is stopped for the user; after stopping, you have to wait until the Wi-Fi is changed, the user has opened it, clicked Retry, or the assistant is up before writing again. */
enum class AutoOnBlock {
    /** After writing it and opening it, the system immediately turned it back to off: This Wi-Fi has not yet been allowed for wireless debugging (the system will also ask the user whether to allow it). */
    NotAllowedHere,
    /** The system does not allow writing (writing throws an exception or returns failure). */
    Refused,
    /** I opened it for the user [WirelessDebuggingAutoOn.MAX_WRITES] times in a row, but the assistant still didn't get up. */
    GaveUp,
}

/** Do you want to turn on wireless debugging for the user at this time? */
sealed interface AutoOnGate {
    data object Act : AutoOnGate
    /** It’s too close to the last time I wrote: Wait for [ms] to come again. */
    data class Wait(val ms: Long) : AutoOnGate
    data class Skip(val reason: AutoOnSkip) : AutoOnGate
}

enum class AutoOnSkip {
    /** The user turned off "Automatically turn on wireless debugging" in Mina. */
    Declined,
    /** Mina is not authorized to modify system settings. */
    NoPermission,
    /** Not connected to Wi-Fi: The system will not turn on wireless debugging when there is no Wi-Fi (it will be dialed back if written). */
    NoWifi,
    /** Has stopped, see [AutoOnBlock]. */
    Blocked,
}

/**
 * When the built-in assistant wants to be started but wireless debugging is turned off, turn it on for
 * the user (only on but not off). Once the assistant is up, wireless debugging is no longer needed, but
 * restarting it (after restarting the phone, after Mina is updated, and the assistant is gone) requires
 * wireless debugging; and the original version of Android will turn it off when Wi-Fi is disconnected
 * and after restarting.
 *
 * Only write this when the user has not turned off "Automatically turn on wireless debugging", Mina has
 * the authorization to modify the system settings, and is connected to Wi-Fi. Of course, the assistant
 * is not there when the assistant is started. It has already been paired and connected to run commands,
 * and wireless debugging is required first, so only the App can directly write this method; if it cannot
 * be done, it will not move, and the interface will still be opened manually.
 *
 * Dependent Android original behavior (AdbDebuggingManager's processing of `adb_wifi_enabled`):
 * - - After the setting is written to 1, the system checks the current Wi-Fi: if it is not connected to
 * Wi-Fi, it immediately writes back to 0;
 * This network (by BSSID) is not in the "Always Allow" list, and it is immediately written back to 0. At
 * the same time, a pop-up box asks the user "Are wireless debugging allowed on this network?" (You can
 * check "Always Allow"). If the user clicks Allow, the system will write back 1 and turn it on; if it
 * refuses, it will remain closed.
 * - - Network is in the list: keep 1, then start the wireless debugging service (the port is found as
 * usual in the connection step).
 * So after writing, read [settleMs]: During this period of time, if you read it off, just treat it as if
 * the Wi-Fi has not been allowed, stop ([AutoOnBlock.NotAllowedHere]), and no longer write repeatedly
 * (the system will pop up a box every time you write) until the Wi-Fi is changed, the user opens it
 * manually, clicks Retry, or the assistant comes up ([reset]). Some systems have other processing for
 * this item (writing is rejected, writing does not take effect), and they stop in the same way without
 * delving into each company's implementation. Press [gapAfter] to create a gap between writing; I wrote
 * [MAX_WRITES] times in succession without the assistant getting up and stopping.
 */
class WirelessDebuggingAutoOn(
    /** Does the user want this option (not turning off "Automatically turn on wireless debugging" in Mina). */
    private val wants: () -> Boolean,
    /** Mina holds the authorization to modify system settings. */
    private val canWrite: () -> Boolean,
    /** The system's wireless debugging switch; null if not read. */
    private val read: () -> Boolean?,
    /** Write the wireless debugging switch to on; the system will not allow it to return false or throw an exception when writing. */
    private val write: () -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val settleMs: Long = SETTLE_MS,
    private val pollMs: Long = POLL_MS,
) {
    private val _blocked = MutableStateFlow<AutoOnBlock?>(null)
    /** Reason for stopping; null if not stopped. Say something about the settings page and status. */
    val blocked: StateFlow<AutoOnBlock?> = _blocked.asStateFlow()

    /** Writing, waiting for system processing: Switch changes during this period are judged here, others should not follow suit. */
    @Volatile var writing = false
        private set

    private var writes = 0
    private var lastWriteAt = 0L

    @Synchronized
    fun gate(wifi: Boolean): AutoOnGate {
        if (_blocked.value == null && writes >= MAX_WRITES) _blocked.value = AutoOnBlock.GaveUp
        return decide(wants(), canWrite(), wifi, _blocked.value, writes, now() - lastWriteAt)
    }

    /** Write open and wait for the system to finish processing. Returns true if the switch remains open; returns false if it is rejected or turned back, and notes the reason for stopping. */
    suspend fun turnOn(): Boolean {
        synchronized(this) {
            writing = true
            writes++
            lastWriteAt = now()
        }
        try {
            val accepted = runCatching(write).getOrDefault(false)
            if (!accepted || read() == false) return false.also { block(if (accepted) AutoOnBlock.NotAllowedHere else AutoOnBlock.Refused) }
            var waited = 0L
            while (waited < settleMs) {
                delay(pollMs)
                waited += pollMs
                if (read() == false) return false.also { block(AutoOnBlock.NotAllowedHere) }
            }
            return true
        } finally {
            writing = false
        }
    }

    /** After changing the Wi-Fi, the user turned on wireless debugging or clicked Retry, and the assistant came up: Calculate from scratch. */
    @Synchronized
    fun reset() {
        writes = 0
        lastWriteAt = 0L
        _blocked.value = null
    }

    private fun block(reason: AutoOnBlock) {
        _blocked.value = reason
    }

    companion object {
        /** After writing, check how long it takes to see if it is dialed back by the system; the original version is processed immediately after receiving the setting change, and it will be dialed back within a few hundred milliseconds. */
        const val SETTLE_MS = 3_000L
        const val POLL_MS = 250L
        const val MAX_WRITES = 3
        const val BASE_GAP_MS = 10_000L
        const val MAX_GAP_MS = 5 * 60_000L

        /** After writing [writes] times, how long will it be before the next time? It doesn’t vary for the first time, then 10 seconds, 20 seconds, etc., capped at 5 minutes. */
        fun gapAfter(writes: Int): Long =
            if (writes <= 0) 0L else (BASE_GAP_MS shl (writes - 1).coerceAtMost(20)).coerceAtMost(MAX_GAP_MS)

        fun decide(wants: Boolean, canWrite: Boolean, wifi: Boolean, blocked: AutoOnBlock?, writes: Int, sinceLastMs: Long): AutoOnGate {
            if (!wants) return AutoOnGate.Skip(AutoOnSkip.Declined)
            if (!canWrite) return AutoOnGate.Skip(AutoOnSkip.NoPermission)
            if (blocked != null || writes >= MAX_WRITES) return AutoOnGate.Skip(AutoOnSkip.Blocked)
            if (!wifi) return AutoOnGate.Skip(AutoOnSkip.NoWifi)
            val gap = gapAfter(writes)
            return if (sinceLastMs < gap) AutoOnGate.Wait(gap - sinceLastMs) else AutoOnGate.Act
        }
    }
}
