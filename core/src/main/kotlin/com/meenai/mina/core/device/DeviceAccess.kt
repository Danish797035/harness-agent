package com.meenai.mina.core.device

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The main screen state has nothing to do with the Agent's virtual screen. If the status cannot be read, the port will report an error and it will not be presumed to be unlocked. */
data class DeviceState(val interactive: Boolean, val keyguardShowing: Boolean, val deviceLocked: Boolean,
    val secure: Boolean, val helperAvailable: Boolean, val userUnlocked: Boolean) {
    val ready: Boolean get() = interactive && !keyguardShowing && !deviceLocked && userUnlocked
}

enum class CredentialKind { Pin, Password }

/** Credentials exist only briefly within the native deterministic process; no toString with text is provided. Erase when finished. */
class DeviceCredential(val kind: CredentialKind, val value: CharArray, val revision: String = "") : AutoCloseable {
    override fun close() { value.fill('\u0000') }
    override fun toString() = "DeviceCredential($kind, redacted)"

    companion object {
        fun valid(kind: CredentialKind, value: CharArray): Boolean = when (kind) {
            CredentialKind.Pin -> value.size in 4..16 && value.all { it in '0'..'9' }
            CredentialKind.Password -> value.size in 4..64 && value.all { it in ' '..'~' }
        }
    }
}

/** Platform adaptation: wake up, display system credentials page, confirm input target, input. Never change system lock screen credentials. */
interface DeviceAccessPort {
    suspend fun state(): DeviceState
    suspend fun wake(): Boolean
    suspend fun dismiss(): Boolean
    suspend fun credentialReady(kind: CredentialKind): Boolean
    suspend fun enter(credential: DeviceCredential): Boolean
}

/** Permissions and credentials come from phone settings and cannot be modified on a computer. The placeholder is persisted before input and will no longer be automatically retried after failure/cancellation/crash. */
interface DeviceAccessStore {
    val enabled: Boolean
    val credentialConfigured: Boolean
    val attemptBlocked: Boolean
    fun credential(): DeviceCredential?
    fun beginAttempt(credential: DeviceCredential): Boolean
    fun completeAttempt(credential: DeviceCredential)
}

data class DeviceAccessResult(val status: String, val state: DeviceState?, val enabled: Boolean,
    val credentialConfigured: Boolean, val attemptBlocked: Boolean)

/** The entire process shares one copy. Concurrent calls are serialized, and success is based on system status, not on keystrokes or command return codes. */
class DeviceAccess(private val port: DeviceAccessPort, private val store: DeviceAccessStore,
    private val pollMs: Long = 150, private val waitMs: Long = 5_000) {
    private val mutex = Mutex()

    suspend fun state(): DeviceAccessResult {
        val s = read()
        return result(if (s == null) "device_unavailable" else "state", s)
    }

    suspend fun wake(): DeviceAccessResult = mutex.withLock {
        prepare()?.let { return@withLock it }
        val before = read() ?: return@withLock result("device_unavailable")
        if (before.interactive) return@withLock result("awake", before)
        if (!port.wake()) return@withLock result("wake_failed", read())
        val after = await { it.interactive }
        result(if (after?.interactive == true) "awake" else "wake_failed", after)
    }

    suspend fun unlock(): DeviceAccessResult = mutex.withLock {
        prepare()?.let { return@withLock it }
        var now = read() ?: return@withLock result("device_unavailable")
        if (now.ready) return@withLock result("unlocked", now)
        if (!now.interactive) {
            if (!port.wake()) return@withLock result("wake_failed", read())
            now = await { it.interactive } ?: return@withLock result("device_unavailable")
            if (!now.interactive) return@withLock result("wake_failed", now)
        }
        if (!store.enabled) return@withLock result("disabled", now)
        if (!port.dismiss()) return@withLock result("dismiss_failed", read())
        now = await { it.ready || it.secure && it.keyguardShowing } ?: return@withLock result("device_unavailable")
        if (now.ready) return@withLock result("unlocked", now)
        if (!now.secure) return@withLock result("unlock_failed", now)
        if (store.attemptBlocked) return@withLock result("attempt_blocked", now)
        val credential = store.credential() ?: return@withLock result("credential_required", now)
        credential.use {
            if (!DeviceCredential.valid(it.kind, it.value)) return@withLock result("credential_unavailable", now)
            var targetReady = false
            var waited = 0L
            while (!targetReady && waited < waitMs) {
                val current = read() ?: return@withLock result("device_unavailable")
                if (current.ready) return@withLock result("unlocked", current)
                if (!store.enabled) return@withLock result("disabled", current)
                if (!current.helperAvailable) return@withLock result("helper_unavailable", current)
                targetReady = port.credentialReady(it.kind)
                if (!targetReady) { delay(pollMs); waited += pollMs }
            }
            if (!targetReady) return@withLock result("unsupported_keyguard", read())
            if (!store.enabled) return@withLock result("disabled", read())
            if (!store.beginAttempt(it)) return@withLock result("attempt_blocked", read())
            // From now on, the interrupt will also retain the placeholder and will not be entered
            // repeatedly. It can only be cleared if the system is unlocked.
            if (!store.enabled) return@withLock result("disabled", read())
            if (!port.enter(it)) {
                val after = read()
                if (after?.ready == true) { store.completeAttempt(it); return@withLock result("unlocked", after) }
                return@withLock result("input_failed", after)
            }
            now = await { s -> s.ready } ?: return@withLock result("device_unavailable")
            if (now.ready) { store.completeAttempt(it); result("unlocked", now) }
            else result("unlock_failed", now)
        }
    }

    private suspend fun prepare(): DeviceAccessResult? {
        if (!store.enabled) return result("disabled", read())
        val s = read() ?: return result("device_unavailable")
        if (!s.userUnlocked) return result("first_unlock_required", s)
        if (!s.helperAvailable) return result("helper_unavailable", s)
        return null
    }

    private suspend fun read(): DeviceState? = try { port.state() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { null }
    private suspend fun await(done: (DeviceState) -> Boolean): DeviceState? {
        var waited = 0L
        var s = read()
        while (s != null && !done(s) && waited < waitMs) { delay(pollMs); waited += pollMs; s = read() }
        return s
    }
    private fun result(status: String, s: DeviceState? = null) =
        DeviceAccessResult(status, s, store.enabled, store.credentialConfigured, store.attemptBlocked)
}
