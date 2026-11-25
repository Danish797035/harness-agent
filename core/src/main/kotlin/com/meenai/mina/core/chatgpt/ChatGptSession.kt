package com.meenai.mina.core.chatgpt

import com.meenai.mina.core.model.BearerToken
import com.meenai.mina.core.oauth.LoopbackCallback
import com.meenai.mina.core.model.ModelError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Login of a ChatGPT account: [clientId], verified identity ([sub], [email]) and token given during
 * registration. The entire copy is encrypted and saved using API Key. After logging out, the token is
 * cleared ([signedIn] is false), the registration and identity are retained, and the same client_id is
 * used when logging in again.
 */
@Serializable
data class ChatGptSession(
    val clientId: String,
    val sub: String,
    val email: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val idToken: String = "",
    val expiresAtMs: Long = 0,
    val scopes: List<String> = emptyList(),
) {
    val signedIn: Boolean get() = accessToken.isNotEmpty() || refreshToken.isNotEmpty()

    /** The user agreed to use the package quota to make calls. */
    val planUsage: Boolean get() = ChatGptAuth.PLAN_SCOPE in scopes

    fun signedOut() = copy(accessToken = "", refreshToken = "", idToken = "", expiresAtMs = 0)

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String?): ChatGptSession? =
            text?.takeIf { it.isNotBlank() }?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/**
 * The access token used when calling: if it is about to expire (less than [refreshAheadMs]) or is
 * rejected (fresh of [BearerToken.get]), use the refresh token to exchange for a new one, and the new
 * refresh token and the access token are saved together ([save]). Only one is being exchanged at the
 * same time (the refresh token is invalidated once it is exchanged, and concurrent exchanges will
 * override each other). The refresh token is no longer available: clear the token and throw
 * [ModelError.SignInExpired] (to log in again); if there is a temporary error on the network or server,
 * throw it away as usual and keep the token.
 */
class ChatGptTokens(
    private val auth: ChatGptAuth,
    private val load: () -> ChatGptSession?,
    private val save: (ChatGptSession) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val refreshAheadMs: Long = 5 * 60_000L,
) : BearerToken {
    private val lock = Mutex()
    override val refreshable: Boolean get() = true

    override suspend fun get(fresh: Boolean): String = lock.withLock {
        val s = load()?.takeIf { it.signedIn } ?: throw ModelError.SignInExpired()
        if (!fresh && s.accessToken.isNotEmpty() && now() < s.expiresAtMs - refreshAheadMs) return@withLock s.accessToken
        if (s.refreshToken.isEmpty()) {
            save(s.signedOut())
            throw ModelError.SignInExpired()
        }
        when (val r = auth.refresh(s.clientId, s.refreshToken)) {
            is TokenResult.Ok -> {
                val t = r.tokens
                val next = s.copy(
                    accessToken = t.accessToken,
                    refreshToken = t.refreshToken ?: s.refreshToken,
                    idToken = t.idToken ?: s.idToken,
                    expiresAtMs = now() + t.expiresInSec * 1000,
                    scopes = t.scopes.ifEmpty { s.scopes },
                )
                save(next)
                if (!next.planUsage) throw ModelError.ChatGptNotEligible()
                next.accessToken
            }
            is TokenResult.Rejected -> {
                if (r.terminal) {
                    save(s.signedOut())
                    throw ModelError.SignInExpired()
                }
                // The client configuration is not equivalent: retrying is useless, but it does not mean
                // that the token has expired, unclear
                throw ModelError.Auth()
            }
            is TokenResult.Failed -> throw r.error
        }
    }
}

/**
 * Go through the login process: Open the local callback → [openBrowser] Open the authorization page →
 * Wait for the callback → Check → Change token → Verify ID token → See if the package quota has been
 * granted. After the callback is completed in the next few steps (at most [finishTimeoutMs]), the result
 * web page is returned to the browser: [page] receiving null means success, otherwise it is the cause of
 * failure. [clientId] is the one registered before (null for the first time, handed over by
 * [onRegistered] after successful registration, and should be saved before changing the token);
 * [previous] is the last login (used to bring the ID token and email, eliminating the need to select an
 * account). Throw [SignInException] on failure.
 */
suspend fun signInWithChatGpt(
    auth: ChatGptAuth,
    hostId: String,
    clientId: String?,
    previous: ChatGptSession?,
    openBrowser: (String) -> Unit,
    onRegistered: (String) -> Unit,
    page: (SignInException?) -> String,
    timeoutMs: Long = 5 * 60_000L,
    finishTimeoutMs: Long = 20_000L,
    now: () -> Long = System::currentTimeMillis,
): ChatGptSession {
    var outcome: Result<ChatGptSession>? = null
    LoopbackCallback.open().use { cb ->
        val attempt = auth.newAttempt(cb.redirectUri)
        val hint = previous?.takeIf { it.signedIn && it.clientId == clientId }
        openBrowser(auth.authorizeUrl(attempt, clientId, hostId, hint?.idToken, previous?.email?.takeIf { clientId != null }))
        cb.await(timeoutMs, { it["state"] == attempt.state }) { params ->
            val r = finishSignIn(finishTimeoutMs) { completeSignIn(auth, params, attempt, clientId, onRegistered, now) }
            outcome = r
            page(r.exceptionOrNull() as SignInException?)
        } ?: throw SignInException(SignInFailure.TimedOut)
    }
    return outcome!!.getOrThrow()
}

/** In the few steps after completing the callback, at most [timeoutMs]: timeout is not considered connected; other exceptions are classified as [SignInException]; if canceled, they will be thrown out as usual. */
private suspend fun finishSignIn(timeoutMs: Long, block: suspend () -> ChatGptSession): Result<ChatGptSession> = try {
    Result.success(withTimeout(timeoutMs) { block() })
} catch (e: TimeoutCancellationException) {
    Result.failure(SignInException(SignInFailure.Network, "no answer in ${timeoutMs / 1000}s", e))
} catch (e: CancellationException) {
    throw e
} catch (e: SignInException) {
    Result.failure(e)
} catch (e: Exception) {
    Result.failure(SignInException(SignInFailure.Network, e.message.orEmpty(), e))
}

private suspend fun completeSignIn(
    auth: ChatGptAuth,
    params: Map<String, String>,
    attempt: SignInAttempt,
    clientId: String?,
    onRegistered: (String) -> Unit,
    now: () -> Long,
): ChatGptSession {
    val (code, issued) = auth.readCallback(params, attempt, clientId)
    if (issued != clientId) onRegistered(issued)
    val tokens = when (val r = auth.exchange(issued, code, attempt)) {
        is TokenResult.Ok -> r.tokens
        is TokenResult.Rejected -> throw ChatGptAuth.rejected(r)
        is TokenResult.Failed -> throw SignInException(SignInFailure.Network, r.error.message.orEmpty(), r.error)
    }
    val idToken = tokens.idToken ?: throw SignInException(SignInFailure.IdToken, "no id_token")
    val who = auth.verifyIdToken(idToken, issued, attempt.nonce)
    if (ChatGptAuth.PLAN_SCOPE !in tokens.scopes) throw SignInException(SignInFailure.NoPlanScope, "scope", code = "no_plan_scope")
    return ChatGptSession(
        clientId = issued, sub = who.sub, email = who.email,
        accessToken = tokens.accessToken, refreshToken = tokens.refreshToken.orEmpty(), idToken = idToken,
        expiresAtMs = now() + tokens.expiresInSec * 1000, scopes = tokens.scopes,
    )
}
