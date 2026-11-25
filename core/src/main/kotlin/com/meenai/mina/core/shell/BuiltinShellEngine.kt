package com.meenai.mina.core.shell

import com.meenai.mina.core.adb.AdbConnectionException
import com.meenai.mina.core.adb.AdbKeyUnreadableException
import com.meenai.mina.core.adb.AdbPairingException
import com.meenai.mina.core.adb.AdbServiceTypes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The platform capabilities required by the engine. Blocking calls are executed on the IO scheduler. */
interface BuiltinShellPlatform {
    fun paired(): Boolean
    fun setPaired(paired: Boolean)
    /** Wireless debugging switch; if it cannot be read, it will be null (do not block the downward movement). */
    fun wirelessDebugging(): Boolean?
    fun wifiConnected(): Boolean
    suspend fun findPairingPort(timeoutMs: Long): Int?
    /** Pair with the pairing code; if failed, throw [AdbPairingException]. */
    fun pair(port: Int, code: String)
    /** Connect the local adbd and start the assistant (not waiting for handover); if it fails, [AdbConnectionException] will be thrown, and if the saved key cannot be read, [AdbKeyUnreadableException] will be thrown. */
    suspend fun launchHelper()
    /** Ask the running assistant to undo the changes and exit; otherwise, do nothing. */
    fun stopHelper()
    /** Delete the pairing key and handover command. */
    fun forget()
    /** If you accept the assistant when it is not paired (it is started from the computer via USB): when it stops, it will tell the user how to start it again, instead of treating it as if it has never been set up. Persistence. */
    fun usbStarted(): Boolean
    fun setUsbStarted(on: Boolean)
}

/** The interval for the watchdog to restart the assistant: after the nth failure, wait for base·2^(n-1), capped at [maxMs]; after [maxAttempts] consecutive failures, wait for someone or a switch change. */
data class Backoff(val baseMs: Long = 2_000, val maxMs: Long = 60_000, val maxAttempts: Int = 5) {
    fun delayAfter(failures: Int): Long? {
        if (failures >= maxAttempts) return null
        return (baseMs shl (failures - 1).coerceIn(0, 20)).coerceAtMost(maxMs)
    }
}

object BuiltinShellFailures {
    fun ofPairing(reason: AdbPairingException.Reason) = when (reason) {
        AdbPairingException.Reason.WRONG_CODE -> BuiltinShellFailure.WrongCode
        // The pairing port cannot be connected: the pairing window has been closed (the port disappears
        // along with the window)
        AdbPairingException.Reason.CONNECT_FAILED -> BuiltinShellFailure.PairingWindowClosed
        AdbPairingException.Reason.TLS_FAILED, AdbPairingException.Reason.PROTOCOL -> BuiltinShellFailure.Unknown
    }

    fun ofConnection(reason: AdbConnectionException.Reason) = when (reason) {
        AdbConnectionException.Reason.UNAUTHORIZED -> BuiltinShellFailure.AuthorizationRevoked
        AdbConnectionException.Reason.CONNECT_FAILED,
        AdbConnectionException.Reason.TLS_FAILED,
        AdbConnectionException.Reason.PROTOCOL -> BuiltinShellFailure.ConnectFailed
    }

    /** Is it useful for the watchdog to try again on its own: the key is revoked, there is no Wi-Fi, and it needs to wait for the user or the switch to change. */
    fun retryable(f: BuiltinShellFailure) = f == BuiltinShellFailure.ConnectFailed ||
        f == BuiltinShellFailure.HelperStartFailed || f == BuiltinShellFailure.Unknown
}

/**
 * Built-in helper status and watchdog. If [helper] has value, the assistant's Binder is in hand
 * (Running); if not: first wait for [graceMs] (the living assistant will hand over after the Mina
 * process comes up), and then start one through adbd. If it fails, press [backoff] to try again. When
 * you want to start the assistant, wireless debugging is turned off. If you have [autoOn], turn it on
 * for the user first (see [WirelessDebuggingAutoOn] for conditions and stopping rules).
 */
class BuiltinShellEngine(
    private val platform: BuiltinShellPlatform,
    private val helper: StateFlow<Any?>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val backoff: Backoff = Backoff(),
    private val graceMs: Long = 3_000,
    private val handoffTimeoutMs: Long = 15_000,
    private val discoveryTimeoutMs: Long = 10 * 60_000L,
    private val pairingPortTimeoutMs: Long = 5_000,
    private val autoOn: WirelessDebuggingAutoOn? = null,
) : BuiltinShellControl {
    private val _status = MutableStateFlow(initialStatus())
    override val status: StateFlow<BuiltinShellStatus> = _status.asStateFlow()
    private val _discovery = MutableStateFlow<PairingDiscovery>(PairingDiscovery.Idle)
    override val pairingDiscovery: StateFlow<PairingDiscovery> = _discovery.asStateFlow()

    /** The exception category and description of the latest pairing or connection failure (for diagnostic information, the original text is not desensitized); cleared after connection. */
    @Volatile var lastError: String? = null
        private set

    private val launchLock = Mutex()
    private var failures = 0
    private var retryJob: Job? = null
    private var discoveryJob: Job? = null
    private var started = false

    override val paired: Boolean get() = platform.paired()

    override val restart: HelperRestart get() = when {
        platform.paired() -> HelperRestart.Wireless
        platform.usbStarted() -> HelperRestart.UsbCommand
        else -> HelperRestart.None
    }

    private fun initialStatus(): BuiltinShellStatus = when {
        helper.value != null -> BuiltinShellStatus.Running
        // The assistant created by the computer may still be alive, waiting for it to take over
        !platform.paired() -> if (platform.usbStarted()) BuiltinShellStatus.Connecting else BuiltinShellStatus.NotPaired
        else -> BuiltinShellStatus.Connecting
    }

    /** The state when there is no pairing and no assistant in hand: if the assistant that has been used on the computer has stopped, tell the truth, otherwise it means it has not been set up yet. */
    private fun unpairedStatus(): BuiltinShellStatus =
        if (platform.usbStarted()) BuiltinShellStatus.Failed(BuiltinShellFailure.UsbHelperStopped) else BuiltinShellStatus.NotPaired

    /** I started to look at the assistant: if it goes offline, it will restart. Only called when the function is turned on. */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        var first = true
        scope.launch {
            helper.collect { h ->
                if (h != null) {
                    synchronized(this@BuiltinShellEngine) { failures = 0; retryJob?.cancel() }
                    autoOn?.reset()
                    // It came without pairing: it was connected to the computer via USB. Write it down,
                    // you won’t know how to prompt until it stops
                    if (!platform.paired()) platform.setUsbStarted(true)
                    _status.value = BuiltinShellStatus.Running
                } else if (!platform.paired()) {
                    // The assistant that is still alive when the app is first started will hand it over
                    // by itself; if it breaks after using it, it may be replaced by a new one: wait for
                    // a while before saying that it has stopped, so as to avoid the interface flashing
                    // "stopped"
                    if (platform.usbStarted()) {
                        _status.value = BuiltinShellStatus.Connecting
                        schedule(graceMs)
                    } else {
                        _status.value = unpairedStatus()
                    }
                } else {
                    if (_status.value == BuiltinShellStatus.Running) _status.value = BuiltinShellStatus.Connecting
                    schedule(if (first) graceMs else backoff.baseMs)
                }
                first = false
            }
        }
    }

    /** Some people want to use the assistant but don't have it: they start it immediately without starting it or queuing to try again. */
    fun need() {
        if (helper.value != null || !platform.paired()) return
        synchronized(this) { if (retryJob?.isActive == true) return }
        if (launchLock.isLocked) return
        schedule(0)
    }

    /**
     * The wireless debugging switch has changed: Just turn it on and try again from the beginning. When
     * it is turned off, it only changes the status, and does not turn it on for the user immediately
     * (the user may turn it off by himself), but wait until he wants to use the assistant. Mina does not
     * move when she is writing this switch: the system may turn it back immediately, depending on the
     * writing side.
     */
    fun wirelessDebuggingChanged() {
        if (helper.value != null || !platform.paired()) return
        if (autoOn?.writing == true) return
        if (platform.wirelessDebugging() == false) {
            _status.value = offStatus()
            return
        }
        autoOn?.reset()
        synchronized(this) { failures = 0 }
        schedule(0)
    }

    /**
     * Connected to Wi-Fi: If it was stuck on "No Wi-Fi" before, try again immediately (you don't need to
     * be able to access the Internet); when wireless debugging can be turned on for the user, if it is
     * stuck on "no Wi-Fi" (including the previous network is not allowed), try again immediately. If you
     * change networks, rejections on the previous network no longer count.
     */
    fun wifiAvailable() {
        autoOn?.reset()
        val s = _status.value
        val off = s == BuiltinShellStatus.WirelessDebuggingOff || s == BuiltinShellStatus.Failed(BuiltinShellFailure.WifiNotAllowed)
        if (s != BuiltinShellStatus.Failed(BuiltinShellFailure.NoWifi) && !(autoOn != null && off)) return
        synchronized(this) { failures = 0 }
        schedule(0)
    }

    /** The status displayed when wireless debugging is turned off: If this Wi-Fi has just been dialed back by the system, tell the truth, otherwise it is turned off. */
    private fun offStatus(): BuiltinShellStatus =
        if (autoOn?.blocked?.value == AutoOnBlock.NotAllowedHere) BuiltinShellStatus.Failed(BuiltinShellFailure.WifiNotAllowed)
        else BuiltinShellStatus.WirelessDebuggingOff

    /** Wireless debugging is turned off: if it can be turned on for the user, it will be turned on. If it is turned back on, it will return true and then connect; otherwise, it will return false after writing the status. */
    private suspend fun turnOnWirelessDebugging(): Boolean {
        val auto = autoOn ?: return false.also { _status.value = offStatus() }
        when (val g = auto.gate(platform.wifiConnected())) {
            AutoOnGate.Act -> {
                _status.value = BuiltinShellStatus.Connecting
                if (withContext(io) { auto.turnOn() }) return true
            }
            // It’s too close to the last time I wrote: I’ll do it again then
            is AutoOnGate.Wait -> synchronized(this) {
                retryJob = scope.launch {
                    delay(g.ms)
                    launchOnce()
                }
            }
            is AutoOnGate.Skip -> Unit
        }
        _status.value = offStatus()
        return false
    }

    @Synchronized
    private fun schedule(delayMs: Long) {
        retryJob?.cancel()
        retryJob = scope.launch {
            delay(delayMs)
            launchOnce()
        }
    }

    private suspend fun launchOnce(): Unit = launchLock.withLock {
        if (helper.value != null) return@withLock
        if (!platform.paired()) {
            _status.value = unpairedStatus()
            return@withLock
        }
        if (platform.wirelessDebugging() == false && !turnOnWirelessDebugging()) return@withLock
        _status.value = BuiltinShellStatus.Connecting
        val failure = attempt()
        if (failure == null) {
            lastError = null
            _status.value = BuiltinShellStatus.Running
            return@withLock
        }
        _status.value = BuiltinShellStatus.Failed(failure)
        val next = synchronized(this) { ++failures }
        if (BuiltinShellFailures.retryable(failure)) backoff.delayAfter(next)?.let { d ->
            synchronized(this) {
                retryJob = scope.launch {
                    delay(d)
                    launchOnce()
                }
            }
        }
    }

    private suspend fun attempt(): BuiltinShellFailure? = try {
        withContext(io) { platform.launchHelper() }
        if (withTimeoutOrNull(handoffTimeoutMs) { helper.first { it != null } } != null) null
        else BuiltinShellFailure.HelperStartFailed.also { lastError = "helper handoff timed out" }
    } catch (e: CancellationException) {
        throw e
    } catch (e: AdbConnectionException) {
        lastError = describe(e)
        val f = BuiltinShellFailures.ofConnection(e.reason)
        if (f == BuiltinShellFailure.ConnectFailed && !platform.wifiConnected()) BuiltinShellFailure.NoWifi else f
    } catch (e: AdbKeyUnreadableException) {
        lastError = describe(e)
        BuiltinShellFailure.KeyUnreadable
    } catch (e: Exception) {
        lastError = describe(e)
        BuiltinShellFailure.Unknown
    }

    private fun describe(e: Exception): String =
        e.javaClass.simpleName + ((e as? AdbConnectionException)?.reason?.let { "($it)" } ?: (e as? AdbPairingException)?.reason?.let { "($it)" } ?: "") +
            (e.message?.let { ": " + it.take(200) } ?: "")

    override fun startDiscovery() {
        synchronized(this) {
            // If you are still looking, start again: The user is back to the matter of matching, and the
            // time limit for searching is calculated from now on.
            discoveryJob?.cancel()
            _discovery.value = PairingDiscovery.Searching
            discoveryJob = scope.launch {
                val port = withContext(io) { platform.findPairingPort(discoveryTimeoutMs) }
                _discovery.value = port?.let { PairingDiscovery.Found(it) } ?: PairingDiscovery.Idle
            }
        }
    }

    override fun stopDiscovery() {
        synchronized(this) {
            discoveryJob?.cancel()
            discoveryJob = null
        }
        _discovery.value = PairingDiscovery.Idle
    }

    override suspend fun startPairing(code: String, port: Int?) {
        _status.value = BuiltinShellStatus.Connecting
        val target = port ?: (_discovery.value as? PairingDiscovery.Found)?.port
            ?: withContext(io) { platform.findPairingPort(pairingPortTimeoutMs) }
        if (target == null) {
            lastError = "pairing port not found"
            _status.value = BuiltinShellStatus.Failed(BuiltinShellFailure.PairingWindowClosed)
            return
        }
        val failure = try {
            withContext(io) { platform.pair(target, code) }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: AdbPairingException) {
            lastError = describe(e)
            BuiltinShellFailures.ofPairing(e.reason)
        } catch (e: Exception) {
            lastError = describe(e)
            BuiltinShellFailure.Unknown
        }
        if (failure != null) {
            _status.value = BuiltinShellStatus.Failed(failure)
            return
        }
        platform.setPaired(true)
        stopDiscovery()
        retry()
    }

    override suspend fun retry() {
        synchronized(this) {
            failures = 0
            retryJob?.cancel()
        }
        // The user clicks Retry: The previous rejection when turning on wireless debugging for the user
        // no longer counts (the system will ask again on a network that is not allowed)
        autoOn?.reset()
        if (helper.value != null) {
            _status.value = BuiltinShellStatus.Running
            return
        }
        launchOnce()
    }

    override suspend fun unpair() {
        synchronized(this) { retryJob?.cancel() }
        launchLock.withLock {
            withContext(io) {
                // Forget about "starting the computer" first: when the assistant is stopped, the status
                // is that there is no setting, and there is no prompt to run the command again.
                platform.setUsbStarted(false)
                runCatching { platform.stopHelper() }
                platform.forget()
            }
            platform.setPaired(false)
            _status.value = BuiltinShellStatus.NotPaired
        }
    }
}
