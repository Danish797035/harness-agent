package com.meenai.mina.settings

import com.meenai.mina.about.Diagnostics
import com.meenai.mina.core.chatgpt.SignInException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** For the latest login failure, only the diagnostic information is used: time, type of cause, server-side error code, and a short description (desensitized). */
data class ChatGptSignInFailureRecord(val atMs: Long, val kind: String, val code: String?, val detail: String)

/** Which company are you logging in to this time? Rely on it when rebuilding front-end services and interfaces to avoid displaying other people's logins as ChatGPT. */
enum class SignInTarget { None, ChatGpt, Xai, OpenRouter }

/** The device code login shows the user code, verification page and when it will expire. The device code itself is not here. */
data class DevicePrompt(val userCode: String, val openUrl: String, val expiresAtMs: Long = 0)

/**
 * The result of an account login (xAI, OpenRouter): success is the saved service provider ID, failure is
 * the reason shown to the user. Put it in [ChatGptSignIns] instead of the interface status: the
 * interface may be rebuilt during login, and the result will be handed over to the interface you were in
 * at that time.
 */
data class AccountOutcome(val target: SignInTarget, val providerId: String? = null, val failure: String? = null)

/**
 * Log in with the account (ChatGPT, xAI, OpenRouter) in the process. Login requires the user to enter
 * the password in the external browser and pass the two-step verification. During this period, Mina is
 * in the background: running in the scope [scope] of the App (the interface is rebuilt and retreated to
 * the background continuously). At the beginning, [keepAlive] is called to support the process (the
 * front-end service, so as not to freeze the process and no one will answer the local callback); if it
 * cannot get up, just remember one sentence and log in as usual. The front-end service looks at
 * [running] and stops itself when it changes back to false, so it will stop on success, failure,
 * cancellation, and timeout. There is only one login at the same time; [cancel] gives up (the "cancel"
 * on the notification also goes here).
 */
class ChatGptSignIns(
    private val scope: CoroutineScope,
    private val keepAlive: () -> Unit = {},
    private val log: (String, Throwable) -> Unit = { _, _ -> },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private var job: Job? = null

    /** The most recent failure (see [recordFailure]); only in memory. */
    @Volatile var lastFailure: ChatGptSignInFailureRecord? = null
        private set

    /** The latest login failure with other accounts (xAI, OpenRouter) indicates that the account has also been desensitized. */
    @Volatile var lastAccountFailure: ChatGptSignInFailureRecord? = null
        private set

    /** Which one is in progress; not [SignInTarget.None] at login. */
    @Volatile var target: SignInTarget = SignInTarget.None
        private set

    /** The text on the front desk notification; if empty, use the two sentences from ChatGPT. */
    @Volatile var noticeTitle: String? = null
        private set

    @Volatile var noticeBody: String? = null
        private set

    private val _device = MutableStateFlow<DevicePrompt?>(null)
    /** The device code logs in to the user code being displayed; it will be cleared after the login is completed. It continues to display after the interface is rebuilt. */
    val device: StateFlow<DevicePrompt?> = _device.asStateFlow()
    val devicePrompt: DevicePrompt? get() = _device.value

    fun showDevice(prompt: DevicePrompt) {
        _device.value = prompt
    }

    private val _outcome = MutableStateFlow<AccountOutcome?>(null)
    /** The result of the latest account login is displayed on the interface as [consumeOutcome]. */
    val outcome: StateFlow<AccountOutcome?> = _outcome.asStateFlow()

    fun finishAccount(o: AccountOutcome) {
        _outcome.value = o
    }

    fun consumeOutcome(o: AccountOutcome) {
        _outcome.compareAndSet(o, null)
    }

    /** To start a login, run [block]; if it does not open once during the run, it returns false. */
    fun start(
        target: SignInTarget = SignInTarget.ChatGpt,
        noticeTitle: String? = null,
        noticeBody: String? = null,
        block: suspend CoroutineScope.() -> Unit,
    ): Boolean = synchronized(this) {
        if (_running.value) return false
        this.target = target
        this.noticeTitle = noticeTitle
        this.noticeBody = noticeBody
        _device.value = null
        _outcome.value = null
        _running.value = true
        runCatching(keepAlive).onFailure { log("sign-in keep-alive could not start", it) }
        // There is no exception handling in the App scope: leaked exceptions are only recorded to
        // prevent the process from crashing.
        val j = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log("sign-in ended with an unexpected error", e)
            }
        }
        job = j
        j.invokeOnCompletion {
            synchronized(this) {
                if (job === j) {
                    job = null
                    _running.value = false
                    this.target = SignInTarget.None
                    _device.value = null
                    this.noticeTitle = null
                    this.noticeBody = null
                }
            }
        }
        true
    }

    fun cancel() {
        synchronized(this) { job }?.cancel()
    }

    fun recordFailure(e: SignInException) {
        val detail = Diagnostics.sanitize(e.message.orEmpty()).take(MAX_DETAIL)
        lastFailure = ChatGptSignInFailureRecord(now(), e.reason.name, e.code, detail)
    }

    /** Subscription account login failed. Just write down the category and error code, and then go through the desensitization process again. */
    fun recordAccountFailure(provider: String, reason: String, code: String?) {
        val detail = Diagnostics.sanitize(listOfNotNull(reason, code).joinToString(": ")).take(MAX_DETAIL)
        lastAccountFailure = ChatGptSignInFailureRecord(now(), provider, code, detail)
    }

    private companion object {
        const val MAX_DETAIL = 160
    }
}
