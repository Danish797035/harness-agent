package com.meenai.mina.core.shell

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** With a paired wireless debugging connection, run a command as a shell and return its output. An exception is thrown when the connection is broken. */
fun interface ShellCommandRunner {
    suspend fun exec(command: String): String
}

/**
 * Several capabilities of mobile phone operation: turned on by default (Mina will make up for it when
 * the connection is available), can be turned off in the settings, and taken back together when
 * unpairing. [RestoreAfterReboot] and [AutoWirelessDebugging] use the same authorization to modify
 * system settings (both are only used to turn on wireless debugging), and they are revoked when both are
 * turned off.
 */
enum class RecoveryCapability {
    Accessibility, BatteryWhitelist, RestoreAfterReboot,
    /** To re-enable the built-in assistant, turn wireless debugging on for the user when it is off (see [WirelessDebuggingAutoOn]). */
    AutoWirelessDebugging;

    /** It depends on the authorization to modify the system settings. */
    val writesSecureSettings: Boolean get() = this == RestoreAfterReboot || this == AutoWirelessDebugging
}

/**
 * Two things about each ability are recorded separately: [applied] has been opened by Mina in the system
 * for the user and is valid at this moment; [declined] has been turned off by the user and will no
 * longer be opened automatically after that. If there is nothing on both sides, it means "untouched" and
 * is turned on by default.
 */
data class RecoveryConsent(
    val applied: Set<RecoveryCapability> = emptySet(),
    val declined: Set<RecoveryCapability> = emptySet(),
) {
    /** Does the user want this item? You just want it without turning it off personally (it is what the switch displays). */
    fun wants(c: RecoveryCapability): Boolean = c !in declined

    fun isApplied(c: RecoveryCapability): Boolean = c in applied

    /** A few things that users want but haven't taken effect yet: add them as soon as the connection becomes available. */
    val pending: List<RecoveryCapability> get() = RecoveryCapability.entries.filter { wants(it) && !isApplied(it) }

    fun chose(c: RecoveryCapability, on: Boolean): RecoveryConsent = copy(declined = if (on) declined - c else declined + c)

    fun withApplied(c: RecoveryCapability, on: Boolean): RecoveryConsent = copy(applied = if (on) applied + c else applied - c)
}

interface RecoveryConsentStore {
    val consent: StateFlow<RecoveryConsent>
    fun save(consent: RecoveryConsent)
}

sealed interface PermissionResult {
    data object Done : PermissionResult
    /** The mobile phone operation has not been set up yet and there is no working connection. */
    data object NoRunner : PermissionResult
    data class Failed(val detail: String) : PermissionResult
}

/** The shell commands corresponding to these capabilities (the original writing method of pm / cmd deviceidle / settings). */
object PermissionCommands {
    const val WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"
    const val READ_ACCESSIBILITY = "settings get secure enabled_accessibility_services"
    const val EXIT_MARK = "__harness_exit="

    fun accessibilityService(pkg: String): String {
        // The package name (applicationId) may have a suffix (version installed concurrently), but the
        // service class is still in the App namespace: use an abbreviation when the package name is a
        // prefix of the class name, otherwise write the full class name
        val service = "com.meenai.mina.a11y.HarnessAccessibilityService"
        return "$pkg/${if (service.startsWith("$pkg.")) service.removePrefix(pkg) else service}"
    }
    fun grantWriteSecureSettings(pkg: String) = "pm grant $pkg $WRITE_SECURE_SETTINGS"
    fun revokeWriteSecureSettings(pkg: String) = "pm revoke $pkg $WRITE_SECURE_SETTINGS"
    fun whitelistAdd(pkg: String) = "cmd deviceidle whitelist +$pkg"
    fun whitelistRemove(pkg: String) = "cmd deviceidle whitelist -$pkg"

    /** Write back the entire list of accessibility services. If it is empty, delete this item and turn off the main switch to avoid leaving an empty string. */
    fun writeAccessibility(list: String): List<String> =
        if (list.isEmpty()) listOf("settings delete secure enabled_accessibility_services", "settings put secure accessibility_enabled 0")
        else listOf("settings put secure enabled_accessibility_services ${quote(list)}", "settings put secure accessibility_enabled 1")

    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** The exec service cannot get the exit code: add a line of markers at the end of the output. When pm or cmd fails, they will probably just write a sentence in the output, not just see whether there is output. */
    fun checked(command: String): String = "$command 2>&1; echo $EXIT_MARK\$?"

    fun exitCode(output: String): Int? =
        output.lineSequence().map { it.trim() }.lastOrNull { it.startsWith(EXIT_MARK) }?.removePrefix(EXIT_MARK)?.toIntOrNull()
}

/** `enabled_accessibility_services` is a colon-separated component name; component names can be written in two ways: "package name/.class name" and "package name/complete class name". */
object AccessibilityList {
    fun entries(raw: String?): List<String> {
        val v = raw?.trim().orEmpty()
        if (v == "null") return emptyList() // `settings get` outputs null for unwritten items
        return v.split(':').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun contains(raw: String?, component: String): Boolean = entries(raw).any { same(it, component) }

    fun with(raw: String?, component: String): String {
        val list = entries(raw)
        return (if (list.any { same(it, component) }) list else list + component).joinToString(":")
    }

    fun without(raw: String?, component: String): String = entries(raw).filterNot { same(it, component) }.joinToString(":")

    private fun same(a: String, b: String) = expand(a) == expand(b)

    private fun expand(c: String): String {
        if ('/' !in c) return c
        val pkg = c.substringBefore('/')
        val cls = c.substringAfter('/')
        return if (cls.startsWith('.')) "$pkg/$pkg$cls" else c
    }
}

/**
 * These capabilities can be turned on or off at the user's option. You have to rely on the paired
 * connection (shell identity) to change the system settings. If [runner] is null, it means that there is
 * no usable connection yet. The user's selection ([RecoveryConsent.declined]) and whether it is valid in
 * the system ([RecoveryConsent.applied]) are recorded separately: the open selection is also recorded
 * when there is no connection, and waits for [applyPending] to make up for it; when the command fails,
 * both remain the same and the interface is displayed faithfully. Several entries are executed in
 * series, and automatic filling and user-toggle switches will not overlap.
 */
class RecoveryPermissions(
    private val runner: () -> ShellCommandRunner?,
    private val store: RecoveryConsentStore,
    private val packageName: String,
) {
    private val lock = Mutex()
    private val _autoFailed = MutableStateFlow<Set<RecoveryCapability>>(emptySet())

    /** Several items that were not successful when automatically added (do not disturb the user, just say one sentence under the item on the settings page); remove them after they are successful. */
    val autoFailed: StateFlow<Set<RecoveryCapability>> = _autoFailed.asStateFlow()

    /** The user flips the switch: turn it on and execute it directly without confirming; turn it off and take it back immediately, and write down "turn it off by the user". */
    suspend fun set(capability: RecoveryCapability, on: Boolean): PermissionResult = lock.withLock {
        val now = store.consent.value
        val shell = runner()
        if (shell == null) {
            return@withLock when {
                // Make a note of it first, and it will be automatically added when the connection is
                // available; the interface prompts you to complete the settings first.
                on -> PermissionResult.NoRunner.also { store.save(now.chose(capability, true)) }
                // It has never taken effect and there is no need to withdraw it.
                !now.isApplied(capability) -> PermissionResult.Done.also { store.save(now.chose(capability, false)) }
                else -> PermissionResult.NoRunner
            }
        }
        val result = run(shell, listOf(capability), on)
        if (result == PermissionResult.Done) {
            store.save(store.consent.value.chose(capability, on).withApplied(capability, on))
            _autoFailed.update { it - capability }
        }
        result
    }

    /**
     * Adjust when the connection is just available: open several items that the user has not turned off
     * and have not yet taken effect. Don't play anything; failure will be recorded as [autoFailed], and
     * try again next time the connection is available. Does nothing without connection.
     */
    suspend fun applyPending(): PermissionResult = lock.withLock {
        val shell = runner() ?: return@withLock PermissionResult.NoRunner
        var failure: PermissionResult? = null
        for (c in store.consent.value.pending) {
            val r = run(shell, listOf(c), on = true)
            if (r == PermissionResult.Done) store.save(store.consent.value.withApplied(c, true))
            else if (failure == null) failure = r
            _autoFailed.update { if (r == PermissionResult.Done) it - c else it + c }
        }
        failure ?: PermissionResult.Done
    }

    /** Keep looking at the status of the built-in assistant: [applyPending] every time it becomes running (the connection has just become available). */
    suspend fun applyWhenRunning(status: Flow<BuiltinShellStatus>) {
        status.collect { if (it == BuiltinShellStatus.Running) applyPending() }
    }

    /**
     * When unpairing, use: Take back every item that Mina has opened here, plus the authorization to
     * modify the system settings (receive it once regardless of the record, and it is harmless to
     * receive non-existing authorizations). The accessibility or whitelist created by the user in the
     * system is not given here and will not change. "Effective" is cleared regardless of success or
     * failure: the pairing is gone, and these abilities are no longer effective; the user's choice
     * remains: several items that were not turned off during pairing will still be turned on
     * automatically, and those turned off by hand will still be turned off.
     */
    suspend fun revokeAll(): PermissionResult = lock.withLock {
        val now = store.consent.value
        // The two authorizations for modifying system settings are shared. It is enough to collect them
        // once: only the one for RestoreAfterReboot is left.
        val granted = now.applied.filterNot { it.writesSecureSettings }.toSet() + RecoveryCapability.RestoreAfterReboot
        store.save(now.copy(applied = emptySet()))
        _autoFailed.value = emptySet()
        val shell = runner() ?: return@withLock PermissionResult.NoRunner
        run(shell, granted.toList(), on = false)
    }

    /**
     * Let Mina's accessibility service run again (the service is not used at runtime, see
     * [AccessibilityRepair]), and other services will remain unchanged: if Mina is not in the list, add
     * it directly; if it is but not bound, remove it first, wait for [gapMs] to be processed by the
     * system, and then re-read the list and add it back. It will be added back after being removed, and
     * the same will be true if it is canceled midway, so as not to leave the service in a closed state;
     * when the addition fails, it will be written back to the original list, and a failure will still be
     * reported. Unchanged [RecoveryConsent]: This is not an ability to open or retract.
     */
    suspend fun restartAccessibility(gapMs: Long): PermissionResult = lock.withLock {
        val shell = runner() ?: return@withLock PermissionResult.NoRunner
        val me = PermissionCommands.accessibilityService(packageName)
        try {
            val current = shell.exec(PermissionCommands.READ_ACCESSIBILITY)
            // Not in the list (the system will remove it when it is forcibly stopped): add it directly;
            // it is in the list but not bound: remove it and add it back, and the system will rebind it.
            if (!AccessibilityList.contains(current, me)) return@withLock runAll(shell, PermissionCommands.writeAccessibility(AccessibilityList.with(current, me)))
            val removed = runAll(shell, PermissionCommands.writeAccessibility(AccessibilityList.without(current, me)))
            if (removed != PermissionResult.Done) return@withLock removed
            withContext(NonCancellable) {
                delay(gapMs)
                val now = shell.exec(PermissionCommands.READ_ACCESSIBILITY)
                val back = runAll(shell, PermissionCommands.writeAccessibility(AccessibilityList.with(now, me)))
                // When the add back fails, write back the original list (together with the main switch):
                // I would rather go back to "there is but not bound" than let Mina disappear from the
                // list; writing back is just a cover-up, and this time it will still report a failed add
                // back (read it as it is with line breaks; it already has Mina, with just organize it
                // into a clean list, the content remains unchanged)
                if (back != PermissionResult.Done) runCatching { runAll(shell, PermissionCommands.writeAccessibility(AccessibilityList.with(current, me))) }
                back
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PermissionResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Each item is executed independently, and the failure of one item does not block the subsequent ones; the first failure is returned. */
    private suspend fun run(shell: ShellCommandRunner, capabilities: List<RecoveryCapability>, on: Boolean): PermissionResult {
        var failure: PermissionResult? = null
        for (c in capabilities) {
            val r = try {
                runAll(shell, commands(shell, c, on))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PermissionResult.Failed(e.message ?: e.javaClass.simpleName)
            }
            if (r != PermissionResult.Done && failure == null) failure = r
        }
        return failure ?: PermissionResult.Done
    }

    private suspend fun commands(shell: ShellCommandRunner, c: RecoveryCapability, on: Boolean): List<String> = when (c) {
        RecoveryCapability.RestoreAfterReboot, RecoveryCapability.AutoWirelessDebugging -> when {
            on -> listOf(PermissionCommands.grantWriteSecureSettings(packageName))
            // Another thing that needs to be used is this authorization: just record it and turn it off,
            // not take it back.
            RecoveryCapability.entries.any { it != c && it.writesSecureSettings && store.consent.value.isApplied(it) } -> emptyList()
            else -> listOf(PermissionCommands.revokeWriteSecureSettings(packageName))
        }
        RecoveryCapability.BatteryWhitelist ->
            listOf(if (on) PermissionCommands.whitelistAdd(packageName) else PermissionCommands.whitelistRemove(packageName))
        RecoveryCapability.Accessibility -> {
            val current = shell.exec(PermissionCommands.READ_ACCESSIBILITY)
            val me = PermissionCommands.accessibilityService(packageName)
            PermissionCommands.writeAccessibility(if (on) AccessibilityList.with(current, me) else AccessibilityList.without(current, me))
        }
    }

    private suspend fun runAll(shell: ShellCommandRunner, commands: List<String>): PermissionResult {
        for (cmd in commands) {
            val out = shell.exec(PermissionCommands.checked(cmd))
            if (PermissionCommands.exitCode(out) != 0) return PermissionResult.Failed("$cmd: ${out.trim()}")
        }
        return PermissionResult.Done
    }
}
