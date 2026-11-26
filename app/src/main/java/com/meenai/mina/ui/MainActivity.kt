package com.meenai.mina.ui

import com.meenai.mina.i18n.AppLanguage
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.meenai.mina.ui.components.KeyboardDismissArea
import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import com.meenai.mina.confirm.ConfirmationNotifier
import com.meenai.mina.memory.MemoryRuntime
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.compose.LifecycleStartEffect
import com.meenai.mina.ui.components.PageBackground
import androidx.compose.ui.platform.LocalContext
import com.meenai.mina.cap.web.WebHandover
import com.meenai.mina.cap.web.WebSites
import com.meenai.mina.browser.UserBrowser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.res.stringResource
import com.meenai.mina.R
import com.meenai.mina.about.ApkUpdater
import com.meenai.mina.about.DownloadPhase
import com.meenai.mina.BuildConfig
import com.meenai.mina.HarnessApp
import com.meenai.mina.about.Diagnostics
import com.meenai.mina.about.DiagnosticsCollector
import com.meenai.mina.about.HarnessLinks
import com.meenai.mina.about.QrImage
import com.meenai.mina.chat.ConversationStore
import com.meenai.mina.record.RunStore
import com.meenai.mina.task.TaskService
import com.meenai.mina.core.knowledge.GuideCatalog
import com.meenai.mina.settings.Prerequisites
import com.meenai.mina.settings.ProviderStore
import com.meenai.mina.settings.SystemStatusMonitor
import com.meenai.mina.cap.phone.PhoneServices
import com.meenai.mina.cap.phone.setup.DeveloperSettings
import com.meenai.mina.cap.phone.setup.PairingNotifier
import com.meenai.mina.core.shell.BuiltinShellStatus
import com.meenai.mina.ui.theme.HarnessTheme
import com.meenai.mina.ui.theme.animationsOff
import com.meenai.mina.ui.theme.appearanceDark
import com.meenai.mina.ui.theme.systemBarsFor

class MainActivity : ComponentActivity() {
    private lateinit var monitor: SystemStatusMonitor
    private var requestedConversation by mutableStateOf<String?>(null)
    private var skipNextResumeRefresh = false

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // When the user selects "Don't ask again", the system will no longer pop up the window and can
        // only be opened in the settings.
        if (!granted && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) openNotificationSettings()
        monitor.refresh()
    }

    // For Android 12 and before, the language selected in the App is applied; from Android 13 onwards,
    // the system can apply it by itself.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        requestedConversation = intent.getStringExtra(EXTRA_OPEN_CONVERSATION)
        monitor = SystemStatusMonitor(this, PhoneServices.of(this).shellControl)
        val app = applicationContext as HarnessApp
        setContent {
            // The appearance is set according to the settings. If you change it, change it on the spot;
            // the depth of the system bar icon will follow the interface.
            val dark = appearanceDark()
            LaunchedEffect(dark) { systemBarsFor(dark) }
            var store by remember {
                mutableStateOf(if (app.providersReady) ProviderStore.get(this@MainActivity) else null)
            }
            LaunchedEffect(Unit) {
                // Allow the first themed frame to render before loading disk/keystore-backed stores on IO.
                withFrameNanos { }
                app.onFirstFrameRendered()
                if (store == null) {
                    store = withContext(Dispatchers.IO) {
                        val loaded = ProviderStore.get(this@MainActivity)
                        app.conversations
                        app.chat
                        app.routerStatus
                        loaded
                    }
                }
            }
            HarnessTheme(dark = dark, reduceMotion = animationsOff(this)) {
                // Click or drag outside the input box to hide the keyboard, unified across the app
                KeyboardDismissArea(Modifier.fillMaxSize()) {
                    val readyStore = store
                    if (readyStore != null) {
                        HarnessRoot(readyStore, monitor, ::perform, requestedConversation) { requestedConversation = null }
                    } else {
                        PageBackground {}
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedConversation = intent.getStringExtra(EXTRA_OPEN_CONVERSATION)
    }

    override fun onStart() {
        super.onStart()
        monitor.start()
        skipNextResumeRefresh = true
        val app = application as HarnessApp
        val appContext = applicationContext
        if (app.hasFirstFrameRendered) {
            // Every time it is opened, quietly check for new versions and ensure MCP on IO off Main.
            app.appScope.launch(Dispatchers.IO) {
                com.meenai.mina.about.UpdateAutomation.onAppOpened(appContext, app.updates)
                app.mcp.ensureRunning()
            }
            // Auto-connect / warm up active model session & quota on foreground return/startup.
            app.chat.warmUpIfNeeded()
        } else {
            app.appScope.launch(Dispatchers.IO) {
                app.awaitFirstFrame()
                com.meenai.mina.about.UpdateAutomation.onAppOpened(appContext, app.updates)
                app.mcp.ensureRunning()
                app.chat
                withContext(Dispatchers.Main) {
                    app.chat.warmUpIfNeeded()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (skipNextResumeRefresh) {
            skipNextResumeRefresh = false
        } else {
            monitor.refresh()
        }
    }

    override fun onStop() {
        monitor.stop()
        super.onStop()
    }

    private val phone get() = PhoneServices.of(this)

    private fun perform(fix: Fix) {
        when (fix) {
            Fix.OpenAccessibility -> open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Fix.OpenAppInfo -> open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            Fix.AllowNotifications -> {
                val needsGrant = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                if (needsGrant) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else openNotificationSettings()
            }
            Fix.OpenAttentionChannel -> openChannelSettings(TaskService.CHANNEL_ATTENTION)
            Fix.OpenResultChannel -> openChannelSettings(TaskService.CHANNEL_RESULT)
            Fix.OpenProgressChannel -> openChannelSettings(TaskService.CHANNEL_PROGRESS)
            Fix.OpenPairingChannel -> openChannelSettings(PairingNotifier.ensureChannel(this).id)
            Fix.AddModel -> Unit // Handle the settings page yourself
            // Paging is handled by HarnessRoot
            Fix.OpenPhoneSetup, Fix.RepairPhoneHelper -> Unit
            Fix.RetryPhoneHelper -> phone.pairing.scope.launch {
                phone.sharedShell.reconnect()
                phone.shellControl.retry()
            }
            Fix.UnpairPhoneHelper -> phone.pairing.scope.launch { phone.recovery.unpair() }
            Fix.OpenAboutPhone -> openFirst(DeveloperSettings.aboutPhone())
            Fix.OpenDeveloperOptions -> openFirst(DeveloperSettings.developerOptionsPage())
            Fix.OpenUsbDebugging -> openFirst(DeveloperSettings.usbDebuggingPage())
            Fix.OpenWirelessDebugging -> openFirst(DeveloperSettings.wirelessDebuggingPage())
            Fix.OpenWifi -> openFirst(DeveloperSettings.wifiPage())
        }
    }

    private fun appNotificationSettings() =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)

    private fun openNotificationSettings() = open(appNotificationSettings())

    /** Directly open the settings page of a certain notification channel; if the system does not have this page, return to the application notification general page. */
    private fun openChannelSettings(channelId: String) {
        val channelPage = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName).putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
        firstThatStarts(listOf(channelPage, appNotificationSettings())) { intent ->
            if (intent.resolveActivity(packageManager) == null) false
            else try { startActivity(intent); true } catch (e: ActivityNotFoundException) { false }
        }
    }

    /** Try them in order, and use the first one that can be opened: Not all settings pages agree with the same entrance. */
    private fun openFirst(intents: List<Intent>) {
        val hit = intents.firstOrNull { it.resolveActivity(packageManager) != null } ?: intents.last()
        open(hit)
    }

    private fun open(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no activity for ${intent.action}", e)
        }
    }

    companion object {
        const val EXTRA_OPEN_CONVERSATION = "open_conversation"
        private const val TAG = "MinaMain"
    }
}

/** Use the system browser to open external links (GitHub, X, etc.); prompt a message if there is no browser. */
internal fun openInBrowser(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, AboutCopy.NO_BROWSER, Toast.LENGTH_SHORT).show()
    }
}

/** Try [candidates] in sequence, and stop if the first [start] succeeds; if not, return false. Not all companies have a system settings page, so you can use this to return to the general page. */
internal fun <T> firstThatStarts(candidates: List<T>, start: (T) -> Boolean): Boolean = candidates.any(start)

/**
 * Page switching: The dialog page is the root, the sidebar and settings and other entrances are pressed
 * into [NavStack], and the return key and the return button on the page are all pressed into
 * [NavStack.back]. Each page only enters and exits through callbacks and does not know each other.
 */
@Composable
private fun HarnessRoot(store: ProviderStore, monitor: SystemStatusMonitor, activityFix: (Fix) -> Unit,
    requestedConversation: String?, onConversationOpened: () -> Unit) {
    val nav = rememberNavStack()
    // When entering "re-pairing" from the settings page, go back to the pairing step first.
    var setupRepair by rememberSaveable { mutableStateOf(false) }
    // Which step is being taken when getting started for the first time; if it is empty, it is not in
    // the getting started process. The configuration model page goes back to where it came from.
    var onboarding by rememberSaveable { mutableStateOf<OnboardingStep?>(null) }
    val onFix: (Fix) -> Unit = { fix ->
        when (fix) {
            Fix.AddModel -> nav.push(Route.Models)
            Fix.OpenPhoneSetup, Fix.RepairPhoneHelper -> {
                setupRepair = fix == Fix.RepairPhoneHelper
                nav.push(Route.PhoneSetup)
            }
            else -> activityFix(fix)
        }
    }
    var runId by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceRunId by rememberSaveable { mutableStateOf<String?>(null) }
    var sourceLegacyText by rememberSaveable { mutableStateOf<String?>(null) }
    val status by monitor.status.collectAsState()
    val roles by store.roles.collectAsState()
    val hasMain = roles.main != null
    val check = Prerequisites.check(status, hasMain)
    val context = LocalContext.current
    val app = context.applicationContext as HarnessApp
    val updateState by app.updates.state.collectAsState()
    var updateDialogDismissedFor by rememberSaveable { mutableStateOf<String?>(null) }
    val availableUpdate = updateState.update
    val showUpdateDialog = availableUpdate != null && updateDialogDismissedFor != availableUpdate.version

    if (showUpdateDialog && availableUpdate != null) {
        val updater = remember { ApkUpdater(context.applicationContext) }
        val scope = rememberCoroutineScope()
        AlertDialog(
            onDismissRequest = { updateDialogDismissedFor = availableUpdate.version },
            title = { Text(stringResource(R.string.update_dialog_title)) },
            text = {
                Text(
                    if (availableUpdate.notes.isNotBlank()) {
                        stringResource(R.string.update_dialog_message, availableUpdate.version) + "\n\n" + availableUpdate.notes.take(300)
                    } else {
                        stringResource(R.string.update_dialog_message, availableUpdate.version)
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    updateDialogDismissedFor = availableUpdate.version
                    if (updater.state(availableUpdate).phase == DownloadPhase.Ready) {
                        runCatching { updater.openInstaller(availableUpdate) }
                    } else {
                        Toast.makeText(context, context.getString(R.string.about_update_downloading), Toast.LENGTH_SHORT).show()
                        scope.launch(Dispatchers.IO) {
                            runCatching { updater.download(availableUpdate) }
                        }
                    }
                }) {
                    Text(stringResource(R.string.update_dialog_download))
                }
            },
            dismissButton = {
                TextButton(onClick = { updateDialogDismissedFor = availableUpdate.version }) {
                    Text(stringResource(R.string.update_dialog_later))
                }
            },
        )
    }
    LaunchedEffect(requestedConversation) {
        if (requestedConversation != null) {
            app.chat.openConversation(requestedConversation)
            nav.reset()
            onConversationOpened()
        }
    }
    val release = remember { Build.VERSION.RELEASE }
    // Click "Look" on the result card: open skill segmentation
    val memoryRuntime = remember { MemoryRuntime.of(context) }
    val phoneSetup = remember { PhoneServices.of(context).setupPrompt }
    val openMemory by memoryRuntime.openRequest.collectAsState()
    LaunchedEffect(openMemory) { if (openMemory != null) nav.push(Route.Memory) }
    // Open for the first time: Welcome → Configure model → Mobile operation; if you have configured the
    // model, skip the first two steps; if the mobile operation is ready or have seen the guide, skip the
    // last step
    val onboardingState = remember { OnboardingState(context) }
    fun onboardingInputs() = Onboarding.Inputs(
        hasMainModel = store.roles.value.main != null,
        modelSetupDone = onboardingState.modelSetupDone(),
        phoneNeeded = status.builtinShell != BuiltinShellStatus.Running && status.sdkInt >= Prerequisites.MIN_SDK,
        phoneGuideSeen = phoneSetup.guideSeen(),
    )
    fun goOnboarding(step: OnboardingStep?) {
        onboarding = step
        when (step) {
            // Every step of getting started is placed directly on the top layer of the dialogue page.
            // When you are done, return to the dialogue page.
            OnboardingStep.Welcome -> nav.show(Route.Welcome)
            OnboardingStep.Model -> nav.show(Route.ModelSetup)
            OnboardingStep.Phone -> { setupRepair = false; nav.show(Route.PhoneSetup) }
            null -> nav.reset()
        }
    }
    LaunchedEffect(Unit) {
        if (onboarding == null) Onboarding.start(onboardingInputs())?.let(::goOnboarding)
    }
    val phoneSetupNeeded by phoneSetup.needed.collectAsState()
    // When the page has its own return processing (subpages and sidebars in the settings page), they are
    // connected first; here we just return to the previous page.
    BackHandler(enabled = nav.canGoBack) { nav.back() }
    val back: () -> Unit = { nav.back() }
    // When the chat page leaves the combination tree, the list position and input content are retained,
    // and they are restored to their original state after returning to sub-pages such as the settings
    // page.
    val screenStates = rememberSaveableStateHolder()
    when (nav.current) {
        Route.PhoneSetup -> PhoneSetupScreen(status, setupRepair, onFix, onboarding = onboarding == OnboardingStep.Phone) {
            phoneSetup.markGuideSeen()
            if (onboarding == OnboardingStep.Phone) goOnboarding(null) else nav.back()
        }
        Route.Welcome -> WelcomeScreen { goOnboarding(OnboardingStep.Model) }
        Route.ModelSetup -> {
            val inOnboarding = onboarding == OnboardingStep.Model
            ModelSetupScreen(
                store, onboarding = inOnboarding,
                onBack = { if (inOnboarding) goOnboarding(OnboardingStep.Welcome) else nav.back() },
                onDone = {
                    if (inOnboarding) {
                        onboardingState.markModelSetupDone()
                        goOnboarding(Onboarding.next(OnboardingStep.Model, onboardingInputs()))
                    } else nav.back()
                },
            )
        }
        Route.Chat -> screenStates.SaveableStateProvider(Route.Chat.name) {
            // The conversation page is visible again (returning to the front desk, coming back from
            // other pages): make up for rounds saved elsewhere, and pick up tasks started elsewhere.
            LifecycleStartEffect(Unit) {
                app.chat.refresh()
                app.chat.warmUpIfNeeded()
                onStopOrDispose {}
            }
            val convStore = remember(context) { ConversationStore.shared(context) }
            ChatWithDrawer(
                vm = app.chat,
                check = check,
                reduceMotion = HarnessTheme.reduceMotion,
                conversations = { convStore.list() },
                conversationChanges = convStore.changes,
                conversationTitle = { convStore.title(it) },
                onPlans = { nav.pushFromDrawer(Route.Plans) },
                onMemory = { nav.pushFromDrawer(Route.Memory) },
                onSettings = { nav.pushFromDrawer(Route.Settings) },
                onBrowser = { nav.pushFromDrawer(Route.Browser) },
                onScreens = { nav.pushFromDrawer(Route.Screens) },
                onAbout = { nav.pushFromDrawer(Route.About) },
                updateAvailable = AboutCopy.drawerDot(updateState),
                reopen = nav.reopenDrawer,
                onReopened = nav::drawerReopened,
                cachedConversations = { convStore.cachedList() },
                asyncLoad = true,
            ) { openDrawer ->
                ChatScreen(
                    vm = app.chat,
                    check = check,
                    frames = app.taskState.frames,
                    webOpen = app.tasks.webScreenOpen,
                    reduceMotion = HarnessTheme.reduceMotion,
                    onOpenDrawer = openDrawer,
                    openSettings = { nav.push(Route.Settings) },
                    openRun = { runId = it; nav.push(Route.Run) },
                    phoneSetupNeeded = phoneSetupNeeded && status.builtinShell != BuiltinShellStatus.Running,
                    openPhoneSetup = { onFix(Fix.OpenPhoneSetup) },
                    loadSource = { id ->
                        withContext(Dispatchers.IO) { evidenceSource(RunStore(context).events(id)) { ConfirmationNotifier.appLabel(context, it) } }
                    },
                    openSource = { id, legacy ->
                        sourceRunId = id
                        sourceLegacyText = legacy
                        nav.push(Route.Source)
                    },
                    voice = app.voice,
                    openSpeechModel = { nav.push(Route.SpeechModel) },
                    routerStatus = app.routerStatus,
                )
            }
        }
        Route.Settings -> {
            val registry = remember { WebSites.registry(context) }
            val sites by registry.entries.collectAsState()
            val webSites = WebSitesState(
                sites = sites,
                onOpenLogin = { WebHandover.showLogin(context, it) },
                onLogout = { WebSites.login(context).logout(it) },
                onLogoutAll = { WebSites.logoutAll(context) },
            )
            val autonomous by app.approvals.autonomous.collectAsState()
            val vaultEntries by app.vault.entries.collectAsState()
            SettingsScreen(store, status, release, onFix, onBack = back, onOpenMemory = { nav.push(Route.Memory) },
                onOpenVault = { nav.push(Route.Vault) }, vaultCount = vaultEntries.size,
                onOpenModels = { nav.push(Route.Models) }, webSites = webSites,
                autonomous = autonomous, onAutonomous = app.approvalControl::setAutonomous,
                updateVersion = updateState.update?.version, onOpenAbout = { nav.push(Route.About) },
                onCheckUpdates = app.updates::checkInBackground,
                updateState = updateState)
        }
        Route.Vault -> VaultScreen(vault = app.vault, onBack = back)
        Route.Models -> ModelsScreen(store, onBack = back, onOpenSpeech = { nav.push(Route.SpeechModel) }, routerStatus = app.routerStatus)
        Route.SpeechModel -> SpeechModelScreen(store, onBack = back)
        Route.About -> AboutScreen(
            versionName = BuildConfig.VERSION_NAME, build = BuildConfig.GIT_HASH, beta = BuildConfig.BETA,
            update = updateState,
            issueUrl = remember {
                HarnessLinks.issueUrl(
                    HarnessLinks.Device(
                        Build.MANUFACTURER, Build.MODEL, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
                        BuildConfig.VERSION_NAME, BuildConfig.GIT_HASH,
                    ),
                )
            },
            onCheck = app.updates::checkInBackground,
            onOpenUrl = { openInBrowser(context, it) },
            onSaveQr = { QrImage.saveToPictures(context) },
            onOpenLicense = { nav.push(Route.License) },
            onBack = back,
            diagnostics = {
                Diagnostics.render(DiagnosticsCollector.collect(context, status))
            },
        )
        Route.License -> LicensesScreen(onBack = back)
        Route.Browser -> BrowserScreen(remember { UserBrowser.of(context) }, onBack = back)
        Route.Memory -> {
            // The first time you retrieve the memory, you need to read the disk and decrypt it, and put
            // it in the background thread; the coroutine of the page follows this screen.
            val vmScope = rememberCoroutineScope()
            val vm by produceState<MemoryViewModel?>(null) {
                value = withContext(Dispatchers.IO) {
                    MemoryViewModel(
                        memoryRuntime.store(), memoryRuntime.settings(), memoryRuntime.skills(),
                        vmScope, memoryRuntime.file().brokenNotice,
                        wipe = { memoryRuntime.scope.launch(Dispatchers.IO) { memoryRuntime.wipeAll() } },
                        onForgotten = { id -> memoryRuntime.scope.launch(Dispatchers.IO) { memoryRuntime.stripMemory(setOf(id)) } },
                        runnableScripts = GuideCatalog.loadBundled().runnableScripts(),
                        appDrafts = memoryRuntime.appKnowledge(),
                    )
                }
            }
            vm?.let { m ->
                // Enter from "Look" on the result card: look directly at the skill segment
                LaunchedEffect(m) { if (memoryRuntime.openRequest.value != null) m.select(Segment.Skills) }
                MemoryScreen(m) { memoryRuntime.requestOpen(null); nav.back() }
            }
        }
        Route.Run -> {
            val id = runId
            val vm = remember(id) { id?.let { RunTimelineViewModel(
                    RunStore(context), it, resolveMemory = app.chat::usedMemoryTexts,
                    alive = { run -> app.taskState.runs.value.any { r -> r.runId.value == run } },
                ) } }
            if (vm != null) RunTimelineScreen(vm, back) else LaunchedEffect(Unit) { nav.back() }
        }
        Route.Source -> {
            val vm = remember(sourceRunId, sourceLegacyText) {
                SourceViewModel(RunStore(context), sourceRunId, sourceLegacyText) { ConfirmationNotifier.appLabel(context, it) }
            }
            SourceScreen(vm, back)
        }
        Route.Plans -> PlansScreen(context, onBack = back, onOpenConversation = {
            app.chat.openConversation(it)
            nav.reset()
        })
        Route.Screens -> ScreensScreen(app.taskState, onBack = back, onOpenConversation = {
            app.chat.openConversation(it)
            nav.reset()
        })
    }
}
