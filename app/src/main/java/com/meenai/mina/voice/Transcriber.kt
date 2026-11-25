package com.meenai.mina.voice

import com.meenai.mina.core.model.ListedModel
import com.meenai.mina.core.model.ModelError
import com.meenai.mina.core.model.ModelHttp
import com.meenai.mina.settings.Provider
import com.meenai.mina.settings.ProviderConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/**
 * Use the service provider's model to convert a recording into text. See [TranscribeMethod] for the
 * interface form. Error reporting and dialogue models use the same set of mappings ([ModelHttp]), and
 * the interface can directly reuse existing statements; requests are only sent according to the
 * protocol, not based on the model name. [headers] is the header carried by each request (client
 * identification, etc., see ClientHeaders). The Key header is added here according to the interface
 * type.
 */
class Transcriber(
    private val http: OkHttpClient,
    private val headers: Map<String, String> = emptyMap(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Transfer a piece of WAV; if failed, throw [ModelError](same as the conversation model). The text returned may be an empty string (no words were heard). */
    suspend fun transcribe(
        p: Provider, key: String?, model: String, method: TranscribeMethod, wav: ByteArray, language: String?,
    ): String {
        val base = p.baseUrl.trim().trimEnd('/')
        val gemini = p.kind == ProviderConfig.Kind.Gemini
        val request = when (method) {
            TranscribeMethod.AudioTranscriptions -> {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
                    .addFormDataPart("model", model)
                    .apply { if (!language.isNullOrBlank()) addFormDataPart("language", language) }
                    .build()
                builder("$base/audio/transcriptions", key, gemini).post(body).build()
            }
            TranscribeMethod.ChatInputAudio, TranscribeMethod.ChatInputAudioUri, TranscribeMethod.ChatInputAudioOnly -> {
                val data = if (method == TranscribeMethod.ChatInputAudio) base64(wav) else "data:audio/wav;base64,${base64(wav)}"
                val payload = buildJsonObject {
                    put("model", model)
                    put("stream", false)
                    put("messages", buildJsonArray {
                        add(buildJsonObject {
                            put("role", "user")
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "input_audio")
                                    putJsonObject("input_audio") { put("data", data); put("format", "wav") }
                                })
                                // Models specialized in transliteration do not accept text blocks;
                                // general models without this command will treat it as a chat reply.
                                if (method != TranscribeMethod.ChatInputAudioOnly) {
                                    add(buildJsonObject { put("type", "text"); put("text", INSTRUCTION) })
                                }
                            })
                        })
                    })
                }
                builder("$base/chat/completions", key, gemini).post(payload.toString().toRequestBody(JSON)).build()
            }
            TranscribeMethod.GeminiInline -> {
                val payload = buildJsonObject {
                    put("contents", buildJsonArray {
                        add(buildJsonObject {
                            put("parts", buildJsonArray {
                                add(buildJsonObject {
                                    putJsonObject("inline_data") { put("mime_type", "audio/wav"); put("data", base64(wav)) }
                                })
                                add(buildJsonObject { put("text", INSTRUCTION) })
                            })
                        })
                    })
                }
                // The same as the conversation adapter: the inventory address does not include /v1beta,
                // please add it here
                builder("$base/v1beta/models/$model:generateContent", key, gemini).post(payload.toString().toRequestBody(JSON)).build()
            }
        }
        val reply = ModelHttp.send(http, request)
        if (!reply.ok) {
            throw if (gemini) ModelHttp.geminiError(reply.status, reply.retryAfter, reply.body, model)
            else ModelHttp.openAiError(reply.status, reply.retryAfter, reply.body, model)
        }
        return parse(method, reply.body)
    }

    /**
     * When selecting a model, try switching: try in order according to the interface type, and return
     * the first one that passes; if it fails, the last error will be thrown. Pass = back to 2xx and
     * parse to get the text field (empty string also counts). For the Key problem (401/403/limit), it is
     * useless to try other methods, just throw it away.
     */
    suspend fun detect(p: Provider, key: String?, model: String): TranscribeMethod {
        val order = if (p.kind == ProviderConfig.Kind.Gemini) listOf(TranscribeMethod.GeminiInline)
        // Those who only send audio without instructions will be placed last: the general model will
        // also respond to 2xx (reply a paragraph by treating the recording as a chat), and will be
        // mistakenly selected if it is ranked first.
        else listOf(
            TranscribeMethod.AudioTranscriptions, TranscribeMethod.ChatInputAudio,
            TranscribeMethod.ChatInputAudioUri, TranscribeMethod.ChatInputAudioOnly,
        )
        val wav = Wav.silence(1000)
        var last: ModelError? = null
        for (method in order) {
            try {
                transcribe(p, key, model, method, wav, null)
                return method
            } catch (e: ModelError) {
                last = e
                if (!triesNext(e)) throw e
            }
        }
        throw last ?: ModelError.BadResponse("no method")
    }

    /** Only if "this interface is not used in this way" (the path cannot be found, the method is wrong, the request is inconsistent, and the response is not in this format) will it be replaced with the next one. */
    private fun triesNext(e: ModelError): Boolean = e is ModelError.ModelNotFound || e is ModelError.BadResponse

    private fun builder(url: String, key: String?, gemini: Boolean): Request.Builder = Request.Builder().url(url).apply {
        headers.forEach { (k, v) -> header(k, v) }
        if (!key.isNullOrEmpty()) { if (gemini) header("x-goog-api-key", key) else header("Authorization", "Bearer $key") }
    }

    private fun parse(method: TranscribeMethod, body: String): String {
        val root = try { json.parseToJsonElement(body) as? JsonObject } catch (e: Exception) { null }
            ?: throw ModelError.BadResponse("not json: ${body.take(120)}")
        val text = when (method) {
            TranscribeMethod.AudioTranscriptions -> root.str("text")
            TranscribeMethod.ChatInputAudio, TranscribeMethod.ChatInputAudioUri, TranscribeMethod.ChatInputAudioOnly -> {
                val msg = ((root["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
                when (val c = msg?.get("content")) {
                    is JsonPrimitive -> c.contentOrNull
                    // The content is an array of blocks: put the text blocks together
                    is JsonArray -> c.mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("")
                    else -> null
                }
            }
            TranscribeMethod.GeminiInline -> {
                val cand = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
                val parts = (cand?.get("content") as? JsonObject)?.get("parts") as? JsonArray
                // If there is no text block (for example, only the end reason is returned when muting),
                // press the empty string; if there are no candidates, the format is incorrect.
                if (cand == null) null else parts.orEmpty().mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("")
            }
        }
        return (text ?: throw ModelError.BadResponse("no text field: ${body.take(120)}")).trim()
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    private fun base64(b: ByteArray): String = Base64.getEncoder().encodeToString(b)

    companion object {
        private val JSON = "application/json".toMediaType()

        /** Instructions given to the model: Just the exact words, no explanation required (the model is in English). */
        private const val INSTRUCTION = "Transcribe this recording word for word. Output only the transcript text."

        private val SPEECH_TOKENS = listOf("asr", "transcribe", "whisper", "paraformer", "sensevoice", "stt", "speech")

        /** Can this company do speech-to-text conversion? OpenAI compatibility with Key, Responses, and Gemini are OK; Anthropic and account login are not. */
        fun supports(p: Provider): Boolean = when (p.kind) {
            ProviderConfig.Kind.OpenAiCompat, ProviderConfig.Kind.OpenAiResponses, ProviderConfig.Kind.Gemini -> p.signIn == null
            else -> false
        }

        /**
         * The list looks like a speech model: the names are cut into segments according to
         * non-alphanumeric numbers, and one segment starts with asr, transscribe, whisper, etc. The
         * model list is only marked "Is it a dialogue model?" There is no separate voice mark, so you
         * can only see the name.
         */
        fun looksLikeSpeech(m: ListedModel): Boolean =
            m.id.lowercase().split(Regex("[^a-z0-9]+")).any { seg -> SPEECH_TOKENS.any { seg.startsWith(it) } }
    }
}
