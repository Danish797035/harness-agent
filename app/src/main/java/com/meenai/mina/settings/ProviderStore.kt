package com.meenai.mina.settings

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import com.meenai.mina.core.agent.ContextLimits
import com.meenai.mina.core.chatgpt.ChatGptAuth
import com.meenai.mina.core.chatgpt.ChatGptSession
import com.meenai.mina.core.chatgpt.ChatGptTokens
import com.meenai.mina.core.oauth.AccountSession
import com.meenai.mina.core.oauth.AccountTokens
import com.meenai.mina.core.oauth.OauthTokenResult
import com.meenai.mina.core.oauth.OpenRouterAuth
import com.meenai.mina.core.oauth.XaiAuth
import com.meenai.mina.core.model.AnthropicModel
import com.meenai.mina.core.model.BearerToken
import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.Author
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.ClientHeaders
import com.meenai.mina.core.gemini.GeminiWebClient
import com.meenai.mina.core.gemini.GeminiWebSession
import com.meenai.mina.core.model.GeminiWebModel
import com.meenai.mina.core.model.GeminiModel
import com.meenai.mina.core.model.ListFailure
import com.meenai.mina.core.model.ListedModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelCatalog
import com.meenai.mina.core.model.ModelEndpoint
import com.meenai.mina.core.model.ModelError
import com.meenai.mina.core.model.ModelListResult
import com.meenai.mina.core.model.ModelProtocol
import com.meenai.mina.core.model.ModelRole
import com.meenai.mina.core.model.ModelSession
import com.meenai.mina.core.model.OpenAiCompatModel
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.PromptCaching
import com.meenai.mina.core.model.ResponsesModel
import com.meenai.mina.core.model.Role
import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.core.usage.UsagePurpose
import com.meenai.mina.core.usage.UsageSink
import com.meenai.mina.core.usage.UsageSource
import com.meenai.mina.core.usage.metered
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import com.meenai.mina.core.net.NetProxy
import okhttp3.OkHttpClient
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * A model access: which service provider it belongs to ([providerId]), model name, and capabilities. The
 * interface type, address and name are copied from the service provider (used when building the model);
 * the key is stored in the service provider. This is a layer inside the storage: users only choose
 * "which model to use for which purpose", and configurations are created as they choose and deleted when
 * no one uses them.
 */
@Immutable
data class ProviderConfig(
    val id: String,
    val kind: Kind,
    val baseUrl: String,
    val model: String,
    val capabilities: Capabilities,
    /** The name displayed on the interface. */
    val name: String = "",
    /** The id of [Provider]; if it is empty, [ProviderStore.save] will assign it to the corresponding service provider according to the interface type, address and name. */
    val providerId: String = "",
) {
    /**
     * Interface type. [OpenAiResponses] is used when specifying the Responses protocol for a model in
     * the model list, presetting "OpenAI", and selecting it when adding a custom service provider;
     * [ChatGpt] is the one that is logged in with a ChatGPT account and called with the package quota:
     * the credential is login (see [ChatGptSession]), not the API Key. [Xai] is also an account login
     * (device code), the credential is login and not API Key, and calls xAI's own Responses interface.
     */
    enum class Kind {
        OpenAiCompat, Anthropic, Gemini, OpenAiResponses, ChatGpt, Xai, GeminiWeb;

        /**
         * Which protocol does this interface type use to send requests? Use it to filter out interfaces
         * that indicate unsupported models, see the trial call of
         * [com.meenai.mina.ui.ModelSetupViewModel]. Both accounts (ChatGpt,
         */
        val protocol: ModelProtocol
            get() = when (this) {
                OpenAiCompat -> ModelProtocol.ChatCompletions
                Anthropic -> ModelProtocol.Messages
                Gemini, GeminiWeb -> ModelProtocol.GenerateContent
                OpenAiResponses, ChatGpt, Xai -> ModelProtocol.Responses
            }

        companion object {
            /** The model individually specifies which interface type adapter to use for the protocol. */
            fun of(protocol: ModelProtocol): Kind = when (protocol) {
                ModelProtocol.ChatCompletions -> OpenAiCompat
                ModelProtocol.Messages -> Anthropic
                ModelProtocol.GenerateContent -> Gemini
                ModelProtocol.Responses -> OpenAiResponses
            }

            /** Optional interface type when adding a custom service provider (fill in the address and API Key). Responses are listed last, consistent with the order in the README. */
            val withApiKey: List<Kind> = listOf(OpenAiCompat, Anthropic, Gemini, OpenAiResponses)
        }
    }
}

/**
 * A connected service provider: name, interface type, address; Key is stored separately and encrypted.
 * [models] is the most recently listed model (recorded during "test and save" or refresh, and stored in
 * the same file as the configuration); null means it has not been listed yet, and an empty list means it
 * has been listed but the service does not provide a list (or none). [sessionHeader] is the request
 * header name (from the default, see [ProviderPreset.sessionHeader]) that requires the conversation
 * session number; otherwise it is null. [verifiedModels] is the model name that has been responded to in
 * this trial call (see [ProviderStore.probe]): the service provider does not provide a list, the model
 * name is filled in by hand, and if it is not here, it has not been verified (the old version is not
 * saved, and the service provider that did not provide the list at that time did not try to call).
 * [typedModels] is the model name that was manually filled in by the user and added after passing the
 * trial call (it is not in the list, or the model cannot be listed in this company): it will not be lost
 * after refreshing the list, and mark "hand filled" when selecting the model. [savedUntested] is a
 * company that users skip testing and save directly (see [ProviderStore.connect]): it has neither listed
 * models nor tried calling them, so [models] is an empty list and [verifiedModels] is empty, but the
 * statement that "this company does not provide a list" is not true for it. After successfully listing
 * the model once or passing it once, it will be cleared (see [ProviderStore.rememberModels],
 * [ProviderStore.markVerified]).
 */
@Immutable
data class Provider(
    val id: String,
    val name: String,
    val kind: ProviderConfig.Kind,
    val baseUrl: String,
    val models: List<ListedModel>? = null,
    val sessionHeader: String? = null,
    val verifiedModels: Set<String> = emptySet(),
    val typedModels: Set<String> = emptySet(),
    /**
     * How does the Key come from: It is exchanged for account authorization (currently only
     * [ProviderStore.OPENROUTER_SIGN_IN]), which is the mark of that company, and the user fills in
     * null. The marked service provider details show the account login (log out, go to the other party's
     * page to delete the key), not "change the key".
     */
    val signIn: String? = null,
    val savedUntested: Boolean = false,
    /** It is retained according to the model name and can be retrieved after the use is switched. */
    val contextSettings: Map<String, ModelContextSettings> = emptyMap(),
    /** Whether a certain model mentioned by the user can be viewed in pictures (according to the model name): both the ratio list and the preset initial value count, see [ModelOptions.seesImages]. */
    val visionOverrides: Map<String, Boolean> = emptyMap(),
) {
    val key: String get() = ProviderConnection.keyOf(kind, baseUrl, name)

    /**
     * [model] has not passed the test here: there is no available list (it is not provided, or it is not
     * listed when the test is skipped and saved), the model name is filled in by hand, and it is not in
     * [verifiedModels]. The model names logged into the account are fixed and do not count. The service
     * provider page "retests" such models in use.
     */
    fun untested(model: String): Boolean =
        kind != ProviderConfig.Kind.ChatGpt && kind != ProviderConfig.Kind.Xai && kind != ProviderConfig.Kind.GeminiWeb && models?.isEmpty() == true && model !in verifiedModels
}

/** The window left blank will inherit the selected model configuration, and the trigger points left blank will be automatically calculated by the core. */
@Immutable
data class ModelContextSettings(val windowTokens: Int? = null, val triggerTokens: Int? = null) {
    fun limits(modelWindow: Int): ContextLimits = ContextLimits.of(windowTokens ?: modelWindow, triggerTokens)

    fun validFor(modelWindow: Int): Boolean = (windowTokens == null || windowTokens > 0) &&
        (triggerTokens == null || (triggerTokens > 0 && triggerTokens == limits(modelWindow).trigger))
}

/** What to choose for a purpose: a specific model, use the main model, or not (you can only use it if you look at the picture). */
@Immutable
sealed interface RoleChoice {
    /** Use the model configuration [configId]. */
    @Immutable data class Model(val configId: String) : RoleChoice
    @Immutable data object SameAsMain : RoleChoice
    @Immutable data object Off : RoleChoice
}

/**
 * What is selected for each of the three uses is clearly stated by the user, and there is no default
 * allocation based on capabilities. [main] is the configuration id of the main model. If it is null, it
 * means it has not been selected. It is not used by default when viewing pictures, and the main model is
 * used by default for lightweight.
 */
@Immutable
data class RoleChoices(
    val main: String? = null,
    val vision: RoleChoice = RoleChoice.Off,
    val light: RoleChoice = RoleChoice.SameAsMain,
    /** Save according to usage, the same model can be used in different gears for different purposes; "Use the main model" will follow along with its gears. */
    val reasoningEfforts: Map<ModelRole, String> = emptyMap(),
) {
    /** The selection for this purpose; null when the main model is not selected. */
    operator fun get(role: ModelRole): RoleChoice? = when (role) {
        ModelRole.Main -> main?.let(RoleChoice::Model)
        ModelRole.Vision -> vision
        ModelRole.Light -> light
    }

    /** The configuration id actually used for this purpose: "Use main model" takes the main model; it is null when it is not used or the main model has not been selected. */
    fun resolve(role: ModelRole): String? = when (val c = get(role)) {
        is RoleChoice.Model -> c.configId
        RoleChoice.SameAsMain -> main
        RoleChoice.Off, null -> null
    }

    /** Configuration IDs that are in use for a purpose; other configurations are not used and will be deleted. */
    val referenced: Set<String> get() = ModelRole.entries.mapNotNull(::resolve).toSet()

    /** Change a purpose; this purpose does not accept selections (the main model can only be a specific model, lightweight cannot be used) and returns null. */
    fun with(role: ModelRole, choice: RoleChoice): RoleChoices? = (when (role) {
        ModelRole.Main -> (choice as? RoleChoice.Model)?.let { copy(main = it.configId) }
        ModelRole.Vision -> copy(vision = choice)
        ModelRole.Light -> if (choice == RoleChoice.Off) null else copy(light = choice)
    })?.let { next ->
        if (get(role) == choice) next else next.copy(reasoningEfforts = reasoningEfforts - role)
    }

    fun reasoningEffort(role: ModelRole): String? = when (get(role)) {
        is RoleChoice.Model -> reasoningEfforts[role]
        RoleChoice.SameAsMain -> main?.let { reasoningEfforts[ModelRole.Main] }
        else -> null
    }

    /** Selections pointing to other than [valid] return to the default: the main model becomes unselected, and is not used for viewing pictures. Use the main model lightly. */
    fun keepOnly(valid: Set<String>): RoleChoices {
        fun RoleChoice.gone() = this is RoleChoice.Model && configId !in valid
        return RoleChoices(
            main = main?.takeIf { it in valid },
            vision = if (vision.gone()) RoleChoice.Off else vision,
            light = if (light.gone()) RoleChoice.SameAsMain else light,
            reasoningEfforts = reasoningEfforts.filterKeys { role -> (get(role) as? RoleChoice.Model)?.configId in valid },
        )
    }
}

/**
 * Additional information provided when building a model client: [headers] Each request carries (client
 * ID, session number, see [ClientHeaders]); [maxOutputTokens] limits the reply length (for connection
 * testing), null is the default by the adapter; [reasoningEffort] is the thinking intensity selected for
 * this purpose; [thinkingTypes] is the model list indicating the thinking method accepted by this model
 * (see [ListedModel.thinkingTypes]), if not stated, it is null.
 */
data class ClientSetup(
    val headers: Map<String, String> = emptyMap(),
    val maxOutputTokens: Int? = null,
    val reasoningEffort: String? = null,
    val thinkingTypes: List<String>? = null,
)

/** "Test and save" the results of the trial call (see [ProviderStore.probe]). */
sealed interface ProbeResult {
    /** The model calls the trial tools: if the interface is passed, the tools will also be called. */
    data object ToolCalled : ProbeResult
    /** The interface works, but the model only replies and does not call tools: Mina relies on tools to work, and this model cannot be used as the main model. */
    data object NoToolCall : ProbeResult
    /** The request failed; [error] with an error from the server. */
    data class Failed(val error: ModelError) : ProbeResult
}

/**
 * Service provider, model configuration access and usage selection. The configuration is saved as a JSON
 * file, and the API Key is encrypted by [KeyCipher] and saved in another file, one copy for each service
 * provider.
 *
 * Users only care about two layers: which service providers are connected and which model to use for
 * each purpose ([RoleChoices]). Model configuration ([ProviderConfig]) is an internal layer: it is
 * created on the spot when you select a model, and it is deleted when it is no longer used (see [prune];
 * the key is stored according to the service provider, and the key does not change when you delete the
 * configuration). Choose whatever the purpose is, regardless of the ability mark; when selecting a
 * specific model, mark the corresponding mark as true (see [choose]).
 *
 * Files of the old version: only the model configuration and Key are stored according to the
 * configuration id. When reading, they are classified as service providers according to
 * [ProviderConnection.keyOf]. The Key is any one in that group. The original Key saved according to the
 * configuration id is cleared (see [migrate]); only the explicitly specified uses are recorded. When
 * reading in, the allocation that is actually effective at that time is written once (see
 * [migrateRoles]). The migrated data will not be moved again, and repeated execution will have no side
 * effects.
 *
 * The model to be called only goes out from [modelFor], and it is measured before going out (see
 * [metered]). Every call is recorded in [usage]: whoever calls it and how many times it is called do not
 * need to keep separate accounts.
 */
class ProviderStore(
    dir: File,
    private val cipher: KeyCipher,
    private val usage: UsageSink = UsageSink { _, _ -> },
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** Send it to the User-Agent of the model service; App to `Mina/<version>`. */
    private val userAgent: String = "Mina",
    /** Column model: interface type, address, Key, and headers carried by each request. */
    private val modelLister: suspend (ProviderConfig.Kind, String, String?, Map<String, String>) -> ModelListResult = ::listAvailable,
    /** Authorization service for "Login with ChatGPT" (token exchange, refresh, logout). */
    val chatGptAuth: ChatGptAuth = defaultChatGptAuth,
    /** xAI subscription login (device code, refresh). */
    val xaiAuth: XaiAuth = defaultXaiAuth,
    /** OpenRouter account authorization (change Key). */
    val openRouterAuth: OpenRouterAuth = defaultOpenRouterAuth,
    private val writeScope: CoroutineScope? = null,
    /**
     * Create a callable model: configuration, Key, interface specified separately for this model in the
     * service provider list (if not null, according to the configured interface type and address),
     * request header, etc. When null, use the built-in adapter (change to false in testing).
     */
    modelFactory: ((ProviderConfig, String?, ModelEndpoint?, ClientSetup) -> ChatModel)? = null,
) {
    private val modelFactory: (ProviderConfig, String?, ModelEndpoint?, ClientSetup) -> ChatModel = modelFactory ?: ::buildModel
    private val writeLock = Any()
    /** One copy for each ChatGPT service provider: only one is exchanging tokens at the same time. */
    private val chatGptTokens = mutableMapOf<String, ChatGptTokens>()
    /** One copy for each subscription account, and just like ChatGPT, only one is exchanging tokens at the same time. */
    private val accountTokens = mutableMapOf<String, AccountTokens>()
    private val configFile = File(dir, CONFIG_FILE)
    private val configBackup = File(dir, "$CONFIG_FILE.bak")
    private val keyFile = File(dir, KEY_FILE)
    private val keyBackup = File(dir, "$KEY_FILE.bak")
    private val chatGptHostFile = File(dir, CHATGPT_HOST_FILE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _providers = MutableStateFlow<List<Provider>>(emptyList())
    /** Connected service providers, in order of connection. */
    val providers: StateFlow<List<Provider>> = _providers.asStateFlow()

    private val _configs = MutableStateFlow<List<ProviderConfig>>(emptyList())
    val configs: StateFlow<List<ProviderConfig>> = _configs.asStateFlow()

    private val _roles = MutableStateFlow(RoleChoices())
    /** What are selected for each of the three purposes; the pointed configurations are all in [configs]. */
    val roles: StateFlow<RoleChoices> = _roles.asStateFlow()

    private val _keysRevision = MutableStateFlow(0L)
    /** Key version count when saved or changed, used to trigger candidate model recalculation. */
    val keysRevision: StateFlow<Long> = _keysRevision.asStateFlow()

    /** Service provider id → encrypted Key. */
    private var keys: Map<String, String> = emptyMap()
    /** In-memory cache of decrypted keys to avoid repeated Keystore Binder IPC on hot paths. */
    private val decryptedKeys = mutableMapOf<String, String>()

    init {
        dir.mkdirs()
        load()
    }

    fun list(): List<ProviderConfig> = _configs.value

    fun provider(id: String): Provider? = _providers.value.firstOrNull { it.id == id }

    /**
     * Add or replace the model configuration with the same ID (the new one must be handed over to
     * [choose], otherwise it will be deleted when no one uses it the next time it is read). The
     * configuration is linked to the service provider pointed by [ProviderConfig.providerId] (the
     * interface type, address, and name are subject to the service provider); if it is not pointed out
     * or the pointed one does not exist, just look for the corresponding service provider based on the
     * interface type, address, and name, and if you cannot find it, create a new one. [apiKey] is the
     * Key of that service provider: null means not to change, and empty string means clearing.
     */
    @Synchronized
    fun save(cfg: ProviderConfig, apiKey: String?) {
        val p = provider(cfg.providerId) ?: findOrAddProvider(ProviderConnection.displayName(cfg), cfg.kind, cfg.baseUrl)
        val attached = cfg.copy(providerId = p.id, kind = p.kind, baseUrl = p.baseUrl, name = p.name)
        val list = _configs.value
        val i = list.indexOfFirst { it.id == cfg.id }
        _configs.value = if (i >= 0) list.toMutableList().also { it[i] = attached } else list + attached
        if (apiKey != null) setKey(p.id, apiKey)
        writeConfig()
    }

    /** This model configures the Key of the service provider it belongs to. */
    fun apiKey(configId: String): String? =
        _configs.value.firstOrNull { it.id == configId }?.let { providerKey(it.providerId) }

    /** The key of the service provider. If it cannot be unlocked (for example, the keystore is cleared by the system), it is treated as if there is no Key. */
    fun providerKey(providerId: String): String? = synchronized(this) {
        decryptedKeys[providerId]?.let { return it }
        val enc = keys[providerId] ?: return null
        val plain = runCatching { String(cipher.decrypt(decode(enc))) }.getOrNull() ?: return null
        decryptedKeys[providerId] = plain
        plain
    }

    /** Does this service provider have a Key stored in it (you don’t need to fill it in for local services). */
    fun hasKey(providerId: String): Boolean = synchronized(this) { providerId in keys }

    /**
     * "Test and save" connects to a service provider: saves it (name, interface type, address, key) and
     * the model listed. If there is already a service provider with the same interface type, address,
     * and name, just update that one (change the key, change the list), and do not add it repeatedly.
     * When [apiKey] is null, the existing Key will not be changed. [verified] is the model name that was
     * responded to during this trial call, and is recorded as [Provider.verifiedModels]; [typed] is
     * filled in by the user, and is recorded as [Provider.typedModels]. [signIn] is the origin of the
     * Key (see [Provider.signIn]): the Key filled in by the user is null, and the one authorized is the
     * mark of that company. The two sources are from different service providers (addresses and names
     * are not merged): authorization will not delete the key filled in by the user, and only the
     * authorized one will be deleted when logging out. [untested] was saved directly by the user
     * skipping the test (see [Provider.savedUntested]): not a single model was listed, and no attempt
     * was made to call it. There is already this one, and it has been listed as a model or verified as
     * outdated, it will not be remarked as untested because of this parameter.
     */
    @Synchronized
    fun connect(
        name: String, kind: ProviderConfig.Kind, baseUrl: String, apiKey: String?, models: List<ListedModel>,
        sessionHeader: String? = ProviderPresets.forBaseUrl(baseUrl)?.sessionHeader,
        verified: Set<String> = emptySet(),
        signIn: String? = null,
        typed: Set<String> = emptySet(),
        untested: Boolean = false,
    ): Provider {
        val found = findOrAddProvider(name, kind, baseUrl, signIn)
        if (apiKey != null) setKey(found.id, apiKey)
        // There is no list to save when skipping the test: keep the list that has been listed before. Do
        // not use an empty list to cover the existing list.
        val listed = if (untested && models.isEmpty()) found.models ?: models else models
        val p = found.copy(
            models = listed, sessionHeader = sessionHeader,
            verifiedModels = found.verifiedModels + verified, typedModels = found.typedModels + typed,
            // This time I have not tested it, this company has not verified the model, and has not made
            // a list, so it is "not tested"; in other cases, it is regarded as "tested".
            savedUntested = untested && verified.isEmpty() && found.verifiedModels.isEmpty() && listed.isEmpty(),
        )
        _providers.value = _providers.value.map { if (it.id == p.id) p else it }
        writeConfig()
        return p
    }

    /** Note the last model listed by the service provider. If it is listed, it means that "not tested" is no longer true (the company that skipped the test and saved it clears the mark here). */
    @Synchronized
    fun rememberModels(providerId: String, models: List<ListedModel>) {
        if (provider(providerId) == null) return
        _providers.value = _providers.value.map {
            if (it.id == providerId) it.copy(models = models, savedUntested = false) else it
        }
        writeConfig()
    }

    /**
     * Note that [model] got a reply from this test call (see [Provider.verifiedModels]). If the test
     * passes, it means that "not tested" is no longer true. The one where you skipped the test and saved
     * it clears the mark here - this model must be cleared even when it is already in
     * [Provider.verifiedModels], and cannot be returned directly.
     */
    @Synchronized
    fun markVerified(providerId: String, model: String) {
        val p = provider(providerId) ?: return
        if (model in p.verifiedModels && !p.savedUntested) return
        _providers.value = _providers.value.map {
            if (it.id == providerId) it.copy(verifiedModels = it.verifiedModels + model, savedUntested = false) else it
        }
        writeConfig()
    }

    /** Note down the [model](see [Provider.typedModels]) that the user filled in here. */
    @Synchronized
    fun markTyped(providerId: String, model: String) {
        val p = provider(providerId) ?: return
        if (model in p.typedModels) return
        _providers.value = _providers.value.map { if (it.id == providerId) it.copy(typedModels = it.typedModels + model) else it }
        writeConfig()
    }

    /** Replace the Key of a service provider (all models below it use this one). */
    @Synchronized
    fun updateProviderKey(providerId: String, apiKey: String) {
        if (provider(providerId) != null) setKey(providerId, apiKey)
    }

    /**
     * Delete a service provider: the model configuration and Key under it are deleted together. Use its
     * purpose to return to the default (the main model becomes unselected, not used for viewing
     * pictures, and the main model is used lightly); the interface has already explained which purposes
     * need to be re-selected before deleting it. What was deleted was ChatGPT, and the registered
     * client_id was also forgotten (the local identity remains): you can log in and register again next
     * time, or you can change your account.
     */
    @Synchronized
    fun removeProvider(providerId: String) {
        if (provider(providerId)?.kind == ProviderConfig.Kind.ChatGpt) {
            writeAtomically(chatGptHostFile, buildJsonObject { put("hostId", chatGptRegistration().first) }.toString())
        }
        _providers.value = _providers.value.filterNot { it.id == providerId }
        _configs.value = _configs.value.filterNot { it.providerId == providerId }
        _roles.value = _roles.value.keepOnly(_configs.value.map { it.id }.toSet())
        prune()
        if (providerId in keys) setKey(providerId, "")
        writeConfig()
    }

    /**
     * Use [model] under the service provider [providerId] as [role]: This model has not been configured
     * yet, so create one according to [caps], and then give it to [choose]. Returns false if the service
     * provider does not exist.
     */
    @Synchronized
    fun use(role: ModelRole, providerId: String, model: String, caps: Capabilities): Boolean {
        val p = provider(providerId) ?: return false
        val id = _configs.value.firstOrNull { it.providerId == p.id && it.model == model }?.id
            ?: newId().also { save(ProviderConfig(it, p.kind, p.baseUrl, model, caps, p.name, p.id), null) }
        return choose(role, RoleChoice.Model(id))
    }

    /**
     * Set a purpose: whatever the user chooses is what it is, regardless of the ability mark. When
     * selecting a specific model as the main model, mark the tool call flag as true (the tool definition
     * will be sent accordingly during runtime); if the saved diagram mark is not changed, press the
     * current selection in [configFor] to push it out, and then go back after changing it. Delete
     * configurations that are no longer used. Returns false if the configuration does not exist, or if
     * this selection is not accepted for this purpose (see [RoleChoices.with]).
     */
    @Synchronized
    fun choose(role: ModelRole, choice: RoleChoice): Boolean {
        if (choice is RoleChoice.Model && _configs.value.none { it.id == choice.configId }) return false
        val next = _roles.value.with(role, choice) ?: return false
        if (choice is RoleChoice.Model) {
            _configs.value = _configs.value.map { c ->
                if (c.id != choice.configId) c
                else if (role == ModelRole.Main) c.copy(capabilities = c.capabilities.copy(toolCalls = true))
                else c
            }
        }
        _roles.value = next
        prune()
        writeConfig()
        return true
    }

    /** Only change this use; unsupported protocols, uses that follow the main model, or gears that are clearly unsupported in the list will not be accepted. */
    @Synchronized
    fun setReasoningEffort(role: ModelRole, effort: String?): Boolean {
        if (_roles.value[role] !is RoleChoice.Model) return false
        val cfg = configFor(role) ?: return false
        val listed = provider(cfg.providerId)?.models?.firstOrNull { it.id == cfg.model }
        val options = ReasoningOptions.of(cfg, listed)
        if (effort != null && (options == null || effort !in options.levels)) return false
        _roles.value = _roles.value.copy(reasoningEfforts =
            if (effort == null) _roles.value.reasoningEfforts - role else _roles.value.reasoningEfforts + (role to effort))
        writeConfig()
        return true
    }

    @Synchronized
    fun setModelContext(configId: String, settings: ModelContextSettings): Boolean {
        val cfg = _configs.value.firstOrNull { it.id == configId } ?: return false
        val p = provider(cfg.providerId) ?: return false
        if (!settings.validFor(cfg.capabilities.contextTokens)) return false
        val saved = if (settings == ModelContextSettings()) p.contextSettings - cfg.model
            else p.contextSettings + (cfg.model to settings)
        val updated = _providers.value.map { if (it.id == p.id) p.copy(contextSettings = saved) else it }
        try {
            writeAtomically(configFile, configJson(updated))
        } catch (_: Exception) {
            return false
        }
        _providers.value = updated
        return true
    }

    /** Write down what the user said about whether [model] can view pictures (see [Provider.visionOverrides]); return false when the service provider does not exist. */
    @Synchronized
    fun setModelVision(providerId: String, model: String, sees: Boolean): Boolean {
        val p = provider(providerId) ?: return false
        _providers.value = _providers.value.map { if (it.id == p.id) p.copy(visionOverrides = p.visionOverrides + (model to sees)) else it }
        writeConfig()
        return true
    }

    /** "Viewing positioning has been verified" for the image viewing model (changed in developer options); when using the main model for viewing images, the configuration of the main model is changed. It does not move when the model is not viewed. */
    @Synchronized
    fun setVisionGroundingVerified(verified: Boolean) {
        val id = _roles.value.resolve(ModelRole.Vision) ?: return
        _configs.value = _configs.value.map { c ->
            if (c.id == id) c.copy(capabilities = c.capabilities.copy(groundingVerified = verified)) else c
        }
        writeConfig()
    }

    /**
     * The actual configuration used for this purpose; null when not selected or not used. The picture
     * viewing mark is pushed out immediately, regardless of whether the saved list data has been
     * written: the picture viewing path is always true (the user selects it to view the picture); for
     * other uses, it is [ModelOptions.seesImages] - what the user has said on this model shall prevail.
     * If it is not mentioned and the picture viewing also uses this configuration (the same model is
     * selected, or "use the main model"), it can still be viewed. The screenshot is directly attached to
     * it, and there is no need to adjust the picture viewing again.
     */
    fun configFor(role: ModelRole): ProviderConfig? {
        val choices = _roles.value
        val cfg = choices.resolve(role)?.let { id -> _configs.value.firstOrNull { it.id == id } } ?: return null
        val sees = role == ModelRole.Vision || ModelOptions.seesImages(cfg.capabilities.vision,
            provider(cfg.providerId)?.visionOverrides?.get(cfg.model), usedForVision = choices.resolve(ModelRole.Vision) == cfg.id)
        return if (sees == cfg.capabilities.vision) cfg else cfg.copy(capabilities = cfg.capabilities.copy(vision = sees))
    }

    /** The main model gives the name of the model when it has not passed the test (see [Provider.untested]); when the task fails due to the model, it prompts to "retest" accordingly. */
    fun untestedMainProvider(): Provider? {
        val cfg = configFor(ModelRole.Main) ?: return null
        return provider(cfg.providerId)?.takeIf { it.untested(cfg.model) }
    }

    /**
     * The model used for this purpose is set with measurement; if it is not selected or not used, it
     * will be null. The usage is recorded under the name [role]: "Use the main model" for image viewing
     * and lightweight calls. The service provider and model are configured for the main model. This is
     * the only entrance to build callable models in the App. [session] is the conversation to which this
     * call belongs (the models built in the same conversation are all given to the same one); the
     * service provider declares the conversation header and brings it with every request.
     */
    fun modelFor(role: ModelRole, session: ModelSession = ModelSession.fresh()): ChatModel? = configFor(role)?.let { cfg ->
        val p = provider(cfg.providerId)
        val source = UsageSource(cfg.providerId, p?.name ?: cfg.name, cfg.model, UsagePurpose.of(role))
        val listed = listedOf(cfg)
        val settings = p?.contextSettings?.get(cfg.model)
        val configured = if (settings == null) cfg else cfg.copy(capabilities = cfg.capabilities.copy(
            contextTokens = settings.windowTokens ?: cfg.capabilities.contextTokens,
            compactionTriggerTokens = settings.triggerTokens,
        ))
        modelFactory(configured, apiKey(cfg.id), listed?.endpoint, ClientSetup(
            headers(p?.sessionHeader, session), reasoningEffort = _roles.value.reasoningEffort(role),
            thinkingTypes = listed?.thinkingTypes,
        )).metered(source, usage)
    }

    /**
     * Builds a metered [ChatModel] for any listed/configured model of [provider] so unified routing
     * and one-tap model switching can invoke every connected provider (Claude, OpenAI, ChatGPT,
     * xAI, Gemini, OpenRouter, custom endpoints) through the same adapter path as [modelFor].
     */
    fun buildCandidateModel(
        provider: Provider,
        modelId: String,
        capabilities: Capabilities,
        endpoint: ModelEndpoint? = null,
        reasoningEffort: String? = null,
        thinkingTypes: List<String>? = null,
        session: ModelSession = ModelSession.fresh(),
    ): ChatModel {
        val settings = provider.contextSettings[modelId]
        val effectiveCaps = if (settings == null) capabilities else capabilities.copy(
            contextTokens = settings.windowTokens ?: capabilities.contextTokens,
            compactionTriggerTokens = settings.triggerTokens,
        )
        val cfg = ProviderConfig(
            id = "${provider.id}:$modelId",
            kind = provider.kind,
            baseUrl = provider.baseUrl,
            model = modelId,
            capabilities = effectiveCaps,
            name = provider.name,
            providerId = provider.id,
        )
        val source = UsageSource(provider.id, provider.name, modelId, UsagePurpose.of(ModelRole.Main))
        return modelFactory(
            cfg,
            providerKey(provider.id),
            endpoint,
            ClientSetup(
                headers = headers(provider.sessionHeader, session),
                reasoningEffort = reasoningEffort,
                thinkingTypes = thinkingTypes,
            ),
        ).metered(source, usage)
    }

    /**
     * Trial call of "Test and Save": The service provider has not yet saved it, build [model] according
     * to the same path as the real task ([modelFactory], the same request header), bring a tool without
     * parameters ([PROBE_TOOL]), and ask it to call; the reply limit is very short (if the protocol
     * allows, see [buildModel]). The tool call returned was [ProbeResult.ToolCalled], and the only
     * response was [ProbeResult.NoToolCall]. The request failed and the server error was returned as is.
     * Unmeasured: These tokens do not belong to any purpose. Use a new session number that does not
     * belong to any conversation. Timeouts are managed by the caller.
     */
    suspend fun probe(
        kind: ProviderConfig.Kind, baseUrl: String, apiKey: String?, sessionHeader: String?,
        model: String, endpoint: ModelEndpoint?, caps: Capabilities,
    ): ProbeResult {
        val cfg = ProviderConfig(PROBE_ID, kind, baseUrl, model, caps.copy(toolCalls = true))
        val m = modelFactory(cfg, apiKey, endpoint, ClientSetup(headers(sessionHeader, ModelSession.fresh()), PROBE_MAX_TOKENS))
        return try {
            val reply = m.complete(PROBE_SYSTEM, listOf(Message(Role.User, listOf(Part.Text(PROBE_TEXT)), Author.Mina)), listOf(PROBE_TOOL))
            if (reply.parts.any { it is Part.ToolCall }) ProbeResult.ToolCalled else ProbeResult.NoToolCall
        } catch (e: ModelError) {
            ProbeResult.Failed(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProbeResult.Failed(ModelError.BadResponse(e.message.orEmpty(), e))
        }
    }

    private fun headers(sessionHeader: String?, session: ModelSession) = ClientHeaders.of(userAgent, sessionHeader, session)

    /** Requests that do not belong to any conversation (such as voice transcription) bring a header to this company: client ID, and declare the session header with a new session number. */
    fun clientHeaders(p: Provider): Map<String, String> = headers(p.sessionHeader, ModelSession.fresh())

    /** The model last listed by the service provider (individually specified interfaces, stated thinking methods, etc.); check it every time you build a model, and use the new one if it is re-listed. */
    private fun listedOf(cfg: ProviderConfig): ListedModel? =
        provider(cfg.providerId)?.models?.firstOrNull { it.id == cfg.model }

    /** Lists the available models for this service provider (see [ModelCatalog]); does not belong to any session, declares the session header with a new session number. */
    suspend fun listModels(kind: ProviderConfig.Kind, baseUrl: String, apiKey: String?, sessionHeader: String? = null): ModelListResult =
        modelLister(kind, baseUrl, apiKey, headers(sessionHeader, ModelSession.fresh()))

    /** Re-list a model of the connected service provider: use the saved Key; use the access token for subscription login (replace with a new one if it is about to expire). */
    suspend fun listModels(p: Provider): ModelListResult {
        if (p.kind == ProviderConfig.Kind.GeminiWeb) {
            return ModelListResult.Ok(p.models?.takeIf { it.isNotEmpty() } ?: GeminiWebClient.DEFAULT_MODELS)
        }
        if (p.kind != ProviderConfig.Kind.ChatGpt && p.kind != ProviderConfig.Kind.Xai) {
            return listModels(p.kind, p.baseUrl, providerKey(p.id), p.sessionHeader)
        }
        val token = try {
            if (p.kind == ProviderConfig.Kind.ChatGpt) tokensFor(p.id).get(fresh = false) else accountTokensFor(p.id).get(fresh = false)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ModelError.SignInExpired) {
            return ModelListResult.Failed(ListFailure.Auth)
        } catch (e: ModelError) {
            return ModelListResult.Failed(if (e is ModelError.Network || e is ModelError.Proxy) ListFailure.of(e) else ListFailure.Server)
        }
        val base = provider(p.id)?.baseUrl ?: p.baseUrl
        return listModels(p.kind, base, token, p.sessionHeader)
    }

    // ---------- ChatGPT Login ----------

    /** Login of the ChatGPT service provider (exists in the same place as the API Key and is also encrypted); if not available or cannot be decoded, it is null. */
    fun chatGptSession(providerId: String): ChatGptSession? = ChatGptSession.fromJson(providerKey(providerId))

    /** Connected ChatGPT service provider (only one). */
    fun chatGptProvider(): Provider? = _providers.value.firstOrNull { it.kind == ProviderConfig.Kind.ChatGpt }

    /** Login successful: Save the login and listed models; if there is an existing ChatGPT service provider, update that one. */
    @Synchronized
    fun connectChatGpt(session: ChatGptSession, models: List<ListedModel>): Provider =
        connect(CHATGPT_NAME, ProviderConfig.Kind.ChatGpt, ChatGptAuth.API_BASE, session.toJson(), models)

    /**
     * Log out: first log out the refresh token on the server (clear it even if it cannot be confirmed),
     * and then clear the local token; keep the registration (client_id) and account number, and use them
     * when logging in again. Returns whether the server confirms the logout. The service provider and
     * its model and usage are not moved.
     */
    suspend fun signOutChatGpt(providerId: String): Boolean {
        val s = chatGptSession(providerId) ?: return true
        val revoked = s.refreshToken.isEmpty() || chatGptAuth.revoke(s.clientId, s.refreshToken)
        saveChatGptSession(providerId, s.signedOut())
        return revoked
    }

    /**
     * The device's local ID and registered client_id on the ChatGPT side. The identifier is generated
     * when it is used for the first time and remains unchanged thereafter; write down the client_id
     * after registration, keep it after logging out (it will be used when logging in again, no repeated
     * registration), and forget it when deleting the ChatGPT service provider. Both are not keys and are
     * stored in clear text.
     */
    @Synchronized
    fun chatGptRegistration(): Pair<String, String?> {
        val saved = runCatching { json.parseToJsonElement(chatGptHostFile.readText()).jsonObject }.getOrNull()
        val host = saved?.get("hostId")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val client = saved?.get("clientId")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        if (host != null) return host to client
        val fresh = ChatGptAuth.newHostId()
        writeAtomically(chatGptHostFile, buildJsonObject { put("hostId", fresh) }.toString())
        return fresh to null
    }

    /** Make a note of the registered client_id (note it before changing the token, you don’t need to re-register next time if the change fails). */
    @Synchronized
    fun rememberChatGptClient(clientId: String) {
        val host = chatGptRegistration().first
        writeAtomically(chatGptHostFile, buildJsonObject { put("hostId", host); put("clientId", clientId) }.toString())
    }

    @Synchronized
    private fun saveChatGptSession(providerId: String, session: ChatGptSession) {
        if (provider(providerId) != null) setKey(providerId, session.toJson())
    }

    private fun tokensFor(providerId: String): ChatGptTokens = synchronized(chatGptTokens) {
        chatGptTokens.getOrPut(providerId) {
            ChatGptTokens(chatGptAuth, { chatGptSession(providerId) }, { saveChatGptSession(providerId, it) })
        }
    }

    // ---------- xAI account login ----------

    /** Login of the subscription account (exists in the same place as the API Key and is also encrypted); if not available or cannot be unlocked, it is null. */
    fun accountSession(providerId: String): AccountSession? = AccountSession.fromJson(providerKey(providerId))

    fun xaiProvider(): Provider? = _providers.value.firstOrNull { it.kind == ProviderConfig.Kind.Xai }

    /**
     * Login successful: Save login and listed models. There is only one service provider when you log in
     * with the same account, and you will update it when you log in again.
     */
    @Synchronized
    fun connectAccount(session: AccountSession, models: List<ListedModel>): Provider {
        val (name, kind, base) = when (session.provider) {
            XaiAuth.PROVIDER -> Triple(XAI_NAME, ProviderConfig.Kind.Xai, XaiAuth.API_BASE)
            else -> throw IllegalArgumentException("unknown account provider")
        }
        val existing = _providers.value.firstOrNull { it.kind == kind }
        if (existing == null) return connect(name, kind, base, session.toJson(), models)
        val p = existing.copy(name = name, baseUrl = base, models = models)
        _providers.value = _providers.value.map { if (it.id == p.id) p else it }
        _configs.value = _configs.value.map { if (it.providerId == p.id) it.copy(baseUrl = base, name = name) else it }
        setKey(p.id, session.toJson())
        writeConfig()
        return p
    }

    /** Log out: Only clear the token on this phone. The authorization in the xAI account must be disconnected by yourself in the settings of xAI. */
    @Synchronized
    fun signOutAccount(providerId: String) {
        val s = accountSession(providerId) ?: return
        saveAccount(providerId, s.signedOut())
    }

    @Synchronized
    private fun saveAccount(providerId: String, session: AccountSession) {
        if (provider(providerId) == null) return
        setKey(providerId, session.toJson())
        val base = session.apiBase
        if (base.isNotBlank() && provider(providerId)?.baseUrl != base) {
            _providers.value = _providers.value.map { if (it.id == providerId) it.copy(baseUrl = base) else it }
            _configs.value = _configs.value.map { if (it.providerId == providerId) it.copy(baseUrl = base) else it }
            writeConfig()
        }
    }

    // ---------- Log in with OpenRouter account ----------

    /** The company to which the OpenRouter account is authorized to connect; if not, it is null. */
    fun openRouterAccount(): Provider? = _providers.value.firstOrNull { it.signIn == OPENROUTER_SIGN_IN }

    /**
     * The authorization has been changed to Key: Press OpenRouter to default to a common OpenAI
     * compatible service provider (the key is encrypted as usual), marked as authorized. The one that
     * has been authorized will update it; the OpenRouter where the user fills in the Key by himself will
     * not move, and the two coexist.
     */
    @Synchronized
    fun connectOpenRouter(apiKey: String, models: List<ListedModel>): Provider =
        connect(OPENROUTER_NAME, ProviderConfig.Kind.OpenAiCompat, OpenRouterAuth.API_BASE, apiKey, models, signIn = OPENROUTER_SIGN_IN)

    /** Log out of OpenRouter: Delete the Key on this phone; keep the service provider, model and purpose. The Key on OpenRouter requires the user to delete it on the Keys page. */
    @Synchronized
    fun signOutOpenRouter(providerId: String) {
        if (provider(providerId)?.signIn == OPENROUTER_SIGN_IN && providerId in keys) setKey(providerId, "")
    }

    /**
     * The token exchanged by the refresh is written back, provided that the saved login has not changed
     * during this period: the token change has to wait for the network, and the user may have logged out
     * or logged in again, so the old refresh result cannot be overwritten (otherwise, you will be logged
     * back quietly after logging out, or the new login will be overridden by the old token).
     */
    @Synchronized
    private fun saveRefreshed(providerId: String, before: AccountSession, after: AccountSession) {
        val now = accountSession(providerId) ?: return
        if (now.refreshToken != before.refreshToken || now.accessToken != before.accessToken) return
        saveAccount(providerId, after)
    }

    private fun accountTokensFor(providerId: String): AccountTokens = synchronized(accountTokens) {
        accountTokens.getOrPut(providerId) {
            AccountTokens(
                account = XAI_ACCOUNT,
                refresh = { session ->
                    when (session.provider) {
                        XaiAuth.PROVIDER -> xaiAuth.refresh(session)
                        else -> OauthTokenResult.Rejected("unknown", terminal = true)
                    }
                },
                load = { accountSession(providerId) },
                save = { before, after -> saveRefreshed(providerId, before, after) },
            )
        }
    }

    // ---------- Gemini Web account login ----------

    fun geminiWebProvider(): Provider? = _providers.value.firstOrNull { it.kind == ProviderConfig.Kind.GeminiWeb || it.signIn == GEMINI_WEB_SIGN_IN }

    fun geminiWebSession(providerId: String): GeminiWebSession? =
        providerKey(providerId)?.let { GeminiWebSession.fromJson(it) }?.takeIf { it.signedIn }

    @Synchronized
    fun sanitizeCarbonConfigs() {
        var changed = false
        val newConfigs = _configs.value.map { cfg ->
            if (cfg.model.contains("carbon", ignoreCase = true)) {
                changed = true
                cfg.copy(model = "gemini-4-argon")
            } else {
                cfg
            }
        }
        if (changed) {
            _configs.value = newConfigs
            writeConfig()
        }
        val currentMainCfgId = _roles.value.main
        val currentMainCfg = _configs.value.firstOrNull { it.id == currentMainCfgId }
        if (currentMainCfg != null && currentMainCfg.model.contains("carbon", ignoreCase = true)) {
            val argonCfg = _configs.value.firstOrNull { it.model == "gemini-4-argon" }
            if (argonCfg != null) {
                _roles.value = _roles.value.copy(main = argonCfg.id)
                writeConfig()
            }
        }
    }

    @Synchronized
    fun repairGeminiWebBindings() {
        sanitizeCarbonConfigs()
        val webProvider = geminiWebProvider() ?: return
        val session = geminiWebSession(webProvider.id)
        if (session == null || !session.signedIn) return

        if (webProvider.models != GeminiWebClient.DEFAULT_MODELS) {
            _providers.value = _providers.value.map {
                if (it.id == webProvider.id) it.copy(models = GeminiWebClient.DEFAULT_MODELS) else it
            }
            writeConfig()
        }

        val webModelIds = GeminiWebClient.DEFAULT_MODELS.map { it.id }.toSet()

        var changed = false
        val newConfigs = _configs.value.map { cfg ->
            val isCarbon = cfg.model.contains("carbon", ignoreCase = true)
            val modelName = if (isCarbon) "gemini-4-argon" else cfg.model
            val looksLikeWeb = cfg.kind == ProviderConfig.Kind.GeminiWeb || modelName in webModelIds
            if (looksLikeWeb && (cfg.kind != ProviderConfig.Kind.GeminiWeb || cfg.providerId != webProvider.id || isCarbon)) {
                changed = true
                cfg.copy(
                    providerId = webProvider.id,
                    kind = ProviderConfig.Kind.GeminiWeb,
                    name = webProvider.name,
                    baseUrl = webProvider.baseUrl,
                    model = modelName,
                )
            } else {
                cfg
            }
        }
        if (changed) {
            _configs.value = newConfigs
            writeConfig()
        }

        val currentMainCfgId = _roles.value.main
        val currentMainCfg = _configs.value.firstOrNull { it.id == currentMainCfgId }
        if (currentMainCfg != null && currentMainCfg.model.contains("carbon", ignoreCase = true)) {
            val argonCfg = _configs.value.firstOrNull { it.providerId == webProvider.id && it.model == "gemini-4-argon" }
            if (argonCfg != null) {
                _roles.value = _roles.value.copy(main = argonCfg.id)
                writeConfig()
            }
        }
    }

    @Synchronized
    fun connectGeminiWeb(session: GeminiWebSession, models: List<ListedModel> = emptyList()): Provider {
        val finalModels = if (models.isNotEmpty()) models else GeminiWebClient.DEFAULT_MODELS
        GeminiWebClient.seedCookies(session.cookies)
        val existing = geminiWebProvider()
        val p = if (existing == null) {
            connect(GEMINI_WEB_NAME, ProviderConfig.Kind.GeminiWeb, GEMINI_WEB_BASE, session.toJson(), finalModels, signIn = GEMINI_WEB_SIGN_IN)
        } else {
            val updated = existing.copy(name = GEMINI_WEB_NAME, baseUrl = GEMINI_WEB_BASE, models = finalModels)
            _providers.value = _providers.value.map { if (it.id == updated.id) updated else it }
            _configs.value = _configs.value.map { if (it.providerId == updated.id) it.copy(baseUrl = GEMINI_WEB_BASE, name = GEMINI_WEB_NAME) else it }
            setKey(updated.id, session.toJson())
            writeConfig()
            updated
        }
        repairGeminiWebBindings()
        return p
    }

    @Synchronized
    fun signOutGeminiWeb(providerId: String): Boolean {
        val s = geminiWebSession(providerId) ?: return true
        setKey(providerId, s.signedOut().toJson())
        GeminiWebClient.clearCookies()
        return true
    }

    /**
     * Only the session changed (Google rotated its cookies or the page handed us
     * a fresh `SNlM0e`). Writing the key is enough: re-running [connectGeminiWeb]
     * here would replace the provider's model list with the defaults and rewrite
     * the whole config from the middle of a streaming reply.
     */
    @Synchronized
    fun updateGeminiWebSession(providerId: String, session: GeminiWebSession) {
        val id = provider(providerId)?.id ?: geminiWebProvider()?.id ?: return
        setKey(id, session.toJson())
    }

    /** If the model specifies the interface separately, select the adapter according to its protocol and use its address; otherwise, use the configured interface type and address. Keys are all from service providers. */
    /**
     * The first four protocols are requested to be the same. The upper limit of replies
     * ([ClientSetup.maxOutputTokens]) is only given to Messages (which is required in the first place)
     * and generateContent; it is not given to Chat Completions (some models only recognize `max_tokens`,
     * and some only recognize `max_completion_tokens`. If the answer is wrong, the entire request will
     * be rejected, and the connection test will cause a false positive), and Responses are not given
     * (the ChatGPT package does not accept `max_output_tokens`).
     */
    private fun buildModel(cfg: ProviderConfig, apiKey: String?, endpoint: ModelEndpoint?, setup: ClientSetup): ChatModel {
        val (kind, adapterBase) = adapterFor(cfg, endpoint)
        // Use the current address of the service provider to subscribe and log in (it may have been
        // changed when logging in), and the configuration is stored in the address when adding.
        val baseUrl = if (cfg.kind == ProviderConfig.Kind.Xai) {
            provider(cfg.providerId)?.baseUrl?.takeIf { it.isNotBlank() } ?: adapterBase
        } else adapterBase
        return when (kind) {
            ProviderConfig.Kind.OpenAiCompat ->
                OpenAiCompatModel(baseUrl, apiKey, cfg.model, cfg.capabilities, http, endpoint?.reasoningField, setup.headers,
                    reasoningEffort = setup.reasoningEffort)
            ProviderConfig.Kind.Anthropic -> AnthropicModel(
                baseUrl, apiKey.orEmpty(), cfg.model, cfg.capabilities, http,
                maxTokens = setup.maxOutputTokens,
                headers = setup.headers, reasoningEffort = setup.reasoningEffort, thinkingTypes = setup.thinkingTypes,
            )
            ProviderConfig.Kind.Gemini ->
                GeminiModel(baseUrl, apiKey.orEmpty(), cfg.model, cfg.capabilities, http, setup.headers, setup.maxOutputTokens,
                    reasoningEffort = setup.reasoningEffort)
            ProviderConfig.Kind.OpenAiResponses ->
                ResponsesModel(baseUrl, BearerToken.fixed(apiKey), cfg.model, cfg.capabilities, http, headers = setup.headers,
                    reasoningEffort = setup.reasoningEffort)
            ProviderConfig.Kind.ChatGpt ->
                ResponsesModel(baseUrl, tokensFor(cfg.providerId), cfg.model, cfg.capabilities, http, chatGptPlan = true, headers = setup.headers,
                    reasoningEffort = setup.reasoningEffort)
            ProviderConfig.Kind.Xai ->
                ResponsesModel(baseUrl, accountTokensFor(cfg.providerId), cfg.model, cfg.capabilities, http, headers = setup.headers,
                    reasoningEffort = setup.reasoningEffort, accountLogin = XAI_ACCOUNT)
            ProviderConfig.Kind.GeminiWeb -> {
                // The selected model can come from an older build and still point
                // at a provider id that no longer holds the session. Fall back to
                // the one Gemini Web provider that does.
                val ownerId = cfg.providerId.takeIf { geminiWebSession(it) != null }
                    ?: geminiWebProvider()?.id
                val sess = ownerId?.let { geminiWebSession(it) }
                    ?: throw ModelError.SignInExpired("Google Gemini")
                GeminiWebModel(
                    session = sess,
                    model = cfg.model,
                    capabilities = cfg.capabilities,
                    http = GeminiWebClient.geminiWebHttp,
                    headers = setup.headers,
                    reasoningEffort = setup.reasoningEffort,
                    onSessionUpdated = { newSess ->
                        updateGeminiWebSession(ownerId, newSess)
                    }
                )
            }
        }
    }

    private fun findOrAddProvider(name: String, kind: ProviderConfig.Kind, baseUrl: String, signIn: String? = null): Provider {
        val key = ProviderConnection.keyOf(kind, baseUrl, name)
        _providers.value.firstOrNull { it.key == key && it.signIn == signIn }?.let { return it }
        val p = Provider(newId(), name.trim(), kind, baseUrl.trim(), signIn = signIn)
        _providers.value = _providers.value + p
        return p
    }

    private fun setKey(providerId: String, apiKey: String) {
        if (apiKey.isEmpty()) {
            keys = keys - providerId
            decryptedKeys.remove(providerId)
        } else {
            keys = keys + (providerId to encode(cipher.encrypt(apiKey.toByteArray())))
            decryptedKeys[providerId] = apiKey
        }
        scheduleWrite(keyFile, keysJson())
        _keysRevision.value++
    }

    /** Delete model configurations that are not used for any purpose. The key exists on the service provider and will not be affected. */
    private fun prune() {
        val used = _roles.value.referenced
        _configs.value = _configs.value.filter { it.id in used }
    }

    private fun load() {
        decryptedKeys.clear()
        var configHealthy = true
        var root: JsonObject? = null
        var rawConfigText: String? = null

        if (configFile.isFile) {
            rawConfigText = runCatching { configFile.readText() }.getOrNull()
            root = rawConfigText?.let(::parseJsonObjectText)
            if (root == null) {
                // Not valid JSON (truncated write, hand edit, full disk). Never
                // replace it with an empty config: try the last good copy and
                // otherwise leave the file untouched so the user can recover it.
                val backupText = runCatching { configBackup.takeIf { it.isFile }?.readText() }.getOrNull()
                val backup = backupText?.let(::parseJsonObjectText)
                if (backup != null && backupText != null) {
                    root = backup
                    rawConfigText = backupText
                    scheduleWrite(configFile, backupText)
                } else {
                    configHealthy = false
                }
            }

            val parsed = root
            if (parsed != null) {
                var dropped = 0
                _providers.value = parsed.array("services").mapNotNull { e ->
                    runCatching { providerOf(e.jsonObject) }.getOrElse { dropped++; null }
                }
                _configs.value = parsed.array("providers").mapNotNull { e ->
                    runCatching { configOf(e.jsonObject) }.getOrElse { dropped++; null }
                }
                val uses = runCatching { parsed["uses"]?.jsonObject }.getOrNull()
                _roles.value = runCatching {
                    if (uses != null) choicesOf(uses) else migrateRoles(parsed["roles"] as? JsonObject)
                }.getOrElse { RoleChoices() }
                // An entry that could not be read is still on disk. Rewriting
                // the file here would delete it permanently, so skip the
                // automatic migration/write-back for this launch.
                if (dropped > 0) configHealthy = false
            }
        }

        var keysHealthy = true
        if (keyFile.isFile) {
            var obj = readJsonObject(keyFile)
            if (obj == null) {
                val backupText = runCatching { keyBackup.takeIf { it.isFile }?.readText() }.getOrNull()
                val backup = backupText?.let(::parseJsonObjectText)
                if (backup != null && backupText != null) {
                    obj = backup
                    scheduleWrite(keyFile, backupText)
                } else {
                    keysHealthy = false
                }
            }
            if (obj != null) keys = obj.mapNotNull { (id, raw) ->
                runCatching { id to raw.jsonPrimitive.content }.getOrNull()
            }.toMap()
        }

        if (!configHealthy || !keysHealthy) {
            Log.w("ProviderStore", "providers.json or provider-keys.json could not be read completely; leaving it untouched")
            return
        }

        if (configFile.isFile && !configBackup.isFile) {
            scheduleBackup(configFile, configBackup)
        }
        if (keyFile.isFile && !keyBackup.isFile) {
            scheduleBackup(keyFile, keyBackup)
        }

        migrate()
        repairGeminiWebBindings()
        // Selections that point to configurations that do not exist are returned to default, and
        // configurations that are no longer used are deleted; they are written back only if they are
        // different from those in the file (including the purpose of the new migration).
        _roles.value = _roles.value.keepOnly(_configs.value.map { it.id }.toSet())
        prune()
        if (configFile.isFile) {
            val text = configJson()
            if (rawConfigText != text) scheduleWrite(configFile, text)
        }
    }

    private fun parseJsonObjectText(text: String): JsonObject? =
        runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()

    private fun readJsonObject(file: File): JsonObject? =
        runCatching { json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()

    private fun JsonObject.array(key: String): List<JsonElement> =
        (this[key] as? JsonArray)?.toList().orEmpty()

    /**
     * The old version of `roles` only remembers the configuration ids that are explicitly specified, and
     * those that are not specified are assigned according to the default rules: the main model takes the
     * first configuration, and when the main model can view the pictures, it is also responsible for it,
     * and it follows the main model lightly. Here, the allocation that actually takes effect at that
     * time is written as [RoleChoices], and there will be no implicit rules thereafter.
     */
    private fun migrateRoles(old: JsonObject?): RoleChoices {
        val ids = _configs.value.map { it.id }.toSet()
        fun explicit(role: ModelRole) = (old?.get(role.name) as? JsonPrimitive)?.content?.takeIf { it in ids }
        val main = explicit(ModelRole.Main) ?: _configs.value.firstOrNull()?.id
        val mainSees = _configs.value.firstOrNull { it.id == main }?.capabilities?.vision == true
        return RoleChoices(
            main = main,
            vision = explicit(ModelRole.Vision)?.let(RoleChoice::Model) ?: if (mainSees) RoleChoice.SameAsMain else RoleChoice.Off,
            light = explicit(ModelRole.Light)?.let(RoleChoice::Model) ?: RoleChoice.SameAsMain,
        )
    }

    /**
     * Group the unowned model configurations (old version files) under the service provider: group them
     * according to [ProviderConnection.keyOf], one service provider for each group, and take the Key
     * from any item in the group that is saved according to the configuration id (the ciphertext is
     * moved over as it is). First write the new and old Key files, then write the configuration, and
     * finally clear the Key that does not belong to any service provider; if it is disconnected midway,
     * the unowned configuration will still be there the next time it is read, and will be redone. Once
     * everything is done, just clean up the remaining Keys.
     */
    private fun migrate() {
        val known = _providers.value.map { it.id }.toSet()
        val orphans = _configs.value.filter { it.providerId !in known }
        if (orphans.isNotEmpty()) {
            val attachedTo = mutableMapOf<String, Provider>()
            orphans.groupBy { ProviderConnection.keyOf(it.kind, it.baseUrl, ProviderConnection.displayName(it)) }.values.forEach { group ->
                val first = group.first()
                val p = findOrAddProvider(ProviderConnection.displayName(first), first.kind, first.baseUrl)
                group.forEach { attachedTo[it.id] = p }
                if (p.id !in keys) group.firstNotNullOfOrNull { keys[it.id] }?.let { keys = keys + (p.id to it) }
            }
            writeAtomically(keyFile, keysJson())
            _configs.value = _configs.value.map { c ->
                attachedTo[c.id]?.let { p -> c.copy(providerId = p.id, kind = p.kind, baseUrl = p.baseUrl, name = p.name) } ?: c
            }
            writeConfig()
        }
        val ids = _providers.value.map { it.id }.toSet()
        if (keys.keys.any { it !in ids }) {
            keys = keys.filterKeys { it in ids }
            decryptedKeys.keys.retainAll(ids)
            scheduleWrite(keyFile, keysJson())
        }
    }

    private fun providerOf(o: JsonObject) = Provider(
        id = o.str("id"),
        name = o.str("name"),
        kind = ProviderConfig.Kind.valueOf(o.str("kind")),
        baseUrl = o.str("baseUrl"),
        // This item is not saved in the old version: fill it in according to the default address that
        // matches the address (note it when writing back the file after reading it)
        sessionHeader = o["sessionHeader"]?.jsonPrimitive?.content ?: ProviderPresets.forBaseUrl(o.str("baseUrl"))?.sessionHeader,
        signIn = o["signIn"]?.jsonPrimitive?.content,
        verifiedModels = o["verifiedModels"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
        contextSettings = (o["contextSettings"] as? JsonObject).orEmpty().mapNotNull { (model, raw) ->
            val entry = raw as? JsonObject ?: return@mapNotNull null
            val settings = ModelContextSettings(entry.positiveInt("windowTokens"), entry.positiveInt("triggerTokens"))
            if (settings == ModelContextSettings()) null else model to settings
        }.toMap(),
        visionOverrides = (o["visionOverrides"] as? JsonObject).orEmpty().mapNotNull { (model, raw) ->
            (raw as? JsonPrimitive)?.booleanOrNull?.let { model to it }
        }.toMap(),
        typedModels = o["typedModels"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet().orEmpty(),
        // There is no such item saved in the old version: at that time, every company had gone through
        // the "test and save" process, and it was calculated based on the test.
        savedUntested = o["untested"]?.jsonPrimitive?.booleanOrNull ?: false,
        models = o["models"]?.jsonArray?.map { e ->
            val m = e.jsonObject
            ListedModel(
                id = m.str("id"),
                displayName = m["displayName"]?.jsonPrimitive?.content.orEmpty(),
                toolCalls = m["toolCalls"]?.jsonPrimitive?.booleanOrNull,
                vision = m["vision"]?.jsonPrimitive?.booleanOrNull,
                contextTokens = m["contextTokens"]?.jsonPrimitive?.intOrNull,
                reasoning = m["reasoning"]?.jsonPrimitive?.booleanOrNull,
                reasoningEfforts = m["reasoningEfforts"]?.jsonArray?.map { it.jsonPrimitive.content },
                thinkingTypes = m["thinkingTypes"]?.jsonArray?.map { it.jsonPrimitive.content },
                chat = m["chat"]?.jsonPrimitive?.booleanOrNull,
                endpoint = (m["api"] as? JsonObject)?.let { a ->
                    ModelProtocol.of(a["protocol"]?.jsonPrimitive?.content)?.let { protocol ->
                        ModelEndpoint(protocol, a.str("baseUrl"), a["reasoningField"]?.jsonPrimitive?.content)
                    }
                },
                unsupportedApis = m["unsupportedApis"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
                protocols = m["protocols"]?.jsonArray?.mapNotNull { ModelProtocol.of(it.jsonPrimitive.content) }?.takeIf { it.isNotEmpty() },
            )
        },
    )

    private fun configOf(o: JsonObject) = ProviderConfig(
        id = o.str("id"),
        kind = ProviderConfig.Kind.valueOf(o.str("kind")),
        baseUrl = o.str("baseUrl"),
        model = o.str("model"),
        capabilities = Capabilities(
            toolCalls = o["toolCalls"]!!.jsonPrimitive.boolean,
            vision = o["vision"]!!.jsonPrimitive.boolean,
            contextTokens = o["contextTokens"]!!.jsonPrimitive.int,
            reasoning = o["reasoning"]!!.jsonPrimitive.boolean,
            groundingVerified = o["groundingVerified"]?.jsonPrimitive?.boolean ?: false,
            promptCaching = PromptCaching.of(o["promptCaching"]?.jsonPrimitive?.content),
        ),
        name = o["name"]?.jsonPrimitive?.content.orEmpty(),
        providerId = o["providerId"]?.jsonPrimitive?.content.orEmpty(),
    )

    /** A use in `uses`: `{"use":"model","config":id}`, `{"use":"main"}` or `{"use":"none"}`; if it is not recognized, press the default. */
    private fun choicesOf(o: JsonObject): RoleChoices {
        fun choice(role: ModelRole): RoleChoice? {
            val c = runCatching { o[role.name]?.jsonObject }.getOrNull() ?: return null
            return when (c["use"]?.jsonPrimitive?.content) {
                USE_MODEL -> c["config"]?.jsonPrimitive?.content?.let(RoleChoice::Model)
                USE_MAIN -> RoleChoice.SameAsMain
                USE_NONE -> RoleChoice.Off
                else -> null
            }
        }
        var r = RoleChoices()
        ModelRole.entries.forEach { role -> choice(role)?.let { c -> r.with(role, c)?.let { r = it } } }
        return r.copy(reasoningEfforts = ModelRole.entries.mapNotNull { role ->
            val effort = runCatching { o[role.name]?.jsonObject?.get("reasoningEffort")?.jsonPrimitive?.content }.getOrNull()
            if (r[role] is RoleChoice.Model && !effort.isNullOrBlank()) role to effort else null
        }.toMap())
    }

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
    private fun JsonObject.positiveInt(k: String) = (this[k] as? JsonPrimitive)?.intOrNull?.takeIf { it > 0 }

    private fun writeConfig() = scheduleWrite(configFile, configJson())

    private fun scheduleWrite(f: File, text: String) {
        val scope = writeScope
        if (scope == null) {
            writeAtomically(f, text)
        } else {
            scope.launch {
                synchronized(writeLock) {
                    runCatching { writeAtomically(f, text) }
                        .onFailure { Log.w("ProviderStore", "Async write failed for ${f.name}", it) }
                }
            }
        }
    }

    private fun scheduleBackup(source: File, backup: File) {
        val scope = writeScope
        if (scope == null) {
            runCatching { source.copyTo(backup, overwrite = true) }
        } else {
            scope.launch {
                synchronized(writeLock) {
                    if (source.isFile && !backup.isFile) {
                        runCatching { source.copyTo(backup, overwrite = true) }
                    }
                }
            }
        }
    }

    private fun configJson(providers: List<Provider> = _providers.value): String {
        val root = buildJsonObject {
            putJsonArray("services") {
                providers.forEach { p ->
                    addJsonObject {
                        put("id", p.id); put("name", p.name); put("kind", p.kind.name); put("baseUrl", p.baseUrl)
                        p.sessionHeader?.let { put("sessionHeader", it) }
                        p.signIn?.let { put("signIn", it) }
                        if (p.savedUntested) put("untested", true)
                        if (p.verifiedModels.isNotEmpty()) putJsonArray("verifiedModels") { p.verifiedModels.forEach { add(it) } }
                        if (p.contextSettings.isNotEmpty()) putJsonObject("contextSettings") {
                            for ((model, settings) in p.contextSettings) putJsonObject(model) {
                                settings.windowTokens?.let { put("windowTokens", it) }
                                settings.triggerTokens?.let { put("triggerTokens", it) }
                            }
                        }
                        if (p.visionOverrides.isNotEmpty()) putJsonObject("visionOverrides") {
                            for ((model, sees) in p.visionOverrides) put(model, sees)
                        }
                        if (p.typedModels.isNotEmpty()) putJsonArray("typedModels") { p.typedModels.forEach { add(it) } }
                        p.models?.let { models ->
                            putJsonArray("models") {
                                models.forEach { m ->
                                    addJsonObject {
                                        put("id", m.id)
                                        if (m.displayName.isNotEmpty()) put("displayName", m.displayName)
                                        m.toolCalls?.let { put("toolCalls", it) }
                                        m.vision?.let { put("vision", it) }
                                        m.contextTokens?.let { put("contextTokens", it) }
                                        m.reasoning?.let { put("reasoning", it) }
                                        m.reasoningEfforts?.let { levels -> putJsonArray("reasoningEfforts") { levels.forEach { add(it) } } }
                                        m.thinkingTypes?.let { types -> putJsonArray("thinkingTypes") { types.forEach { add(it) } } }
                                        m.chat?.let { put("chat", it) }
                                        m.endpoint?.let { e ->
                                            putJsonObject("api") {
                                                put("protocol", e.protocol.wire); put("baseUrl", e.baseUrl)
                                                e.reasoningField?.let { put("reasoningField", it) }
                                            }
                                        }
                                        if (m.unsupportedApis.isNotEmpty()) putJsonArray("unsupportedApis") { m.unsupportedApis.forEach { add(it) } }
                                        m.protocols?.takeIf { it.isNotEmpty() }?.let { ps -> putJsonArray("protocols") { ps.forEach { add(it.wire) } } }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            putJsonArray("providers") {
                _configs.value.forEach { c ->
                    addJsonObject {
                        put("id", c.id); put("providerId", c.providerId); put("name", c.name); put("kind", c.kind.name)
                        put("baseUrl", c.baseUrl); put("model", c.model)
                        put("toolCalls", c.capabilities.toolCalls); put("vision", c.capabilities.vision)
                        put("contextTokens", c.capabilities.contextTokens); put("reasoning", c.capabilities.reasoning)
                        put("groundingVerified", c.capabilities.groundingVerified)
                        put("promptCaching", c.capabilities.promptCaching.wire)
                    }
                }
            }
            putJsonObject("uses") {
                ModelRole.entries.forEach { role ->
                    when (val c = _roles.value[role]) {
                        is RoleChoice.Model -> putJsonObject(role.name) {
                            put("use", USE_MODEL); put("config", c.configId)
                            _roles.value.reasoningEfforts[role]?.let { put("reasoningEffort", it) }
                        }
                        RoleChoice.SameAsMain -> putJsonObject(role.name) { put("use", USE_MAIN) }
                        RoleChoice.Off -> putJsonObject(role.name) { put("use", USE_NONE) }
                        null -> Unit
                    }
                }
            }
        }
        return root.toString()
    }

    private fun keysJson() = buildJsonObject { keys.forEach { (id, v) -> put(id, v) } }.toString()

    private fun writeAtomically(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.delete()
            check(tmp.renameTo(f)) { "cannot write ${f.name}" }
        }
        // Keep the last successfully written file so a later truncated write or
        // hand edit can be recovered instead of silently losing providers/keys.
        val backup = when (f) {
            configFile -> configBackup
            keyFile -> keyBackup
            else -> null
        }
        if (backup != null) runCatching { f.copyTo(backup, overwrite = true) }
    }

    private fun encode(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun decode(s: String) = Base64.getDecoder().decode(s)

    /** Give the application in the process. */
    interface Owner {
        val providers: ProviderStore
    }

    companion object {
        private const val CONFIG_FILE = "providers.json"
        private const val KEY_FILE = "provider-keys.json"
        private const val CHATGPT_HOST_FILE = "chatgpt-host.json"

        /** `use` for each purpose in `uses`: specific model (remember `config`), use the main model, do not use. The old version of the file only has `roles`. */
        private const val USE_MODEL = "model"
        private const val USE_MAIN = "main"
        private const val USE_NONE = "none"

        // Even the service provider's client is built through NetProxy: Go to Settings › Network proxy,
        // local machine, LAN address (local model) direct connection
        private val http by lazy { NetProxy.shared.client(OkHttpClient.Builder().readTimeout(120, TimeUnit.SECONDS)) }

        /** A connection pool shared with the conversation model; other requests from the same service provider (such as speech transcription) also use it. */
        val modelHttp: OkHttpClient get() = http

        /** Column model, account login and token exchange are used: the read timeout is shorter, and the network proxy is also used. */
        private val shortHttp by lazy { NetProxy.shared.client(OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS)) }

        private val catalog by lazy { ModelCatalog(shortHttp) }

        private val defaultChatGptAuth by lazy { ChatGptAuth(shortHttp) }
        private val defaultOpenRouterAuth by lazy { OpenRouterAuth(shortHttp) }
        private val defaultXaiAuth by lazy { XaiAuth(shortHttp) }

        /** The name of the ChatGPT service provider (brand name, does not change with the interface language). */
        const val CHATGPT_NAME = "ChatGPT"
        /** The name of the service provider logged in with the xAI account (brand name, does not change with the interface language). It is not the same company as the "xAI" where you filled in the Key. */
        const val XAI_NAME = "xAI / Grok"
        /** Service provider authorized with OpenRouter account: The name is the same as the default, [Provider.signIn] is marked as this. */
        const val OPENROUTER_NAME = "OpenRouter"
        const val OPENROUTER_SIGN_IN = "openrouter"
        /** Service provider logged in with Gemini Web account. */
        const val GEMINI_WEB_NAME = "Google Gemini"
        const val GEMINI_WEB_BASE = "https://gemini.google.com"
        const val GEMINI_WEB_SIGN_IN = "gemini_web"
        /** What is the name of this login in the error message? */
        private const val XAI_ACCOUNT = "xAI"

        /**
         * Trial call: temporarily configured id, system prompt, sentence, trial tool, upper limit of
         * reply. The upper limit is enough for one tool call, along with the thought of the model that
         * must be thought about first (Messages, generateContent and thinking are also included in the
         * upper limit).
         */
        private const val PROBE_ID = "probe"
        private const val PROBE_SYSTEM = "This is a connection test. Call the get_time tool now. Do not answer with text."
        private const val PROBE_TEXT = "What time is it? Use the get_time tool."
        private val PROBE_TOOL = ToolSpec("get_time", "Returns the current time.", """{"type":"object","properties":{}}""")
        private const val PROBE_MAX_TOKENS = 1024

        /**
         * Column model interfaces: OpenAI compatible, Responses, ChatGPT and xAI are all `GET
         * {base}/models` (Bearer; [apiKey] for subscription login is the access token). [headers] comes
         * with every request (see [ClientHeaders]).
         */
        suspend fun listAvailable(kind: ProviderConfig.Kind, baseUrl: String, apiKey: String?, headers: Map<String, String>): ModelListResult =
            when (kind) {
                ProviderConfig.Kind.OpenAiCompat, ProviderConfig.Kind.OpenAiResponses, ProviderConfig.Kind.ChatGpt, ProviderConfig.Kind.Xai ->
                    catalog.openAiCompat(baseUrl, apiKey, headers)
                ProviderConfig.Kind.Anthropic -> catalog.anthropic(baseUrl, apiKey.orEmpty(), headers)
                ProviderConfig.Kind.Gemini -> catalog.gemini(baseUrl, apiKey.orEmpty(), headers)
                ProviderConfig.Kind.GeminiWeb -> ModelListResult.Ok(GeminiWebClient.DEFAULT_MODELS)
            }

        /**
         * Which adapter to use to call this model and what Base URL to give it. Click it to build a
         * model ([buildModel]) and give a thinking gear ([ReasoningOptions]). Subscription login (xAI)
         * always uses this interface, and does not follow other protocols written in the model list, so
         * as not to transfer the subscription to another interface.
         */
        internal fun adapterFor(cfg: ProviderConfig, endpoint: ModelEndpoint?): Pair<ProviderConfig.Kind, String> =
            if (endpoint == null || cfg.kind == ProviderConfig.Kind.Xai) cfg.kind to cfg.baseUrl
            else ProviderConfig.Kind.of(endpoint.protocol) to endpoint.adapterBaseUrl

        /** The one in the product (App built): configured under files/settings, the Key is encrypted with the Android keystore, and the usage is recorded as [usage]. */
        fun create(context: Context, usage: UsageSink, userAgent: String): ProviderStore =
            ProviderStore(
                File(context.applicationContext.filesDir, "settings"),
                KeystoreKeyCipher(),
                usage,
                userAgent = userAgent,
                writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1)),
            )

        /** The share shared within the App (given by [Owner]). */
        fun get(context: Context): ProviderStore = (context.applicationContext as? Owner)?.providers
            ?: error("the Application (${context.applicationContext.javaClass.name}) must implement ProviderStore.Owner to give the model settings")
    }
}
