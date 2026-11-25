package com.meenai.mina.voice

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SystemSpeechTest {
    @Test
    fun errorCodesMapToFailures() {
        assertNull(SystemSpeech.failureOf(SpeechRecognizer.ERROR_NO_MATCH))
        assertNull(SystemSpeech.failureOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertEquals(SpeechFailure.Busy, SystemSpeech.failureOf(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
        assertEquals(SpeechFailure.Network, SystemSpeech.failureOf(SpeechRecognizer.ERROR_NETWORK))
        assertEquals(SpeechFailure.Network, SystemSpeech.failureOf(SpeechRecognizer.ERROR_NETWORK_TIMEOUT))
        assertEquals(SpeechFailure.NoPermission, SystemSpeech.failureOf(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertEquals(SpeechFailure.AudioError, SystemSpeech.failureOf(SpeechRecognizer.ERROR_AUDIO))
        assertEquals(SpeechFailure.MicUnavailable, SystemSpeech.failureOf(SpeechRecognizer.ERROR_CLIENT))
    }

    @Test
    fun silenceFromAServiceThatNeverAnsweredIsNoResponse() {
        assertEquals(
            SystemSpeech.Outcome.Fail(SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemNoResponse)),
            SystemSpeech.afterSilence(answered = false),
        )
        // The service that has been answered is only stuck in this section: press the end of what has
        // been heard, and if it is not recorded, it will not respond.
        assertEquals(SystemSpeech.Outcome.Finish, SystemSpeech.afterSilence(answered = true))
    }

    @Test
    fun releaseWaitStillEndsWhenTheRecognizerNeverAnswered() {
        assertEquals(
            SystemSpeech.Outcome.Fail(SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemNoResponse)),
            SystemSpeech.afterReleaseWait(answered = false),
        )
        assertEquals(SystemSpeech.Outcome.Finish, SystemSpeech.afterReleaseWait(answered = true))
    }

    @Test
    fun languageTagsFollowAppLanguage() {
        assertEquals("en-US", SystemSpeech.speechTag(com.meenai.mina.i18n.AppLanguage.English, java.util.Locale.CHINA))
        assertEquals("fr-FR", SystemSpeech.speechTag(com.meenai.mina.i18n.AppLanguage.System, java.util.Locale.FRANCE))
        assertEquals("hi-IN", SystemSpeech.speechTag(com.meenai.mina.i18n.AppLanguage.Hindi, java.util.Locale.US))
        assertEquals("hi", SystemSpeech.isoCode("hi-IN"))
        assertEquals("en", SystemSpeech.isoCode("en-US"))
    }

    @Test
    fun systemLanguageDropsScriptAndExtensions() {
        val system = com.meenai.mina.i18n.AppLanguage.System
        assertEquals("zh-CN", SystemSpeech.speechTag(system, java.util.Locale.forLanguageTag("zh-Hans-CN")))
        assertEquals("zh-TW", SystemSpeech.speechTag(system, java.util.Locale.forLanguageTag("zh-Hant-TW")))
        assertEquals("sr-RS", SystemSpeech.speechTag(system, java.util.Locale.forLanguageTag("sr-Latn-RS")))
        assertEquals("de-DE", SystemSpeech.speechTag(system, java.util.Locale.forLanguageTag("de-DE-u-co-phonebk")))
        assertEquals("ja", SystemSpeech.speechTag(system, java.util.Locale.forLanguageTag("ja")))
        val english = com.meenai.mina.i18n.AppLanguage.English
        assertEquals("en-GB", SystemSpeech.speechTag(english, java.util.Locale.forLanguageTag("en-Latn-GB")))
    }

    private fun svc(name: String, open: Boolean = true) = RecognizerService("a.b/.$name", open, null)

    /** When there is only one identification service, it is the default: just try the default one, and try again without naming it, and you will wait in vain. */
    @Test
    fun aSingleServiceIsOnlyTriedAsTheDefault() {
        assertEquals(listOf<String?>(null), SystemSpeech.attempts(listOf(svc("One")), remembered = null))
        assertEquals(listOf<String?>(null), SystemSpeech.attempts(emptyList(), remembered = null))
    }

    /** When the default is no reply, the others will be called and those open to other apps will come first; the total number has an upper limit. */
    @Test
    fun otherServicesFollowTheDefaultOpenOnesFirst() {
        val services = listOf(svc("Closed", open = false), svc("Open"), svc("Third"))
        assertEquals(listOf(null, "a.b/.Open", "a.b/.Third"), SystemSpeech.attempts(services, remembered = null))
        assertEquals(SystemSpeech.MAX_ATTEMPTS, SystemSpeech.attempts(services + svc("Fourth"), null).size)
    }

    /** The one that was previously called by name will be ranked first and will not be repeated; if it is no longer on the phone, just ignore it. */
    @Test
    fun theServiceThatAnsweredBeforeGoesFirst() {
        val services = listOf(svc("One"), svc("Two"))
        assertEquals(listOf("a.b/.Two", null, "a.b/.One"), SystemSpeech.attempts(services, remembered = "a.b/.Two"))
        assertEquals(listOf(null, "a.b/.One", "a.b/.Two"), SystemSpeech.attempts(services, remembered = "a.b/.Gone"))
    }
}
