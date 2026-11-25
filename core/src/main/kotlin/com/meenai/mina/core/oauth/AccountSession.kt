package com.meenai.mina.core.oauth

import com.meenai.mina.core.model.BearerToken
import com.meenai.mina.core.model.ModelError
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The reason why the subscription account login failed. The interface speaks human language according to
 * it. The exception text contains only the category and error code, not the token, authorization code,
 * or device code. [NoSubscription] means that the account does not have this subscription; [BadIssuer]
 * means that the found address is not on an allowed host, and the request will not be sent.
 */
enum class AccountSignInFailure { Denied, Expired, TimedOut, Rejected, NoSubscription, Network, BadIssuer }

/** [code] is the server-side error code (not confidential). */
class AccountSignInException(
    val reason: AccountSignInFailure,
    val code: String? = null,
    cause: Throwable? = null,
) : Exception(buildString {
    append(reason.name)
    if (!code.isNullOrBlank()) append(": ").append(code)
}, cause)

/** A set of tokens obtained by exchanging tokens. Fields not provided by refresh remain null, and the caller will use the old ones. */
data class OauthTokens(
    val accessToken: String,
    val refreshToken: String? = null,
    val idToken: String? = null,
    val expiresInSec: Long = 3600,
    val scopes: List<String> = emptyList(),
    /** The interface root to switch to; if not given, it will be null. */
    val apiBase: String? = null,
)

/** The result of token exchange. When rejected, [terminal] is true, which means that this authorization can no longer be used, and it is necessary to clear it and log in again. */
sealed interface OauthTokenResult {
    data class Ok(val tokens: OauthTokens) : OauthTokenResult
    data class Rejected(val code: String, val terminal: Boolean) : OauthTokenResult
    data class Failed(val error: ModelError) : OauthTokenResult
}

/**
 * Log in with an account (currently xAI), and the entire file is encrypted and saved as an API Key.
 * [accessToken] is the access token used to call the model, and [refreshToken] is used to replace it
 * with a new one. After logging out, the token is cleared and the account name is left for others to
 * see.
 */
@Serializable
data class AccountSession(
    val provider: String,
    val accountId: String = "",
    val label: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAtMs: Long = 0,
    val scopes: List<String> = emptyList(),
    val apiBase: String = "",
    val clientId: String = "",
    val issuer: String = "",
    /** When logging in, read and check the host's token address from the discovery document; use it for refresh. The old version is not saved and will be pushed according to the issuer when refreshing. */
    val tokenUrl: String = "",
) {
    val signedIn: Boolean get() = accessToken.isNotEmpty() || refreshToken.isNotEmpty()

    fun signedOut() = copy(accessToken = "", refreshToken = "", expiresAtMs = 0)

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String?): AccountSession? =
            text?.takeIf { it.isNotBlank() }?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/**
 * The access token used when making the call. If it is about to expire or the previous one was rejected,
 * please use [refresh] to exchange for a new one. There is only one exchange at the same time. The
 * exchanged token is first [save] and then used; [save] is received at the same time as the token before
 * the exchange, and the storage is used to determine whether the user has logged out or logged in again
 * during this period (in this case, it should not be written back). If the authorization is no longer
 * available, clear the token and throw [ModelError.SignInExpired]; if there is a network or server
 * error, throw it away as usual and keep the token.
 */
class AccountTokens(
    /** Which company to log in to (the name on the interface) will be told to the user when the login fails. */
    private val account: String,
    private val refresh: suspend (AccountSession) -> OauthTokenResult,
    private val load: () -> AccountSession?,
    private val save: (before: AccountSession, after: AccountSession) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val refreshAheadMs: Long = 5 * 60_000L,
) : BearerToken {
    private val lock = Mutex()
    override val refreshable: Boolean get() = true

    override suspend fun get(fresh: Boolean): String = lock.withLock {
        val s = load()?.takeIf { it.signedIn } ?: throw ModelError.SignInExpired(account)
        if (!fresh && s.accessToken.isNotEmpty() && now() < s.expiresAtMs - refreshAheadMs) return@withLock s.accessToken
        when (val r = refresh(s)) {
            is OauthTokenResult.Ok -> {
                val t = r.tokens
                val next = s.copy(
                    accessToken = t.accessToken,
                    refreshToken = t.refreshToken ?: s.refreshToken,
                    expiresAtMs = now() + t.expiresInSec * 1000,
                    scopes = t.scopes.ifEmpty { s.scopes },
                    apiBase = t.apiBase ?: s.apiBase,
                )
                save(s, next)
                next.accessToken
            }
            is OauthTokenResult.Rejected -> {
                if (r.terminal) {
                    save(s, s.signedOut())
                    throw ModelError.SignInExpired(account)
                }
                throw ModelError.Auth()
            }
            is OauthTokenResult.Failed -> throw r.error
        }
    }
}
