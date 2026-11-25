package com.meenai.mina.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.meenai.mina.R
import com.meenai.mina.core.chatgpt.ChatGptAuth
import com.meenai.mina.core.oauth.OpenRouterAuth
import com.meenai.mina.HarnessApp
import com.meenai.mina.voice.SystemSpeech
import com.meenai.mina.settings.ChatGptSignIns
import com.meenai.mina.core.router.picker.ChatModelFilter
import com.meenai.mina.i18n.tr
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meenai.mina.core.model.ModelRole
import com.meenai.mina.image.ImageSettings
import com.meenai.mina.settings.ModelContextSettings
import com.meenai.mina.settings.ModelOption
import com.meenai.mina.settings.ModelOptions
import com.meenai.mina.settings.ProviderConfig
import com.meenai.mina.settings.ProviderPresets
import com.meenai.mina.settings.ProviderStore
import com.meenai.mina.ui.image.ImageRolesSection
import com.meenai.mina.settings.RoleChoice
import com.meenai.mina.ui.components.ButtonKind
import com.meenai.mina.ui.components.ButtonSize
import com.meenai.mina.ui.components.CapabilityTag
import com.meenai.mina.ui.components.FooterNote
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.GroupLabel
import com.meenai.mina.ui.components.InputRow
import com.meenai.mina.ui.components.ListRow
import com.meenai.mina.ui.components.MinaButton
import com.meenai.mina.ui.components.PageBackground
import com.meenai.mina.ui.components.ProgressDots
import com.meenai.mina.ui.components.SelectChip
import com.meenai.mina.ui.components.TagTone
import com.meenai.mina.ui.components.TileTone
import com.meenai.mina.ui.components.TopBar
import com.meenai.mina.settings.FeatureFlags
import com.meenai.mina.ui.router.LocalRouterStatus
import com.meenai.mina.ui.router.ProviderUsage as RouterProviderUsage
import com.meenai.mina.ui.router.RouterStatus
import com.meenai.mina.ui.router.RouterUsageCard
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType

/**
 * Configuration model: two parts: purpose (main model, graph viewing model, lightweight model) and
 * service provider. When you first get started, [onboarding] is true (the service provider is first,
 * there is a paging point and "Next" at the bottom); Settings › Model ([ModelsScreen]) is also on this
 * page, with a different title and guide, and no bottom button. Select usage, service provider details,
 * and all service provider models are subpages in this page. When getting started, select the main model
 * in "Next" and click "[onDone]".
 */
@Composable
fun ModelSetupScreen(
    store: ProviderStore, onboarding: Boolean, onBack: () -> Unit, onDone: () -> Unit,
    /** Click the "Speech to Text" card in the settings to go to the selection page; if you don't upload the onboarding process, the card will not appear. */
    onOpenSpeech: (() -> Unit)? = null,
    routerStatus: RouterStatus = LocalRouterStatus.current,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val flags = remember { FeatureFlags.get(context) }
    var showSwitchNotice by remember { mutableStateOf(flags.showSwitchNotice()) }
    val activeModel by routerStatus.current.collectAsState()
    val routerUsages by routerStatus.usage.collectAsState()
    val vm = remember {
        val app = context.applicationContext as? HarnessApp
        val signIns = app?.chatGptSignIn ?: ChatGptSignIns(scope)
        ModelSetupViewModel(
            store, scope, browser = AndroidSignInBrowser(context.applicationContext), signIns = signIns,
            speech = app?.speech,
            systemSpeech = {
                val silent = app?.speech?.systemSilent?.value ?: false
                SystemSpeech.capability(context.applicationContext, silent)
            },
            // The post-login test message must survive leaving this screen.
            probeScope = app?.appScope ?: scope,
            // A task that failed over to another provider must come back to the
            // model the user just signed in for, not stay on flash-lite.
            onGeminiWebConnected = { modelId ->
                (context.applicationContext as? HarnessApp)
                    ?.freeKeyRouter
                    ?.switchCandidate(modelId, "Gemini Web signed in")
            },
        )
    }
    val ui by vm.state.collectAsState()
    var picking by rememberSaveable { mutableStateOf<ModelRole?>(null) }
    var openProvider by rememberSaveable { mutableStateOf<String?>(null) }
    var allModels by rememberSaveable { mutableStateOf(false) }
    // Account login panel: It can be opened in the add form and service provider details. It can be
    // closed during login without interrupting login.
    var signInSheet by rememberSaveable { mutableStateOf<SignInKind?>(null) }

    // Successfully logged in with the account: close the panel and open the details of this company (see
    // account and model, set the main model)
    LaunchedEffect(ui.signedInProvider) {
        val id = ui.signedInProvider ?: return@LaunchedEffect
        signInSheet = null
        picking = null
        openProvider = id
        allModels = false
        vm.signedInShown()
    }
    // If the ChatGPT login is successful, you will still stay on this page (the current package quota
    // will be explained for the first time), and the panel will be closed.
    LaunchedEffect(ui.chatGptAccount?.signedIn, ui.chatGptSignIn) {
        if (signInSheet == SignInKind.ChatGpt && ui.chatGptAccount?.signedIn == true && ui.chatGptSignIn == ListState.Idle) signInSheet = null
    }
    LaunchedEffect(ui.geminiWebAccount?.signedIn, ui.geminiWebSignIn) {
        if (signInSheet == SignInKind.GeminiWeb && ui.geminiWebAccount?.signedIn == true && ui.geminiWebSignIn == ListState.Idle) signInSheet = null
    }
    // After logging in, Gemini will send a detection message on the server: when the detection fails,
    // the reason has been written in the account line. Let it open the panel once and show the reason
    // for the failure.
    LaunchedEffect(ui.geminiWebSignIn) {
        if (signInSheet == null && ui.geminiWebSignIn is ListState.Failed) signInSheet = SignInKind.GeminiWeb
    }

    val provider = openProvider?.let { id -> ui.providers.firstOrNull { it.id == id } }
    val role = picking
    when {
        provider != null -> {
            LaunchedEffect(provider.id) { vm.ensureListed() }
            val accountKind = when {
                provider.kind == ProviderConfig.Kind.Xai -> SignInKind.Xai
                provider.kind == ProviderConfig.Kind.GeminiWeb || provider.signIn == ProviderStore.GEMINI_WEB_SIGN_IN -> SignInKind.GeminiWeb
                provider.signIn == ProviderStore.OPENROUTER_SIGN_IN -> SignInKind.OpenRouter
                else -> null
            }
            val actions = ProviderDetailActions(
                refresh = { vm.refresh(provider.id) }, changeKey = { vm.changeKey(provider.id, it) },
                remove = { vm.removeProvider(provider.id); openProvider = null; allModels = false },
                use = { r, model -> vm.pick(r, provider.id, model) },
                setVision = { model, sees -> vm.setModelVision(provider.id, model, sees) },
                showAll = { allModels = true },
                retest = { vm.retest(provider.id) },
                // For ChatGPT, go directly to the browser to log in as usual; for xAI, OpenRouter, and
                // Gemini, open the help panel first.
                signIn = { if (accountKind != null) signInSheet = accountKind else vm.signInWithChatGpt() },
                signOut = {
                    when (accountKind) {
                        SignInKind.GeminiWeb -> vm.signOutGeminiWeb(provider.id)
                        SignInKind.Xai, SignInKind.OpenRouter -> vm.signOutAccount(provider.id)
                        else -> vm.signOutChatGpt(provider.id)
                    }
                },
                manageUsage = {
                    openInBrowser(context, when (accountKind) {
                        SignInKind.Xai -> ModelsCopy.ACCOUNT_MANAGE_URL
                        SignInKind.OpenRouter -> OpenRouterAuth.KEYS_PAGE
                        SignInKind.GeminiWeb -> "https://gemini.google.com"
                        else -> ChatGptAuth.USAGE_URL
                    })
                },
                useAsMain = { vm.useAsMain(provider.id) },
            )
            if (allModels) {
                BackHandler { allModels = false }
                AllModelsContent(ui, provider, actions, onBack = { allModels = false })
            } else {
                BackHandler { openProvider = null }
                ProviderDetailContent(ui, provider, actions, onBack = { openProvider = null })
            }
        }
        role != null -> {
            BackHandler { picking = null }
            LaunchedEffect(role) { vm.ensureListed(); vm.clearManualPick() }
            RolePickerContent(
                ui, role,
                RolePickerActions(
                    pick = { pid, model ->
                        // The one you just saved and none of the models passed: try calling it first,
                        // then select it and return if it passes.
                        if (ui.probeBeforePick(role, pid)) vm.pickManual(role, pid, model) { picking = null }
                        else { vm.pick(role, pid, model); picking = null }
                    },
                    pickManual = { pid, model -> vm.pickManual(role, pid, model) { picking = null } },
                    choose = { vm.choose(role, it); picking = null },
                    addProvider = { vm.openForm(); picking = null },
                ),
                onBack = { picking = null },
            )
        }
        else -> {
            if (ui.chatGptWelcome) ChatGptWelcome(vm::dismissChatGptWelcome)
            BackHandler(onBack = onBack)
            ModelSetupContent(
                ui, onboarding,
                ModelSetupActions(
                    selectPreset = vm::selectPreset, setKind = vm::setKind, setBaseUrl = vm::setBaseUrl, setApiKey = vm::setApiKey,
                    setModel = vm::setModel, setManual = vm::setManual, setName = vm::setName,
                    testAndSave = vm::testAndSave, saveUntested = vm::saveWithoutTest,
                    openForm = vm::openForm, closeForm = vm::closeForm,
                    openProvider = { openProvider = it; allModels = false }, pickRole = { picking = it }, next = onDone, openSpeech = onOpenSpeech,
                    openSignIn = { signInSheet = it },
                    setReasoningEffort = vm::setReasoningEffort,
                    setModelContext = vm::setModelContext,
                ),
                onBack,
                routerUsages = routerUsages,
                showSwitchNotice = showSwitchNotice,
                onToggleSwitchNotice = { flags.setShowSwitchNotice(it); showSwitchNotice = it },
                activeModel = activeModel,
                onSelectModel = { routerStatus.pinModel(it) },
                onPinClick = { routerStatus.pinModel(if (activeModel.isPinned) null else activeModel.id) },
            )
        }
    }

    signInSheet?.let { kind ->
        SignInSheet(
            ui, kind,
            SignInSheetActions(
                start = { k ->
                    when (k) {
                        SignInKind.GeminiWeb -> vm.signInWithGeminiWeb()
                        else -> k.account?.let(vm::signInWithAccount) ?: vm.signInWithChatGpt()
                    }
                },
                cancel = { k ->
                    when (k) {
                        SignInKind.GeminiWeb -> vm.cancelGeminiWebSignIn()
                        else -> if (k.account != null) vm.cancelAccountSignIn() else vm.cancelChatGptSignIn()
                    }
                },
                useApiKey = { k ->
                    signInSheet = null
                    openProvider = null
                    picking = null
                    vm.openForm()
                    k.keyPreset?.let(vm::selectPreset)
                },
                dismiss = {
                    signInSheet = null
                    // I have seen the reasons for the failure; if you are still logged in, do not
                    // interrupt.
                    if (!ui.accountSignIn.running && ui.geminiWebSignIn != ListState.Running) vm.dismissAccountFailure()
                },
            ),
        )
    }

    if (ui.geminiWebLoginOpen) {
        GeminiWebLoginDialog(
            onDismiss = { vm.cancelGeminiWebSignIn() },
            onSuccess = { session ->
                signInSheet = null
                vm.onGeminiWebLoginSuccess(session)
            },
            onError = { reason ->
                vm.onGeminiWebLoginFailed(reason)
            }
        )
    }
}

/** All actions on the main page. */
class ModelSetupActions(
    val selectPreset: (String) -> Unit = {},
    val setKind: (ProviderConfig.Kind) -> Unit = {},
    val setBaseUrl: (String) -> Unit = {},
    val setApiKey: (String) -> Unit = {},
    val setModel: (String) -> Unit = {},
    /** Opening and closing of "Manually fill in model name". */
    val setManual: (Boolean) -> Unit = {},
    val setName: (String) -> Unit = {},
    val testAndSave: () -> Unit = {},
    /** Do not test, save directly (see [ModelSetupViewModel.saveWithoutTest]). */
    val saveUntested: () -> Unit = {},
    val openForm: () -> Unit = {},
    val closeForm: () -> Unit = {},
    val openProvider: (String) -> Unit = {},
    val pickRole: (ModelRole) -> Unit = {},
    /** Open the "speech to text" selection page; if it is null, this card will not be displayed (getting started). */
    val openSpeech: (() -> Unit)? = null,
    val next: () -> Unit = {},
    /** Open the instructions panel for logging in to an account. */
    val openSignIn: (SignInKind) -> Unit = {},
    val setReasoningEffort: (ModelRole, String?) -> Unit = { _, _ -> },
    val setModelContext: (String, ModelContextSettings) -> Boolean = { _, _ -> false },
)

/**
 * The main page without state is convenient for testing. Changes take effect on the spot (save the
 * service provider after passing the test, and use it after selecting the purpose), so you can only go
 * back when entering the settings; leave "Next" at the bottom when you get started, and hide the
 * keyboard when it pops up, leaving space for the form.
 */
@Composable
fun ModelSetupContent(
    ui: ModelSetupUi,
    onboarding: Boolean,
    actions: ModelSetupActions,
    onBack: () -> Unit,
    routerUsages: List<RouterProviderUsage> = emptyList(),
    showSwitchNotice: Boolean = true,
    onToggleSwitchNotice: (Boolean) -> Unit = {},
    activeModel: com.meenai.mina.ui.router.ActiveModel? = null,
    onSelectModel: ((String) -> Unit)? = null,
    onPinClick: (() -> Unit)? = null,
) {
    val c = MinaTheme.colors
    // After the form is closed (save it after passing the test, or cancel it), there is no place to type
    // on the page, so you can easily close the keyboard
    val focus = LocalFocusManager.current
    LaunchedEffect(ui.showForm) { if (!ui.showForm) focus.clearFocus() }
    val onPick: (ModelRole) -> Unit = { role -> if (ui.providers.isEmpty()) actions.openForm() else actions.pickRole(role) }
    PageBackground {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars).windowInsetsPadding(WindowInsets.ime)) {
            TopBar(if (onboarding) ModelSetupCopy.TITLE else ModelsCopy.TITLE, MinaIcons.Back, onBack, stringResource(R.string.back))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                Text(
                    if (onboarding) ModelSetupCopy.LEAD else ModelsCopy.LEAD, Modifier.padding(top = 4.dp, bottom = 12.dp),
                    fontSize = 14.5.sp, lineHeight = 23.sp, color = c.ink2,
                )
                // When getting started, first connect to the service provider and then select the model;
                // first look at the usage in the settings
                if (onboarding) {
                    ProvidersSection(ui, actions)
                    RolesSection(ui, onPick, actions.setReasoningEffort, null)
                    FooterNote(ModelSetupCopy.ONBOARDING_NOTE)
                } else {
                    RolesSection(ui, onPick, actions.setReasoningEffort, actions.openSpeech, actions.setModelContext)
                    val ctx = LocalContext.current
                    val imageSettings = remember {
                        (ctx.applicationContext as? com.meenai.mina.HarnessApp)?.imageSettings
                            ?: ImageSettings.get(ctx)
                    }
                    ImageRolesSection(imageSettings)
                    ProvidersSection(ui, actions)
                    RouterUsageCard(
                        usages = routerUsages,
                        showSwitchNotice = showSwitchNotice,
                        onToggleSwitchNotice = onToggleSwitchNotice,
                        activeModel = activeModel,
                        onSelectModel = onSelectModel,
                        onPinClick = onPinClick,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
            // Look at the actual height of the keyboard; isImeVisible defaults to true before the system
            // reports it for the first time, which will hide the button by mistake.
            if (onboarding && WindowInsets.ime.getBottom(LocalDensity.current) == 0) {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
                    ProgressDots(Onboarding.DOTS, Onboarding.dot(OnboardingStep.Model), Modifier.padding(bottom = 14.dp))
                    MinaButton(ModelSetupCopy.NEXT, actions.next, Modifier.fillMaxWidth().testTag("setup-next"), enabled = ui.canNext)
                }
            }
        }
    }
}

/** Service Provider: One line for each company (indicate which purposes it is working for), add "Add Service Provider"; when there is no service provider or click Add, the form will be displayed here. */
@Composable
private fun ProvidersSection(ui: ModelSetupUi, actions: ModelSetupActions) {
    val c = MinaTheme.colors
    if (ui.providers.isNotEmpty()) {
        GroupLabel(ModelsCopy.PROVIDERS)
        GroupCard {
            ui.providers.forEachIndexed { i, p ->
                val line = if (ui.signedOut(p)) ModelsCopy.CHATGPT_SIGNED_OUT else ModelsCopy.providerLine(p, ui.listing[p.id], ui.rolesOf(p))
                // There is a model that is used but failed to pass the test, or the test is skipped and
                // saved and is already in use: this line reminds "the test did not pass"
                val warning = ModelsCopy.UNVERIFIED.takeIf {
                    ui.unverified(p).isNotEmpty() || (ui.untested(p) && ui.rolesOf(p).isNotEmpty())
                }
                ProviderRow(p, line, first = i == 0, warning = warning) { actions.openProvider(p.id) }
            }
            if (!ui.showForm) {
                ListRow(
                    MinaIcons.Plus, ModelsCopy.ADD_PROVIDER, titleColor = c.accent, tone = TileTone.Accent,
                    onClick = actions.openForm,
                )
            }
        }
    }
    ui.probeNote?.let { FooterNote(it, Modifier.testTag("setup-probe-note"), color = c.warn) }
    // None of the models I just saved passed the test: go directly to select the main model (try calling
    // it first when selecting)
    if (ui.pickToProbe != null && ui.roles.main == null) {
        Text(
            ModelSetupCopy.PICK_MAIN,
            Modifier.padding(start = 16.dp, bottom = 12.dp).clickable { actions.pickRole(ModelRole.Main) }.testTag("setup-pick-main").padding(vertical = 4.dp),
            fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.accent,
        )
    }
    if (ui.showForm) AddProviderForm(ui, actions)
    FooterNote(ModelsCopy.KEY_NOTE)
}

/** Add a service provider: First, the "Log in with account" group (no need to fill in the Key), then fill in the Key: Default capsule → (when customizing) interface type and address → API Key → "Test and save". */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddProviderForm(ui: ModelSetupUi, actions: ModelSetupActions) {
    val c = MinaTheme.colors
    val f = ui.form
    var showKey by remember { mutableStateOf(false) }
    var confirmUntested by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        GroupLabel(ModelsCopy.ADD_PROVIDER, Modifier.weight(1f))
        if (ui.providers.isNotEmpty()) {
            Text(
                ModelSetupCopy.CANCEL, Modifier.clickable(onClick = actions.closeForm).padding(horizontal = 4.dp, vertical = 6.dp),
                fontSize = 13.sp, color = c.accent,
            )
        }
    }
    AccountSignInGroup(ui, actions.openSignIn, actions.openProvider)
    GroupLabel(ModelsCopy.KEY_GROUP)
    FlowRow(
        Modifier.fillMaxWidth().padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ProviderPresets.all.forEach { p ->
            // "Custom" is displayed according to the interface language; the name of the service
            // provider remains unchanged
            SelectChip(if (p == ProviderPresets.custom) stringResource(R.string.preset_custom) else p.label, f.preset == p.label, { actions.selectPreset(p.label) }, Modifier.testTag("preset-${p.label}"))
        }
    }
    GroupCard {
        if (f.isCustom) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(ModelSetupCopy.KIND, Modifier.padding(end = 12.dp), style = MinaType.Body, color = c.ink)
                // If the four interfaces cannot fit in one row, fold them to the next row.
                FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProviderConfig.Kind.withApiKey.forEach { k -> SelectChip(ModelsCopy.kindLabel(k), f.kind == k, { actions.setKind(k) }, small = true) }
                }
            }
        }
        if (f.urlEditable) {
            InputRow(
                ModelSetupCopy.BASE_URL, f.baseUrl, actions.setBaseUrl, "https://…", first = !f.isCustom,
                keyboard = KeyboardType.Uri, modifier = Modifier.testTag("setup-base-url"),
            )
        }
        if (f.isCustom) {
            // You can leave it blank: directly write the name (the host name of the address) in the
            // prompt if you leave it blank.
            InputRow(
                ModelSetupCopy.NAME, f.name, actions.setName,
                if (f.urlProblem == null) f.defaultName else ModelSetupCopy.NAME_HINT,
                modifier = Modifier.testTag("setup-name"),
            )
        }
        InputRow(
            ModelSetupCopy.API_KEY, f.apiKey, actions.setApiKey,
            if (f.keyRequired) ModelSetupCopy.KEY_HINT else ModelSetupCopy.KEY_OPTIONAL, first = !f.urlEditable,
            keyboard = KeyboardType.Password, secret = !showKey, modifier = Modifier.testTag("setup-api-key"),
        ) {
            Text(if (showKey) stringResource(R.string.hide) else stringResource(R.string.show), Modifier.clickable { showKey = !showKey }, fontSize = 13.sp, color = c.accent)
        }
        if (f.showModel) {
            InputRow(
                ModelSetupCopy.MODEL_NAME, f.model, actions.setModel, ModelSetupCopy.modelExample(f.exampleKind),
                keyboard = KeyboardType.Uri, modifier = Modifier.testTag("setup-model"),
            )
        }
    }
    // If the model you want is not in the list, or the company does not provide a list, you can fill it
    // in at any time (the column is already there when the model is not listed)
    if (!f.noList) {
        Text(
            if (f.manual) ModelSetupCopy.MANUAL_OFF else ModelSetupCopy.MANUAL_ON,
            Modifier.padding(start = 16.dp, top = 8.dp).clickable { actions.setManual(!f.manual) }.padding(vertical = 4.dp).testTag("setup-manual-model"),
            fontSize = 13.sp, color = c.accent,
        )
    }
    if (f.manual && !f.noList) FooterNote(ModelSetupCopy.MANUAL_NOTE, Modifier.testTag("setup-manual-note"))
    f.urlProblem?.takeIf { f.urlEditable && f.baseUrl.isNotBlank() }?.let { FooterNote(it, color = c.bad) }
    f.suggestedPreset?.let { preset -> PresetSuggestion(f, preset.label) { actions.selectPreset(preset.label) } }
    if (f.noList) FooterNote(if (f.isCustom) ModelSetupCopy.NO_LIST_CUSTOM else ModelSetupCopy.NO_LIST_PRESET, Modifier.testTag("setup-no-list"))
    if (f.isSelfHosted) FooterNote(ModelSetupCopy.SELF_HOSTED_NOTE, Modifier.testTag("setup-self-hosted"))
    f.unencryptedWarning?.let { FooterNote(it, Modifier.testTag("setup-url-unencrypted"), color = c.warn) }
    MinaButton(
        if (f.test == ListState.Running) ModelSetupCopy.TESTING else ModelSetupCopy.TEST_AND_SAVE,
        actions.testAndSave, Modifier.fillMaxWidth().padding(top = 2.dp).testTag("setup-test"),
        kind = ButtonKind.Secondary, size = ButtonSize.Medium, enabled = f.canTest,
    )
    (f.test as? ListState.Failed)?.let { failed ->
        Text(
            ModelSetupCopy.failed(failed.message), Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp).testTag("setup-test-failed"),
            fontSize = 13.sp, lineHeight = 18.sp, color = c.bad,
        )
    }
    // What I want to save first after trying it out: make it a button, don’t hide it in a line of small
    // words - it is a real action, not an explanation
    MinaButton(
        ModelSetupCopy.SAVE_UNTESTED, { confirmUntested = true },
        Modifier.fillMaxWidth().padding(top = 6.dp).testTag("setup-save-untested"),
        kind = ButtonKind.Tonal, size = ButtonSize.Small, enabled = f.canSaveUntested,
    )
    if (confirmUntested) AlertDialog(
        onDismissRequest = { confirmUntested = false },
        title = { Text(ModelSetupCopy.SAVE_UNTESTED_TITLE) },
        text = { Text(ModelSetupCopy.SAVE_UNTESTED_BODY) },
        confirmButton = {
            TextButton({
                confirmUntested = false
                actions.saveUntested()
            }) { Text(ModelSetupCopy.SAVE_UNTESTED_CONFIRM) }
        },
        dismissButton = {
            TextButton({ confirmUntested = false }) { Text(stringResource(R.string.cancel)) }
        },
    )
    FooterNote(ModelSetupCopy.TEST_NOTE, Modifier.padding(top = 6.dp))
}

/** The custom address is a certain company's official address: it is recommended to use that company's default (without blocking), and if the interface type does not match, tell it as well; click to change it, and keep the key. */
@Composable
private fun PresetSuggestion(f: AddForm, label: String, onUse: () -> Unit) {
    val c = MinaTheme.colors
    FooterNote(ModelSetupCopy.presetSuggestion(f), Modifier.padding(top = 2.dp).testTag("setup-preset-hint"), color = c.warn)
    Text(
        ModelSetupCopy.usePreset(label),
        Modifier.padding(start = 16.dp, bottom = 12.dp).clickable(onClick = onUse).testTag("setup-use-preset").padding(vertical = 4.dp),
        fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.accent,
    )
}

/** After successfully logging in with ChatGPT for the first time, please explain: The usage in Mina will be used for your ChatGPT package. */
@Composable
private fun ChatGptWelcome(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(ModelsCopy.CHATGPT_WELCOME_TITLE) },
        text = { Text(ModelsCopy.CHATGPT_WELCOME_BODY) },
        confirmButton = { TextButton(onDismiss) { Text(ModelsCopy.CHATGPT_GOT_IT) } },
    )
}

/**
 * The system capabilities required for login: the authorization page is opened with the system browser;
 * the callback page (given after the token is replaced) writes the reason for success or failure, and
 * then puts a link back to Mina (intent link, click to open Mina with the browser); after the login is
 * completed (success or failure), Mina is brought back to the front desk. Android restricts the
 * background opening interface and cannot bring it back when the user clicks the link of the callback
 * page or returns from the recent task.
 */
private class AndroidSignInBrowser(private val context: Context) : SignInBrowser {
    override fun open(url: String) {
        try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, tr(R.string.chatgpt_no_browser), Toast.LENGTH_SHORT).show()
        }
    }

    override fun returnToApp() {
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        }
    }

    override fun page(failure: String?): String {
        val text = failure ?: tr(R.string.chatgpt_page_done)
        val back = "intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=${context.packageName};end"
        return SignInPage.html(tr(R.string.chatgpt_page_title), text, tr(R.string.chatgpt_page_back), back)
    }
}

/** The page in the browser after the callback: Add a button to return to the App in one sentence. The text is escaped and put in. */
internal object SignInPage {
    fun html(title: String, text: String, back: String, backUrl: String): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">""" +
            """<title>${esc(title)}</title><style>body{font-family:sans-serif;margin:0;padding:48px 24px;text-align:center;color:#1d1d1f;background:#fff}""" +
            """p{font-size:17px;line-height:1.5}a{display:inline-block;margin-top:16px;padding:12px 28px;border-radius:999px;background:#1d1d1f;""" +
            """color:#fff;text-decoration:none;font-size:16px}</style></head><body><p>${esc(text)}</p><a href="${esc(backUrl)}">${esc(back)}</a></body></html>"""
    }
}

/** Purpose: There is one card each for the main model, the picture viewing model, and the lightweight model. Click to open the selection page. */
@Composable
private fun RolesSection(ui: ModelSetupUi, onPick: (ModelRole) -> Unit, onReasoning: (ModelRole, String?) -> Unit,
    onSpeech: (() -> Unit)?, onContext: ((String, ModelContextSettings) -> Boolean)? = null,
) {
    GroupLabel(ModelsCopy.ROLES)
    GroupCard {
        ModelRole.entries.forEachIndexed { i, role ->
            RoleCard(ModelsCopy.roleIcon(role), ModelsCopy.roleLabel(role), ModelsCopy.roleLine(ui, role), first = i == 0, tag = "role-${role.name.lowercase()}") {
                onPick(role)
            }
            ReasoningControl(ui, role) { onReasoning(role, it) }
            if (onContext != null) ModelContextControl(ui, role, onContext)
        }
        // The fourth use: speech to text (not a dialogue model, click to open a separate selection page)
        if (onSpeech != null) {
            RoleCard(ModelsCopy.SPEECH_ICON, ModelsCopy.SPEECH_LABEL, ModelsCopy.speechLine(ui), tag = "role-speech", onClick = onSpeech)
        }
    }
}

@Composable
private fun ModelContextControl(ui: ModelSetupUi, role: ModelRole,
    onSave: (String, ModelContextSettings) -> Boolean,
) {
    if (ui.roles[role] !is RoleChoice.Model) return
    val cfg = ui.configFor(role) ?: return
    val saved = ui.providerOf(cfg)?.contextSettings?.get(cfg.model) ?: ModelContextSettings()
    var open by rememberSaveable(cfg.id) { mutableStateOf(false) }
    TextButton(onClick = { open = true }, modifier = Modifier.padding(start = 8.dp).testTag("context-${role.name.lowercase()}")) {
        Text(stringResource(R.string.model_context_title))
    }
    if (!open) return
    var window by rememberSaveable(cfg.id) { mutableStateOf(saved.windowTokens?.toString().orEmpty()) }
    var trigger by rememberSaveable(cfg.id) { mutableStateOf(saved.triggerTokens?.toString().orEmpty()) }
    var saveFailed by remember { mutableStateOf(false) }
    fun positiveInt(text: String) = text.trim().takeIf { it.all { c -> c in '0'..'9' } }?.toIntOrNull()?.takeIf { it > 0 }
    val settings = ModelContextSettings(positiveInt(window), positiveInt(trigger))
    val modelWindow = cfg.capabilities.contextTokens
    val limits = settings.limits(modelWindow)
    val badWindow = window.isNotBlank() && settings.windowTokens == null
    val badTrigger = trigger.isNotBlank() && (settings.triggerTokens == null || (!badWindow && settings.triggerTokens != limits.trigger))
    val keyboard = KeyboardOptions(keyboardType = KeyboardType.Number)
    AlertDialog(
        onDismissRequest = { open = false },
        modifier = Modifier.imePadding(),
        title = { Text(stringResource(R.string.model_context_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(cfg.model)
                Text(stringResource(R.string.model_context_note))
                OutlinedTextField(window, { window = it; saveFailed = false },
                    modifier = Modifier.fillMaxWidth().testTag("context-window"), singleLine = true, keyboardOptions = keyboard,
                    label = { Text(stringResource(R.string.model_context_window)) },
                    placeholder = { Text(stringResource(R.string.model_context_auto, modelWindow)) },
                    supportingText = if (badWindow) ({ Text(stringResource(R.string.model_context_integer)) }) else null,
                    isError = badWindow)
                OutlinedTextField(trigger, { trigger = it; saveFailed = false },
                    modifier = Modifier.fillMaxWidth().testTag("context-trigger"), singleLine = true, keyboardOptions = keyboard,
                    label = { Text(stringResource(R.string.model_context_trigger)) },
                    placeholder = { Text(stringResource(R.string.model_context_auto, limits.trigger)) },
                    supportingText = {
                        if (!badWindow && limits.usable > 0) Text(stringResource(R.string.model_context_range,
                            limits.minTrigger.coerceAtLeast(1), limits.usable))
                        else if (badTrigger) Text(stringResource(R.string.model_context_integer))
                    }, isError = badTrigger)
                if (!badWindow && (trigger.isBlank() || settings.triggerTokens != null)) Text(stringResource(R.string.model_context_effective, limits.window, limits.trigger),
                    modifier = Modifier.testTag("context-effective"))
                TextButton(onClick = { window = ""; trigger = ""; saveFailed = false }) { Text(stringResource(R.string.reset)) }
                if (saveFailed) Text(stringResource(R.string.model_context_save_failed), color = MinaTheme.colors.bad)
            }
        },
        confirmButton = { TextButton(onClick = {
            if (onSave(cfg.id, settings)) open = false else saveFailed = true
        }, enabled = !badWindow && !badTrigger, modifier = Modifier.testTag("context-save")) { Text(stringResource(R.string.save)) } },
        dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.cancel)) } },
    )
}

/** The model's ability tag; [accent] is always a neutral color when false (the accent color in the list row is reserved for "in use" capsules). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Tags(o: ModelOption, forVision: Boolean = false, accent: Boolean = true) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ModelSetupCopy.tags(o, forVision).forEach { (text, strong) -> CapabilityTag(text, if (strong && accent) TagTone.Accent else TagTone.Neutral) }
    }
}

internal val ModelIdStyle = MinaType.Body.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)

/** Configure the model page (when getting started) and add the text of the service provider form (pure function, easy for testing). The usage statement is in [ModelsCopy]. */
object ModelSetupCopy {
    val TITLE: String get() = tr(R.string.model_setup_title)
    val LEAD: String get() = tr(R.string.model_setup_lead)
    val ONBOARDING_NOTE: String get() = tr(R.string.model_setup_onboarding_note)
    val CANCEL: String get() = tr(R.string.cancel)
    val KIND: String get() = tr(R.string.model_kind)
    val BASE_URL: String get() = tr(R.string.model_base_url)
    val NAME: String get() = tr(R.string.model_provider_name)
    val NAME_HINT: String get() = tr(R.string.model_provider_name_hint)
    const val API_KEY = "API Key"
    val KEY_HINT: String get() = tr(R.string.model_key_hint)
    val KEY_OPTIONAL: String get() = tr(R.string.model_key_optional)
    val TEST_AND_SAVE: String get() = tr(R.string.model_test_save)
    val TESTING: String get() = tr(R.string.model_testing)
    val PICK_MAIN: String get() = tr(R.string.model_pick_main)
    val TEST_NOTE: String get() = tr(R.string.model_test_note)
    val SELF_HOSTED_NOTE: String get() = tr(R.string.model_self_hosted_note)
    val MODEL_NAME: String get() = tr(R.string.model_manual_name)
    val NO_LIST_CUSTOM: String get() = tr(R.string.model_no_list_custom)
    val NO_LIST_PRESET: String get() = tr(R.string.model_no_list_preset)
    val MANUAL_ON: String get() = tr(R.string.model_manual_on)
    val MANUAL_OFF: String get() = tr(R.string.model_manual_off)
    val MANUAL_NOTE: String get() = tr(R.string.model_manual_note)
    val SAVE_UNTESTED: String get() = tr(R.string.model_save_untested)
    val SAVE_UNTESTED_TITLE: String get() = tr(R.string.model_save_untested_title)
    val SAVE_UNTESTED_BODY: String get() = tr(R.string.model_save_untested_body)
    val SAVE_UNTESTED_CONFIRM: String get() = tr(R.string.model_save_untested_confirm)

    /** Examples of hand-filled model names, based on interface type (model names look different from company to company). */
    fun modelExample(kind: ProviderConfig.Kind): String = tr(
        when (kind) {
            ProviderConfig.Kind.Gemini, ProviderConfig.Kind.GeminiWeb -> R.string.model_example_gemini
            ProviderConfig.Kind.Anthropic -> R.string.model_example_anthropic
            ProviderConfig.Kind.OpenAiResponses, ProviderConfig.Kind.ChatGpt -> R.string.model_example_openai
            ProviderConfig.Kind.Xai -> R.string.model_example_grok
            ProviderConfig.Kind.OpenAiCompat -> R.string.model_example_compat
        },
    )

    /** It is recommended when the custom address is the official address of a certain company; add a supplement if the interface type does not match that company. */
    fun presetSuggestion(f: AddForm): String {
        val preset = f.suggestedPreset ?: return ""
        val match = tr(R.string.model_preset_match, preset.label)
        return if (!f.presetKindMismatch) match
        else match + tr(R.string.sentence_sep) + tr(R.string.model_preset_kind_mismatch, preset.label, ModelsCopy.kindLabel(preset.kind), ModelsCopy.kindLabel(f.kind))
    }

    fun usePreset(label: String) = tr(R.string.model_use_preset, label)
    val NEXT: String get() = tr(R.string.next)

    fun failed(reason: String) = tr(R.string.model_failed, reason)

    /** Model's ability label: (text, whether to accent color). The interface was not provided, the user did not say anything, and it was marked "Unconfirmed" based on the default inference. For reference only, does not prevent selection. */
    fun tags(o: ModelOption, forVision: Boolean = false): List<Pair<String, Boolean>> = buildList {
        val caps = o.capabilities
        val visionKnown = o.visionKnown || o.visionByUser != null
        if (o.typed) add(tr(R.string.cap_typed) to false)
        if (forVision) {
            if (!ChatModelFilter.isImageEditOrGen(o.id)) {
                add((if (visionKnown) tr(R.string.cap_vision) else tr(R.string.cap_vision_unconfirmed)) to false)
            }
        } else {
            when {
                !caps.toolCalls -> add(tr(R.string.cap_no_tools) to false)
                o.toolCallsKnown -> add(tr(R.string.cap_tools) to true)
                else -> add(tr(R.string.cap_tools_unconfirmed) to false)
            }
            if (caps.vision && visionKnown && !ChatModelFilter.isImageEditOrGen(o.id)) add(tr(R.string.cap_vision) to false)
        }
        if (caps.reasoning) add(tr(R.string.cap_reasoning) to false)
        if (o.contextKnown) add(ModelOptions.contextLabel(caps.contextTokens) to false)
    }
}
