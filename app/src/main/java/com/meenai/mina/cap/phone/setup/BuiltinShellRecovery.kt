package com.meenai.mina.cap.phone.setup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import com.meenai.mina.cap.phone.a11y.ScreenAccessibility
import com.meenai.mina.core.shell.A11yLossRecord
import com.meenai.mina.core.shell.A11yObservation
import com.meenai.mina.core.shell.A11yStartCheck
import com.meenai.mina.core.shell.AccessibilityList
import com.meenai.mina.core.shell.AccessibilityRepair
import com.meenai.mina.core.shell.BootRestore
import com.meenai.mina.core.shell.BuiltinShellControl
import com.meenai.mina.core.shell.BuiltinShellStatus
import com.meenai.mina.core.shell.PermissionCommands
import com.meenai.mina.core.shell.PermissionResult
import com.meenai.mina.core.shell.RecoveryCapability
import com.meenai.mina.core.shell.RecoveryInputs
import com.meenai.mina.core.shell.RecoveryPermissions
import com.meenai.mina.core.shell.RecoveryPolicy
import com.meenai.mina.core.shell.RestoreOutcome
import com.meenai.mina.core.shell.ShellCommandRunner
import com.meenai.mina.settings.RecoverySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Built-in assistant's automatic recovery and several capabilities. To run the shell command, use
 * [runner](connected via assistant or paired; not given if neither is available); the capabilities and boot results are recorded in [settings]; [control]
 * is the built-in assistant itself. A copy is created by [com.meenai.mina.cap.phone.PhoneServices], and
 * [start] starts watching the connection when the App starts.
 */
class BuiltinShellRecovery(
    context: Context,
    private val runner: ShellCommandRunner,
    private val settings: RecoverySettings,
    private val control: BuiltinShellControl,
) {
    private val app = context.applicationContext

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    val permissions: RecoveryPermissions by lazy {
        // The assistant is running (regardless of how it was started) or paired, and the command is
        // available.
        RecoveryPermissions({ runner.takeIf { control.status.value == BuiltinShellStatus.Running || control.paired } }, settings, app.packageName)
    }

    /**
     * The accessibility service does not automatically turn it on at runtime; the result
     * ([AccessibilityRepair.last]) is to say something to the settings page. If the App itself has the
     * authorization to modify the system settings (given by "Automatic recovery after restart"), it can
     * be written directly without waiting for the assistant; there is no need to go through the
     * assistant.
     */
    val accessibilityRepair = AccessibilityRepair(
        restart = { gap -> if (canWriteSecure()) restartDirectly(gap) else permissions.restartAccessibility(gap) },
        consent = { settings.consent.value },
        direct = ::canWriteSecure,
    )

    private fun canWriteSecure() =
        app.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** The same steps as [RecoveryPermissions.restartAccessibility], except that the app writes the system settings by itself: if it is not available, add it; if it is not bound, remove it and add it back. */
    private suspend fun restartDirectly(gapMs: Long): PermissionResult = withContext(Dispatchers.IO) {
        val cr = app.contentResolver
        val me = PermissionCommands.accessibilityService(app.packageName)
        fun read() = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        fun write(list: String) {
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list.ifEmpty { null })
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, if (list.isEmpty()) 0 else 1)
        }
        try {
            val current = read()
            if (AccessibilityList.contains(current, me)) {
                write(AccessibilityList.without(current, me))
                withContext(NonCancellable) {
                    delay(gapMs)
                    // When the add back fails, write back the original list (together with the main
                    // switch) to prevent Mina from disappearing from the list; this time, still press
                    // the add back failure button to report
                    try {
                        write(AccessibilityList.with(read(), me))
                    } catch (e: Exception) {
                        runCatching { write(current.orEmpty()) }
                        throw e
                    }
                }
            } else {
                write(AccessibilityList.with(current, me))
            }
            PermissionResult.Done
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PermissionResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Every time the assistant becomes running (just after pairing, restarting after being disconnected,
     * already paired when the app is started, automatically restored and connected after booting), it
     * will add several items that are turned on by default, have not been turned off by the user, and
     * have not yet taken effect. These roads all end up in the same state, so I only look at them here
     * and don't adjust them separately everywhere. At the same time, look at the accessibility service:
     * when it is not running and there is a way to write system settings (the App itself has
     * authorization or the assistant is running), it will be automatically turned on.
     */
    @Synchronized
    fun start() {
        if (started) return
        started = true
        scope.launch { permissions.applyWhenRunning(control.status) }
        val service = accessibility
        // Take a look at startup: after being forcibly stopped, the system will remove the service from
        // the list, or it will be in the list but not bound; write it down to provide diagnostic
        // information.
        val initial = service.value
        val seen = settings.accessibilitySeen
        scope.launch {
            val connected = withTimeoutOrNull(A11yStartCheck.CONNECT_WAIT_MS) { service.first { it.connected } } != null
            A11yStartCheck.loss(initial, connected, seen)?.let {
                settings.recordA11yLoss(A11yLossRecord(it, System.currentTimeMillis()))
            }
        }
        scope.launch { service.collect { if (it.connected) settings.accessibilitySeen = true } }
        scope.launch { accessibilityRepair.watch(service, control.status, userUnlocked) }
    }

    /**
     * Has the user unlocked it after booting ([UserManager.isUserUnlocked]): When it is not unlocked,
     * the accessibility service cannot be bound to the original, and the automatic repair will not stop;
     * when receiving [Intent.ACTION_USER_UNLOCKED], read it again and let the automatic repair make a
     * judgment. One copy of the entire process.
     */
    private val userUnlocked: StateFlow<Boolean> by lazy {
        fun read() = runCatching { app.getSystemService(UserManager::class.java)?.isUserUnlocked }.getOrNull() != false
        callbackFlow {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) { trySend(read()) }
            }
            val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else app.registerReceiver(receiver, filter)
            trySend(read()) // Changes between before and after registration
            awaitClose { runCatching { app.unregisterReceiver(receiver) } }
        }.stateIn(scope, SharingStarted.Eagerly, read())
    }

    /** Whether there is Mina in the system list (according to the change of system settings) and whether the service instance is connected (according to the connection and disconnection). One copy of the entire process. */
    val accessibility: StateFlow<A11yObservation> by lazy { serviceObservations() }

    private fun serviceObservations(): StateFlow<A11yObservation> {
        val cr = app.contentResolver
        val me = PermissionCommands.accessibilityService(app.packageName)
        fun enabled() = AccessibilityList.contains(Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), me)
        val listChanges = callbackFlow {
            val observer = object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) { trySend(Unit) }
            }
            cr.registerContentObserver(Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, observer)
            send(Unit)
            awaitClose { cr.unregisterContentObserver(observer) }
        }
        return combine(listChanges, ScreenAccessibility.instance) { _, service -> A11yObservation(enabled(), service != null) }
            .stateIn(scope, SharingStarted.Eagerly, A11yObservation(enabled(), ScreenAccessibility.instance.value != null))
    }

    /** Unpairing: First withdraw authorization while the connection is still there, and then delete the pairing. */
    suspend fun unpair() {
        val a11yApplied = settings.consent.value.isApplied(RecoveryCapability.Accessibility)
        val result = permissions.revokeAll()
        // Accessibility services can at least turn themselves off when the connection is no longer
        // available; battery whitelisting and authorization to modify system settings can only be
        // revoked by the connection
        if (result != PermissionResult.Done && a11yApplied) {
            runCatching { ScreenAccessibility.instance.value?.disableSelf() }
        }
        control.unpair()
    }

    /** After powering on, press the "Automatic recovery after restart" that has taken effect to reopen wireless debugging and restart the assistant; return null if no action is needed. */
    suspend fun restoreAfterBoot(): RestoreOutcome? {
        // Not paired (the assistant is started from the computer via USB): Mina cannot get up the
        // assistant by herself and does not use wireless debugging.
        if (!control.paired) return null
        val holds = app.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
        when (BootRestore.gate(Build.VERSION.SDK_INT, settings.consent.value, holds)) {
            BootRestore.Gate.Skip -> return null
            BootRestore.Gate.PermissionMissing -> return RestoreOutcome.PermissionMissing.also(settings::recordRestore)
            BootRestore.Gate.Act -> Unit
        }
        val cr = app.contentResolver
        // Capability test: If writing is rejected (throws an exception/returns false) or if the read
        // value is not 1 after writing, it will be treated as if the phone does not support it.
        val written = DeveloperSettings.turnOnWirelessDebugging(cr) && DeveloperSettings.wirelessDebugging(cr)
        if (!written) return RestoreOutcome.SettingUnavailable.also(settings::recordRestore)

        val outcome = withTimeoutOrNull(BOOT_BUDGET_MS) {
            RecoveryPolicy.recover(
                inputs = {
                    RecoveryInputs(
                        paired = control.paired,
                        // When not on a trusted Wi-Fi, the system will turn the switch back: reread each
                        // round, turn it back and stop
                        wirelessDebugging = DeveloperSettings.wirelessDebugging(cr),
                        status = control.status.value,
                    )
                },
                attempt = { control.retry() },
            )
        }
        val result = if (outcome?.recovered == true) RestoreOutcome.Restored else RestoreOutcome.NotRecovered
        return result.also(settings::recordRestore)
    }

    private companion object {
        /** Start broadcasting goAsync for about one minute; leave a margin. */
        const val BOOT_BUDGET_MS = 50_000L
    }
}
