package com.meenai.mina.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import com.meenai.mina.i18n.AppLanguage
import com.meenai.mina.settings.Provider
import com.meenai.mina.settings.ProviderStore

/** The interface (input field, floating capsule) is where the voice conversation is taken; in the App it is [VoiceInput], and the interface test is replaced with a fake one. */
interface VoiceSource {
    fun spec(): SpeechEngineSpec
    fun start(language: String? = null): SpeechSession
}

/**
 * Entrance to voice input: dialogue page input boxes and floating capsules can only be accessed here.
 * First, decide on the engine ([spec]) according to the user's choice, mobile phone capabilities and
 * existing services, and then open a session ([start]). [transcriber] provides transcribers according to
 * service providers: each has different request headers (client ID, session headers it requires).
 */
class VoiceInput(
    context: Context,
    private val settings: SpeechSettings,
    private val providers: ProviderStore,
    private val transcriber: (Provider) -> Transcriber = { p -> Transcriber(ProviderStore.modelHttp, providers.clientHeaders(p)) },
) : VoiceSource {
    private val context = context.applicationContext

    /** The interface first asks if it can be used and which one is used (the small label on the top of the card). */
    override fun spec(): SpeechEngineSpec =
        SpeechResolver.resolve(settings.choice.value, SystemSpeech.capability(context, settings.systemSilent.value), providers.providers.value)

    /**
     * Open a session and press the time call (it must be on the main thread). [language] is the
     * recognition language (BCP-47); null is the App interface language. When there is no recording
     * permission and the engine cannot be used, only one Failed session will be sent, and the interface
     * will be processed in the same way.
     */
    override fun start(language: String?): SpeechSession {
        val spec = spec()
        if (spec is SpeechEngineSpec.Unavailable) return FailedSession(SpeechFailure.Unavailable(spec.why))
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return FailedSession(SpeechFailure.NoPermission)
        }
        val tag = language ?: SystemSpeech.speechTag(AppLanguage.current(context))
        return when (spec) {
            is SpeechEngineSpec.Doubao -> {
                val credentials = settings.doubaoCredentials()
                    ?: return FailedSession(SpeechFailure.Unavailable(SpeechEngineSpec.Why.DoubaoNotConfigured))
                DoubaoSpeech(PcmRecorder(streaming = true), { DoubaoTransport().connect(credentials, spec.resourceId) })
            }
            is SpeechEngineSpec.System -> SystemSpeechSession(
                context, spec.onDevice, tag,
                onNoResponse = settings::noteSystemSilent, onAnswered = settings::noteSystemAnswered,
                attempts = SystemSpeech.attempts(SystemSpeech.services(context), settings.systemService),
            )
            is SpeechEngineSpec.Provider -> {
                val p = spec.provider
                val t = transcriber(p)
                val iso = SystemSpeech.isoCode(tag)
                ProviderSpeech(PcmRecorder(), { wav ->
                    t.transcribe(p, providers.providerKey(p.id), spec.model, spec.method, wav, iso)
                })
            }
            is SpeechEngineSpec.Unavailable -> FailedSession(SpeechFailure.Unavailable(spec.why))
        }
    }
}
