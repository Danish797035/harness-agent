package com.meenai.mina.core.oauth

import com.meenai.mina.core.model.HttpSupport
import com.meenai.mina.core.model.ModelError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** A random value for an OpenRouter authorization: newly generated each time. [callbackUrl] is the callback address monitored by this machine this time. */
data class OpenRouterAttempt(val state: String, val verifier: String, val callbackUrl: String) {
    val challenge: String get() = Pkce.challenge(verifier)
}

/**
 * Log in with an OpenRouter account: OpenRouter’s official OAuth PKCE for third-party applications. Open
 * the authorization page in the browser, and after the user agrees, return to the local callback with
 * `code`, and then use `code` and the verification string to exchange for an API Key belonging to this
 * user. What is replaced is an ordinary OpenRouter Key, and then it is used in the same way as the
 * OpenRouter service provider that fills in the Key ([API_BASE] is used for column models and calls).
 * Key is not entered into the log; the exception text only contains the category and error code.
 */
class OpenRouterAuth(
    private val http: OkHttpClient,
    private val authUrl: String = AUTH_URL,
    private val keysUrl: String = KEYS_URL,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun newAttempt(callbackUrl: String) = OpenRouterAttempt(Pkce.state(), Pkce.verifier(), callbackUrl)

    /** Authorization page address: callback address, S256 verification, Key name (pre-filled, user can change), state. */
    fun authorizeUrl(attempt: OpenRouterAttempt): String = authUrl.toHttpUrl().newBuilder()
        .addQueryParameter("callback_url", attempt.callbackUrl)
        .addQueryParameter("code_challenge", attempt.challenge)
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("key_label", KEY_LABEL)
        .addQueryParameter("state", attempt.state)
        .build().toString()

    /**
     * Whether the local callback will be accepted this time: the state will be accepted; if there is no
     * state, no code, or `error` (OpenRouter does not have state when the user refuses on the
     * authorization page), it will be accepted as no consent. Others (with code but no or inconsistent
     * state, or nothing) will not be accepted: it is not initiated this time, return 400 and wait.
     */
    fun accepts(params: Map<String, String>, attempt: OpenRouterAttempt): Boolean {
        val state = params["state"]
        if (state != null) return state == attempt.state
        return params["code"].isNullOrEmpty() && !params["error"].isNullOrEmpty()
    }

    /** Take out `code` from the callback; if not, the user did not agree (or the authorization page reported an error). */
    fun readCallback(params: Map<String, String>, attempt: OpenRouterAttempt): String {
        val state = params["state"]
        if (state != null && state != attempt.state) throw AccountSignInException(AccountSignInFailure.Rejected, "state")
        val code = params["code"]?.takeIf { it.isNotEmpty() }
            ?: throw AccountSignInException(AccountSignInFailure.Denied, params["error"]?.take(40) ?: "no_code")
        if (state == null) throw AccountSignInException(AccountSignInFailure.Rejected, "state")
        return code
    }

    /**
     * Use `code` and checksum exchange Key(`POST /api/v1/auth/keys`). 400 means the verification method
     * does not match, 403 means the code or verification string is incorrect, or the code has expired
     * (10 minutes). When an error occurs, only the status code is returned, without the response text.
     */
    suspend fun exchange(code: String, attempt: OpenRouterAttempt): String {
        val body = buildJsonObject {
            put("code", code)
            put("code_verifier", attempt.verifier)
            put("code_challenge_method", "S256")
        }.toString().toRequestBody(JSON_TYPE)
        val raw = try {
            HttpSupport.execute(http, Request.Builder().url(keysUrl).header("Accept", "application/json").post(body).build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelError) {
            throw AccountSignInException(AccountSignInFailure.Network, "http", e)
        }
        when {
            raw.status == 403 -> throw AccountSignInException(AccountSignInFailure.Expired, "http_403")
            raw.status == 429 || raw.status in 500..599 -> throw AccountSignInException(AccountSignInFailure.Network, "http_${raw.status}")
            raw.status != 200 -> throw AccountSignInException(AccountSignInFailure.Rejected, "http_${raw.status}")
        }
        val root = runCatching { json.parseToJsonElement(raw.body) as? JsonObject }.getOrNull()
        return (root?.get("key") as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw AccountSignInException(AccountSignInFailure.Rejected, "no_key")
    }

    companion object {
        const val AUTH_URL = "https://openrouter.ai/auth"
        const val KEYS_URL = "https://openrouter.ai/api/v1/auth/keys"
        /** The interface root called by the changed Key is the same as the OpenRouter default. */
        const val API_BASE = "https://openrouter.ai/api/v1"
        /** The page where users manage (delete) Keys on OpenRouter. */
        const val KEYS_PAGE = "https://openrouter.ai/settings/keys"
        /** The Key name is prefilled on the authorization page, and the user can use it to identify which key it is on the Keys page. */
        const val KEY_LABEL = "Mina"
        /** Use localhost as the callback address (OpenRouter indicates that any callback port on this machine can be used); the listening address is only tied to 127.0.0.1. */
        const val CALLBACK_HOST = "localhost"
        private val JSON_TYPE = "application/json".toMediaType()
    }
}

/**
 * Go through the OpenRouter login: open the local callback → [openBrowser] open the authorization page →
 * wait for the callback → change the Key. After the key change step (at most [finishTimeoutMs]) is
 * completed, the result webpage is returned to the browser: [page] receiving null means success,
 * otherwise it is the cause of failure. If failed, throw [AccountSignInException]; if canceled, throw
 * out as usual. Returns the changed API Key.
 */
suspend fun signInWithOpenRouter(
    auth: OpenRouterAuth,
    openBrowser: (String) -> Unit,
    page: (AccountSignInException?) -> String,
    timeoutMs: Long = 5 * 60_000L,
    finishTimeoutMs: Long = 20_000L,
): String {
    var outcome: Result<String>? = null
    LoopbackCallback.open().use { cb ->
        val attempt = auth.newAttempt(cb.redirectUri(OpenRouterAuth.CALLBACK_HOST))
        openBrowser(auth.authorizeUrl(attempt))
        cb.await(timeoutMs, { auth.accepts(it, attempt) }) { params ->
            val r = try {
                Result.success(withTimeout(finishTimeoutMs) { auth.exchange(auth.readCallback(params, attempt), attempt) })
            } catch (e: TimeoutCancellationException) {
                Result.failure(AccountSignInException(AccountSignInFailure.Network, "timeout", e))
            } catch (e: CancellationException) {
                throw e
            } catch (e: AccountSignInException) {
                Result.failure(e)
            } catch (e: Exception) {
                Result.failure(AccountSignInException(AccountSignInFailure.Network, cause = e))
            }
            outcome = r
            page(r.exceptionOrNull() as AccountSignInException?)
        } ?: throw AccountSignInException(AccountSignInFailure.TimedOut)
    }
    return outcome!!.getOrThrow()
}
