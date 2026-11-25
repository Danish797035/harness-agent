package com.meenai.mina.voice

import android.content.Context
import android.content.SharedPreferences
import com.meenai.mina.settings.FeatureFlags
import com.meenai.mina.settings.KeyCipher
import com.meenai.mina.settings.KeystoreKeyCipher
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** User choices for Speech to Text, and its access in settings. The specific engine to use is determined by [SpeechResolver] combined with the capabilities of the mobile phone. */
sealed interface SpeechChoice {
    /** Not selected: If there is a mobile phone with built-in recognition, use it. */
    data object Auto : SpeechChoice

    /** Explicitly select the built-in identification of the mobile phone. */
    data object System : SpeechChoice

    /** No voice input is required. */
    data object Off : SpeechChoice

    /** Transcribed using a certain model of a certain service provider, [method] determines how to send the request. */
    data class Provider(val providerId: String, val model: String, val method: TranscribeMethod) : SpeechChoice

    /** Native streaming ASR, with credentials stored separately from this non-secret selection. */
    data class Doubao(val resourceId: String = "volc.seedasr.sauc.duration") : SpeechChoice
}

/**
 * The service provider transcribes the audio interface form. What is saved is the name, which can only
 * be added later and cannot be renamed or deleted. Only the old settings can be read back. The three
 * types with the ChatInputAudio prefix are all input_audio in the conversation interface, but the
 * writing methods are different. When selecting a model to try to transfer, try them all in sequence:
 * [ChatInputAudio]'s data is exposed to base64 and a transliteration instruction is attached;
 * [ChatInputAudioUri]'s data is replaced with `data:audio/wav;base64,...`'s data URI (some services only
 * recognize this); [ChatInputAudioOnly] also uses data URI. But it only sends audio without instructions
 * (models that specialize in transcription do not accept text blocks).
 */
enum class TranscribeMethod { AudioTranscriptions, ChatInputAudio, GeminiInline, ChatInputAudioUri, ChatInputAudioOnly }

/**
 * Select the setting where [FeatureFlags] exists, a key and a piece of JSON; if it cannot be read, it
 * will be treated as [SpeechChoice.Auto]. Another note is "the mobile phone's built-in recognition does
 * not respond" ([systemSilent]): the service never responds when you press and hold to speak, and it is
 * recorded consecutively [SILENT_STRIKES] times (a stuck is probably due to the slow cold start of the
 * recognition service, and this path should not be turned off from now on); if there is a reply any time
 * in the middle, count again. When the user selects "Automatic" or "Included with the phone" in the
 * settings, the marks and times are cleared so that phones that have changed the recognition service and
 * updated the system can try again.
 */
class SpeechSettings(private val prefs: SharedPreferences, private val cipher: KeyCipher = KeystoreKeyCipher("mina-speech-keys")) {
    private val _choice = MutableStateFlow(read())
    val choice: StateFlow<SpeechChoice> = _choice.asStateFlow()

    val hasDoubaoCredentials: Boolean get() = prefs.contains(DOUBAO_KEY)

    /** Save without making a billable network request. Both authentication generations are supported. */
    internal fun saveDoubao(credentials: DoubaoCredentials, resourceId: String) {
        require(credentials.valid && resourceId in DoubaoProtocol.resources)
        val json = buildJsonObject {
            put("apiKey", JsonPrimitive(credentials.apiKey))
            put("appId", JsonPrimitive(credentials.appId))
            put("accessKey", JsonPrimitive(credentials.accessKey))
        }.toString().toByteArray(Charsets.UTF_8)
        val encrypted = Base64.getEncoder().encodeToString(cipher.encrypt(json))
        val selected = SpeechChoice.Doubao(resourceId)
        prefs.edit().putString(DOUBAO_KEY, encrypted).putString(KEY, encode(selected).toString()).apply()
        _choice.value = selected
    }

    internal fun doubaoCredentials(): DoubaoCredentials? = runCatching {
        val encrypted = prefs.getString(DOUBAO_KEY, null) ?: return null
        val obj = Json.parseToJsonElement(String(cipher.decrypt(Base64.getDecoder().decode(encrypted)), Charsets.UTF_8)).jsonObject
        DoubaoCredentials(
            obj["apiKey"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            obj["appId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            obj["accessKey"]?.jsonPrimitive?.contentOrNull.orEmpty(),
        ).takeIf { it.valid }
    }.getOrNull()

    fun clearDoubao() {
        prefs.edit().remove(DOUBAO_KEY).apply()
        if (_choice.value is SpeechChoice.Doubao) set(SpeechChoice.Off)
    }

    private val _systemSilent = MutableStateFlow(prefs.getBoolean(SILENT, false))
    val systemSilent: StateFlow<Boolean> = _systemSilent.asStateFlow()

    fun set(choice: SpeechChoice) {
        // Both of these will use the phone's built-in recognition: if the user actively chooses it, then
        // give it another chance.
        val retry = choice == SpeechChoice.System || choice == SpeechChoice.Auto
        val edit = prefs.edit().putString(KEY, encode(choice).toString())
        if (retry) edit.remove(SILENT).remove(STRIKES)
        edit.apply()
        _choice.value = choice
        if (retry) _systemSilent.value = false
    }

    /** The built-in recognition of the mobile phone did not give a single callback this time: it was recorded as not responding until the [SILENT_STRIKES]th time in a row. */
    fun noteSystemSilent() {
        val strikes = prefs.getInt(STRIKES, 0) + 1
        val edit = prefs.edit().putInt(STRIKES, strikes)
        if (strikes >= SILENT_STRIKES) edit.putBoolean(SILENT, true)
        edit.apply()
        if (strikes >= SILENT_STRIKES) _systemSilent.value = true
    }

    /**
     * If the identification service passes the roll call test (the system does not reply by default, it
     * will reply), try it first next time; null means it does not exist, or the system will reply by
     * default. See [SystemSpeech.attempts].
     */
    val systemService: String? get() = prefs.getString(SERVICE, null)

    /** The mobile phone comes with a built-in recognition that the call was answered this time ([service] is the one who answered the call, null is the system default): the previous times of no response are invalid, and the service of the call is remembered. */
    fun noteSystemAnswered(service: String? = null) {
        val edit = prefs.edit()
        var changed = false
        if (prefs.getInt(STRIKES, 0) != 0) { edit.remove(STRIKES); changed = true }
        if (service != systemService) {
            if (service == null) edit.remove(SERVICE) else edit.putString(SERVICE, service)
            changed = true
        }
        if (changed) edit.apply()
    }

    private fun read(): SpeechChoice {
        val raw = prefs.getString(KEY, null) ?: return SpeechChoice.Auto
        return runCatching { decode(Json.parseToJsonElement(raw).jsonObject) }.getOrNull() ?: SpeechChoice.Auto
    }

    private fun encode(choice: SpeechChoice): JsonObject = buildJsonObject {
        when (choice) {
            SpeechChoice.Auto -> put(TYPE, JsonPrimitive("auto"))
            SpeechChoice.System -> put(TYPE, JsonPrimitive("system"))
            SpeechChoice.Off -> put(TYPE, JsonPrimitive("off"))
            is SpeechChoice.Doubao -> {
                put(TYPE, JsonPrimitive("doubao"))
                put("resourceId", JsonPrimitive(choice.resourceId))
            }
            is SpeechChoice.Provider -> {
                put(TYPE, JsonPrimitive("provider"))
                put("providerId", JsonPrimitive(choice.providerId))
                put("model", JsonPrimitive(choice.model))
                put("method", JsonPrimitive(choice.method.name))
            }
        }
    }

    /** If the field is missing, the type is not recognized, or the method name does not exist, null will be returned, and it will be returned automatically by the calling site. */
    private fun decode(obj: JsonObject): SpeechChoice? = when (obj[TYPE]?.jsonPrimitive?.contentOrNull) {
        "auto" -> SpeechChoice.Auto
        "system" -> SpeechChoice.System
        "off" -> SpeechChoice.Off
        "doubao" -> obj["resourceId"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it in DoubaoProtocol.resources }?.let { SpeechChoice.Doubao(it) }
        "provider" -> {
            val id = obj["providerId"]?.jsonPrimitive?.contentOrNull
            val model = obj["model"]?.jsonPrimitive?.contentOrNull
            val method = TranscribeMethod.entries.firstOrNull { it.name == obj["method"]?.jsonPrimitive?.contentOrNull }
            if (id == null || model == null || method == null) null else SpeechChoice.Provider(id, model, method)
        }
        else -> null
    }

    companion object {
        private const val KEY = "speech_choice"
        private const val DOUBAO_KEY = "speech_doubao_credentials_encrypted"
        private const val TYPE = "type"
        private const val SILENT = "speech_system_silent"
        private const val STRIKES = "speech_system_silent_strikes"
        private const val SERVICE = "speech_system_service"

        /** I wrote it down after not responding so many times. */
        const val SILENT_STRIKES = 2

        fun get(context: Context) = SpeechSettings(
            context.applicationContext.getSharedPreferences(FeatureFlags.PREFS, Context.MODE_PRIVATE),
        )
    }
}
