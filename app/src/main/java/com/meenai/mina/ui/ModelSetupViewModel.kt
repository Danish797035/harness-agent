package com.meenai.mina.ui

import android.webkit.CookieManager
import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.core.diag.ChatTrace
import com.meenai.mina.core.model.Author
import com.meenai.mina.core.model.GeminiWebModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import com.meenai.mina.core.model.ListFailure
import com.meenai.mina.core.net.ProxyFailure
import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.ListedModel
import com.meenai.mina.core.model.ModelEndpoint
import com.meenai.mina.core.model.ModelError
import com.meenai.mina.core.model.ModelListResult
import com.meenai.mina.core.model.ModelRole
import com.meenai.mina.core.net.LocalHosts
import com.meenai.mina.settings.ModelOption
import com.meenai.mina.settings.ModelOptions
import com.meenai.mina.settings.Provider
import com.meenai.mina.settings.ProviderConfig
import com.meenai.mina.settings.ProviderConnection
import com.meenai.mina.settings.ProviderPreset
import com.meenai.mina.settings.ProviderPresets
import com.meenai.mina.settings.ProbeResult
import com.meenai.mina.settings.ProviderStore
import com.meenai.mina.settings.ModelContextSettings
import com.meenai.mina.settings.RoleChoice
import com.meenai.mina.settings.RoleChoices
import com.meenai.mina.ui.router.AppFreeKeyRouter
import com.meenai.mina.settings.ChatGptSignIns
import com.meenai.mina.settings.AccountOutcome
import com.meenai.mina.settings.DevicePrompt
import com.meenai.mina.settings.SignInTarget
import com.meenai.mina.voice.SpeechChoice
import com.meenai.mina.voice.SpeechSettings
import com.meenai.mina.voice.SystemCapability
import com.meenai.mina.voice.Transcriber
import com.meenai.mina.core.chatgpt.ChatGptAuth
import com.meenai.mina.core.chatgpt.SignInException
import com.meenai.mina.core.chatgpt.SignInFailure
import com.meenai.mina.core.chatgpt.signInWithChatGpt
import com.meenai.mina.core.gemini.GeminiWebClient
import com.meenai.mina.core.gemini.GeminiWebSession
import com.meenai.mina.core.oauth.AccountSignInException
import com.meenai.mina.core.oauth.AccountSignInFailure
import com.meenai.mina.core.oauth.DeviceChallenge
import com.meenai.mina.core.oauth.OpenRouterAuth
import com.meenai.mina.core.oauth.XaiAuth
import com.meenai.mina.core.oauth.signInWithOpenRouter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** List the progress of a model ("Test and Save" or refresh a provider). */
sealed interface ListState {
    data object Idle : ListState
    data object Running : ListState
    data class Failed(val message: String) : ListState
}

/** Account logged in using ChatGPT: [email] comes from the verified ID token (may be empty); [signedIn] is false after logging out. */
data class ChatGptAccount(val email: String, val signedIn: Boolean)

/** Account logged in with Google Gemini. */
data class GeminiWebAccount(val email: String, val signedIn: Boolean)

/**
 * In addition to ChatGPT, there are several companies that use accounts to log in: xAI / Grok uses the
 * device code (check the short code in the browser), and OpenRouter uses the official PKCE authorization
 * (local callback to change the key). There is another set of logins for ChatGPT (see
 * [ModelSetupViewModel.signInWithChatGpt]), which is side by side with them on the interface.
 */
enum class AccountProvider(val target: SignInTarget) {
    Xai(SignInTarget.Xai), OpenRouter(SignInTarget.OpenRouter);

    companion object {
        fun of(target: SignInTarget): AccountProvider? = entries.firstOrNull { it.target == target }
    }
}

/** An account to log in to with one account. [label] is the account name shown to others (email, etc., which may be empty); [signedIn] is false after logging out. This is null if there is no such provider. */
data class SubscriptionAccount(val label: String, val signedIn: Boolean)

/**
 * An account login is in progress or has just failed. [provider] is null when there is no login or
 * failure. When logging in with the device code, [userCode] is the short code shown to the user,
 * [openUrl] is the verification page that has been checked against the host, and [expiresAtMs] is the
 * time when the short code expires.
 */
data class AccountSignInUi(
    val provider: AccountProvider? = null,
    val running: Boolean = false,
    val userCode: String = "",
    val openUrl: String = "",
    val expiresAtMs: Long = 0,
    val failure: String? = null,
)

/**
 * The system capabilities required to log in with an account: open the authorization page in the
 * browser, return to Mina after logging in, and the web page displayed in the browser after the local
 * callback is received. The interface is given (Context is required); if it is not given in the test,
 * you cannot log in.
 */
interface SignInBrowser {
    fun open(url: String)
    fun returnToApp()
    /** The webpage returned to the browser after the callback is processed: [failure] is null, which means the login is successful, otherwise it is the reason for failure (the same sentence as on the interface). */
    fun page(failure: String?): String
}

/**
 * "Add Provider" form. When [noList] is true, the last test listed no models: fill in [model]
 * (manually entered model name) first, and the next test call uses it. [manual] is true when the user
 * tapped "Enter model name manually" (or auto-expanded after a listing error): can be filled anytime
 * and used for the test call regardless of whether listing works, omits it, or fails. [name] is the
 * custom provider name (only for custom providers, optional, see [providerName]).
 */
data class AddForm(
    val preset: String,
    val kind: ProviderConfig.Kind,
    val baseUrl: String,
    val apiKey: String,
    val test: ListState = ListState.Idle,
    val model: String = "",
    val noList: Boolean = false,
    val name: String = "",
    val manual: Boolean = false,
) {
    /** Display the "Model Name" column: No model is listed, or the user needs to fill it in manually. */
    val showModel: Boolean get() = noList || manual

    /** The model name is filled in; it does not count when that column is not displayed. */
    val typedModel: String get() = if (showModel) model.trim() else ""

    val isCustom: Boolean get() = preset == ProviderPresets.custom.label

    /** Services running on the user's own machine (see [ProviderPreset.selfHosted]): The address is pre-filled with the default port of the machine and must be able to be changed to that machine. */
    val isSelfHosted: Boolean get() = presetFor(preset).selfHosted

    /** The address needs to be filled in or changed by the user: customized, and a service run by oneself. */
    val urlEditable: Boolean get() = isCustom || isSelfHosted

    val urlProblem: String?
        get() = when {
            baseUrl.isBlank() -> tr(R.string.model_need_url)
            !baseUrl.trim().startsWith("http://") && !baseUrl.trim().startsWith("https://") -> tr(R.string.model_url_scheme)
            else -> null
        }

    /** Plain text http is not the address of this machine or LAN: a reminder, don’t block it (see [LocalHosts]). */
    val unencryptedWarning: String?
        get() = if (urlProblem == null && LocalHosts.unencryptedRemote(baseUrl)) tr(R.string.model_url_unencrypted) else null

    /** You don’t need to fill in the Key for services you run and custom OpenAI compatible interfaces (mostly local services), but the rest are required. */
    val keyRequired: Boolean get() = !isSelfHosted && !(isCustom && kind == ProviderConfig.Kind.OpenAiCompat)

    val canTest: Boolean get() =
        urlProblem == null && (!keyRequired || apiKey.isNotBlank()) && test != ListState.Running && (!noList || model.isNotBlank())

    /** It can be saved without testing: the address format is correct, and the key should be there. Different from [canTest], the test progress is not checked and the model name is not required. */
    val canSaveUntested: Boolean get() = urlProblem == null && (!keyRequired || apiKey.isNotBlank()) && test != ListState.Running

    /** The custom address is the official address of a certain service provider (the host name matches, see [ProviderPresets.forHost]): it is recommended to use that default instead. */
    val suggestedPreset: ProviderPreset? get() = if (isCustom && urlProblem == null) ProviderPresets.forHost(baseUrl) else null

    /** The address is the one of [suggestedPreset], but the selected interface type is different from it: it will definitely not work in this way. */
    val presetKindMismatch: Boolean
        get() = suggestedPreset?.let { s -> ProviderPresets.forBaseUrl(baseUrl.trim()) == s && kind != s.kind } == true

    /** Take the example of model name, which type of interface to press: if the address matches a certain preset, press that preset, otherwise press the selected interface type. */
    val exampleKind: ProviderConfig.Kind get() = suggestedPreset?.kind ?: kind

    /** Customize the name used when no name is given: the host name of the address (if the address is preset by a certain company, use the default name). */
    val defaultName: String get() = ProviderConnection.nameForUrl(baseUrl)

    /**
     * The name used when saving as a service provider: use the default name for default, use the
     * filled-in name for customization, and use [defaultName] if not filled in. Service providers are
     * grouped according to interface type, address and name. If the same address has different names,
     * then there are two providers (for example, two Keys for the same transfer station).
     */
    val providerName: String get() = if (isCustom) name.trim().ifEmpty { defaultName } else preset

    /** This company needs to put the session number in the request header: use it if the selected default is declared; if the customized address matches a certain default, use that default. */
    val sessionHeader: String? get() = presetFor(preset).sessionHeader ?: ProviderPresets.forBaseUrl(baseUrl.trim())?.sessionHeader

    /** The word in this company's own model name (see [ProviderPreset.familyTokens]): Use it if the selected preset is given, otherwise use the preset with the matching address. */
    val familyTokens: List<String> get() = presetFor(preset).familyTokens.ifEmpty { ModelOptions.familyOf(baseUrl) }

    companion object {
        fun of(p: ProviderPreset) = AddForm(p.label, p.kind, p.baseUrl, "")
        fun presetFor(label: String): ProviderPreset = ProviderPresets.all.firstOrNull { it.label == label } ?: ProviderPresets.custom
    }
}

/**
 * Configuring the model (when getting started) and setting › All states shared by the model: connected
 * providers, add provider form, three uses. [providers], [configs], [roles] are snapshots of
 * [ProviderStore], [keyed] is the service provider id where the Key is stored; [listing] is the progress
 * of each service provider refreshing the list (according to service provider id).
 */
data class ModelSetupUi(
    val form: AddForm,
    val formOpen: Boolean = false,
    val providers: List<Provider> = emptyList(),
    val configs: List<ProviderConfig> = emptyList(),
    val roles: RoleChoices = RoleChoices(),
    val keyed: Set<String> = emptySet(),
    val listing: Map<String, ListState> = emptyMap(),
    /** The account number of the ChatGPT service provider; if there is no such service provider, it will be null. */
    val chatGptAccount: ChatGptAccount? = null,
    /** Progress of logging in with ChatGPT. */
    val chatGptSignIn: ListState = ListState.Idle,
    /** The account number of the Google Gemini service provider; if there is no such provider, it will be null. */
    val geminiWebAccount: GeminiWebAccount? = null,
    /** Progress of logging in with Gemini Web. */
    val geminiWebSignIn: ListState = ListState.Idle,
    /** Whether to open the Gemini Web login pop-up window. */
    val geminiWebLoginOpen: Boolean = false,
    /** After successfully logging in for the first time, state once "I am using your ChatGPT package" and click "I understand" to close it. */
    val chatGptWelcome: Boolean = false,
    /** When logging out, ChatGPT did not confirm the logout (the local token has been cleared). */
    val chatGptRevokeUnconfirmed: Boolean = false,
    /** The connected Grok subscription; null if there is no such home. */
    val xaiAccount: SubscriptionAccount? = null,
    /** The one to which the OpenRouter account is authorized to connect; if not, it is null. */
    val openRouterAccount: SubscriptionAccount? = null,
    /** Login progress of xAI and OpenRouter. */
    val accountSignIn: AccountSignInUi = AccountSignInUi(),
    /** Open the details page of the service provider you just successfully logged in with your account ID: interface (see account and model, set master model), and clear it after opening. */
    val signedInProvider: String? = null,
    /**
     * Instructions when just saving the service provider: The first tried model failed to pass the trial
     * call, which one was used for the main model, or none of them passed, the user has to choose by
     * himself (see [ModelSetupViewModel.testAndSave]); or this company skipped the test and saved (see
     * [ModelSetupViewModel.saveWithoutTest]). Clear it when testing again and selecting the main model.
     */
    val probeNote: String? = null,
    /**
     * The service provider ID that has just been saved and has not yet been tested by any model: when
     * selecting the main model in it, try calling it first (see [probeBeforePick]); clear it after
     * selecting it. Two sources: none of the models being tried passed, or the user skipped the test and
     * saved it directly.
     */
    val pickToProbe: String? = null,
    /** The progress of the trial call after manually filling in the model name on the selection page (according to the service provider ID). */
    val manualPick: Map<String, ListState> = emptyMap(),
    /** The progress of "Retest" on the service provider page (by service provider ID). */
    val retest: Map<String, ListState> = emptyMap(),
    /** Speech-to-text is now an option, and this phone comes with built-in recognition capabilities (for usage cards and selection pages). */
    val speech: SpeechChoice = SpeechChoice.Auto,
    val systemSpeech: SystemCapability = SystemCapability(available = false, onDevice = false),
    /** The ID of the service provider that is trying to transfer a short piece of recording; if not available, it will be null. */
    val speechTesting: String? = null,
) {
    /** The form is always expanded when there is no service provider yet. */
    val showForm: Boolean get() = formOpen || providers.isEmpty()

    /**
     * To select [role] in [providerId], you must first try to call it (go to
     * [ModelSetupViewModel.pickManual]): choose the main model if you have just saved it and none of the
     * models have passed the test, or if you skip the test and save the one that has not been tested yet
     * ([Provider.savedUntested], it counts after reopening the app).
     */
    fun probeBeforePick(role: ModelRole, providerId: String): Boolean = role == ModelRole.Main &&
        (providerId == pickToProbe || providers.firstOrNull { it.id == providerId }?.savedUntested == true)

    /** Once you have selected the main model, you can "next step". */
    val canNext: Boolean get() = roles.main != null

    /** The configuration actually used for this purpose (when "using the main model", it is the main model). */
    fun configFor(role: ModelRole): ProviderConfig? = roles.resolve(role)?.let { id -> configs.firstOrNull { it.id == id } }

    fun providerOf(cfg: ProviderConfig): Provider? = providers.firstOrNull { it.id == cfg.providerId }

    /** All models available for a provider (remembered list plus existing configurations). */
    fun options(p: Provider): List<ModelOption> = ModelOptions.ofProvider(p, configs)

    /** All models of a service provider are arranged first, and the others remain in their original order. */
    fun sortedOptions(p: Provider): List<ModelOption> = options(p).sortedBy { if (rolesOf(p.id, it.id).isEmpty()) 1 else 0 }

    /** The actual purpose of using this model (including "using the main model") is in the order of main model, viewing pictures, and lightweight. */
    fun rolesOf(providerId: String, model: String): List<ModelRole> =
        ModelRole.entries.filter { role -> configFor(role)?.let { it.providerId == providerId && it.model == model } == true }

    /** What are the actual uses of this service provider? */
    fun rolesOf(p: Provider): List<ModelRole> = ModelRole.entries.filter { configFor(it)?.providerId == p.id }

    /**
     * For service providers that do not provide a list, the model names that have been used but have not
     * been tried to be called (see [Provider.verifiedModels]): These are filled in by hand, and the task
     * will fail if filled in incorrectly. For service providers with a list, the model is selected from
     * the list and does not count.
     */
    fun unverified(p: Provider): List<String> =
        roles.referenced.mapNotNull { id -> configs.firstOrNull { it.id == id && it.providerId == p.id }?.model }
            .distinct().filter(p::untested)

    /**
     * This company skipped the test and saved it directly (see [ModelSetupViewModel.saveWithoutTest]):
     * no model was listed, and none of the models passed the test. The service provider's page
     * accordingly says "it hasn't been tested yet", rather than the general "no model list is provided"
     * - the latter is wrong about it. After that, it will not be counted if the model is listed once or
     * passed once (see [ProviderStore.rememberModels], [ProviderStore.markVerified]).
     */
    fun untested(p: Provider): Boolean = p.savedUntested

    /** The company that logged in with the account has logged out (the tokens of ChatGPT and xAI have been cleared, and the Key of OpenRouter has been deleted). */
    fun signedOut(p: Provider): Boolean = when {
        p.kind == ProviderConfig.Kind.ChatGpt -> chatGptAccount?.signedIn != true
        p.kind == ProviderConfig.Kind.Xai -> xaiAccount?.signedIn != true
        p.signIn != null -> p.id !in keyed
        else -> false
    }

    /** This purpose clearly selects whether this model is used ("use the main model" does not count). */
    fun picked(role: ModelRole, providerId: String, model: String): Boolean {
        val id = (roles[role] as? RoleChoice.Model)?.configId ?: return false
        return configs.firstOrNull { it.id == id }?.let { it.providerId == providerId && it.model == model } == true
    }
}

/**
 * Configure the state and actions of the model. Add a service provider: Select Default → Fill in the Key
 * → "Test and Save" (list the model, and then ask the one who will be the main model to reply, save this
 * and the list only after it has been completed, do not say the reason, or not save); If the service
 * provider, address or Key is changed, the ongoing test will be invalidated. Purpose: Select from the
 * models remembered by all connected service providers, and the selection will take effect; you can also
 * "use the main model" or "disable" when viewing pictures, and "use the main model" when lightweight.
 * When connecting to the first one (which does not have a main model yet), one of its models is
 * automatically selected as the main model. Getting started and setting up › Model sharing.
 */
class ModelSetupViewModel(
    private val store: ProviderStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val browser: SignInBrowser? = null,
    /** Where to log in and run: The interface is for the App (it goes back to the background and the interface is rebuilt continuously, and the front-end service is turned on during this period); [scope] is used in the test. */
    private val signIns: ChatGptSignIns = ChatGptSignIns(scope),
    /** Speech-to-text option; if the testing and getting started process is not transmitted, the card will not appear. */
    private val speech: SpeechSettings? = null,
    private val systemSpeech: () -> SystemCapability = { SystemCapability(available = false, onDevice = false) },
    /** The transcriber I tried to use when selecting the voice model is the same construction method (with client identifier) as the one used when pressing and speaking. */
    private val transcriber: (Provider) -> Transcriber = { p -> Transcriber(ProviderStore.modelHttp, store.clientHeaders(p)) },
    /**
     * Scope for the post-login test message. It has to outlive this screen:
     * leaving Model Setup used to cancel the probe mid-request and report
     * "The coroutine scope left the composition" even though the login was fine.
     */
    private val probeScope: CoroutineScope = scope,
    /**
     * A fresh Gemini Web login should also move the router's sticky active
     * candidate back to it. Otherwise a task that had failed over to flash-lite
     * keeps using it even though the user just signed in again.
     */
    private val onGeminiWebConnected: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(snapshot(ModelSetupUi(AddForm.of(ProviderPresets.all.first()))))
    val state: StateFlow<ModelSetupUi> = _state.asStateFlow()

    private val _speechProbe = MutableStateFlow<Map<String, String>>(emptyMap())

    /** Service provider id -> The reason why the trial transfer failed when selecting its voice model (using the current term); it is cleared when it is selected or re-selected. */
    val speechProbe: StateFlow<Map<String, String>> = _speechProbe.asStateFlow()

    private var testJob: Job? = null
    private val refreshJobs = mutableMapOf<String, Job>()

    init {
        // Update when storage is changed elsewhere (developer options, etc.)
        scope.launch { merge(store.providers, store.configs, store.roles).collect { sync() } }
        speech?.let { s -> scope.launch { s.choice.collect { sync() } } }
        // Login ended elsewhere (cancelled on notification) or started before the interface was rebuilt:
        // follow its progress
        scope.launch {
            signIns.running.collect { running ->
                val prompt = signIns.devicePrompt
                val target = signIns.target
                _state.update {
                    when {
                        running && target == SignInTarget.ChatGpt && it.chatGptSignIn == ListState.Idle ->
                            it.copy(chatGptSignIn = ListState.Running)
                        !running && it.chatGptSignIn == ListState.Running -> snapshot(it.copy(chatGptSignIn = ListState.Idle))
                        running && AccountProvider.of(target) != null -> it.copy(
                            accountSignIn = it.accountSignIn.copy(
                                provider = AccountProvider.of(target),
                                running = true,
                                userCode = prompt?.userCode ?: it.accountSignIn.userCode,
                                openUrl = prompt?.openUrl ?: it.accountSignIn.openUrl,
                                expiresAtMs = prompt?.expiresAtMs ?: it.accountSignIn.expiresAtMs,
                            ),
                        )
                        !running && it.accountSignIn.running -> snapshot(it.copy(accountSignIn = AccountSignInUi()))
                        else -> it
                    }
                }
            }
        }
        // The short code of the device code may come after the interface is rebuilt.
        scope.launch {
            signIns.device.collect { prompt ->
                if (prompt == null) return@collect
                val provider = AccountProvider.of(signIns.target) ?: return@collect
                _state.update {
                    it.copy(accountSignIn = it.accountSignIn.copy(
                        provider = provider, running = true,
                        userCode = prompt.userCode, openUrl = prompt.openUrl, expiresAtMs = prompt.expiresAtMs,
                    ))
                }
            }
        }
        // The result of account login is handed over to the current interface (the login may be started
        // from the interface before reconstruction)
        scope.launch {
            signIns.outcome.collect { o ->
                if (o == null) return@collect
                val provider = AccountProvider.of(o.target) ?: return@collect
                _state.update {
                    if (o.providerId != null) snapshot(it.copy(accountSignIn = AccountSignInUi(), formOpen = false, signedInProvider = o.providerId))
                    else it.copy(accountSignIn = AccountSignInUi(provider = provider, failure = o.failure))
                }
                signIns.consumeOutcome(o)
            }
        }
    }

    private fun snapshot(ui: ModelSetupUi): ModelSetupUi {
        val providers = store.providers.value
        return ui.copy(
            providers = providers, configs = store.configs.value, roles = store.roles.value,
            keyed = providers.filter { store.hasKey(it.id) }.map { it.id }.toSet(),
            chatGptAccount = store.chatGptProvider()?.let { p ->
                store.chatGptSession(p.id).let { ChatGptAccount(it?.email.orEmpty(), it?.signedIn == true) }
            },
            geminiWebAccount = store.geminiWebProvider()?.let { p ->
                store.geminiWebSession(p.id).let { GeminiWebAccount(it?.email.orEmpty(), it?.signedIn == true) }
            },
            xaiAccount = store.xaiProvider()?.let { p ->
                store.accountSession(p.id).let { SubscriptionAccount(it?.label.orEmpty(), it?.signedIn == true) }
            },
            openRouterAccount = store.openRouterAccount()?.let { p -> SubscriptionAccount("", store.hasKey(p.id)) },
            speech = speech?.choice?.value ?: SpeechChoice.Auto,
            systemSpeech = systemSpeech(),
        )
    }

    private fun sync() = _state.update(::snapshot)

    // ---------- Add service provider ----------

    fun openForm() = _state.update { it.copy(formOpen = true) }

    fun closeForm() {
        testJob?.cancel()
        _state.update { it.copy(formOpen = false, form = AddForm.of(ProviderPresets.all.first())) }
    }

    fun selectPreset(label: String) {
        val p = AddForm.presetFor(label)
        if (p.label == _state.value.form.preset) return
        val key = _state.value.form.apiKey
        edit { AddForm.of(p).copy(apiKey = key) }
    }

    // If the interface type or address is changed, the "model not listed" last time will not be counted
    // and needs to be listed again.
    fun setKind(kind: ProviderConfig.Kind) = edit { it.copy(kind = kind, noList = false) }
    fun setBaseUrl(url: String) = edit { it.copy(baseUrl = url, noList = false) }
    fun setApiKey(key: String) = edit { it.copy(apiKey = key) }
    fun setModel(model: String) = edit { it.copy(model = model) }

    /** "Manually fill in the model name": Display the model name column when it is opened; clear it when it is closed (the column is always there when the model is not listed). */
    fun setManual(on: Boolean) = edit { it.copy(manual = on, model = if (on || it.noList) it.model else "") }
    // Cut by character (code point), do not cut emoji into half
    fun setName(name: String) = edit {
        val n = name.codePointCount(0, name.length)
        it.copy(name = if (n <= MAX_NAME) name else name.substring(0, name.offsetByCodePoints(0, MAX_NAME)))
    }

    /** The connection information has changed: the ongoing test and the last failure reason are invalid. */
    private fun edit(change: (AddForm) -> AddForm) {
        testJob?.cancel()
        _state.update { it.copy(form = change(it.form).copy(test = ListState.Idle)) }
    }

    /**
     * Test and save: List the model, then press the sequence of [ModelOptions.candidates] to let the
     * model call the tool once (see [probe]), then save the service provider, use the passed one as the
     * main model (when there is no main model), clear the form and close it. If the first test fails and
     * it is a problem with the model itself (the account is not opened or has no permissions, the model
     * does not exist, and the tool cannot be called), then try the next one, and try at most
     * [PROBE_CANDIDATES] Keys. The key has been proven to be correct. The one that has passed will be
     * the master model and say which of the first tests failed; if they have not passed, the service
     * provider and the undetermined master model will be saved, explain the reasons, and ask the user to
     * choose from the list. ([ModelSetupUi.probeNote], [ModelSetupUi.pickToProbe], try calling first
     * when selecting). The first one failed due to other reasons (Key, network, address, timeout, server
     * error) and the reason was that it did not exist.
     *
     * The model is not listed (the list interface does not exist, or the list is empty): maybe this
     * company really does not provide a list, or maybe the address and interface type are filled in
     * wrongly, and you can't tell the difference just by looking at the list. Therefore, instead of
     * saving it directly, the user is required to fill in a model name ([AddForm.noList]), and use it to
     * try to call it when testing: it will be saved as a service provider that does not provide a list.
     * If it fails, explain the reason and it will not be saved.
     *
     * After filling in the model name by hand ([AddForm.typedModel], you can click "Manually fill in the
     * model name" at any time), just try this one: it will be the same whether it is listed in the list,
     * not listed, or whether it is in the list. It does not matter if there are other errors in the list
     * (the Key is wrong, the server has an error, the response is not JSON...), the conclusion of the
     * trial call has the final say - the Key and address are incorrect, and the trial call will not
     * work. If there are other errors in the list when I am not busy filling it in, I will still tell
     * the reason and expand the model name column: some services just have a list interface that is not
     * easy to use, but I can still connect by filling in a model name.
     */
    fun testAndSave() {
        val f = _state.value.form
        if (!f.canTest) return
        testJob?.cancel()
        _state.update { it.copy(form = it.form.copy(test = ListState.Running), probeNote = null, pickToProbe = null) }
        testJob = scope.launch {
            val key = f.apiKey.trim()
            val baseUrl = f.baseUrl.trim()
            val typed = f.typedModel
            val models = when (val r = withContext(io) { store.listModels(f.kind, baseUrl, key.ifEmpty { null }, f.sessionHeader) }) {
                is ModelListResult.Ok -> r.models
                is ModelListResult.Failed -> {
                    if (r.reason != ListFailure.NotSupported && typed.isEmpty()) {
                        _state.update { it.copy(form = it.form.copy(test = ListState.Failed(r.message), manual = true)) }
                        return@launch
                    }
                    emptyList()
                }
            }
            if (models.isEmpty() && typed.isEmpty()) {
                _state.update { it.copy(form = it.form.copy(test = ListState.Idle, noList = true)) }
                return@launch
            }
            // The model name is filled in by hand: only try this one, the list (if any) will be saved as
            // usual
            if (typed.isNotEmpty()) {
                val listed = models.firstOrNull { it.id == typed }
                val base = (ProviderPresets.forBaseUrl(baseUrl) ?: ProviderPresets.custom).capabilities
                val caps = listed?.let { ModelOptions.merge(listOf(it), base).firstOrNull()?.capabilities } ?: base
                val outcome = probeModel(
                    f.kind, baseUrl, key.ifEmpty { null }, f.sessionHeader, typed, listed?.endpoint, caps, f.exampleKind,
                    listMissing = models.isEmpty() && f.isCustom,
                )
                if (outcome is ProbeOutcome.Failed) {
                    _state.update { it.copy(form = it.form.copy(test = ListState.Failed(outcome.message))) }
                    return@launch
                }
                val noTools = outcome is ProbeOutcome.NoToolCall
                withContext(io) {
                    val p = store.connect(
                        f.providerName, f.kind, baseUrl, key, models, f.sessionHeader, setOf(typed),
                        typed = if (listed == null) setOf(typed) else emptySet(),
                    )
                    if (!noTools && store.roles.value.main == null) use(ModelRole.Main, p, typed, ModelOptions.ofProvider(p, store.list()))
                }
                val note = if (noTools) tr(R.string.model_probe_no_tools, typed) else null
                _state.update { snapshot(it.copy(form = AddForm.of(ProviderPresets.all.first()), formOpen = false, probeNote = note)) }
                return@launch
            }
            val probed = when (val r = probe(f, baseUrl, key.ifEmpty { null }, models)) {
                is ListProbe.Blocked -> {
                    _state.update { it.copy(form = it.form.copy(test = ListState.Failed(r.message))) }
                    return@launch
                }
                is ListProbe.Done -> r
            }
            val noneUsable = probed.main == null && probed.rejected.isNotEmpty()
            val saved = withContext(io) {
                val p = store.connect(f.providerName, f.kind, baseUrl, key, models, f.sessionHeader, setOfNotNull(probed.main))
                if (!noneUsable && store.roles.value.main == null) {
                    val options = ModelOptions.ofProvider(p, store.list())
                    (probed.main ?: ModelOptions.defaultMain(options, f.familyTokens) ?: options.firstOrNull()?.id)
                        ?.let { m -> use(ModelRole.Main, p, m, options) }
                }
                p
            }
            _state.update {
                snapshot(it.copy(
                    form = AddForm.of(ProviderPresets.all.first()), formOpen = false,
                    probeNote = probeNote(probed), pickToProbe = saved.id.takeIf { noneUsable },
                ))
            }
        }
    }

    /**
     * Do not test, save directly: only save the service provider according to the filled interface type,
     * address and Key, do not list the model, do not try to call (see [AddForm.canSaveUntested]). The
     * saved one has [Provider.savedUntested]: the service provider's page will clearly state that it has
     * not been tested yet, and will leave "retest"; if the main model is not determined,
     * [ModelSetupUi.pickToProbe] will be set, and then the main model selected from the list will be
     * tried to call once (see [ModelSetupUi.probeBeforePick]).
     *
     * When the model name ([AddForm.typedModel]) is filled in by hand, it will only be recorded as
     * hand-filled - it will not be lost if it is not in the list, or the model cannot be listed in this
     * company, and the list will be refreshed. Models that have not been tested will not automatically
     * become the main model: when there is no main model, the user has to select it. When selecting, try
     * to call it first, and then use it after it works.
     *
     * The list parameter gives an empty list ("listed but not one") instead of null: so that "no model
     * is listed" and [Provider.savedUntested] clearly distinguish the two situations - there is really
     * no list, or it is not listed this time. The old [testAndSave] doesn't move a single line.
     */
    fun saveWithoutTest() {
        val f = _state.value.form
        if (!f.canSaveUntested) return
        testJob?.cancel()
        _state.update { it.copy(form = it.form.copy(test = ListState.Running), probeNote = null, pickToProbe = null) }
        testJob = scope.launch {
            val key = f.apiKey.trim()
            val baseUrl = f.baseUrl.trim()
            val typed = f.typedModel
            val saved = withContext(io) {
                store.connect(
                    f.providerName, f.kind, baseUrl, key.ifEmpty { null }, emptyList(), f.sessionHeader, untested = true,
                ).also { p -> if (typed.isNotEmpty()) store.markTyped(p.id, typed) }
            }
            _state.update {
                snapshot(it.copy(
                    form = AddForm.of(ProviderPresets.all.first()), formOpen = false,
                    probeNote = tr(R.string.model_saved_untested, f.providerName),
                    // When there is no main model, selecting the main model in it will try to call it
                    // first.
                    pickToProbe = saved.id.takeIf { store.roles.value.main == null },
                ))
            }
        }
    }

    /**
     * The conclusion of the trial call: passed, [model] did not call the tool, failed ([message] is the
     * reason for showing it to others). If the failure is a problem with the model itself (this account
     * cannot be used, does not have this model, does not support tools), [modelReason] is the cause of
     * the failure, and it may be sufficient to change to another model; [notAvailable] is true that it
     * is not activated or has no permission; [serverSide] is true that there is a server error (5xx), it
     * may be that the upstream of this model is down, see [probe].
     */
    private sealed interface ProbeOutcome {
        data object Passed : ProbeOutcome
        data class NoToolCall(val model: String) : ProbeOutcome
        data class Failed(
            val message: String, val modelReason: String? = null,
            val notAvailable: Boolean = false, val serverSide: Boolean = false,
        ) : ProbeOutcome
    }

    /**
     * A model that was tried first and failed: [reason] is short; [serverSide] is a true server error
     * (5xx), see [probe]. [skipped] I haven’t tried it at all (the interface states that it does not use
     * this interface type), so I can’t say “tried” in the copy.
     */
    private data class Rejected(
        val model: String, val reason: String, val notAvailable: Boolean, val serverSide: Boolean = false,
        val skipped: Boolean = false,
    )

    /** The result of trying to call when there is a list: [Blocked] is the first one that fails due to reasons other than the model and does not exist; [Done] see [probe]. */
    private sealed interface ListProbe {
        data class Blocked(val message: String) : ListProbe
        /** [main] is the model that passed; [rejected] was tried before but failed. All empty means there are no models to try. */
        data class Done(val main: String?, val rejected: List<Rejected>) : ListProbe
    }

    /**
     * After listing the models, press the order of [ModelOptions.candidates] (if not, use the first one)
     * to let the model call the tool once (see [probeModel]), try at most [PROBE_CANDIDATES]: if it
     * passes, stop; if it fails, if it is the problem of the model itself, try the next one. The first
     * one that fails due to other reasons is [ListProbe.Blocked]; the subsequent ones that fail due to
     * other reasons will not be tried again (the Key has been proven to be correct), and will be treated
     * the same as if they failed. If there is no usable item in the list, it will be considered as
     * passed and no attempt will be made.
     *
     * A server-side error (5xx) does not necessarily mean that the entire company is unavailable: the
     * aggregation service provider puts many upstream models in the same list, and this will happen if
     * one of the upstreams is down. So 5xx first press this model and note it if it fails, and then try
     * the next one: if there is a pass later, it means that the problem is with that model, so save it
     * as usual; if all the tests are like this, it will be regarded as unavailable and will not be
     * saved. The Key has not been proven to be correct when it has only encountered 5xx. After that, it
     * will still not exist if it fails for other reasons.
     */
    private suspend fun probe(f: AddForm, baseUrl: String, key: String?, models: List<ListedModel>): ListProbe {
        // You do not need to go through this form when logging in using ChatGPT (the login itself has
        // been checked); the form only gives the interface type of Key, so block it again here.
        if (f.kind == ProviderConfig.Kind.ChatGpt || f.kind == ProviderConfig.Kind.Xai || f.kind == ProviderConfig.Kind.GeminiWeb) {
            return ListProbe.Done(null, emptyList())
        }
        val base = (ProviderPresets.forBaseUrl(baseUrl) ?: ProviderPresets.custom).capabilities
        val options = ModelOptions.merge(models, base)
        // When the interface states which protocols the model can use, only try the ones that can be
        // used by this interface type: calling with a mismatched protocol will inevitably fail (for
        // example, the aggregation service ranks models that only support /messages first, and the
        // current interface is OpenAI compatible). If you hit a wall with all three, you will have to
        // wait for PROBE_TIMEOUT_MS × 3 minutes. If the interface is separately specified for a model in
        // the list (see [ListedModel.endpoint]), the call will be based on the protocol it says. It has
        // nothing to do with the interface type selected here and cannot be filtered out; if the
        // protocol is unknown (this field is not written), it will still be tried.
        val mismatched = mutableListOf<String>()
        val usable = ModelOptions.candidates(options, f.familyTokens).filter { m ->
            val listed = models.firstOrNull { it.id == m }
            if (listed?.endpoint != null) return@filter true
            val protocols = listed?.protocols ?: return@filter true
            if (f.kind.protocol in protocols) true else { mismatched += m; false }
        }
        if (usable.isEmpty() && mismatched.isNotEmpty()) {
            val reason = tr(R.string.model_reason_wrong_protocol)
            return ListProbe.Done(null, mismatched.take(PROBE_CANDIDATES).map { Rejected(it, reason, notAvailable = false, skipped = true) })
        }
        val order = usable.ifEmpty { listOfNotNull(options.firstOrNull()?.id) }
        val rejected = mutableListOf<Rejected>()
        var firstServerError: String? = null
        for ((i, model) in order.take(PROBE_CANDIDATES).withIndex()) {
            val caps = options.first { it.id == model }.capabilities
            val endpoint = models.firstOrNull { it.id == model }?.endpoint
            when (val o = probeModel(f.kind, baseUrl, key, f.sessionHeader, model, endpoint, caps, f.exampleKind, listMissing = false)) {
                ProbeOutcome.Passed -> return ListProbe.Done(model, rejected)
                is ProbeOutcome.NoToolCall -> rejected += Rejected(model, tr(R.string.model_reason_no_tools), notAvailable = false)
                is ProbeOutcome.Failed -> when {
                    o.modelReason != null || o.notAvailable ||
                        o.message.contains("no provider supported", ignoreCase = true) ||
                        o.message.contains("provider not supported", ignoreCase = true) -> {
                        val reason = o.modelReason ?: tr(R.string.model_reason_not_available, o.message)
                        rejected += Rejected(model, reason, notAvailable = true, serverSide = o.serverSide)
                        if (o.serverSide && firstServerError == null) firstServerError = o.message
                    }
                    // When there is only 5xx in the front, the Key has not been proved to be correct:
                    // when encountering Key, network, address, timeout, etc., they still do not exist.
                    firstServerError != null || i == 0 -> return ListProbe.Blocked(o.message)
                    else -> {
                        rejected += Rejected(model, o.message, notAvailable = false)
                        break
                    }
                }
            }
        }
        // Everything I tried was an error on the server side: most of them were unavailable or not
        // available.
        if (rejected.isNotEmpty() && rejected.all { it.serverSide }) {
            return ListProbe.Blocked(firstServerError ?: rejected.first().reason)
        }
        return ListProbe.Done(null, rejected)
    }

    /**
     * Save a sentence that the service provider will show to others: the first one failed, the later
     * ones passed, tell which ones failed, and which one was used for the main model; if none of them
     * passed, tell why the first one failed, which ones were tried, and ask the user to choose from the
     * list. All successfully are null.
     */
    private fun probeNote(r: ListProbe.Done): String? {
        if (r.rejected.isEmpty()) return null
        val names = r.rejected.joinToString(tr(R.string.list_sep)) { it.model }
        val first = r.rejected.first()
        if (r.main != null) return tr(R.string.model_probe_fallback, names, first.reason, r.main)
        val head = tr(R.string.model_probe_none_usable, first.model, first.reason)
        // Haven’t tried any of them (even if the interface is clearly stated, I won’t use this interface
        // type): the reason has been clearly stated in the head, and I can’t write “tried” anymore.
        if (r.rejected.size == 1 || r.rejected.all { it.skipped }) return head
        val tried = if (r.rejected.all { it.notAvailable }) tr(R.string.model_probe_tried_unavailable, names) else tr(R.string.model_probe_tried, names)
        return "$head\n$tried"
    }

    /**
     * Let [model] call the tool once, and follow the same model building path as the real task (the
     * request header and session number are the same): the key is wrong, the request header required by
     * the service provider is missing, the model name is wrong, and the model does not call the tool, it
     * is exposed here without waiting for the first task. If it exceeds [PROBE_TIMEOUT_MS], it will not
     * be counted as a failure, and the error report from the server will be sent as it is; if the model
     * cannot be found, please indicate which model it is and what the model name probably looks like (an
     * example of [exampleKind]). The list is not listed even when [listMissing] is true. It's probably
     * because the address or interface type is filled in incorrectly. Let's talk about it together.
     */
    private suspend fun probeModel(
        kind: ProviderConfig.Kind, baseUrl: String, key: String?, sessionHeader: String?,
        model: String, endpoint: ModelEndpoint?, caps: Capabilities, exampleKind: ProviderConfig.Kind, listMissing: Boolean,
    ): ProbeOutcome {
        val result = try {
            withContext(io) { withTimeout(PROBE_TIMEOUT_MS) { store.probe(kind, baseUrl, key, sessionHeader, model, endpoint, caps) } }
        } catch (e: TimeoutCancellationException) {
            return ProbeOutcome.Failed(tr(R.string.model_probe_timeout, model, (PROBE_TIMEOUT_MS / 1000).toInt()))
        }
        return when (result) {
            ProbeResult.ToolCalled -> ProbeOutcome.Passed
            ProbeResult.NoToolCall -> ProbeOutcome.NoToolCall(model)
            is ProbeResult.Failed -> when (val e = result.error) {
                is ModelError.ModelNotAvailable -> {
                    val detail = e.detail.ifEmpty { e.message.orEmpty() }
                    ProbeOutcome.Failed(
                        tr(R.string.model_probe_not_available, model, detail),
                        tr(R.string.model_reason_not_available, detail), notAvailable = true,
                    )
                }
                is ModelError.ToolsUnsupported -> ProbeOutcome.Failed(tr(R.string.model_probe_no_tools_pick, model), tr(R.string.model_reason_no_tools))
                is ModelError.ModelNotFound -> ProbeOutcome.Failed(
                    if (listMissing) tr(R.string.model_probe_not_found_no_list, model, ModelSetupCopy.modelExample(exampleKind))
                    else tr(R.string.model_probe_not_found, model, ModelSetupCopy.modelExample(exampleKind)),
                    tr(R.string.model_reason_not_found),
                )
                // 5xx: It may be that the upstream of this model is down. If it fails first, it will be
                // considered as failed. It is up to [probe] to decide whether to try the next one.
                is ModelError.Server -> ProbeOutcome.Failed(
                    tr(R.string.model_probe_failed, model, e.message.orEmpty()),
                    tr(R.string.model_reason_server, "HTTP ${e.status}"), serverSide = true,
                )
                // The problem with the agency part: It’s impossible to say it’s an agent, but not a
                // model or service provider.
                is ModelError.Proxy -> ProbeOutcome.Failed(tr(R.string.model_probe_failed, model, proxyReason(e)))
                else -> ProbeOutcome.Failed(tr(R.string.model_probe_failed, model, e.message.orEmpty()))
            }
        }
    }

    /** Try calling [model] from the connected service provider: use the saved Key and request header, and can be based on the existing configuration or default. */
    private suspend fun probeSaved(p: Provider, model: String): ProbeOutcome {
        val options = ModelOptions.ofProvider(p, store.list())
        val caps = options.firstOrNull { it.id == model }?.capabilities ?: ModelOptions.baseOf(p)
        val endpoint = p.models?.firstOrNull { it.id == model }?.endpoint
        val example = ProviderPresets.forHost(p.baseUrl)?.kind ?: p.kind
        return probeModel(p.kind, p.baseUrl, store.providerKey(p.id), p.sessionHeader, model, endpoint, caps, example, listMissing = false)
    }

    // ---------- Log in with ChatGPT ----------

    /**
     * Open the browser and log in to ChatGPT, change the token after the callback, check, list the
     * model, save (or update) the ChatGPT service provider, and return to Mina. If there is no main
     * model yet, select one of its models as the main model. After successful registration for the first
     * time, the amount of the package currently used will be indicated. If it fails, state the reason
     * (and write it down for diagnostic information). Running in [signIns]: When the user logs in in the
     * browser, Mina is in the background and cannot follow the interface.
     */
    fun signInWithChatGpt() {
        val browser = browser ?: return
        if (_state.value.chatGptSignIn == ListState.Running || signIns.running.value) return
        _state.update { it.copy(chatGptSignIn = ListState.Running, chatGptRevokeUnconfirmed = false) }
        signIns.start {
            val (listProblem, first) = try {
                withContext(io) {
                    val (hostId, clientId) = store.chatGptRegistration()
                    val existing = store.chatGptProvider()
                    val session = signInWithChatGpt(
                        store.chatGptAuth, hostId, clientId, existing?.let { store.chatGptSession(it.id) },
                        openBrowser = browser::open, onRegistered = store::rememberChatGptClient,
                        page = { e -> browser.page(e?.let { ModelsCopy.chatGptFailure(it.reason, it.code) }) },
                    )
                    val listed = store.listModels(ProviderConfig.Kind.ChatGpt, ChatGptAuth.API_BASE, session.accessToken)
                    val p = store.connectChatGpt(session, (listed as? ModelListResult.Ok)?.models ?: existing?.models.orEmpty())
                    if (store.roles.value.main == null) {
                        val options = ModelOptions.ofProvider(p, store.list())
                        (ModelOptions.defaultMain(options) ?: options.firstOrNull()?.id)?.let { m -> use(ModelRole.Main, p, m, options) }
                    }
                    (listed as? ModelListResult.Failed)?.message to (clientId == null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = e as? SignInException ?: SignInException(SignInFailure.Network, e.message.orEmpty(), e)
                signIns.recordFailure(failure)
                browser.returnToApp()
                _state.update { it.copy(chatGptSignIn = ListState.Failed(ModelsCopy.chatGptFailure(failure.reason, failure.code))) }
                return@start
            }
            browser.returnToApp()
            val id = store.chatGptProvider()?.id
            _state.update {
                snapshot(it.copy(
                    chatGptSignIn = ListState.Idle, chatGptWelcome = first, formOpen = false,
                    listing = if (listProblem != null && id != null) it.listing + (id to ListState.Failed(listProblem)) else it.listing - id.orEmpty(),
                ))
            }
        }
    }

    /** Abort the ongoing login (the local callback is turned off). */
    fun cancelChatGptSignIn() {
        signIns.cancel()
        _state.update { it.copy(chatGptSignIn = ListState.Idle) }
    }

    fun dismissChatGptWelcome() = _state.update { it.copy(chatGptWelcome = false) }

    /**
     * Log in to xAI or OpenRouter with an account: xAI opens the verified verification page in the
     * browser, leaves a short code on the interface, and waits for the user to confirm; OpenRouter opens
     * the authorization page, and after agreeing, returns to the local callback and changes to a key
     * belonging to the user. After success, list the models, save this one, select one if there is no
     * main model, and then open its details page ([ModelSetupUi.signedInProvider]). On failure just say
     * the category and error code (and write it down for diagnostic information). Like ChatGPT, it runs
     * in [signIns]: Mina is in the background when the user is in the browser.
     */
    fun signInWithAccount(provider: AccountProvider) {
        val browser = browser ?: return
        if (signIns.running.value || _state.value.accountSignIn.running || _state.value.chatGptSignIn == ListState.Running) return
        _state.update { it.copy(accountSignIn = AccountSignInUi(provider = provider, running = true)) }
        val started = signIns.start(
            target = provider.target,
            noticeTitle = tr(R.string.account_signin_notif_title),
            noticeBody = tr(R.string.account_signin_notif_body),
        ) {
            try {
                val (p, failed) = withContext(io) {
                    val (p, listed) = when (provider) {
                        AccountProvider.Xai -> connectXai(browser)
                        AccountProvider.OpenRouter -> connectOpenRouter(browser)
                    }
                    if (store.roles.value.main == null) {
                        val options = ModelOptions.ofProvider(p, store.list())
                        (ModelOptions.defaultMain(options) ?: options.firstOrNull()?.id)?.let { m -> use(ModelRole.Main, p, m, options) }
                    }
                    p to (listed as? ModelListResult.Failed)
                }
                browser.returnToApp()
                _state.update {
                    it.copy(listing = if (failed != null) it.listing + (p.id to ListState.Failed(failed.message)) else it.listing - p.id)
                }
                signIns.finishAccount(AccountOutcome(provider.target, providerId = p.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val failure = e as? AccountSignInException ?: AccountSignInException(AccountSignInFailure.Network, cause = e)
                signIns.recordAccountFailure(provider.name, failure.reason.name, failure.code)
                browser.returnToApp()
                signIns.finishAccount(AccountOutcome(provider.target, failure = ModelsCopy.accountFailure(failure)))
            }
        }
        if (!started) _state.update { it.copy(accountSignIn = AccountSignInUi()) }
    }

    /** Log in with xAI device code, list models, and save (or update) which one. */
    private suspend fun connectXai(browser: SignInBrowser): Pair<Provider, ModelListResult> {
        val session = store.xaiAuth.signIn(openBrowser = browser::open, onChallenge = ::publishDevice)
        val existing = store.xaiProvider()
        val listed = store.listModels(ProviderConfig.Kind.Xai, session.apiBase.ifBlank { XaiAuth.API_BASE }, session.accessToken)
        return store.connectAccount(session, (listed as? ModelListResult.Ok)?.models ?: existing?.models.orEmpty()) to listed
    }

    /** Change the OpenRouter authorization key, list the models, and click the OpenRouter default to save (or update) that one. */
    private suspend fun connectOpenRouter(browser: SignInBrowser): Pair<Provider, ModelListResult> {
        val key = signInWithOpenRouter(store.openRouterAuth, browser::open, page = { e -> browser.page(e?.let(ModelsCopy::accountFailure)) })
        val existing = store.openRouterAccount()
        val listed = store.listModels(ProviderConfig.Kind.OpenAiCompat, OpenRouterAuth.API_BASE, key)
        return store.connectOpenRouter(key, (listed as? ModelListResult.Ok)?.models ?: existing?.models.orEmpty()) to listed
    }

    /** Leave the shortcode on the interface. Opening the browser is done by the login process, so I won’t open it again here. */
    private fun publishDevice(c: DeviceChallenge) {
        val openUrl = c.verificationUriComplete ?: c.verificationUri
        val expiresAt = System.currentTimeMillis() + c.expiresInSec * 1000
        // The interface is then displayed via signIns.device (the reconstructed interface can also be
        // received)
        signIns.showDevice(DevicePrompt(c.userCode, openUrl, expiresAt))
    }

    fun cancelAccountSignIn() {
        signIns.cancel()
        _state.update { it.copy(accountSignIn = AccountSignInUi()) }
    }

    fun dismissAccountFailure() = _state.update {
        it.copy(
            accountSignIn = AccountSignInUi(),
            geminiWebSignIn = if (it.geminiWebSignIn is ListState.Failed) ListState.Idle else it.geminiWebSignIn
        )
    }

    /** The details page has been opened. */
    fun signedInShown() = _state.update { it.copy(signedInProvider = null) }

    /** Log out of the account and log in: xAI clears the local token, OpenRouter deletes the local Key; keep the service provider and purpose, log in again and continue using it. */
    fun signOutAccount(providerId: String) {
        scope.launch {
            withContext(io) {
                val p = store.provider(providerId) ?: return@withContext
                if (p.kind == ProviderConfig.Kind.Xai) store.signOutAccount(providerId) else store.signOutOpenRouter(providerId)
            }
            _state.update { snapshot(it.copy(listing = it.listing - providerId)) }
        }
    }

    /** Set the default model (see [ModelOptions.defaultMain]) as the main model. */
    fun useAsMain(providerId: String) {
        val p = store.provider(providerId) ?: return
        val options = ModelOptions.ofProvider(p, store.list())
        val m = ModelOptions.defaultMain(options) ?: options.firstOrNull()?.id ?: return
        pick(ModelRole.Main, providerId, m)
    }

    /** Exit ChatGPT and log in: log out of the server and clear the token locally; the service provider and purpose remain unchanged, and you can continue to use it by logging in again. */
    fun signOutChatGpt(providerId: String) {
        scope.launch {
            val revoked = withContext(io) { store.signOutChatGpt(providerId) }
            _state.update { snapshot(it.copy(chatGptRevokeUnconfirmed = !revoked, listing = it.listing - providerId)) }
        }
    }

    // ---------- Log in with Google Gemini ----------

    fun signInWithGeminiWeb() {
        if (_state.value.geminiWebSignIn == ListState.Running) return
        _state.update { it.copy(geminiWebLoginOpen = true, geminiWebSignIn = ListState.Running) }
    }

    fun onGeminiWebLoginSuccess(session: GeminiWebSession, selfTest: Boolean = true) {
        _state.update { it.copy(geminiWebLoginOpen = false, formOpen = false) }
        scope.launch {
            val p = withContext(io) {
                // Always install the current model list: reusing the list saved by
                // an older build kept dead model ids that Google no longer serves.
                val provider = store.connectGeminiWeb(session, GeminiWebClient.DEFAULT_MODELS)
                // The router may have marked gemini_web as a bad key when the old
                // session expired. A fresh sign-in is proof that the key works
                // again; leaving the flag set would keep routing around it.
                AppFreeKeyRouter.clearGeminiWebAuthFailure()
                val options = ModelOptions.ofProvider(provider, store.list())
                val argonOption = options.firstOrNull { it.id == "gemini-4-argon" } ?: options.firstOrNull()
                argonOption?.let { m ->
                    use(ModelRole.Main, provider, m.id, options)
                    onGeminiWebConnected(m.id)
                }
                if (store.roles.value.light is RoleChoice.SameAsMain || store.roles.value.light == null) {
                    val lightOption = options.firstOrNull { it.id == "gemini-3.1-pro" }
                        ?: options.firstOrNull { it.id == "gemini-3.5-flash-lite" }
                    lightOption?.let { m -> use(ModelRole.Light, provider, m.id, options) }
                }
                if (store.roles.value.vision is RoleChoice.Off || store.roles.value.vision == null) {
                    argonOption?.let { m -> use(ModelRole.Vision, provider, m.id, options) }
                }
                provider
            }
            // The login itself is done: say so now instead of keeping the user on
            // "Signing in" while a test message streams.
            _state.update {
                snapshot(it.copy(
                    signedInProvider = p.id,
                    geminiWebSignIn = ListState.Idle,
                    geminiWebLoginOpen = false,
                    formOpen = false
                ))
            }
            if (!selfTest) return@launch
            // End-to-end check: a login that only passes the account probe can
            // still be unusable from the chat endpoint, and the user deserves the
            // real error instead of silence. It runs off this screen's lifecycle:
            // leaving Model Setup to try the chat must not cancel the request.
            probeScope.launch {
                val testError = withContext(io) {
                    try {
                        val probeModel = GeminiWebModel(
                            session = session,
                            model = "gemini-4-argon",
                            capabilities = Capabilities(toolCalls = false, vision = false, contextTokens = 1_000_000, reasoning = false),
                            http = GeminiWebClient.geminiWebHttp,
                            onSessionUpdated = { store.updateGeminiWebSession(p.id, it) },
                        )
                        probeModel.complete(
                            "",
                            listOf(Message(Role.User, listOf(Part.Text("Reply with the single word: OK")), Author.User)),
                            emptyList()
                        )
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        e.message
                    }
                }
                ChatTrace.record(
                    "gemini self-test error=${testError ?: "none"} " +
                        "at=${if (session.snlm0e.isNotBlank()) "yes" else "no"} " +
                        "bl=${session.cfb2h} cookies=${session.cookies.split(';').count { it.isNotBlank() }}"
                )
                if (testError != null) {
                    _state.update {
                        snapshot(it.copy(
                            geminiWebSignIn = ListState.Failed("Connected, but the test message failed: $testError")
                        ))
                    }
                }
            }
        }
    }

    fun onGeminiWebLoginFailed(reason: String) {
        _state.update {
            it.copy(
                geminiWebSignIn = ListState.Failed(reason),
                geminiWebLoginOpen = false
            )
        }
    }

    fun cancelGeminiWebSignIn() {
        _state.update { it.copy(geminiWebSignIn = ListState.Idle, geminiWebLoginOpen = false) }
    }

    fun signOutGeminiWeb(providerId: String) {
        scope.launch {
            withContext(io) {
                store.signOutGeminiWeb(providerId)
                // The login WebView keeps Google cookies of its own; without
                // clearing them the next Sign-in loads the old session silently.
                runCatching { CookieManager.getInstance().removeAllCookies(null) }
                runCatching { CookieManager.getInstance().flush() }
            }
            _state.update { snapshot(it.copy(listing = it.listing - providerId)) }
        }
    }

    // ---------- Purpose ----------

    fun setReasoningEffort(role: ModelRole, effort: String?) {
        store.setReasoningEffort(role, effort)
        sync()
    }

    fun setModelContext(configId: String, settings: ModelContextSettings): Boolean {
        val saved = store.setModelContext(configId, settings)
        sync()
        return saved
    }

    fun setModelVision(providerId: String, model: String, sees: Boolean) {
        store.setModelVision(providerId, model, sees)
        sync()
    }

    /** Use [model] under [providerId] as [role] (Both the selection page and the "Use as" panel go here): If there is no configuration, create one on the spot. */
    fun pick(role: ModelRole, providerId: String, model: String) {
        val m = model.trim()
        if (m.isEmpty()) return
        scope.launch {
            withContext(io) {
                val p = store.provider(providerId) ?: return@withContext
                use(role, p, m, ModelOptions.ofProvider(p, store.list()))
            }
            // After selecting the main model, the instructions for trial calling are over.
            _state.update { snapshot(if (role == ModelRole.Main) it.copy(probeNote = null, pickToProbe = null) else it) }
        }
    }

    /** Viewing pictures, lightweight fixed options: "Use main model" or (only for viewing pictures) "Don't use". */
    fun choose(role: ModelRole, choice: RoleChoice) {
        scope.launch {
            withContext(io) { store.choose(role, choice) }
            sync()
        }
    }

    /**
     * On the selection page, you have manually filled in the model name for a certain company (each
     * company can fill in the name: if it does not provide a list, there is nothing in the list), or you
     * can choose the main model from the company that has just saved it and none of the models has
     * passed the test (see [ModelSetupUi.probeBeforePick]): try calling it once, and then use it as
     * [role] if it passes, and then call [onPicked]; if you don't know the reason, don't select it. The
     * main model also calls tools.
     */
    fun pickManual(role: ModelRole, providerId: String, model: String, onPicked: () -> Unit = {}) {
        val m = model.trim()
        if (m.isEmpty() || _state.value.manualPick[providerId] == ListState.Running) return
        val p = store.provider(providerId) ?: return
        _state.update { it.copy(manualPick = it.manualPick + (providerId to ListState.Running)) }
        scope.launch {
            val outcome = probeSaved(p, m)
            val problem = when {
                outcome is ProbeOutcome.Failed -> outcome.message
                outcome is ProbeOutcome.NoToolCall && role == ModelRole.Main -> tr(R.string.model_probe_no_tools_pick, m)
                else -> null
            }
            if (problem != null) {
                _state.update { it.copy(manualPick = it.manualPick + (providerId to ListState.Failed(problem))) }
                return@launch
            }
            withContext(io) {
                store.markVerified(p.id, m)
                // Those that are not in the list are recorded as hand-filled: they will not be lost
                // after refreshing the list, and mark them when selecting.
                if (p.models.orEmpty().none { it.id == m }) store.markTyped(p.id, m)
                store.provider(p.id)?.let { fresh -> use(role, fresh, m, ModelOptions.ofProvider(fresh, store.list())) }
            }
            _state.update {
                snapshot(if (role == ModelRole.Main) it.copy(manualPick = it.manualPick - providerId, probeNote = null, pickToProbe = null)
                    else it.copy(manualPick = it.manualPick - providerId))
            }
            onPicked()
        }
    }

    // ---------- Speech to text ----------

    /** Select "Comes with your phone" or "Don't use it", it will take effect immediately. */
    fun pickSpeech(choice: SpeechChoice) {
        if (choice is SpeechChoice.Provider) return
        speech?.set(choice)
        _speechProbe.value = emptyMap()
        sync()
    }

    /**
     * Select the voice model of a certain service provider: first take a short period of silence and try
     * to transfer it (by the way, you can identify which interface to use). If it works, save the
     * selection and mark it as verified, and then adjust [onPicked]; if it doesn't work, just tell the
     * reason under that group ([speechProbe]), and don't change the current selection.
     */
    fun pickSpeechModel(providerId: String, model: String, onPicked: () -> Unit = {}) {
        val m = model.trim()
        val settings = speech ?: return
        if (m.isEmpty() || _state.value.speechTesting != null) return
        val p = store.provider(providerId) ?: return
        _speechProbe.value = _speechProbe.value - providerId
        _state.update { it.copy(speechTesting = providerId) }
        scope.launch {
            val problem = try {
                val method = withContext(io) {
                    withTimeout(PROBE_TIMEOUT_MS) { transcriber(p).detect(p, store.providerKey(p.id), m) }
                }
                withContext(io) { store.markVerified(p.id, m) }
                settings.set(SpeechChoice.Provider(p.id, m, method))
                null
            } catch (e: TimeoutCancellationException) {
                tr(R.string.speech_pick_timeout, m, (PROBE_TIMEOUT_MS / 1000).toInt())
            } catch (e: CancellationException) {
                throw e
            } catch (e: ModelError) {
                speechFailure(m, p, e)
            } catch (e: Exception) {
                tr(R.string.speech_pick_failed, e.message.orEmpty())
            }
            if (problem != null) _speechProbe.value = _speechProbe.value + (providerId to problem)
            _state.update { snapshot(it.copy(speechTesting = null)) }
            if (problem == null) onPicked()
        }
    }

    /** The reasons why the trial transfer failed: the account has not been opened, and the model cannot be found. Use the same explanation as when selecting the main model, and the rest will follow the original words given by the service provider. */
    private fun speechFailure(model: String, p: Provider, e: ModelError): String = when (e) {
        is ModelError.ModelNotAvailable -> tr(R.string.model_probe_not_available, model, e.detail.ifEmpty { e.message.orEmpty() })
        is ModelError.ModelNotFound -> tr(R.string.model_probe_not_found, model, ModelSetupCopy.modelExample(ProviderPresets.forHost(p.baseUrl)?.kind ?: p.kind))
        is ModelError.Proxy -> tr(R.string.speech_pick_failed, proxyReason(e))
        else -> tr(R.string.speech_pick_failed, e.message.orEmpty())
    }

    private fun proxyReason(e: ModelError.Proxy): String = tr(
        if (e.failure == ProxyFailure.AuthFailed) R.string.net_proxy_auth_failed else R.string.net_proxy_unreachable,
    )

    /** When the selection page is opened, the results of the last trial transfer will be invalidated. */
    fun clearSpeechProbe() { _speechProbe.value = emptyMap() }

    /** When the selection page is opened, the results of the last manual test call are invalid. */
    fun clearManualPick() = _state.update { it.copy(manualPick = emptyMap()) }

    /**
     * "Re-test": Try calling the currently used but unverified models (see [ModelSetupUi.unverified])
     * one by one. If the model passes, it will be recorded as verified; if there is a problem, stop and
     * explain the reason.
     */
    fun retest(providerId: String) {
        val p = store.provider(providerId) ?: return
        val models = _state.value.unverified(p)
        if (models.isEmpty() || _state.value.retest[providerId] == ListState.Running) return
        _state.update { it.copy(retest = it.retest + (providerId to ListState.Running)) }
        scope.launch {
            for (m in models) {
                val outcome = probeSaved(p, m)
                if (outcome is ProbeOutcome.Failed) {
                    _state.update { snapshot(it.copy(retest = it.retest + (providerId to ListState.Failed(outcome.message)))) }
                    return@launch
                }
                withContext(io) { store.markVerified(p.id, m) }
            }
            _state.update { snapshot(it.copy(retest = it.retest - providerId)) }
        }
    }

    private fun use(role: ModelRole, p: Provider, model: String, options: List<ModelOption>) {
        val caps = options.firstOrNull { it.id == model }?.capabilities ?: ModelOptions.baseOf(p)
        store.use(role, p.id, model, caps)
    }

    // ---------- A service provider ----------

    /** Relist the provider's model and note it down; note the reason if it fails. */
    fun refresh(providerId: String) {
        val p = store.provider(providerId) ?: return
        if (refreshJobs[providerId]?.isActive == true) return
        _state.update { it.copy(listing = it.listing + (providerId to ListState.Running)) }
        refreshJobs[providerId] = scope.launch {
            val r = withContext(io) { store.listModels(p) }
            val models: List<ListedModel>? = when (r) {
                is ModelListResult.Ok -> r.models
                is ModelListResult.Failed -> if (r.reason == ListFailure.NotSupported) emptyList() else null
            }
            if (models != null) withContext(io) { store.rememberModels(p.id, models) }
            val after = if (r is ModelListResult.Failed && models == null) ListState.Failed(r.message) else ListState.Idle
            _state.update { snapshot(it.copy(listing = it.listing + (providerId to after))) }
        }
    }

    /** When the selection page and service provider page are opened: the service providers that have not yet been memorized are listed once. */
    fun ensureListed() {
        _state.value.providers.filter { it.models == null && _state.value.listing[it.id] == null && listable(it) }.forEach { refresh(it.id) }
    }

    /** If you log out, the model cannot be listed. Please log in again. */
    private fun listable(p: Provider) = if (p.signIn != null) store.hasKey(p.id) else when (p.kind) {
        ProviderConfig.Kind.ChatGpt -> store.chatGptSession(p.id)?.signedIn == true
        ProviderConfig.Kind.Xai -> store.accountSession(p.id)?.signedIn == true
        else -> true
    }

    /** Change the Key, and then re-list the model with the new Key. */
    fun changeKey(providerId: String, apiKey: String) {
        val k = apiKey.trim()
        if (k.isEmpty()) return
        scope.launch {
            withContext(io) { store.updateProviderKey(providerId, k) }
            refreshJobs.remove(providerId)?.cancel()
            refresh(providerId)
        }
    }

    /** Delete the service provider and its Key; use its purpose and return to default. */
    fun removeProvider(providerId: String) {
        scope.launch {
            withContext(io) {
                // For ChatGPT, log out and log in on the server first, and then delete it.
                when (store.provider(providerId)?.kind) {
                    ProviderConfig.Kind.ChatGpt -> store.signOutChatGpt(providerId)
                    ProviderConfig.Kind.Xai -> store.signOutAccount(providerId)
                    else -> Unit
                }
                // Keys authorized by OpenRouter will be deleted together with the service provider; Keys
                // from OpenRouter must be deleted by the user on the Keys page.
                store.removeProvider(providerId)
            }
            _state.update { snapshot(it.copy(listing = it.listing - providerId)) }
        }
    }

    companion object {
        /** How long should you wait for a test call in "Test and Save". */
        const val PROBE_TIMEOUT_MS = 20_000L

        /** When there is a list in "Test and Save", you can try at most a few models: the one you try first, plus the 2 more models you will try next if you fail. */
        const val PROBE_CANDIDATES = 3

        /** The name of the customized service provider can be up to a few words (the service provider list can fit in one line). */
        const val MAX_NAME = 32
    }
}
