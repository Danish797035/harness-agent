package com.meenai.mina.about

import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.meenai.mina.BuildConfig
import com.meenai.mina.HarnessApp
import com.meenai.mina.cap.phone.PhoneServices
import com.meenai.mina.cap.phone.a11y.AppCopyReader
import com.meenai.mina.cap.phone.a11y.launchableApps
import com.meenai.mina.cap.phone.shell.ShellService
import com.meenai.mina.cap.phone.shell.adb.AndroidAdb
import com.meenai.mina.cap.phone.setup.DeveloperSettings
import com.meenai.mina.cap.phone.setup.PairingChannel
import com.meenai.mina.cap.sandbox.SandboxState
import com.meenai.mina.core.cap.sandbox.SandboxStatus
import com.meenai.mina.core.model.ModelRole
import com.meenai.mina.core.shell.PairingDiscovery
import com.meenai.mina.core.shell.PermissionCommands
import com.meenai.mina.core.shell.SetupInputs
import com.meenai.mina.core.shell.SetupStep
import com.meenai.mina.i18n.AppLanguage
import com.meenai.mina.record.RunStore
import com.meenai.mina.settings.AccessibilityState
import com.meenai.mina.settings.SystemStatus
import com.meenai.mina.task.TaskService
import com.meenai.mina.voice.SpeechChoice
import com.meenai.mina.voice.SystemSpeech

/** The input of the boot page: consists of the system status plus several items recorded by the boot page (the ones confirmed by the user, skipped, and redone). The boot page and diagnostic information share this one. */
fun SystemStatus.setupInputs(
    discovery: PairingDiscovery,
    repair: Boolean,
    accessibilitySkipped: Boolean = false,
    confirmed: Set<SetupStep> = emptySet(),
    revisit: SetupStep? = null,
    stalledMs: Long = 0L,
    usbAsked: Boolean = false,
) = SetupInputs(
    developerOptions = developerOptions, wirelessDebugging = wirelessDebugging,
    status = builtinShell, discovery = discovery, notificationsAllowed = notificationsAllowed,
    pairingChannel = pairingChannel, repair = repair,
    usbDebugging = usbDebugging, accessibility = accessibility == AccessibilityState.On,
    accessibilitySkipped = accessibilitySkipped, restrictedSettings = restrictedSettings,
    confirmed = confirmed, revisit = revisit,
    wirelessMissing = wirelessDebuggingMissing, stalledMs = stalledMs, usbAsked = usbAsked,
    restart = helperRestart, developerSettingsMissing = developerSettingsMissing,
)

/**
 * Collect a copy of [DiagnosticsSnapshot] from the ready-made status in the App: system status, built-in
 * assistant, automatic recovery, model configuration, running records, and check for updates. To read
 * the disk (running record), call it in the background thread. [setup] is the current input of the boot
 * page; when the boot page is not on the boot page, create a copy according to the system status (there
 * is no step confirmed by the user).
 */
object DiagnosticsCollector {
    private const val RECENT_RUNS = 3

    fun collect(context: Context, status: SystemStatus, setup: SetupInputs? = null): DiagnosticsSnapshot {
        val app = context.applicationContext as HarnessApp
        val phone = PhoneServices.of(app)
        val inputs = setup ?: status.setupInputs(phone.shellControl.pairingDiscovery.value, phone.pairing.repairing.value)
        return DiagnosticsSnapshot(
            generatedAtMs = System.currentTimeMillis(),
            app = DiagnosticsSnapshot.App(BuildConfig.VERSION_NAME, BuildConfig.GIT_HASH, installer(app), language(app)),
            device = DiagnosticsSnapshot.Device(
                Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.toList(),
                sandbox(SandboxState.get(app).status.value),
            ),
            setup = inputs,
            phone = DiagnosticsSnapshot.Phone(
                accessibility = status.accessibility,
                wirelessDebuggingSetting = runCatching {
                    Settings.Global.getString(app.contentResolver, DeveloperSettings.ADB_WIFI_ENABLED)?.let { it == "1" }
                }.getOrNull(),
                connect = phone.shellManager.lastConnectCandidates,
                pairingChannels = PairingChannel.IDS.mapNotNull { id -> channel(app, id)?.let { id to it } }.toMap(),
                attentionChannelImportance = channel(app, TaskService.CHANNEL_ATTENTION),
                wifiConnected = wifi(app),
                lastError = phone.shellManager.engine.lastError,
                appCopies = appCopies(app, phone),
                adbKey = adbKey(app),
                appsAfterTask = com.meenai.mina.task.AppsAfterTaskEnd.lastLine,
            ),
            recovery = DiagnosticsSnapshot.Recovery(
                consent = phone.recoverySettings.consent.value,
                writeSecureSettings = app.checkSelfPermission(PermissionCommands.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED,
                ignoringBatteryOptimizations = runCatching {
                    app.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(app.packageName)
                }.getOrNull(),
                lastRestore = phone.recoverySettings.lastRestore.value?.name,
                helperRestores = phone.hub.restores.value,
                lastA11yLoss = phone.recoverySettings.lastA11yLoss.value,
            ),
            models = models(app),
            runs = runs(app),
            update = app.updates.state.value.let { u ->
                DiagnosticsSnapshot.Update(u.status.name, u.latest?.version, u.update?.version)
            },
            speech = speech(app),
        )
    }

    /** The built-in assistant asks it for each user's judgment during the connection, and then counts how many other copies can be opened (only counting, not listing package names); if not asked, it will be null. */
    private fun appCopies(app: HarnessApp, phone: PhoneServices): String? = runCatching {
        val shell = phone.hub.shell.value ?: return null
        val summary = shell.appCopyUsers()?.getString(ShellService.KEY_COPY_SUMMARY) ?: return null
        val copies = AppCopyReader.read(shell, launchableApps(app.packageManager, app.packageName))
        "$summary; ${copies?.size ?: "?"} app copies"
    }.getOrNull()

    private fun installer(context: Context): String? = runCatching {
        context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
    }.getOrNull()

    private fun language(context: Context): String {
        val shown = context.resources.configuration.locales.takeUnless { it.isEmpty }?.get(0)?.toLanguageTag() ?: "?"
        return "${AppLanguage.current(context).name} ($shown)"
    }

    private fun sandbox(s: SandboxStatus): String = when (s) {
        is SandboxStatus.Unsupported -> "Unsupported: ${s.reason}"
        is SandboxStatus.Installing -> "Installing ${s.percent}%"
        is SandboxStatus.Ready -> "Ready"
        is SandboxStatus.Broken -> "Broken: ${s.reason}"
        SandboxStatus.NotInstalled -> "NotInstalled"
    }

    private fun channel(context: Context, id: String): Int? =
        runCatching { context.getSystemService(NotificationManager::class.java).getNotificationChannel(id)?.importance }.getOrNull()

    /** Just look at the condition, don't read the key for diagnosis. */
    private fun adbKey(context: Context): String? = runCatching {
        val src = AndroidAdb.identities(context)
        src.state.name + (src.problem?.let { " ($it)" } ?: "")
    }.getOrNull()

    private fun wifi(context: Context): Boolean? = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
    }.getOrNull()

    private fun models(app: HarnessApp): DiagnosticsSnapshot.Models {
        val store = app.providers
        fun role(r: ModelRole) = store.configFor(r)?.let { DiagnosticsSnapshot.RoleModel(it.model, it.baseUrl) }
        val chatGpt = store.chatGptProvider()
        return DiagnosticsSnapshot.Models(
            providers = store.providers.value.map { DiagnosticsSnapshot.Provider(it.kind.name, it.baseUrl, it.models?.size) },
            main = role(ModelRole.Main),
            vision = role(ModelRole.Vision),
            light = role(ModelRole.Light),
            chatGptSignedIn = chatGpt != null && store.hasKey(chatGpt.id),
            chatGptLastFailure = app.chatGptSignIn.lastFailure,
            accountLastFailure = app.chatGptSignIn.lastAccountFailure,
        )
    }

    /** Speech to text: selection, the recognition capabilities and tokens that come with the mobile phone, and the recognition service registered on the mobile phone (component name, whether to open or not, required permissions). */
    private fun speech(app: HarnessApp): DiagnosticsSnapshot.Speech? = runCatching {
        val settings = app.speech
        val cap = SystemSpeech.capability(app, settings.systemSilent.value)
        val choice = when (val c = settings.choice.value) {
            SpeechChoice.Auto -> "auto"
            SpeechChoice.System -> "system"
            SpeechChoice.Off -> "off"
            is SpeechChoice.Doubao -> "doubao-asr-2.0 / ${c.resourceId}"
            is SpeechChoice.Provider -> c.model + (app.providers.providers.value.firstOrNull { it.id == c.providerId }?.let { " @ ${Diagnostics.host(it.baseUrl)}" } ?: " (provider removed)")
        }
        DiagnosticsSnapshot.Speech(
            choice = choice,
            systemAvailable = cap.available,
            onDevice = cap.onDevice,
            silent = settings.systemSilent.value,
            remembered = settings.systemService,
            services = SystemSpeech.services(app).map { svc ->
                svc.component + (if (svc.open) "" else " (not exported)") + (svc.permission?.let { " needs $it" } ?: "")
            },
        )
    }.getOrNull()

    /** The last few runs: the ending and number of steps are summarized, and the model is summarized; the goals, answers, and original words of failure are not taken. */
    private fun runs(app: HarnessApp): List<DiagnosticsSnapshot.Run> = runCatching {
        val store = RunStore(app)
        val models = store.list().take(RECENT_RUNS * 2).associate { it.id to it.model }
        store.metrics.recent(RECENT_RUNS).reversed().map { m ->
            DiagnosticsSnapshot.Run(m.startedAtMs, m.outcome, m.failKind, m.judged, m.steps, models[m.runId])
        }
    }.getOrDefault(emptyList())
}
