package com.meenai.mina.voice

import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.settings.Provider

/**
 * The pure logic used in "speech to text" in Settings › Model: how to group service providers on the
 * selection page, and what to write on the right side of the usage card. It does not touch the interface
 * and network, so it can be tested directly.
 */
object SpeechOptions {
    /**
     * Select a group in the page: [models] to only display the voice model. There is another manual
     * entry under each group (the interface is responsible): The service provider's model list is often
     * incomplete, and voice models are especially easy to miss, or the list may not be provided at all.
     */
    data class Group(val provider: Provider, val models: List<String>)

    /** There are one group of service providers that can transcribe; only those with voice models are left in the list. */
    fun groups(providers: List<Provider>): List<Group> = providers.filter(Transcriber::supports).map { p ->
        Group(p, p.models.orEmpty().filter(Transcriber::looksLikeSpeech).map { it.id })
    }

    /** The two lines on the right side of the card: [title] is what is selected, [sub] is supplement (may not be available), [subOk] is supplemented with "available" green when it is true. */
    data class Summary(val title: String, val sub: String?, val subOk: Boolean)

    fun summary(choice: SpeechChoice, system: SystemCapability, providers: List<Provider>): Summary {
        val unset = Summary(tr(R.string.settings_not_set), null, false)
        // The system says yes, but no response after trying
        val silent = Summary(tr(R.string.speech_system), tr(R.string.speech_system_silent), false)
        return when (choice) {
            SpeechChoice.Off -> Summary(tr(R.string.models_off), null, false)
            is SpeechChoice.Doubao -> Summary(tr(R.string.speech_doubao_model), tr(R.string.speech_doubao_provider), false)
            SpeechChoice.Auto -> when {
                system.usable -> systemSummary(system)
                system.available -> silent
                else -> unset
            }
            SpeechChoice.System -> when {
                // I clearly chose to bring it with my phone, but now I can’t use it: tell the truth and
                // don’t quietly change it to another one
                system.usable -> systemSummary(system)
                system.available -> silent
                else -> Summary(tr(R.string.speech_system), tr(R.string.speech_system_gone), false)
            }
            is SpeechChoice.Provider -> {
                val p = providers.firstOrNull { it.id == choice.providerId } ?: return unset
                Summary(choice.model, p.name, false)
            }
        }
    }

    private fun systemSummary(system: SystemCapability) = Summary(
        tr(R.string.speech_system),
        tr(if (system.onDevice) R.string.speech_system_offline else R.string.speech_system_online),
        subOk = system.onDevice,
    )
}
