package com.meenai.mina.ui

import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.i18n.trPlural
import androidx.compose.ui.res.stringResource
import com.meenai.mina.settings.ProviderConnection
import android.app.ActivityManager
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.meenai.mina.BuildConfig
import com.meenai.mina.cap.sandbox.SandboxState
import com.meenai.mina.capsule.CapsuleMode
import com.meenai.mina.core.agent.SpeedFlags
import com.meenai.mina.core.cap.phone.OperatingPace
import com.meenai.mina.core.cap.sandbox.SandboxStatus
import com.meenai.mina.core.model.ModelRole
import com.meenai.mina.core.record.RunMetricsStore
import com.meenai.mina.core.shell.BuiltinShellStatus
import com.meenai.mina.record.RunStore
import com.meenai.mina.replay.FileRouteStore
import com.meenai.mina.settings.AccessibilityState
import com.meenai.mina.settings.FeatureFlags
import com.meenai.mina.settings.Prerequisites
import com.meenai.mina.settings.Problem
import com.meenai.mina.settings.ProviderConfig
import com.meenai.mina.settings.ProviderStore
import com.meenai.mina.settings.NetProxyStore
import com.meenai.mina.cap.web.WebProxy
import com.meenai.mina.settings.SystemStatus
import com.meenai.mina.settings.notificationOverview
import com.meenai.mina.task.ParallelCapability
import com.meenai.mina.task.ParallelLimit
import com.meenai.mina.ui.components.ButtonKind
import com.meenai.mina.ui.components.ButtonSize
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.GroupLabel
import com.meenai.mina.ui.components.LazyPageScaffold
import com.meenai.mina.ui.components.ListRow
import com.meenai.mina.ui.components.MinaButton
import com.meenai.mina.ui.components.PillTone
import com.meenai.mina.ui.components.RowChevron
import com.meenai.mina.ui.components.RowValue
import com.meenai.mina.ui.components.StateCard
import com.meenai.mina.ui.components.StatusPill
import com.meenai.mina.ui.components.TileTone
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.core.usage.UsageLedger
import com.meenai.mina.core.usage.UsageLimitsQuery
import com.meenai.mina.core.usage.UsageQuery
import com.meenai.mina.core.usage.UsageRange
import com.meenai.mina.i18n.AppLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

import com.meenai.mina.about.UpdateState
import com.meenai.mina.about.CheckStatus
import android.widget.Toast

/** The subpages that can be entered in the settings; [Home] is the settings homepage. */
private enum class SettingsPage { Home, AppAccess, Usage, UsageLimits, PhoneOps, DuringWork, Capsule, Language, Notifications, WebSites, Sandbox, Routes, Recovery, SelfCheck, ComputerAccess, Developer, RunStats, ModelSpecialist }

/**
 * Settings page: General Status Card + Basic/Privacy & Data/Advanced/About four groups. Each sub-page is
 * switched here (the return key returns to the homepage first). The model page is handed over to
 * Activity by [onOpenModels], and the about page is handed over to Activity. The repair action is handed
 * over to Activity by [onFix] (requesting permissions, jumping to system settings).
 */
@Composable
fun SettingsScreen(
    store: ProviderStore,
    status: SystemStatus,
    androidRelease: String,
    onFix: (Fix) -> Unit,
    onBack: () -> Unit,
    onOpenModels: () -> Unit,
    onOpenMemory: (() -> Unit)? = null,
    onOpenVault: (() -> Unit)? = null,
    vaultCount: Int? = null,
    webSites: WebSitesState? = null,
    /** "Autonomous Mode" in Developer Options: Current value and how to change it. */
    autonomous: Boolean = false,
    onAutonomous: (Boolean) -> Unit = {},
    /** The new version number found; when it is found, the "About Mina" line says "x can be updated" with a red dot. */
    updateVersion: String? = null,
    onOpenAbout: () -> Unit = {},
    onCheckUpdates: (() -> Unit)? = null,
    updateState: UpdateState? = null,
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.Home) }
    var explaining by remember { mutableStateOf<Problem?>(null) }
    fun fix(f: Fix) = if (f == Fix.AddModel) onOpenModels() else onFix(f)
    val onProblem: (Problem) -> Unit = { p -> StatusCopy.directFix(p)?.let(::fix) ?: run { explaining = p } }

    val context = LocalContext.current
    var waitingRound by remember { mutableIntStateOf(-1) }
    LaunchedEffect(updateState?.manualRounds) {
        val s = updateState ?: return@LaunchedEffect
        if (waitingRound == s.manualRounds) {
            when {
                s.update != null -> {}
                s.status == CheckStatus.Failed -> Toast.makeText(context, AboutCopy.OFFLINE_TOAST, Toast.LENGTH_SHORT).show()
                s.status == CheckStatus.Done -> Toast.makeText(context, AboutCopy.UP_TO_DATE_TOAST, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // The upper limit of parallel tasks only appears when the experimental switch parallelTasks is
    // turned on; the device capability is calculated based on this moment, and if the second screen
    // fails to be built, it is calculated as failure.
    val flags = remember { FeatureFlags.get(context) }
    var parallelMax by remember { mutableIntStateOf(1) }
    var keepScreenOn by remember { mutableStateOf(true) }
    var muteTarget by remember { mutableStateOf(true) }
    var operatingPace by remember { mutableStateOf(OperatingPace.DEFAULT) }
    var selfConfig by remember { mutableStateOf(false) }
    var capsuleMode by remember { mutableStateOf(CapsuleMode.DEFAULT) }
    var language by remember { mutableStateOf(AppLanguage.System) }
    var speed by remember { mutableStateOf(SpeedFlags.OFF) }
    var isLowRam by remember { mutableStateOf(false) }
    val routeStore = remember { FileRouteStore.shared(context) }
    var routeCount by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val loadedParallelMax = flags.parallelMax()
            val loadedKeepOn = flags.keepScreenOn()
            val loadedMute = flags.muteTarget()
            val loadedPace = flags.operatingPace()
            val loadedSelfConfig = flags.selfConfig()
            val loadedCapsule = flags.capsuleMode()
            val loadedLang = AppLanguage.current(context)
            val loadedSpeed = flags.speedFlags()
            val loadedLowRam = context.getSystemService(ActivityManager::class.java)?.isLowRamDevice == true
            val loadedRoutes = runCatching { routeStore.count() }.getOrNull()
            withContext(Dispatchers.Main) {
                parallelMax = loadedParallelMax
                keepScreenOn = loadedKeepOn
                muteTarget = loadedMute
                operatingPace = loadedPace
                selfConfig = loadedSelfConfig
                capsuleMode = loadedCapsule
                language = loadedLang
                speed = loadedSpeed
                isLowRam = loadedLowRam
                routeCount = loadedRoutes
            }
        }
    }
    val sandboxState = remember { SandboxState.get(context) }
    // Network proxy: read settings and WebView proxy mode in the background so Keystore init stays off Main.
    var proxy by remember { mutableStateOf<String?>(null) }
    var browserProxy by remember { mutableStateOf<WebProxy.Mode?>(null) }
    var editingProxy by remember { mutableStateOf(false) }
    LaunchedEffect(proxy) {
        if (proxy == null) proxy = withContext(Dispatchers.IO) { NetProxyStore.get(context).get() }
        browserProxy = withContext(Dispatchers.IO) { WebProxy.mode() }
    }
    val app = remember(context) { context.applicationContext as com.meenai.mina.HarnessApp }
    val mcp by produceState(if (app.mcpReady) app.mcp else null, app) {
        if (value == null) value = withContext(Dispatchers.IO) { app.mcp }
    }
    val usage = app.usage
    // Developer options are only listed in the debug version and performance test version: the default
    // values of each switch in the release version are determined based on the evaluation results.
    val showDeveloper = com.meenai.mina.record.DetailedTraces.supportedBuild
    val toHome = { page = SettingsPage.Home }
    // The notification page can be entered from the home page or mobile operation page, and returned to
    // the page from which it was entered.
    var notificationsFrom by rememberSaveable { mutableStateOf(SettingsPage.Home) }
    val back = {
        page = when (page) {
            SettingsPage.RunStats -> SettingsPage.Developer
            SettingsPage.SelfCheck -> SettingsPage.Recovery
            SettingsPage.Notifications -> notificationsFrom
            else -> SettingsPage.Home
        }
    }

    if (page != SettingsPage.Home) BackHandler { back() }

    val visiblePage = if (!showDeveloper && (page == SettingsPage.Developer || page == SettingsPage.RunStats)) SettingsPage.Home else page
    when (visiblePage) {
        SettingsPage.Home -> SettingsContent(
            status = status, androidRelease = androidRelease,
            mainModel = null,
            onBack = onBack, onProblem = onProblem, onOpenModels = onOpenModels,
            proxy = proxy, proxyNote = proxy?.let { NetProxyCopy.subtitle(it, browserProxy) }, onOpenProxy = { editingProxy = true },
            usageTokens = null, onOpenUsage = { page = SettingsPage.Usage },
            usageToday = null, onOpenUsageLimits = { page = SettingsPage.UsageLimits },
            onOpenPhoneOps = { page = SettingsPage.PhoneOps },
            onOpenAppAccess = { page = SettingsPage.AppAccess },
            onOpenDuringWork = { page = SettingsPage.DuringWork },
            capsuleMode = capsuleMode, onOpenCapsule = { page = SettingsPage.Capsule },
            onOpenNotifications = { notificationsFrom = SettingsPage.Home; page = SettingsPage.Notifications },
            language = language, onOpenLanguage = { page = SettingsPage.Language },
            onOpenMemory = onOpenMemory,
            onOpenVault = onOpenVault, vaultCount = vaultCount,
            webSitesCount = webSites?.sites?.size, onOpenWebSites = { page = SettingsPage.WebSites },
            sandbox = null, onOpenSandbox = { page = SettingsPage.Sandbox },
            routes = routeCount, onOpenRoutes = { page = SettingsPage.Routes },
            onOpenRecovery = { page = SettingsPage.Recovery },
            computerAccess = null, onOpenComputerAccess = { page = SettingsPage.ComputerAccess },
            showDeveloper = showDeveloper, onOpenDeveloper = { page = SettingsPage.Developer },
            versionName = BuildConfig.VERSION_NAME, updateVersion = updateVersion, onOpenAbout = onOpenAbout,
            onCheckUpdates = onCheckUpdates?.let { fn -> { waitingRound = (updateState?.manualRounds ?: 0) + 1; fn() } },
            updateState = updateState,
            onOpenSpecialist = { page = SettingsPage.ModelSpecialist },
            store = store,
            usage = usage,
            sandboxFlow = sandboxState.status,
            mcpNetworkFlow = mcp?.settings?.enabled,
            mcpUsbFlow = mcp?.usb?.enabled,
        )
        SettingsPage.AppAccess -> AppAccessScreen(onBack = toHome)
        SettingsPage.Usage -> UsageScreen(usage, store.providers, onBack = toHome)
        SettingsPage.UsageLimits -> UsageLimitsScreen(usage, store, onBack = toHome)
        SettingsPage.PhoneOps -> PhoneOpsScreen(
            status, androidRelease, onProblem,
            onOpenNotifications = { notificationsFrom = SettingsPage.PhoneOps; page = SettingsPage.Notifications },
            onRepair = { onFix(Fix.RepairPhoneHelper) }, onUnpair = { onFix(Fix.UnpairPhoneHelper) }, onBack = toHome,
        )
        SettingsPage.Notifications -> NotificationsScreen(status, onFix = ::fix, onBack = back)
        SettingsPage.DuringWork -> DuringWorkHost(
            parallelLimitFlow = app.taskState.parallelLimit,
            sdkInt = status.sdkInt,
            isLowRam = isLowRam,
            parallelTasksEnabled = speed.parallelTasks,
            parallelMax = parallelMax,
            onParallelMax = { flags.setParallelMax(it); parallelMax = it },
            keepScreenOn = keepScreenOn,
            onKeepScreenOn = { flags.setKeepScreenOn(it); keepScreenOn = it },
            muteTarget = muteTarget,
            onMuteTarget = { flags.setMuteTarget(it); muteTarget = it },
            operatingPace = operatingPace,
            onOperatingPace = { flags.setOperatingPace(it); operatingPace = it },
            selfConfig = selfConfig,
            onSelfConfig = { flags.setSelfConfig(it); selfConfig = it },
            onBack = toHome,
        )
        SettingsPage.Language -> LanguageScreen(language, onLanguage = { AppLanguage.set(context, it) }, onBack = toHome)
        SettingsPage.Capsule -> CapsuleScreen(capsuleMode, onMode = { flags.setCapsuleMode(it); capsuleMode = it }, onBack = toHome)
        // When there is no data, the homepage does not display the entrance and you cannot reach this
        // page.
        SettingsPage.WebSites -> webSites?.let { WebSitesScreen(it, onBack = toHome) }
        SettingsPage.Sandbox -> SandboxScreen(onBack = toHome)
        SettingsPage.Routes -> RoutesScreen(
            routeCount,
            onClear = { scope.launch { routeCount = withContext(Dispatchers.IO) { routeStore.clear(); routeStore.count() } } },
            onBack = toHome,
        )
        SettingsPage.Recovery -> RecoveryScreen(onBack = toHome, onOpenSelfCheck = { page = SettingsPage.SelfCheck })
        SettingsPage.SelfCheck -> SelfCheckScreen(status, onBack = back)
        SettingsPage.ComputerAccess -> mcp?.let { McpScreen(it, onBack = toHome) }
        SettingsPage.Developer -> DeveloperOptionsHost(
            store = store,
            speed = speed,
            onSpeed = { flags.setSpeed(it); speed = it },
            onBack = toHome,
            autonomous = autonomous,
            onAutonomous = onAutonomous,
            onOpenRunStats = { page = SettingsPage.RunStats },
        )
        SettingsPage.RunStats -> RunStatsHost(onBack = back)
        SettingsPage.ModelSpecialist -> com.meenai.mina.ui.router.ModelSpecialistScreen(store = store, onBack = toHome)
    }

    if (editingProxy) NetProxyDialog(
        initial = proxy.orEmpty(),
        onDismiss = { editingProxy = false },
        onSave = { v ->
            scope.launch {
                withContext(Dispatchers.IO) { NetProxyStore.get(context).set(v) }
                proxy = v.trim()
                editingProxy = false
            }
        },
    )

    explaining?.let { p ->
        val e = StatusCopy.explain(p, androidRelease)
        AlertDialog(
            onDismissRequest = { explaining = null },
            title = { Text(e.title) },
            text = { Text(e.body) },
            confirmButton = {
                if (e.action != null && e.fix != null) {
                    TextButton({ explaining = null; fix(e.fix) }) { Text(e.action) }
                } else {
                    TextButton({ explaining = null }) { Text(stringResource(R.string.ok_got_it)) }
                }
            },
            dismissButton = if (e.fix != null) ({ TextButton({ explaining = null }) { Text(stringResource(R.string.cancel)) } }) else null,
        )
    }
}

/**
 * Set the homepage without status for easy testing. When the optional entry ([usageTokens],
 * [onOpenMemory], [webSitesCount], [grantsCount], [routes], [sandbox]) is empty, the corresponding row
 * will not be displayed; when [showDeveloper] is false (release version), there is no developer option.
 */
@Composable
fun SettingsContent(
    status: SystemStatus,
    androidRelease: String,
    /** The name of the current main model; empty if no main model is available. */
    mainModel: String?,
    onBack: () -> Unit,
    onProblem: (Problem) -> Unit,
    onOpenModels: () -> Unit,
    /** Network proxy (empty string means not used); this line will not be displayed when it is empty (it has not been read out yet). */
    proxy: String? = null,
    /** Description below the network proxy line. */
    proxyNote: String? = null,
    onOpenProxy: () -> Unit = {},
    /** How many tokens have been used in the past 7 days; if it is empty (not yet calculated), this line will not be displayed. */
    usageTokens: Long? = null,
    onOpenUsage: () -> Unit = {},
    /** How many requests were sent today; if it is empty (not yet calculated), the "Usage Limit" line will not be displayed. */
    usageToday: Int? = null,
    onOpenUsageLimits: () -> Unit = {},
    onOpenPhoneOps: () -> Unit = {},
    onOpenAppAccess: () -> Unit = {},
    onOpenDuringWork: () -> Unit = {},
    /** The current selection of the suspended capsule is written to the right of the "suspended capsule" line. */
    capsuleMode: CapsuleMode = CapsuleMode.DEFAULT,
    onOpenCapsule: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    /** The interface language selected at this time is written to the right of the "Language" line. */
    language: AppLanguage = AppLanguage.System,
    onOpenLanguage: () -> Unit = {},
    onOpenMemory: (() -> Unit)? = null,
    onOpenVault: (() -> Unit)? = null,
    vaultCount: Int? = null,
    webSitesCount: Int? = null,
    onOpenWebSites: () -> Unit = {},
    sandbox: SandboxStatus? = null,
    onOpenSandbox: () -> Unit = {},
    /** The number of memorized operation routes; if it is empty (not read yet), this line will not be displayed. */
    routes: Int? = null,
    onOpenRoutes: () -> Unit = {},
    onOpenRecovery: () -> Unit = {},
    /** Computer connection (MCP) is turned on or not; this line will not be displayed when it is empty. */
    computerAccess: Boolean? = null,
    onOpenComputerAccess: () -> Unit = {},
    showDeveloper: Boolean = false,
    onOpenDeveloper: () -> Unit = {},
    versionName: String = "",
    updateVersion: String? = null,
    onOpenAbout: () -> Unit = {},
    onCheckUpdates: (() -> Unit)? = null,
    updateState: UpdateState? = null,
    onOpenSpecialist: () -> Unit = {},
    store: ProviderStore? = null,
    usage: UsageLedger? = null,
    sandboxFlow: StateFlow<SandboxStatus>? = null,
    mcpNetworkFlow: StateFlow<Boolean>? = null,
    mcpUsbFlow: StateFlow<Boolean>? = null,
) {
    val notifSummary = remember(status) { StatusCopy.notificationSummary(status.notificationOverview().summary) }
    val privacyRows = onOpenMemory != null || webSitesCount != null || onOpenVault != null
    LazyPageScaffold(stringResource(R.string.settings_title), onBack) {
        item(key = "status", contentType = "status") {
            SettingsStatusSummarySection(status = status, store = store, fallbackMainModel = mainModel, onProblem = onProblem)
        }

        item(key = "basic-label", contentType = "label") {
            GroupLabel(stringResource(R.string.settings_basic))
        }
        item(key = "basic-card", contentType = "card") {
            GroupCard {
                SettingsModelRow(store = store, fallbackMainModel = mainModel, onOpenModels = onOpenModels)
                ListRow(
                    MinaIcons.Spark,
                    stringResource(R.string.model_specialist_title),
                    stringResource(R.string.model_specialist_sub),
                    onClick = onOpenSpecialist,
                ) {
                    RowValue(stringResource(R.string.router_mode_auto))
                    RowChevron()
                }
                // Adjust whether the connection of the model should use a proxy. You can find it together
                // with the model; containers, updates, and built-in browsers can also be found here.
                proxy?.let {
                    ListRow(MinaIcons.Link, NetProxyCopy.TITLE, proxyNote, onClick = onOpenProxy) {
                        RowValue(NetProxyCopy.value(it))
                        RowChevron()
                    }
                }
                SettingsUsageRows(
                    store = store,
                    usage = usage,
                    fallbackUsageTokens = usageTokens,
                    fallbackUsageToday = usageToday,
                    onOpenUsage = onOpenUsage,
                    onOpenUsageLimits = onOpenUsageLimits,
                )
                ListRow(MinaIcons.Phone, stringResource(R.string.settings_phone_ops), onClick = onOpenPhoneOps) {
                    PhoneOpsPill(status)
                    RowChevron()
                }
                ListRow(MinaIcons.Phone, stringResource(R.string.appaccess_title), stringResource(R.string.appaccess_row_sub), onClick = onOpenAppAccess) { RowChevron() }
                ListRow(MinaIcons.Moon, stringResource(R.string.settings_during_work), stringResource(R.string.settings_during_work_sub), onClick = onOpenDuringWork) { RowChevron() }
                ListRow(MinaIcons.Message, CapsuleSettingsCopy.TITLE, CapsuleSettingsCopy.SUBTITLE, onClick = onOpenCapsule) {
                    RowValue(CapsuleSettingsCopy.label(capsuleMode))
                    RowChevron()
                }
                ListRow(MinaIcons.Globe, stringResource(R.string.language_title), onClick = onOpenLanguage) {
                    RowValue(languageLabel(language))
                    RowChevron()
                }
                val (notifText, notifTone) = notifSummary
                ListRow(MinaIcons.Bell, StatusCopy.NOTIFICATIONS, stringResource(R.string.settings_notif_sub), onClick = onOpenNotifications) {
                    RowValue(notifText, notifTone)
                    RowChevron()
                }
            }
        }

        if (privacyRows) {
            item(key = "privacy-label", contentType = "label") {
                GroupLabel(stringResource(R.string.settings_privacy))
            }
            item(key = "privacy-card", contentType = "card") {
                GroupCard {
                    var first = true
                    fun take(): Boolean = first.also { first = false }
                    onOpenMemory?.let {
                        ListRow(MinaIcons.Heart, stringResource(R.string.memory_and_skills), stringResource(R.string.settings_memory_sub), first = take(), onClick = it) { RowChevron() }
                    }
                    onOpenVault?.let {
                        ListRow(MinaIcons.Lock, stringResource(R.string.settings_vault), stringResource(R.string.settings_vault_sub), first = take(), onClick = it) {
                            vaultCount?.let { count -> RowValue(trPlural(R.plurals.settings_count_vault, count)) }
                            RowChevron()
                        }
                    }
                    webSitesCount?.let {
                        ListRow(MinaIcons.Globe, stringResource(R.string.settings_web_logins), first = take(), onClick = onOpenWebSites) { RowValue(trPlural(R.plurals.settings_count_sites, it)); RowChevron() }
                    }
                }
            }
        }

        item(key = "advanced-section", contentType = "advanced-section") {
            SettingsAdvancedSection(
                status = status,
                sandbox = sandbox,
                sandboxFlow = sandboxFlow,
                routes = routes,
                onOpenRoutes = onOpenRoutes,
                onOpenSandbox = onOpenSandbox,
                onOpenRecovery = onOpenRecovery,
                computerAccess = computerAccess,
                mcpNetworkFlow = mcpNetworkFlow,
                mcpUsbFlow = mcpUsbFlow,
                onOpenComputerAccess = onOpenComputerAccess,
                showDeveloper = showDeveloper,
                onOpenDeveloper = onOpenDeveloper,
            )
        }

        item(key = "about-card", contentType = "card") {
            GroupCard {
                if (onCheckUpdates != null) {
                    ListRow(
                        MinaIcons.Refresh,
                        stringResource(R.string.about_check),
                        first = true,
                        onClick = onCheckUpdates,
                    ) {
                        if (updateState != null) {
                            TrailingValue(AboutCopy.checkValue(updateState))
                        }
                        RowChevron()
                    }
                }
                ListRow(
                    MinaIcons.Info,
                    stringResource(R.string.settings_about),
                    first = onCheckUpdates == null,
                    onClick = onOpenAbout,
                ) {
                    TrailingValue(AboutCopy.settingsValue(updateVersion, versionName))
                    RowChevron()
                }
            }
        }
    }
}

@Composable
private fun DuringWorkHost(
    parallelLimitFlow: StateFlow<ParallelLimit>,
    sdkInt: Int,
    isLowRam: Boolean,
    parallelTasksEnabled: Boolean,
    parallelMax: Int,
    onParallelMax: (Int) -> Unit,
    keepScreenOn: Boolean,
    onKeepScreenOn: (Boolean) -> Unit,
    muteTarget: Boolean,
    onMuteTarget: (Boolean) -> Unit,
    operatingPace: OperatingPace,
    onOperatingPace: (OperatingPace) -> Unit,
    selfConfig: Boolean,
    onSelfConfig: (Boolean) -> Unit,
    onBack: () -> Unit,
) {
    val runtimeLimit by parallelLimitFlow.collectAsState()
    val parallel = if (!parallelTasksEnabled) null else ParallelSetting(
        parallelMax,
        ParallelCapability.detect(
            sdkInt,
            isLowRam,
            parallelMax,
            secondDisplayFailed = runtimeLimit.reason == ParallelCapability.SECOND_DISPLAY_FAILED,
        ),
    )
    DuringWorkScreen(
        keepScreenOn,
        onKeepScreenOn = onKeepScreenOn,
        muteTarget,
        onMuteTarget = onMuteTarget,
        parallel = parallel,
        onParallelMax = onParallelMax,
        onBack = onBack,
        operatingPace = operatingPace,
        onOperatingPace = onOperatingPace,
        selfConfig = selfConfig,
        onSelfConfig = onSelfConfig,
    )
}

@Composable
private fun DeveloperOptionsHost(
    store: ProviderStore,
    speed: SpeedFlags,
    onSpeed: (SpeedFlags) -> Unit,
    onBack: () -> Unit,
    autonomous: Boolean,
    onAutonomous: (Boolean) -> Unit,
    onOpenRunStats: () -> Unit,
) {
    val configs by store.configs.collectAsState()
    val roles by store.roles.collectAsState()
    // "View image positioning verified" in the developer options follows the image model (when using the
    // main model to view images, it is the configuration of the main model)
    val visionConfig = remember(configs, roles) { store.configFor(ModelRole.Vision) }
    val scope = rememberCoroutineScope()
    DeveloperOptionsScreen(
        speed = speed,
        onSpeed = onSpeed,
        onBack = onBack,
        autonomous = autonomous,
        onAutonomous = onAutonomous,
        grounding = visionConfig?.let { GroundingSetting(it.model, it.capabilities.groundingVerified) },
        onGrounding = { verified -> scope.launch { withContext(Dispatchers.IO) { store.setVisionGroundingVerified(verified) } } },
        onOpenRunStats = onOpenRunStats,
    )
}

@Composable
private fun RunStatsHost(onBack: () -> Unit) {
    val context = LocalContext.current
    var metricsStore by remember(context) { mutableStateOf<RunMetricsStore?>(null) }
    LaunchedEffect(context) {
        metricsStore = withContext(Dispatchers.IO) { RunStore(context).metrics }
    }
    val store = metricsStore
    if (store != null) {
        RunStatsScreen(store, onBack = onBack)
    } else {
        RunStatsContent(null, 1, {}, {}, {}, onBack = onBack, loaded = false)
    }
}

@Composable
private fun rememberMainModelName(
    store: ProviderStore?,
    fallbackMainModel: String?,
): String? {
    if (store == null) return fallbackMainModel
    val configs by store.configs.collectAsState()
    val roles by store.roles.collectAsState()
    return remember(configs, roles) {
        roles.main?.let { id -> configs.firstOrNull { it.id == id } }?.let(ProviderConnection::displayName)
    }
}

@Composable
private fun SettingsStatusSummarySection(
    status: SystemStatus,
    store: ProviderStore?,
    fallbackMainModel: String?,
    onProblem: (Problem) -> Unit,
) {
    val activeMainModel = rememberMainModelName(store, fallbackMainModel)
    val check = remember(status, activeMainModel) { Prerequisites.check(status, hasMainModel = activeMainModel != null) }
    StatusSummary(check, onProblem)
}

@Composable
private fun SettingsModelRow(
    store: ProviderStore?,
    fallbackMainModel: String?,
    onOpenModels: () -> Unit,
) {
    val activeMainModel = rememberMainModelName(store, fallbackMainModel)
    ListRow(MinaIcons.Spark, stringResource(R.string.settings_model), first = true, onClick = onOpenModels) {
        RowValue(activeMainModel ?: stringResource(R.string.settings_not_set))
        RowChevron()
    }
}

@Composable
private fun SettingsUsageRows(
    store: ProviderStore?,
    usage: UsageLedger?,
    fallbackUsageTokens: Long?,
    fallbackUsageToday: Int?,
    onOpenUsage: () -> Unit,
    onOpenUsageLimits: () -> Unit,
) {
    val (tokens, today) = if (store != null && usage != null) {
        val providerList by store.providers.collectAsState()
        val usageVersion by usage.version.collectAsState()
        var computedWeek by remember { mutableStateOf<Long?>(null) }
        var computedToday by remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(usageVersion, providerList) {
            val (week, day) = withContext(Dispatchers.Default) {
                val now = Instant.now()
                val snapshot = usage.snapshot()
                val weekTokens = UsageQuery.report(snapshot, UsageRange.LastDays(7), now, ZoneId.systemDefault(), emptyMap()).totals.tokens
                val gemini = providerList.firstOrNull {
                    it.kind == ProviderConfig.Kind.GeminiWeb || it.signIn == ProviderStore.GEMINI_WEB_SIGN_IN
                }?.id
                val dayCalls = UsageLimitsQuery.report(snapshot, gemini, now, ZoneId.of("Asia/Kolkata")).dailyCalls.toInt()
                weekTokens to dayCalls
            }
            computedWeek = week
            computedToday = day
        }
        computedWeek to computedToday
    } else {
        fallbackUsageTokens to fallbackUsageToday
    }
    tokens?.let {
        ListRow(MinaIcons.Chart, UsageCopy.TITLE, onClick = onOpenUsage) {
            RowValue(UsageCopy.settingsValue(it))
            RowChevron()
        }
    }
    today?.let {
        ListRow(MinaIcons.Gauge, stringResource(R.string.usage_limits_title), onClick = onOpenUsageLimits) {
            RowValue(stringResource(R.string.usage_limits_today_value, it))
            RowChevron()
        }
    }
}

@Composable
private fun SettingsAdvancedSection(
    status: SystemStatus,
    sandbox: SandboxStatus?,
    sandboxFlow: StateFlow<SandboxStatus>?,
    routes: Int?,
    onOpenRoutes: () -> Unit,
    onOpenSandbox: () -> Unit,
    onOpenRecovery: () -> Unit,
    computerAccess: Boolean?,
    mcpNetworkFlow: StateFlow<Boolean>?,
    mcpUsbFlow: StateFlow<Boolean>?,
    onOpenComputerAccess: () -> Unit,
    showDeveloper: Boolean,
    onOpenDeveloper: () -> Unit,
) {
    val activeSandbox = if (sandboxFlow != null) sandboxFlow.collectAsState().value else sandbox
    val activeComputerAccess = if (mcpNetworkFlow != null && mcpUsbFlow != null) {
        val netOn by mcpNetworkFlow.collectAsState()
        val usbOn by mcpUsbFlow.collectAsState()
        netOn || usbOn
    } else {
        computerAccess
    }
    val sandboxRow = activeSandbox?.takeIf { it !is SandboxStatus.Unsupported }
    val recoveryRow = status.sdkInt >= Prerequisites.MIN_SDK
    if (sandboxRow != null || routes != null || recoveryRow || activeComputerAccess != null || showDeveloper) {
        GroupLabel(stringResource(R.string.settings_advanced))
        GroupCard {
            var first = true
            fun take(): Boolean = first.also { first = false }
            if (sandboxRow != null) {
                ListRow(MinaIcons.Terminal, stringResource(R.string.settings_sandbox), first = take(), onClick = onOpenSandbox) {
                    RowValue(sandboxValue(sandboxRow)); RowChevron()
                }
            }
            routes?.let {
                ListRow(MinaIcons.Route, stringResource(R.string.settings_routes), first = take(), onClick = onOpenRoutes) { RowValue(trPlural(R.plurals.settings_count_routes, it)); RowChevron() }
            }
            if (recoveryRow) {
                ListRow(MinaIcons.Wrench, stringResource(R.string.settings_recovery), first = take(), onClick = onOpenRecovery) { RowChevron() }
            }
            activeComputerAccess?.let { on ->
                ListRow(MinaIcons.Desktop, stringResource(R.string.mcp_title), first = take(), onClick = onOpenComputerAccess) {
                    RowValue(stringResource(if (on) R.string.state_on else R.string.state_off)); RowChevron()
                }
            }
            if (showDeveloper) {
                ListRow(MinaIcons.Code, stringResource(R.string.settings_developer), first = take(), onClick = onOpenDeveloper) { RowChevron() }
            }
        }
    }
}

/** Status capsule at the end of the "Mobile Operation" line: "Available" only when accessibility and built-in assistants are ready. */
@Composable
private fun PhoneOpsPill(status: SystemStatus) {
    when {
        status.sdkInt < Prerequisites.MIN_SDK -> StatusPill(stringResource(R.string.pill_unsupported), PillTone.Neutral)
        status.builtinShell == BuiltinShellStatus.Running && status.accessibility == AccessibilityState.On -> StatusPill(stringResource(R.string.pill_available), PillTone.Ok)
        else -> StatusPill(stringResource(R.string.pill_needs_attention), PillTone.Warn)
    }
}

private fun sandboxValue(s: SandboxStatus): String = when (s) {
    is SandboxStatus.Ready -> tr(R.string.sandbox_ready)
    is SandboxStatus.Installing -> tr(R.string.sandbox_preparing)
    is SandboxStatus.Broken -> tr(R.string.pill_needs_attention)
    else -> tr(R.string.sandbox_not_prepared)
}

/**
 * The overall status at the top of the page: If everything is normal, it means "everything is ready";
 * otherwise, it becomes a prompt for the first problem with a repair button. The focus reminder cannot
 * be changed. It is only prompted in the "System Version" line of the mobile phone operation page and
 * does not occupy this space.
 */
@Composable
private fun StatusSummary(check: Prerequisites.Check, onProblem: (Problem) -> Unit) {
    val problem = check.problems.firstOrNull { it != Problem.FocusMayLeak }
    if (problem == null) {
        StateCard(MinaIcons.Check, stringResource(R.string.all_ready), stringResource(R.string.settings_all_ready_detail), TileTone.Ok, Modifier.testTag("settings-status"))
    } else {
        val copy = StatusCopy.banner(problem)
        StateCard(
            MinaIcons.Exclaim, copy.title, copy.detail,
            if (problem in check.blockers) TileTone.Bad else TileTone.Warn,
            Modifier.testTag("settings-status"),
        ) {
            MinaButton(copy.link, { onProblem(problem) }, kind = ButtonKind.Tonal, size = ButtonSize.Small)
        }
    }
}
