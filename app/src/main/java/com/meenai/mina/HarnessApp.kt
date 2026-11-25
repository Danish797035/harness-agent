package com.meenai.mina

import com.meenai.mina.record.DetailedTraces
import com.meenai.mina.i18n.ResourceTexts
import com.meenai.mina.i18n.UiText
import com.meenai.mina.i18n.AppLanguage
import android.app.Application
import android.net.Uri
import android.util.Log
import com.meenai.mina.chat.ConversationDeleter
import com.meenai.mina.chat.ConversationStore
import com.meenai.mina.core.cap.sandbox.SandboxStatus
import com.meenai.mina.cap.sandbox.AndroidSandbox
import com.meenai.mina.cap.sandbox.Attachments
import com.meenai.mina.ui.AttachFiles
import com.meenai.mina.cap.sandbox.ShareExporter
import com.meenai.mina.cap.web.WebFiles
import com.meenai.mina.cap.sandbox.RuntimePreparer
import com.meenai.mina.cap.sandbox.SandboxDirs
import com.meenai.mina.cap.sandbox.SandboxState
import com.meenai.mina.cap.sandbox.SkillsSync
import com.meenai.mina.capsule.AppForeground
import com.meenai.mina.capsule.CapsuleController
import com.meenai.mina.confirm.ConfirmationNotifier
import com.meenai.mina.memory.AppLearningActions
import com.meenai.mina.memory.MemoryRuntime
import com.meenai.mina.settings.ApprovalControl
import com.meenai.mina.settings.ApprovalStore
import com.meenai.mina.settings.FeatureFlags
import com.meenai.mina.cap.phone.PhoneServices
import com.meenai.mina.replay.FileRouteStore
import com.meenai.mina.settings.ProviderStore
import com.meenai.mina.core.run.Outbox
import com.meenai.mina.core.run.RunStore
import com.meenai.mina.core.usage.UsageLedger
import com.meenai.mina.task.FileOutboxStore
import com.meenai.mina.task.FileRunStateStore
import com.meenai.mina.task.LocalChannel
import com.meenai.mina.task.RunRecovery
import com.meenai.mina.task.TaskRecovery
import com.meenai.mina.task.TriggerOutcomes
import com.meenai.mina.task.TaskService
import com.meenai.mina.task.TaskCenterState
import com.meenai.mina.ui.ChatViewModel
import com.meenai.mina.ui.TaskActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.meenai.mina.cap.web.cdp.WebDevtools
import com.meenai.mina.cap.web.WebOwner
import com.meenai.mina.cap.web.SiteRegistry
import com.meenai.mina.cap.sandbox.SandboxOwner
import com.meenai.mina.core.workspace.SandboxTree
import com.meenai.mina.task.TaskEnvironment
import com.meenai.mina.task.ResourceScheduler
import com.meenai.mina.task.RemoteTaskHub
import com.meenai.mina.task.FileChildRunStore
import com.meenai.mina.task.TriggerRuntime

/**
 * App container: All services shared in the process are created here and handed over to the Android
 * portal (interface, service, broadcast, provider) through their respective Owner interfaces, and the
 * rest of the code is obtained through construction parameters. The helper process (HelperMain,
 * ShellService) does not have this object and does not use any services here.
 */
class HarnessApp : Application(), WebOwner, SandboxOwner, TaskEnvironment.Owner, PhoneServices.Owner,
    MemoryRuntime.Owner, ProviderStore.Owner, FileRouteStore.Owner, TaskRecovery.Owner,
    com.meenai.mina.vault.PasswordVaultStore.Owner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Work that must outlive a screen: the post-login Gemini test message runs
     * here so leaving Model Setup does not cancel it.
     */
    val appScope: CoroutineScope get() = scope

    /** Local password vault: independent of API keys, using harness-password-vault Keystore alias */
    override val vault: com.meenai.mina.vault.PasswordVaultStore by lazy { com.meenai.mina.vault.PasswordVaultStore.create(this) }

    /** Phone-capable services: built-in assistant, shared shell connection, pairing, "go to settings" prompt. */
    override val phone: PhoneServices by lazy { PhoneServices(this) }

    /** DevTools session entry for web page tags: one copy of the entire process; if the connection cannot be made, the built-in assistant can be used to connect on behalf of the user. */
    override val webDevtools: WebDevtools by lazy { WebDevtools(phone.hub::openDevtoolsSocket) }

    /** The upper limit of the total number of tags shared by the tag pools of each task. */
    val resources = ResourceScheduler()
    val detailedTraces by lazy { DetailedTraces(this) }

    val remoteTasks by lazy { RemoteTaskHub(this) }

    /**
     * Computer connection (MCP): switch, access token (encrypted and stored like the service provider's
     * Key), monitoring. The LAN type is closed by default; the USB type is opened by the computer via
     * adb until it is shut down this time (the boot number is the same as `settings get global
     * boot_count` read in the shell).
     */
    val mcp by lazy {
        val settingsDir = java.io.File(filesDir, "settings")
        com.meenai.mina.mcp.McpRuntime(this,
            com.meenai.mina.settings.McpSettings(settingsDir, com.meenai.mina.settings.KeystoreKeyCipher("mina-mcp-token")),
            com.meenai.mina.settings.McpUsbSettings(settingsDir,
                bootId = { android.provider.Settings.Global.getString(contentResolver, android.provider.Settings.Global.BOOT_COUNT) ?: "null" },
                infoFile = { getExternalFilesDir(null)?.let { java.io.File(it, com.meenai.mina.core.mcp.McpUsb.INFO_FILE) } }),
            remoteTasks, com.meenai.mina.device.AndroidDeviceAccess(this) { phone.hub.shell.value },
            com.meenai.mina.settings.RelaySettings(settingsDir, com.meenai.mina.settings.KeystoreKeyCipher("mina-remote-relay")))
    }
    val childRuns by lazy { FileChildRunStore(java.io.File(filesDir, "children-v3.json")) }
    override val tabBudget get() = resources.tabBudget

    /** The page that stays in the background and continues to run after the task is completed; when it is closed, write down the reason in the round of the source dialogue ("Background web page has been closed" is displayed below the round of the dialogue page). */
    override val pageKeeper: com.meenai.mina.cap.web.PageKeeper by lazy {
        com.meenai.mina.cap.web.PageKeeper(this, tabBudget).also { k ->
            k.onClosed = { p, reason ->
                p.runId?.let { runId ->
                    scope.launch(Dispatchers.IO) {
                        runCatching { conversations.updateTurn(p.conversationId, runId) { it.copy(keptPageClosed = reason.wire) } }
                            .onFailure { android.util.Log.w("HarnessApp", "could not note the kept page closing", it) }
                    }
                }
            }
        }
    }

    /** Memory, personal skills, switch and outcome card prompts. */
    override val memory: MemoryRuntime by lazy { MemoryRuntime(this) }

    /** Native token usage ledger: Every call of each model created through model configuration is recorded here. */
    val usage: UsageLedger by lazy { UsageLedger(java.io.File(filesDir, "usage-v1.json")) }

    /** Model configuration and Key; the built models are all set with measurement and recorded as [usage]. */
    override val providers: ProviderStore by lazy { ProviderStore.create(this, usage, "HarnessAgent/${BuildConfig.VERSION_NAME}") }

    /** Speech-to-text selection: There is one copy of the entire process, and the input box can be seen immediately after changing the setting page (the two instances cannot see each other's set). */
    val speech: com.meenai.mina.voice.SpeechSettings by lazy { com.meenai.mina.voice.SpeechSettings.get(this) }

    /** Image generation and editing settings. */
    val imageSettings: com.meenai.mina.image.ImageSettings by lazy { com.meenai.mina.image.ImageSettings.get(this) }

    /** Image generation and editing service. */
    val imageService: com.meenai.mina.core.image.ImageGenerationService by lazy {
        val remoteHarnessClient = com.meenai.mina.core.image.HarnessImageClient(
            baseUrlSupplier = { imageSettings.getHarnessBaseUrl() },
            logger = { msg -> Log.d("ImageGen", msg) }
        )
        val nativeArenaClient = com.meenai.mina.image.NativeArenaImageClient(
            context = this,
            logger = { msg -> Log.d("ImageGen", msg) }
        )
        val harnessClient = com.meenai.mina.image.CompositeArenaImageClient(
            remoteClient = remoteHarnessClient,
            nativeClient = nativeArenaClient,
            logger = { msg -> Log.d("ImageGen", msg) }
        )
        val modelScopeClient = com.meenai.mina.core.image.ModelScopeImageClient(
            baseUrl = "https://api-inference.modelscope.ai",
            logger = { msg -> Log.d("ImageGen", msg) }
        )
        val openRouterClient = com.meenai.mina.core.image.OpenRouterImageClient(
            baseUrl = "https://openrouter.ai/api/v1",
            logger = { msg -> Log.d("ImageGen", msg) }
        )
        val pollinationsClient = com.meenai.mina.core.image.PollinationsImageClient(
            logger = { msg -> Log.d("ImageGen", msg) }
        )
        com.meenai.mina.core.image.ImageGenerationService(
            modelScopeClient = modelScopeClient,
            openRouterClient = openRouterClient,
            harnessClient = harnessClient,
            pollinationsClient = pollinationsClient,
            getApiKey = { provider ->
                when (provider.lowercase()) {
                    "modelscope" -> com.meenai.mina.ui.router.AppFreeKeyRouter.resolveModelScopeKey(providers)
                    "openrouter" -> com.meenai.mina.ui.router.AppFreeKeyRouter.resolveOpenRouterKey(providers)
                    else -> providers.providers.value.firstOrNull { it.id.equals(provider, ignoreCase = true) }?.let { providers.providerKey(it.id) }
                }
            },
            getPinnedModel = { role -> imageSettings.getPinnedModel(role) },
            isPaidAllowed = { imageSettings.isPaidAllowed() },
            logger = { msg -> Log.d("ImageGen", msg) }
        )
    }

    /** The voice input entrance, input box and floating capsule are shared. */
    val voice: com.meenai.mina.voice.VoiceInput by lazy { com.meenai.mina.voice.VoiceInput(this, speech, providers) }

    /** Log in with ChatGPT: run in the scope of the App, and open the foreground service to support the process during this period (see [com.meenai.mina.settings.ChatGptSignIns]). */
    val chatGptSignIn by lazy {
        com.meenai.mina.settings.ChatGptSignIns(scope,
            keepAlive = { com.meenai.mina.settings.ChatGptSignInService.start(this) },
            log = { msg, e -> Log.w("MinaChatGpt", msg, e) })
    }

    /** Experience route. */
    override val routes: FileRouteStore by lazy { FileRouteStore.create(this) }

    /** Recovery of system changes left after abnormal exit; running tasks are registered here. */
    override val taskRecovery: TaskRecovery by lazy { TaskRecovery(this, phone.sharedShell) }

    /** Running ledger and delivery ledger: one copy for the entire process, shared by the task service and recovery at startup (the same file cannot have two instances). */
    val runStore: RunStore by lazy { FileRunStateStore(java.io.File(filesDir, "runs-v3.json")) }
    val outbox: Outbox by lazy { Outbox(FileOutboxStore(java.io.File(filesDir, "outbox-v3.json"))) }

    /** The aftermath of the unfinished operation (standard interrupt, supplementary delivery, notification): only do it once in the process, and it will be called when the App starts and the task service starts. */
    val runRecovery: RunRecovery by lazy {
        val app = this
        RunRecovery(runStore, outbox, LocalChannel { _, delivery ->
            TaskService.ensureChannels(app)
            TaskService.notifyDelivery(app, delivery, TaskService.nextResultId())
        }, object : TriggerOutcomes {
            override fun interrupted(key: String, reason: String) = TriggerRuntime.interrupted(app, key, reason)
            override fun delivered(key: String) = TriggerRuntime.delivered(app, key)
            override fun actionFailed(key: String, reason: String) = TriggerRuntime.actionFailed(app, key, reason)
        }, conversations, scope)
    }

    /** Site registration form (logged-in websites, etc.): shared by the settings page and tag pool. */
    override val webSites: SiteRegistry by lazy { SiteRegistry(java.io.File(filesDir, "web")) }

    /** `files/sandbox/`'s tree lock: installation, reset, commands, downloads, and attachments are all written in its permission. */
    override val sandboxTree = SandboxTree()

    /** The task is to get the model source, evaluation hook, and screen screen switch from the App. */
    override val tasks = TaskEnvironment()

    /** Sandbox status: The installation service is updated, and the settings page is the same as what the Agent sees. */
    override val sandboxState: SandboxState by lazy { SandboxState(this) }

    /** Conversation record: one copy of the entire process, shared buttons for task services, chat interface, and result cards. */
    val conversations: ConversationStore by lazy { ConversationStore(this) }

    /** Delete a conversation (long press on the sidebar): together with its tasks, scheduled tasks and workspace. */
    val conversationDeleter: ConversationDeleter by lazy {
        val app = this
        ConversationDeleter(
            conversations, taskState.activeConversations,
            cancelTasks = { id -> taskState.runs.value.filter { it.conversationId == id }.forEach { TaskService.cancel(app, it.key) } },
            schedulesOf = { id -> TriggerRuntime.list(app).filter { it.threadId == id }.map { it.id } },
            removeSchedule = { TriggerRuntime.remove(app, it) },
            deleteWorkspace = { id ->
                // There are attachments, screens sent by users, and files written by tasks in the
                // workspace; commands in the sandbox can be accessed here, including deleting by handle,
                // not following links, and resetting mutual exclusions.
                val dirs = SandboxDirs(app)
                dirs.tree.shared { withContext(Dispatchers.IO) { dirs.gate.deleteTree(dirs.work, dirs.workspace(id).name) } }
            },
        )
    }

    /** Screens sent by users (stored in the workspace along with their respective conversations). */
    val userScreens: com.meenai.mina.chat.UserScreenFiles by lazy { com.meenai.mina.chat.UserScreenFiles(this) }

    /** Global query/autonomous: One copy for the entire process, the settings page and each task are shared. */
    val approvals: ApprovalStore by lazy { ApprovalStore(java.io.File(filesDir, "approvals.json")) }

    val appAccess: com.meenai.mina.settings.AppAccessStore by lazy {
        com.meenai.mina.settings.AppAccessStore(java.io.File(filesDir, "app-access.json"))
    }

    /** Global inquiry/autonomy and each task's own share; the "autonomous mode" in the developer options can be changed by it. */
    val approvalControl: ApprovalControl by lazy { ApprovalControl(approvals) }

    /** The queue and display status of each Run follow the App process, and the same copy will continue to be used when the Service is rebuilt. */
    val taskState = TaskCenterState()

    /** Mina's own interface is currently invisible. */
    val foreground = AppForeground()

    val updates: com.meenai.mina.about.UpdateChecker by lazy {
        com.meenai.mina.about.UpdateChecker(
            java.io.File(filesDir, "update-v1.json"),
            BuildConfig.VERSION_NAME,
            deviceAbis = android.os.Build.SUPPORTED_ABIS.toList(),
            currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
        )
    }

    val modelPreferences: com.meenai.mina.ui.router.ModelPreferencesStore by lazy {
        com.meenai.mina.ui.router.ModelPreferencesStore.get(this)
    }

    val freeKeyRouter: com.meenai.mina.core.router.FreeKeyRouter by lazy {
        com.meenai.mina.ui.router.AppFreeKeyRouter.initRankingsRepository(filesDir)
        com.meenai.mina.ui.router.AppFreeKeyRouter.createRouter(providers, preferences = modelPreferences)
    }

    val routerStatus: com.meenai.mina.ui.router.RouterStatus by lazy {
        com.meenai.mina.ui.router.RealRouterStatus(
            router = freeKeyRouter,
            scope = scope,
            store = providers,
            getFallbackModel = {
                val cfg = providers.configFor(com.meenai.mina.core.model.ModelRole.Main)
                if (cfg != null) {
                    val pName = providers.provider(cfg.providerId)?.name ?: cfg.name
                    cfg.model to pName
                } else null
            },
        )
    }

    /** Suspended capsules that float on top of other apps. */
    val capsule: CapsuleController by lazy { CapsuleController(this, taskState, foreground, resources.takeoverQueue) }

    /** The status of the dialog page depends on the process: when the interface is closed and reopened, the steps of the running task are still there. */
    val chat: ChatViewModel by lazy {
        val app = this
        ChatViewModel(
            CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
            taskState.state,
            taskState.takeoverStage,
            object : TaskActions {
                override fun start(goal: String, conversationId: String) = TaskService.start(app, goal, conversationId)
                override fun steer(conversationId: String, messageId: String, text: String) = TaskService.start(app, text, conversationId, messageId)
                override fun withdrawSteer(conversationId: String, messageId: String) = taskState.run(conversationId)?.withdrawSteer(messageId) ?: false
                override fun cancel() = TaskService.cancel(app)
                override fun resume() = TaskService.resume(app)
                override fun pause() = TaskService.pause(app)
                override fun takeOver() = TaskService.takeOver(app)
                override fun handBack() = TaskService.handBack(app)
                override fun finishedMyself() = TaskService.finishedMyself(app)
                override fun skip() = TaskService.skip(app)
                override fun answer(text: String) = TaskService.answer(app, text)
                override fun approve(key: Int) = TaskService.approve(app, key)
                override fun decline(key: Int) = TaskService.decline(app, key)
                override fun show(conversationId: String) = taskState.show(conversationId)

                // The moment you press it, it changes to the number of the dialogue task: if you switch
                // to the dialogue or change the task, the action will not fall on other tasks.
                private fun key(conversationId: String) = taskState.run(conversationId)?.key
                override fun cancelIn(conversationId: String) { key(conversationId)?.let { TaskService.cancel(app, it) } }
                override fun resumeIn(conversationId: String) { key(conversationId)?.let { TaskService.resume(app, it) } }
                override fun pauseIn(conversationId: String) { key(conversationId)?.let { TaskService.pause(app, it) } }
                override fun takeOverIn(conversationId: String) { key(conversationId)?.let { TaskService.takeOver(app, runKey = it) } }
                override fun handBackIn(conversationId: String) { key(conversationId)?.let { TaskService.handBack(app, runKey = it) } }
                override fun finishedMyselfIn(conversationId: String) { key(conversationId)?.let { TaskService.finishedMyself(app, runKey = it) } }
                override fun skipIn(conversationId: String) { key(conversationId)?.let { TaskService.skip(app, runKey = it) } }
                override fun answerIn(conversationId: String, text: String) { key(conversationId)?.let { TaskService.answer(app, text, it) } }
                override fun approveIn(conversationId: String, key: Int) { key(conversationId)?.let { TaskService.approve(app, key, it) } }
                override fun declineIn(conversationId: String, key: Int) { key(conversationId)?.let { TaskService.decline(app, key, it) } }
            },
            conversations,
            taskState.busy,
            appLabel = { ConfirmationNotifier.appLabel(app, it) },
            // Only if an app has been opened on the virtual screen or the built-in browser is being
            // used: picture-in-picture can be viewed (those with only a blank screen are not counted)
            screenOpen = tasks.previewOpen
                .stateIn(scope, SharingStarted.Eagerly, tasks.agentAppShown.value || tasks.webScreenOpen.value),
            endedRunId = taskState.endedRunId,
            // Take its own task according to the conversation, and do not need to serve the external one
            // (that is another task displayed in parallel)
            liveRunId = { taskState.run(it)?.runId?.value },
            endedRunIds = taskState.endedRunIds,
            parallelFlag = { FeatureFlags.get(app).speedFlags().parallelTasks },
            shownTask = taskState.shownTask,
            activeConversations = taskState.activeConversations,
            runGoal = { taskState.run(it)?.goal },
            runState = { taskState.run(it)?.state },
            runNotes = memory.notes,
            learning = AppLearningActions(app, memory),
            memoryItems = memory.items(),
            // Attachments enter the workspace of the conversation, and the running environment does not
            // need to be prepared: if the device supports it, just give the attachment button
            sandboxReady = SandboxState.get(app).status.map { it !is SandboxStatus.Unsupported }
                .stateIn(scope, SharingStarted.Eagerly, SandboxState.get(app).status.value !is SandboxStatus.Unsupported),
            sharedFiles = taskState.sharedFiles,
            generatedImages = taskState.generatedImages,
            activities = taskState.activities,
            steers = taskState.steers,
            summarizing = taskState.summarizing,
            importAttachments = { id, uris ->
                val sandbox = AndroidSandbox(app, SandboxState.get(app))
                val dirs = SandboxDirs(app)
                Attachments.importInto(app, uris.map(Uri::parse), dirs.workspace(id), dirs.gate, dirs.tree, sandbox::freeBytes) { sandbox.workspaceBytes(id) }
            },
            describeAttachments = { uris -> AttachFiles.describe(app, uris) },
            deletionImpact = conversationDeleter::impact,
            removeConversation = conversationDeleter::delete,
            keptPage = pageKeeper.page,
            closeKept = { pageKeeper.close(com.meenai.mina.core.cap.web.KeptPageClose.User) },
            runningModel = taskState.activeModel,
        )
    }

    override fun onCreate() {
        super.onCreate()
        val rankingsRepo = com.meenai.mina.ui.router.AppFreeKeyRouter.initRankingsRepository(filesDir)
        com.meenai.mina.ui.router.AppFreeKeyRouter.initStatsTracker(filesDir)
        com.meenai.mina.ui.router.AppFreeKeyRouter.initCooldownStore(this)
        scope.launch(Dispatchers.IO) { runCatching { rankingsRepo.getRankings() } }
        // Text outside the interface (notifications, services, floating capsules) is taken according to
        // the App language.
        AppLanguage.applyDefaultLocale(this)
        UiText.source = ResourceTexts { AppLanguage.resources(this) }
        // Chat/model diagnostics → Download/harness-chat.log (readable as text).
        com.meenai.mina.diag.ChatLog.install(this)
        registerActivityLifecycleCallbacks(foreground)
        // Settings › Network proxy: Mina's own connection is processed from the first request; the
        // account password of the SOCKS proxy is given by the authenticator of the whole process.
        com.meenai.mina.settings.NetProxyStore.get(this)
        com.meenai.mina.core.net.NetProxy.shared.installSocksAuth()
        // Built-in browser: Set up WebView only after setting up a proxy (read settings need to be
        // decrypted and put in the background; if no proxy is set up, WebView will not be loaded for
        // this)
        scope.launch(Dispatchers.IO) { runCatching { com.meenai.mina.cap.web.WebProxy.apply(this@HarnessApp) } }
        tasks.modelFactory = { c, s ->
            if (freeKeyRouter.candidates.isNotEmpty()) {
                freeKeyRouter
            } else {
                ProviderStore.get(c).modelFor(com.meenai.mina.core.model.ModelRole.Main, s)
            }
        }
        capsule.start()
        // Real implementation of the built-in assistant, and start watching the assistant (after the
        // Mina process starts, the assistant that is still alive will hand over Binder by itself)
        phone.shellManager.start()
        // Supplement several capabilities that are turned on by default as soon as the connection is
        // available
        phone.recovery.start()
        // Tell the built-in assistant: Do you want to pull Mina up after she is killed (this is required
        // only when the capsule is always displayed or there is a task running)
        com.meenai.mina.cap.phone.setup.RestoreWishSync(
            phone.hub, FeatureFlags.get(this).capsuleModeChanges(), taskState.busy, phone.recoverySettings.consent, phone.recovery.accessibility,
        ).start()
        // Changes left after the last abnormal exit (the App was muted) are restored here.
        scope.launch { taskRecovery.recover() }
        scope.launch {
            providers.repairGeminiWebBindings()
            var appliedMainModel: String? = null
            kotlinx.coroutines.flow.combine(providers.providers, providers.roles, providers.keysRevision) { list, _, _ -> list }.collect {
                providers.repairGeminiWebBindings()
                val candidates = com.meenai.mina.ui.router.AppFreeKeyRouter.createCandidates(providers)
                freeKeyRouter.updateCandidates(candidates)
                val mainModel = providers.configFor(com.meenai.mina.core.model.ModelRole.Main)?.model
                if (mainModel != null) {
                    modelPreferences.setManualRole("main", mainModel)
                    // Only when the configured main model actually changes: this
                    // collector also fires after every saved session (cookie
                    // rotation), and clearing a cooldown there would keep retrying
                    // a model that is genuinely rate limited.
                    if (mainModel != appliedMainModel) {
                        appliedMainModel = mainModel
                        val matching = candidates.firstOrNull { it.id.equals(mainModel, ignoreCase = true) }
                        if (matching != null) {
                            freeKeyRouter.cooldownStore.clearCooldown(matching.id)
                            if (freeKeyRouter.pinnedModelId == null && freeKeyRouter.activeCandidate?.id != matching.id) {
                                freeKeyRouter.switchCandidate(matching.id, "Configured main model")
                            }
                        }
                    }
                }
                providers.configFor(com.meenai.mina.core.model.ModelRole.Vision)?.model?.let {
                    modelPreferences.setManualRole("vision", it)
                }
                providers.configFor(com.meenai.mina.core.model.ModelRole.Light)?.model?.let {
                    modelPreferences.setManualRole("light", it)
                }
                com.meenai.mina.ui.router.AppFreeKeyRouter.autoPopulateSettingsRoles(providers, freeKeyRouter.difficultyRouter.picker)
            }
        }
        // The operation that was not completed when the last process was killed: mark it as interrupted
        // now and notify it, and wait until the task is served.
        runRecovery.recover().let { if (it.isNotEmpty()) Log.w("MinaTask", "${it.size} runs interrupted by process restart") }
        com.meenai.mina.core.router.MagicubeLedger.attachStorage(com.meenai.mina.ui.router.PrefsMagicubeStorage(this))
        com.meenai.mina.ui.router.MagicubeReminder.scheduleDailyAlarm(this)
        scope.launch(Dispatchers.IO) { runCatching { TriggerRuntime.rearm(this@HarnessApp) } }
        // A "Connect via USB" script that runs on the computer; by the way, it clears the USB connection
        // information left behind by the last boot.
        scope.launch(Dispatchers.IO) { runCatching { mcp.writeUsbScript() }.onFailure { Log.w("MinaMcp", "cannot write USB script", it) } }
        // Empty shell entries left behind by being killed halfway through the last export.
        scope.launch { runCatching { ShareExporter(this@HarnessApp).cleanPending() } }
        // Upload a copy of the web page left by the last process
        scope.launch { runCatching { WebFiles.clearStale(this@HarnessApp, SandboxDirs(this@HarnessApp).gate) } }
        // After the update has been installed, click the installation package under the About page.
        scope.launch(Dispatchers.IO) { runCatching { com.meenai.mina.about.ApkUpdater(this@HarnessApp).dropInstalled() } }
        // 7 din purani generated images saaf karo
        scope.launch(Dispatchers.IO) { runCatching { com.meenai.mina.image.ImageStorageManager.cleanOldCache(this@HarnessApp) } }
        // If the operating environment is not ready (first startup, after Mina upgrade), it will be
        // prepared in the background, not connected to the Internet, and invisible to users.
        RuntimePreparer.ensure(this)
        // When the sandbox is ready (ready at startup, or just installed), check the skill directory:
        // the scripts brought by the App update will be available immediately.
        scope.launch {
            SandboxState.get(this@HarnessApp).status.first { it is SandboxStatus.Ready }
            val state = SandboxState.get(this@HarnessApp)
            runCatching { SkillsSync.sync(SandboxDirs(this@HarnessApp)) { state.status.value is SandboxStatus.Ready } }
        }
        // FCM push updates: pehli run par topic subscribe karo; Google Play Services na ho to silently
        // skip
        scope.launch(Dispatchers.IO) {
            runCatching { com.meenai.mina.about.PushUpdateSubscriber.ensureSubscribed(this@HarnessApp) }
        }
        // Background download of fresh model rankings (once per day)
        // (init + warm-up already done at the top of onCreate)
    }
}

typealias MinaApp = HarnessApp
