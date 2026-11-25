package com.meenai.mina.voice

import com.meenai.mina.settings.Provider

/**
 * The mobile phone has its own recognition capability: the system says whether it is available
 * ([available]), whether it is recognized on the local machine (not connected to the Internet), and when
 * it is tried on this mobile phone, it ignores us ([responsive]; the system says it is, and the request
 * is sent but no callback is given, so it is recorded as false).
 */
data class SystemCapability(val available: Boolean, val onDevice: Boolean, val responsive: Boolean = true) {
    /** It can really be used for dictation: the system has it and it is not recorded as unresponsive. */
    val usable get() = available && responsive
}

/** The recognition engine actually used this time; if the selected thing is no longer available, [Unavailable] and the reason will be given, and the interface will prompt. */
sealed interface SpeechEngineSpec {
    data class System(val onDevice: Boolean) : SpeechEngineSpec
    data class Provider(val provider: com.meenai.mina.settings.Provider, val model: String, val method: TranscribeMethod) : SpeechEngineSpec
    data class Doubao(val resourceId: String) : SpeechEngineSpec
    data class Unavailable(val why: Why) : SpeechEngineSpec
    /** [SystemNoResponse]: device built-in speech recognizer reports available, but requests get no callback (recorded and cleared when the user selects it again). */
    enum class Why { Off, NoSystemNoModel, SystemGone, SystemNoResponse, ProviderGone, DoubaoNotConfigured }
}

/** Compare the user's choices, mobile phone capabilities and existing service providers to figure out which engine to use this time. Pure function, does not touch the system. */
object SpeechResolver {
    fun resolve(choice: SpeechChoice, system: SystemCapability, providers: List<Provider>): SpeechEngineSpec = when (choice) {
        SpeechChoice.Off -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.Off)
        is SpeechChoice.Doubao -> if (choice.resourceId in DoubaoProtocol.resources) SpeechEngineSpec.Doubao(choice.resourceId)
            else SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.DoubaoNotConfigured)
        // If the built-in recognition does not respond, tell the truth and ask for settings. Do not
        // quietly switch to a service provider model that costs money.
        SpeechChoice.Auto -> when {
            system.usable -> SpeechEngineSpec.System(system.onDevice)
            system.available -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.SystemNoResponse)
            else -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.NoSystemNoModel)
        }
        SpeechChoice.System -> when {
            system.usable -> SpeechEngineSpec.System(system.onDevice)
            system.available -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.SystemNoResponse)
            else -> SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.SystemGone)
        }
        is SpeechChoice.Provider -> {
            val p = providers.firstOrNull { it.id == choice.providerId }
            if (p == null) SpeechEngineSpec.Unavailable(SpeechEngineSpec.Why.ProviderGone)
            else SpeechEngineSpec.Provider(p, choice.model, choice.method)
        }
    }
}
