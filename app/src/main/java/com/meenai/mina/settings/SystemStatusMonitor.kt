package com.meenai.mina.settings

import android.app.AppOpsManager
import android.app.NotificationManager
import com.meenai.mina.task.TaskService
import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserManager
import android.provider.Settings
import com.meenai.mina.cap.phone.a11y.ScreenAccessibility
import com.meenai.mina.core.shell.BuiltinShellControl
import com.meenai.mina.cap.phone.setup.DeveloperSettings
import com.meenai.mina.cap.phone.setup.PairingNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import com.meenai.mina.a11y.HarnessAccessibilityService

/**
 * Real-time summary of system status required to start tasks. The built-in assistant, accessibility, and
 * developer switches will change if there is a callback; the notification permission will not have a
 * callback, and [refresh] will be adjusted when the interface returns to the foreground.
 */
private const val OP_RESTRICTED_SETTINGS = "android:access_restricted_settings"

class SystemStatusMonitor(context: Context, private val shell: BuiltinShellControl) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val _status = MutableStateFlow(
        SystemStatus(
            sdkInt = Build.VERSION.SDK_INT,
            builtinShell = shell.status.value,
            accessibility = if (ScreenAccessibility.instance.value != null) AccessibilityState.On else AccessibilityState.Off,
            notificationsAllowed = true,
        ),
    )
    val status: StateFlow<SystemStatus> = _status.asStateFlow()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var scope: CoroutineScope? = null
    private val a11ySetting = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = refreshAsync()
    }

    init {
        refreshRequests
            .onEach { _status.value = read() }
            .launchIn(ioScope)
        refreshAsync()
    }

    fun start() {
        if (scope != null) return
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope = it }
        app.contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, a11ySetting,
        )
        ScreenAccessibility.instance.onEach { refreshAsync() }.launchIn(s)
        // The boot page should follow the system switch in real time: after the user opens it in the
        // system settings, he will see the next step when he comes back.
        app.contentResolver.registerContentObserver(DeveloperSettings.developerOptionsUri, false, a11ySetting)
        app.contentResolver.registerContentObserver(DeveloperSettings.wirelessDebuggingUri, false, a11ySetting)
        app.contentResolver.registerContentObserver(DeveloperSettings.usbDebuggingUri, false, a11ySetting)
        shell.status.onEach { refreshAsync() }.launchIn(s)
        refreshAsync()
    }

    fun stop() {
        scope?.cancel()
        scope = null
        app.contentResolver.unregisterContentObserver(a11ySetting)
    }

    fun refresh() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            refreshAsync()
        } else {
            _status.value = read()
        }
    }

    private fun refreshAsync() {
        refreshRequests.tryEmit(Unit)
    }

    private fun read() = SystemStatus(
        sdkInt = Build.VERSION.SDK_INT,
        builtinShell = shell.status.value,
        accessibility = accessibilityState(),
        userLocked = userLocked(),
        notificationsAllowed = app.getSystemService(NotificationManager::class.java).areNotificationsEnabled(),
        attentionLowered = channelLowered(TaskService.CHANNEL_ATTENTION),
        resultLowered = channelLowered(TaskService.CHANNEL_RESULT),
        // Create a channel first: You can see whether it is turned off or turned down before pairing,
        // and you can also directly open its settings page.
        pairingChannel = PairingNotifier.ensureChannel(app).health,
        attentionImportance = channelImportance(TaskService.CHANNEL_ATTENTION),
        resultImportance = channelImportance(TaskService.CHANNEL_RESULT),
        progressImportance = channelImportance(TaskService.CHANNEL_PROGRESS),
        developerOptions = DeveloperSettings.developerOptions(app.contentResolver),
        wirelessDebugging = DeveloperSettings.wirelessDebugging(app.contentResolver),
        wirelessDebuggingMissing = DeveloperSettings.wirelessDebuggingMissing(app.contentResolver),
        developerSettingsMissing = DeveloperSettings.developerOptionsPageMissing(app.packageManager),
        usbDebugging = DeveloperSettings.usbDebugging(app.contentResolver),
        restrictedSettings = restrictedSettings(),
        helperRestart = shell.restart,
    )

    private fun channelImportance(id: String): Int? =
        app.getSystemService(NotificationManager::class.java).getNotificationChannel(id)?.importance

    /** The channel has been created, but its importance is lower than "pop-up" (if the user or system lowers it, the App cannot change it back). */
    private fun channelLowered(id: String) = app.getSystemService(NotificationManager::class.java).getNotificationChannel(id)
        ?.let { it.importance < NotificationManager.IMPORTANCE_HIGH } ?: false

    /**
     * Will the system prevent accessibility from being opened? Judging by capabilities: Android 13 has
     * the "Restricted Settings" permission. If it can be read and allowed, it will not be blocked; if it
     * cannot be read (the system does not have this or does not allow reading), it will be considered as
     * possible blocking, but it will only provide an additional entrance.
     */
    private fun restrictedSettings(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val mode = runCatching {
            app.getSystemService(AppOpsManager::class.java).unsafeCheckOpNoThrow(OP_RESTRICTED_SETTINGS, Process.myUid(), app.packageName)
        }.getOrNull()
        return mode != AppOpsManager.MODE_ALLOWED
    }

    private fun accessibilityState(): AccessibilityState {
        if (ScreenAccessibility.instance.value != null) return AccessibilityState.On
        val enabled = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').mapNotNull { ComponentName.unflattenFromString(it.trim()) }
        val me = ComponentName(app, HarnessAccessibilityService::class.java)
        return if (me in enabled) AccessibilityState.EnabledNotRunning else AccessibilityState.Off
    }

    /** It has not been unlocked once after booting up: The failure to bind the accessibility service at this time is a system behavior, not a malfunction. */
    private fun userLocked(): Boolean =
        runCatching { app.getSystemService(UserManager::class.java)?.isUserUnlocked }.getOrNull() == false
}
