package com.meenai.mina.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.meenai.mina.i18n.AppLanguage
import kotlinx.coroutines.flow.Flow
import java.util.Locale

/** An identification service registered on a mobile phone, see [SystemSpeech.services]. [component] is the abbreviation of "package name/class name". */
data class RecognizerService(val component: String, val open: Boolean, val permission: String?)

/** The mobile phone comes with identification: ability detection, how to classify error codes, and the language used for identification. */
object SystemSpeech {
    /**
     * Starting from Android 11, query RecognitionService must be declared in the manifest, otherwise it
     * will never be available. [silent] is a sign that the service has not responded after trying it
     * before (see [SpeechSettings.systemSilent]): the system can only tell whether it is available, and
     * you will not know until you try it.
     */
    fun capability(context: Context, silent: Boolean = false): SystemCapability {
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }.getOrDefault(false)
        val online = runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)
        return SystemCapability(available = online || onDevice, onDevice = onDevice, responsive = !silent)
    }

    /**
     * Identifies the type of failure that the error code of the service belongs to; null means "no words
     * were heard in this paragraph" (no matching, no opening). Restart when pressed, and the result will
     * be the same as before when released. Codes outside the list (server errors, language not
     * supported, etc.) will be handled as service problems.
     */
    fun failureOf(code: Int): SpeechFailure? = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, ERROR_TOO_MANY_REQUESTS -> SpeechFailure.Busy
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER, ERROR_SERVER_DISCONNECTED -> SpeechFailure.Network
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechFailure.NoPermission
        SpeechRecognizer.ERROR_AUDIO -> SpeechFailure.AudioError
        SpeechRecognizer.ERROR_CLIENT -> SpeechFailure.MicUnavailable
        else -> SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemGone)
    }

    /** What to do after an error code arrives: restart, press the end you heard, report failure. */
    sealed interface Outcome {
        data object Retry : Outcome
        data object Finish : Outcome
        data class Fail(val why: SpeechFailure) : Outcome
    }

    /**
     * [holding] means the hand is still pressed, [heard] means the word has been heard. Don't lose the
     * words you have heard: after releasing the word, or if you make a mistake while pressing, just
     * finish with the word you heard. Some services will report ERROR_CLIENT after stopListening. If you
     * don't hear anything, it means you didn't hear clearly. It's not that the microphone is broken.
     */
    fun afterError(code: Int, holding: Boolean, heard: Boolean): Outcome {
        val failure = failureOf(code)
        return when {
            failure == null && holding -> Outcome.Retry
            failure == null || heard -> Outcome.Finish
            !holding && code == SpeechRecognizer.ERROR_CLIENT -> Outcome.Finish
            else -> Outcome.Fail(failure)
        }
    }

    /**
     * What to do when the recognition service gives no callback within
     * [SystemSpeechSession.NO_RESPONSE_MS] after listening starts. [answered] means this recognizer
     * answered before (the service works, it just got stuck now): finish with what was heard; if nothing
     * was heard, the speech was unclear. If it never answered, the phone's recognition service is
     * ignoring requests from third-party apps, so waiting longer will not produce words; report
     * [SpeechEngineSpec.Why.SystemNoResponse].
     */
    fun afterSilence(answered: Boolean): Outcome =
        if (answered) Outcome.Finish else Outcome.Fail(SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemNoResponse))

    /**
     * How to close [SystemSpeechSession.FINAL_WAIT_MS] when there is still no final result after
     * releasing it. Same as [afterSilence]: if you reply, end with what you heard. If you never reply,
     * it means no response. You can't do nothing just because you haven't replied, otherwise the
     * suspension capsule will stop at "Transfer text...".
     */
    fun afterReleaseWait(answered: Boolean): Outcome = afterSilence(answered)

    /**
     * The recognition service registered on the mobile phone (the component that declares
     * RecognitionService). [RecognizerService.open] means it is open (exported) to other apps;
     * [RecognizerService.permission] means the permissions it needs to be bound. For diagnostic
     * information and [attempts].
     */
    fun services(context: Context): List<RecognizerService> = runCatching {
        context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0).map { r ->
            val s = r.serviceInfo
            RecognizerService(ComponentName(s.packageName, s.name).flattenToShortString(), s.exported, s.permission)
        }
    }.getOrDefault(emptyList())

    /**
     * A session is used to try the identification services one by one, and null is the system default
     * one (not named, chosen by the system). Those who have tried calling before and responded
     * ([remembered], still on the phone) will be ranked first; when none of the default callbacks are
     * given, then try the others by calling, and those open to other apps will be tried first. When
     * there is only one service on the phone, it will be the default one, and you will not need to name
     * it and try again; you can try [MAX_ATTEMPTS] at most, so that the person pressing the button will
     * not have to wait.
     */
    fun attempts(services: List<RecognizerService>, remembered: String?): List<String?> {
        val known = remembered?.takeIf { r -> services.any { it.component == r } }
        val others = if (services.size < 2) emptyList() else
            services.filter { it.component != known }.sortedBy { !it.open }.map { it.component }
        return (listOfNotNull(known) + listOf(null) + others).take(MAX_ATTEMPTS)
    }

    /** A maximum of several identification services (including the default ones) can be tried in one session. */
    const val MAX_ATTEMPTS = 3

    /** Local recognition says that this language cannot be used (no language pack installed, etc.): Switch to network recognition and try again. */
    internal fun languageMissing(code: Int) = code == ERROR_LANGUAGE_NOT_SUPPORTED || code == ERROR_LANGUAGE_UNAVAILABLE

    /** The recognition language corresponding to the App language (BCP-47); use the system language when following the system. When the selected language is the same as the system language (except Chinese), the system region will be used. */
    fun speechTag(language: AppLanguage, system: Locale = Locale.getDefault()): String = when {
        language == AppLanguage.System -> languageRegion(system)
        // For Chinese, select Simplified and Traditional to determine the region, which does not follow
        // the system.
        language.language == "zh" -> language.speechTag!!
        // The system is also the region where the system is used in this language (British English,
        // Mexican Spanish...), otherwise the most common region in which this language is used
        AppLanguage.modernCode(system.language) == language.language -> languageRegion(system)
        else -> language.speechTag!!
    }

    /**
     * Only "Language-Region" is left: the system language often has a text sub-tag (zh-Hans-CN), and the
     * recognition service generally only recognizes zh-CN. If it is written with more than one
     * paragraph, it may be regarded as an unsupported language. Extensions and variants are also
     * removed.
     */
    private fun languageRegion(locale: Locale): String =
        Locale.Builder().setLanguage(locale.language).setRegion(locale.country).build().toLanguageTag()

    /** ISO-639-1 language code required by the transliteration interface. */
    fun isoCode(tag: String): String = Locale.forLanguageTag(tag).language

    // The error code is only available since Android 12; it is written as a numerical value. It will not
    // be affected if lower versions cannot receive it.
    private const val ERROR_TOO_MANY_REQUESTS = 10
    private const val ERROR_SERVER_DISCONNECTED = 11
    private const val ERROR_LANGUAGE_NOT_SUPPORTED = 12
    private const val ERROR_LANGUAGE_UNAVAILABLE = 13
}

/**
 * A session that the phone comes with recognition. [SpeechRecognizer] can only be created and reconciled
 * in the main thread, so all operations on it here are cast to the main thread. Press the period
 * identification service to end a section (the final result or "not heard") and then restart it
 * immediately. Each section is put together by [Segments]; after releasing, stopListening, wait at most
 * [FINAL_WAIT_MS] to get the final result, and if it cannot wait, use the last intermediate result.
 * Every time after startListening, there is no callback for the service in [NO_RESPONSE_MS]: the hand is
 * still pressed, and there is still a service in [attempts] that has not been tried, so I change to the
 * next one to listen; I have tried [SystemSpeech.afterSilence] to end it. When there is no reply, dial
 * [onNoResponse]. When there is a reply, dial [onAnswered] once and bring the service of the reply (null
 * is the system default): set the number of consecutive non-responses according to this number. Only
 * after enough, remember it. Also remember the one who was called to try.
 */
class SystemSpeechSession(
    context: Context,
    private val onDevice: Boolean,
    private val languageTag: String,
    private val onNoResponse: () -> Unit = {},
    private val onAnswered: (String?) -> Unit = {},
    /** Try the identification service in turn, see [SystemSpeech.attempts]; null is the system default. */
    private val attempts: List<String?> = listOf(null),
) : SpeechSession {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val out = SessionEvents()
    private val segments = Segments()
    override val events: Flow<SpeechEvent> = out.flow
    override val live = true

    // The following are only read and written in the main thread
    private var recognizer: SpeechRecognizer? = null
    private var usingOnDevice = false
    /** Trying the number in [attempts]. */
    private var attempt = 0
    private var holding = true
    private val restarts = Restarts()
    private var restartPending = false
    /** The current recognizer has not responded (any callbacks count). */
    private var answered = false
    /** [onAnswered] has been reported for this session; it is reported only once per session to avoid having to write settings for each volume callback. */
    private var reported = false
    /** This session has already been demerited once for non-response. The waiting and silent timing after release may occur one after another and cannot be recorded twice. */
    private var notedSilent = false
    // Release and wait for [FINAL_WAIT_MS]. After replying, end as you heard; it must end even if there
    // is no callback, and you cannot stop at "convert to text..."
    private val timeout = Runnable { concludeRelease() }
    private val silence = Runnable {
        if (done()) return@Runnable
        if (!answered && holding && attempt + 1 < attempts.size) {
            // This service didn't give a single callback: I still kept my hand pressed, so I changed to
            // the next service and continued listening.
            attempt++
            open(preferOnDevice = false)
            return@Runnable
        }
        concludeSilence()
    }

    /** Be silent to the point, and will not change to the next service: end with whether there is a reply. Not responding to the same session is only recorded once. */
    private fun concludeSilence() {
        if (done()) return
        when (val o = SystemSpeech.afterSilence(answered)) {
            is SystemSpeech.Outcome.Fail -> {
                noteSilent()
                finish(SpeechEvent.Failed(o.why))
            }
            else -> finish(SpeechEvent.Final(segments.full()))
        }
    }
    private val restart = Runnable {
        restartPending = false
        if (done()) return@Runnable
        if (holding) listen() else concludeRelease()
    }

    /** If you wait too long after releasing it, or if you find that your finger has left when you reopen it, you must end this session. */
    private fun concludeRelease() {
        if (done()) return
        when (val o = SystemSpeech.afterReleaseWait(answered)) {
            is SystemSpeech.Outcome.Fail -> {
                noteSilent()
                finish(SpeechEvent.Failed(o.why))
            }
            else -> finish(SpeechEvent.Final(segments.full()))
        }
    }

    private fun noteSilent() {
        if (notedSilent) return
        notedSilent = true
        onNoResponse()
    }

    override fun release() = onMain {
        if (!holding || done()) return@onMain
        holding = false
        if (restartPending) {
            // The previous segment has ended and has not been restarted yet: If you are not listening,
            // just press the ending button you have heard.
            finish(SpeechEvent.Final(segments.full()))
            return@onMain
        }
        try {
            recognizer?.stopListening()
        } finally {
            // The synchronization callback in stopListening may have ended the session, or may have
            // removed the wait; if it is not over, it will be queued again.
            if (!done()) main.postDelayed(timeout, FINAL_WAIT_MS)
        }
    }

    override fun cancel() {
        // First close the event exit (any thread takes effect immediately), and then the main thread
        // cleans up the recognizer
        if (!out.cancel()) return
        onMain {
            recognizer?.cancel()
            teardown()
        }
    }

    private fun done() = out.finished || out.cancelled

    private fun open(preferOnDevice: Boolean) {
        if (done()) return
        teardown()
        val r = if (preferOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
        } else {
            null
        }
        usingOnDevice = r != null
        // It is used for services that are named; services that are not named (or whose names cannot be
        // recognized) are left to the system's default service.
        val named = attempts.getOrNull(attempt)?.let(ComponentName::unflattenFromString)
        val rec = r ?: runCatching { SpeechRecognizer.createSpeechRecognizer(context, named) }.getOrNull()
        if (rec == null) {
            finish(SpeechEvent.Failed(SpeechFailure.Unavailable(SpeechEngineSpec.Why.SystemGone)))
            return
        }
        recognizer = rec
        answered = false
        rec.setRecognitionListener(listener)
        listen()
    }

    private fun listen() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // When identifying on the Internet: If your mobile phone has native identification
            // capabilities, please ask the service to identify it offline as much as possible.
            if (!usingOnDevice && onDevice) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        main.removeCallbacks(silence)
        main.postDelayed(silence, NO_RESPONSE_MS)
        try {
            recognizer?.startListening(intent)
        } catch (e: RuntimeException) {
            finish(SpeechEvent.Failed(SpeechFailure.MicUnavailable))
        }
    }

    private fun finish(e: SpeechEvent) {
        main.removeCallbacks(timeout)
        out.finish(e)
        teardown()
    }

    /** This section is empty when pressed: press [Restarts] to decide how long it will take to reopen, or not to reopen again, and press the ending you have heard. Restarting is always done in the main thread and is not started directly in the callback. */
    private fun emptySegment() {
        when (val d = restarts.onEmpty(android.os.SystemClock.elapsedRealtime())) {
            Restarts.Decision.GiveUp -> finish(SpeechEvent.Final(segments.full()))
            is Restarts.Decision.Restart -> {
                restartPending = true
                main.postDelayed(restart, d.delayMs)
            }
        }
    }

    /** The service replied: This period is not considered unresponsive. */
    private fun heard() {
        answered = true
        main.removeCallbacks(silence)
        if (!reported) {
            reported = true
            onAnswered(if (usingOnDevice) null else attempts.getOrNull(attempt))
        }
    }

    private fun teardown() {
        main.removeCallbacks(timeout)
        main.removeCallbacks(silence)
        main.removeCallbacks(restart)
        restartPending = false
        val rec = recognizer
        recognizer = null
        // Remove it first and then destroy:destroy may call back onError synchronously. At this time,
        // you cannot change the engine, otherwise it will be nested and restarted in the failure
        // processing.
        usingOnDevice = false
        rec?.let { runCatching { it.destroy() } }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun firstText(results: Bundle?): String =
        results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = heard()
        override fun onBeginningOfSpeech() = heard()
        override fun onBufferReceived(buffer: ByteArray?) = heard()
        override fun onEndOfSpeech() = heard()
        override fun onEvent(eventType: Int, params: Bundle?) = heard()

        override fun onRmsChanged(rmsdB: Float) {
            heard()
            if (done()) return
            // The identification service gives roughly -2..10 dB, and the pressure is reduced to 0..1 to
            // draw the waveform.
            out.send(SpeechEvent.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f)))
        }

        override fun onPartialResults(partialResults: Bundle?) {
            heard()
            if (done()) return
            val text = firstText(partialResults)
            if (text.isBlank()) return
            restarts.onSpeech()
            out.send(Segments.split(segments.partial(text)))
        }

        override fun onResults(results: Bundle?) {
            heard()
            if (done()) return
            val text = firstText(results)
            segments.segmentDone(text)
            if (holding) {
                // The recognition service feels that the sentence has been finished, but the hand is
                // still pressed: continue to listen to the next paragraph
                if (text.isBlank()) {
                    emptySegment()
                } else {
                    restarts.onSpeech()
                    out.send(Segments.split(segments.full()))
                    restartPending = true
                    main.post(restart)
                }
            } else {
                finish(SpeechEvent.Final(segments.full()))
            }
        }

        override fun onError(error: Int) {
            if (recognizer == null || done()) return
            heard()
            if (recognizer == null || done()) return
            if (usingOnDevice && SystemSpeech.languageMissing(error) && segments.full().isEmpty()) {
                open(preferOnDevice = false)
                if (!holding && !done()) {
                    // Already loosened: the new recognizer is only finishing, waiting for the upper
                    // limit to be recalculated
                    recognizer?.stopListening()
                    main.postDelayed(timeout, FINAL_WAIT_MS)
                }
                return
            }
            when (val o = SystemSpeech.afterError(error, holding, heard = segments.full().isNotEmpty())) {
                SystemSpeech.Outcome.Retry -> emptySegment()
                SystemSpeech.Outcome.Finish -> finish(SpeechEvent.Final(segments.full()))
                is SystemSpeech.Outcome.Fail -> finish(SpeechEvent.Failed(o.why))
            }
        }
    }

    // Placed after all attributes: onMain is executed on the spot on the main thread, and attributes
    // such as listener used by open have been initialized at this time.
    init {
        onMain { open(onDevice && attempts.firstOrNull() == null) }
    }

    companion object {
        /** Wait for the upper limit of the final result after releasing. */
        const val FINAL_WAIT_MS = 2_000L

        /**
         * After starting to listen, wait for the upper limit of the first callback of the recognition
         * service (usually onReadyForSpeech, which normally occurs within a few hundred milliseconds).
         * Set aside time to identify the cold start of the service process; if it exceeds this period,
         * it will be treated as unresponsive.
         */
        const val NO_RESPONSE_MS = 3_000L
    }
}
