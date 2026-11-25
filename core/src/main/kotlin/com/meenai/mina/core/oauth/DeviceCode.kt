package com.meenai.mina.core.oauth

import com.meenai.mina.core.model.HttpSupport
import com.meenai.mina.core.model.ModelError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Device code shown to the user: user code, verification page, validity period and polling interval. The
 * device code itself is not here, does not enter the interface, and does not enter the log.
 * [verificationUriComplete] has been brought with the user code, and the host must be in the allowed
 * list to use it; otherwise, use [verificationUri].
 */
data class DeviceChallenge(
    val userCode: String,
    val verificationUri: String,
    val verificationUriComplete: String?,
    val expiresInSec: Long,
    val intervalSec: Long,
)

/** One time device code authorization. The device code is only reserved for polling and is not provided to the interface. */
class DeviceGrant internal constructor(
    val challenge: DeviceChallenge,
    internal val deviceCode: String,
)

/** The result of polling once. There is only the code in the error, not the response body (the body may contain a token). */
sealed interface DevicePoll {
    data object Pending : DevicePoll
    data object SlowDown : DevicePoll
    data class Tokens(val tokens: OauthTokens) : DevicePoll
    data class Rejected(val code: String, val terminal: Boolean) : DevicePoll
    data class Failed(val error: ModelError) : DevicePoll
}

/**
 * RFC 8628 device code: Ask [deviceUrl] for a set of codes, and then ask [tokenUrl] to exchange the
 * device code for a token. The hosts of both addresses must be in [allowedHosts], and must be https
 * except for local testing; if they do not match, throw [AccountSignInException] and the request will
 * not be sent.
 */
class DeviceCodeClient(
    private val http: OkHttpClient,
    private val deviceUrl: String,
    private val tokenUrl: String,
    private val clientId: String,
    private val scope: String,
    private val allowedHosts: Set<String>,
    private val extraDeviceForm: Map<String, String> = emptyMap(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun start(): DeviceGrant {
        val url = OauthUrls.check(deviceUrl, allowedHosts)
        val form = FormBody.Builder().add("client_id", clientId).add("scope", scope).apply {
            extraDeviceForm.forEach { (k, v) -> add(k, v) }
        }.build()
        val raw = postForm(url, form)
        val root = jsonObject(raw.body) ?: throw AccountSignInException(AccountSignInFailure.Rejected, "http_${raw.status}")
        if (raw.status != 200) {
            val code = root.str("error") ?: "http_${raw.status}"
            throw AccountSignInException(failureOf(raw.status, code), code)
        }
        val device = root.str("device_code")?.takeIf { it.isNotEmpty() }
            ?: throw AccountSignInException(AccountSignInFailure.Rejected, "no_device_code")
        val user = root.str("user_code")?.takeIf { it.isNotEmpty() }
            ?: throw AccountSignInException(AccountSignInFailure.Rejected, "no_user_code")
        val verify = root.str("verification_uri")?.takeIf { it.isNotEmpty() }
            ?: throw AccountSignInException(AccountSignInFailure.Rejected, "no_verification_uri")
        // The verification page is handed over to the caller to check against its own host list before
        // opening it. It will not be sent here.
        val complete = root.str("verification_uri_complete")?.takeIf { it.isNotEmpty() }
        val expires = (root["expires_in"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: DEFAULT_EXPIRES_SEC
        val interval = (root["interval"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: DEFAULT_INTERVAL_SEC
        return DeviceGrant(
            DeviceChallenge(user, verify, complete, expires, interval),
            device,
        )
    }

    /** Ask once. The device code is only placed in the form and does not appear in the returned error. */
    suspend fun poll(deviceCode: String): DevicePoll {
        val url = OauthUrls.check(tokenUrl, allowedHosts)
        val form = FormBody.Builder()
            .add("client_id", clientId)
            .add("device_code", deviceCode)
            .add("grant_type", DEVICE_GRANT)
            .build()
        val raw = try {
            postForm(url, form)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AccountSignInException) {
            return DevicePoll.Failed((e.cause as? ModelError) ?: ModelError.Network(e))
        }
        return interpret(raw.status, raw.body)
    }

    private fun interpret(status: Int, body: String): DevicePoll {
        if (status in 500..599) return DevicePoll.Failed(ModelError.Server(status))
        val root = jsonObject(body)
        val err = root?.str("error")
        if (err != null) return when (err) {
            "authorization_pending" -> DevicePoll.Pending
            "slow_down" -> DevicePoll.SlowDown
            "access_denied", "authorization_denied" -> DevicePoll.Rejected(err, terminal = true)
            "expired_token" -> DevicePoll.Rejected(err, terminal = true)
            else -> DevicePoll.Rejected(err, terminal = status != 429)
        }
        if (status == 429) return DevicePoll.SlowDown
        if (status != 200 || root == null) return DevicePoll.Rejected("http_$status", terminal = false)
        val access = root.str("access_token") ?: return DevicePoll.Rejected("no_access_token", terminal = true)
        return DevicePoll.Tokens(
            OauthTokens(
                accessToken = access,
                refreshToken = root.str("refresh_token"),
                idToken = root.str("id_token"),
                expiresInSec = (root["expires_in"] as? JsonPrimitive)?.longOrNull?.takeIf { it > 0 } ?: DEFAULT_EXPIRES_SEC,
                scopes = root.str("scope")?.split(' ')?.filter { it.isNotEmpty() }.orEmpty(),
            ),
        )
    }

    private suspend fun postForm(url: HttpUrl, form: FormBody) = try {
        HttpSupport.execute(http, Request.Builder().url(url).header("Accept", "application/json").post(form).build())
    } catch (e: CancellationException) {
        throw e
    } catch (e: ModelError) {
        throw AccountSignInException(AccountSignInFailure.Network, e.message?.take(40), e)
    }

    private fun jsonObject(body: String): JsonObject? = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private fun failureOf(status: Int, code: String): AccountSignInFailure = when {
        status in 500..599 || status == 429 -> AccountSignInFailure.Network
        code == "access_denied" -> AccountSignInFailure.Denied
        else -> AccountSignInFailure.Rejected
    }

    companion object {
        const val DEVICE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        private const val DEFAULT_EXPIRES_SEC = 900L
        private const val DEFAULT_INTERVAL_SEC = 5L
    }
}

/**
 * Wait until the user finishes clicking in the browser, or timeout, reject, or cancel. The polling
 * interval is as specified by the server; add 5 seconds to [slow_down] (RFC 8628). When the user is in
 * the browser, Mina is in the background, and the network may be temporarily unavailable: it will be
 * considered a failure if it cannot be connected for [MAX_POLL_FAILURES] times, and it will wait for the
 * next round as usual. Change [sleep] to empty in the test; use [delay] on the real machine.
 */
suspend fun awaitDeviceGrant(
    grant: DeviceGrant,
    now: () -> Long,
    sleep: suspend (Long) -> Unit = ::oauthSleep,
    poll: suspend (String) -> DevicePoll,
): OauthTokens {
    val deadline = now() + grant.challenge.expiresInSec * 1000
    var intervalMs = grant.challenge.intervalSec.coerceAtLeast(1) * 1000
    var failures = 0
    while (now() < deadline) {
        when (val r = poll(grant.deviceCode)) {
            DevicePoll.Pending -> sleep(intervalMs.coerceAtMost((deadline - now()).coerceAtLeast(0)))
            DevicePoll.SlowDown -> {
                intervalMs += SLOW_DOWN_MS
                sleep(intervalMs.coerceAtMost((deadline - now()).coerceAtLeast(0)))
            }
            is DevicePoll.Tokens -> return r.tokens
            is DevicePoll.Rejected -> throw AccountSignInException(
                when {
                    r.code == "expired_token" -> AccountSignInFailure.Expired
                    r.code == "access_denied" || r.code == "authorization_denied" -> AccountSignInFailure.Denied
                    else -> AccountSignInFailure.Rejected
                },
                r.code,
            )
            is DevicePoll.Failed -> {
                if (++failures >= MAX_POLL_FAILURES) throw AccountSignInException(AccountSignInFailure.Network, cause = r.error)
                sleep(intervalMs.coerceAtMost((deadline - now()).coerceAtLeast(0)))
                continue
            }
        }
        failures = 0
    }
    throw AccountSignInException(AccountSignInFailure.TimedOut, "expired")
}

/** Only addresses that accept https (except local testing) and the host is in the list. User information (userinfo) is always rejected. */
object OauthUrls {
    fun check(url: String, allowedHosts: Set<String>): HttpUrl {
        if (!allowed(url, allowedHosts)) throw AccountSignInException(AccountSignInFailure.BadIssuer, "host")
        return url.toHttpUrlOrNull()!!
    }

    fun allowed(url: String, allowedHosts: Set<String>): Boolean {
        val u = url.toHttpUrlOrNull() ?: return false
        if (u.username.isNotEmpty()) return false
        val local = u.host == "127.0.0.1" || u.host == "localhost"
        if (!local && u.scheme != "https") return false
        return u.host in allowedHosts
    }
}

/** The refresh token can no longer be used. */
internal val TERMINAL_REFRESH = setOf(
    "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
    "refresh_token_invalidated", "refresh_token_reused",
)

private const val SLOW_DOWN_MS = 5_000L
private const val MAX_POLL_FAILURES = 3

/** I'll wait for so long before asking next time on a real machine. Default parameters are referenced by functions to avoid the compiler not being able to handle hanging calls in lambdas. */
internal suspend fun oauthSleep(ms: Long) = delay(ms)

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
