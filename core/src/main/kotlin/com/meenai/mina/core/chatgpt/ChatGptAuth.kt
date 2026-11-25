package com.meenai.mina.core.chatgpt

import com.meenai.mina.core.model.HttpSupport
import com.meenai.mina.core.model.ModelError
import com.meenai.mina.core.oauth.Pkce
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.math.BigInteger
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** Random value for a login attempt: newly generated each time, checked during callback and token exchange. [redirectUri] is the callback address of this monitoring. */
data class SignInAttempt(val state: String, val nonce: String, val verifier: String, val redirectUri: String) {
    /** PKCE S256 (see [Pkce.challenge]). */
    val challenge: String get() = Pkce.challenge(verifier)
}

/** A set of tokens obtained by changing the token (using authorization code or refresh token); [scopes] is the actual granted permission. */
data class TokenSet(
    val accessToken: String,
    val refreshToken: String?,
    val idToken: String?,
    val expiresInSec: Long,
    val scopes: List<String>,
)

/** The result of the token exchange: obtained, rejected (with server error code; [terminal] is true, indicating that the refresh token can no longer be used), or failed temporarily (network, 5xx). */
sealed interface TokenResult {
    data class Ok(val tokens: TokenSet) : TokenResult
    /**
     * [detail] is the error description (error_description or error.message) given by the server. It is
     * only for people to see and does not contain a token. If [nonJson] is true, it means that the
     * returned value is not JSON (mostly the HTML of the protection page). In this case, [code] is
     * `http_<status code>`.
     */
    data class Rejected(val code: String, val terminal: Boolean, val detail: String? = null, val nonJson: Boolean = false) : TokenResult
    data class Failed(val error: ModelError) : TokenResult
}

/** The verified identity in the ID token: [sub] is the account identifier, [email] is only used for people to see (may be empty). */
data class IdClaims(val sub: String, val email: String)

/**
 * Why the login failed? Click on the interface to tell the reason (see [ChatGptAuth.failureOf]).
 * [CodeExpired] means that the authorization code has expired, been used, or does not match; [Region]
 * means that the region is not supported; [Blocked] means that the request was blocked by protection
 * (the webpage returned is not JSON); [NoPlanScope] is only used when the server mentions the package
 * and permissions, or there is no package item in the granted permissions; other rejections are
 * [Exchange] (with error code).
 */
enum class SignInFailure { Denied, StateMismatch, ClientMismatch, NoClientId, TimedOut, Exchange, CodeExpired, Region, Blocked, IdToken, NoPlanScope, Network }

/** [code] is the error code given by the server (not confidential, it can be seen by others and written into diagnostic information); if not, it is null. */
class SignInException(val reason: SignInFailure, detail: String = "", cause: Throwable? = null, val code: String? = null) :
    Exception(if (detail.isEmpty()) reason.name else "${reason.name}: $detail", cause)

/**
 * "Log in with ChatGPT" and use the user's ChatGPT package quota to call the model (OpenAI provides this
 * path for open source, natively running apps): public client, no key, authorization code + PKCE (S256).
 * The first time you register with `dynamic_agent_client`, the client_id unique to this account is given
 * in the callback. You can use it later when changing tokens, refreshing, and logging in again. The
 * local identification ([hostId], `urn:uuid:…`) is generated once for each device and used forever. The
 * callback address can only be `http://127.0.0.1:<port>/auth/callback` (the port is variable).
 *
 * The ID token is verified according to the published JWKS (RS256), and the issuer, audience (=
 * client_id), expiration time and this nonce are checked; the granted permissions must have [PLAN_SCOPE]
 * to use the package quota.
 */
class ChatGptAuth(
    private val http: OkHttpClient,
    private val issuer: String = ISSUER,
    private val now: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val tokenUrl = "$issuer/api/accounts/oauth/token"
    private val revokeUrl = "$issuer/api/accounts/oauth/revoke"
    private val jwksUrl = "$issuer/.well-known/jwks.json"

    /** The most recently obtained JWKS (kid → public key parameter); kid cannot be found during signature verification and retrieved again (the server changed the key). */
    @Volatile private var jwks: Map<String, JsonObject> = emptyMap()

    fun newAttempt(redirectUri: String) = SignInAttempt(randomString(), randomString(), randomString(48), redirectUri)

    /**
     * Open the address of the authorization page. [clientId] is null, which means the first time, use
     * `dynamic_agent_client` to register and bring the App name; log in again with the last ID token
     * ([idTokenHint], which may have expired; do not bring it after logging out) and email address
     * ([loginHint]). There may be an ID token in the address, so do not write it to the log.
     */
    fun authorizeUrl(attempt: SignInAttempt, clientId: String?, hostId: String, idTokenHint: String? = null, loginHint: String? = null): String =
        "$issuer/api/accounts/authorize".toHttpUrl().newBuilder()
            .addQueryParameter("client_id", clientId ?: REGISTER_CLIENT_ID)
            .apply { if (clientId == null) addQueryParameter("agent_name_hint", APP_NAME) }
            .addQueryParameter("ext_agent_host_id", hostId)
            .apply {
                if (clientId != null && !idTokenHint.isNullOrEmpty()) addQueryParameter("id_token_hint", idTokenHint)
                if (clientId != null && !loginHint.isNullOrEmpty()) addQueryParameter("login_hint", loginHint)
            }
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", attempt.redirectUri)
            .addQueryParameter("scope", SCOPES)
            .addQueryParameter("resource", RESOURCE)
            .addQueryParameter("state", attempt.state)
            .addQueryParameter("nonce", attempt.nonce)
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("code_challenge", attempt.challenge)
            .build().toString()

    /**
     * Read the callback parameters and get (authorization code, client_id used this time). First check
     * the state; if the user refuses, the state is incorrect, the client_id is inconsistent with this
     * request, or the client_id is not given during the first registration, [SignInException] will be
     * thrown.
     */
    fun readCallback(params: Map<String, String>, attempt: SignInAttempt, clientId: String?): Pair<String, String> {
        if (params["state"] != attempt.state) throw SignInException(SignInFailure.StateMismatch)
        params["error"]?.let { err ->
            // Error brought back by the authorization page: If you can tell about the region and
            // package, you will be told, and the rest will be treated as if the user has not agreed.
            val f = failureOf(err, params["error_description"])
            throw SignInException(if (f == SignInFailure.Region || f == SignInFailure.NoPlanScope) f else SignInFailure.Denied, err, code = err)
        }
        val code = params["code"]?.takeIf { it.isNotEmpty() } ?: throw SignInException(SignInFailure.Exchange, "no code", code = "no_code")
        val issued = params["client_id"]?.takeIf { it.isNotEmpty() }
        if (clientId != null && issued != null && issued != clientId) throw SignInException(SignInFailure.ClientMismatch)
        val id = issued ?: clientId ?: throw SignInException(SignInFailure.NoClientId)
        return code to id
    }

    suspend fun exchange(clientId: String, code: String, attempt: SignInAttempt): TokenResult = token(
        FormBody.Builder().add("grant_type", "authorization_code").add("client_id", clientId).add("code", code)
            .add("code_verifier", attempt.verifier).add("redirect_uri", attempt.redirectUri).add("resource", RESOURCE).build(),
    )

    /** Without scope: retain the originally granted permissions. */
    suspend fun refresh(clientId: String, refreshToken: String): TokenResult = token(
        FormBody.Builder().add("grant_type", "refresh_token").add("client_id", clientId).add("refresh_token", refreshToken)
            .add("resource", RESOURCE).build(),
    )

    /** Unregister the refresh token; the server side confirmation (HTTP 200, the token is already invalid is also 200) is true. Logging out does not delete the registered client. */
    suspend fun revoke(clientId: String, refreshToken: String): Boolean = try {
        val form = FormBody.Builder().add("token", refreshToken).add("token_type_hint", "refresh_token").add("client_id", clientId).build()
        HttpSupport.execute(http, Request.Builder().url(revokeUrl).post(form).build()).status == 200
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    private suspend fun token(form: FormBody): TokenResult {
        val raw = try {
            HttpSupport.execute(http, Request.Builder().url(tokenUrl).post(form).build())
        } catch (e: CancellationException) {
            throw e
        } catch (e: ModelError) {
            return TokenResult.Failed(e)
        }
        val root = runCatching { json.parseToJsonElement(raw.body) as? JsonObject }.getOrNull()
        if (raw.status in 500..599) return TokenResult.Failed(ModelError.Server(raw.status))
        if (raw.status != 200) {
            val code = root?.let { (it["error"] as? JsonObject)?.str("code") ?: it.str("error") ?: it.str("code") } ?: "http_${raw.status}"
            val detail = root?.let { it.str("error_description") ?: (it["error"] as? JsonObject)?.str("message") }
            return TokenResult.Rejected(code, code in TERMINAL_REFRESH_ERRORS, detail, nonJson = root == null)
        }
        val access = root?.str("access_token") ?: return TokenResult.Failed(ModelError.BadResponse("换令牌的返回里没有 access_token"))
        return TokenResult.Ok(
            TokenSet(
                accessToken = access,
                refreshToken = root.str("refresh_token"),
                idToken = root.str("id_token"),
                expiresInSec = (root["expires_in"] as? JsonPrimitive)?.longOrNull ?: DEFAULT_EXPIRES_SEC,
                scopes = root.str("scope")?.split(' ')?.filter { it.isNotEmpty() }.orEmpty(),
            ),
        )
    }

    /** Verify ID token: signature (JWKS, RS256), issuer, audience, expiration, nonce; all return identity, otherwise throw [SignInException]. */
    suspend fun verifyIdToken(idToken: String, clientId: String, nonce: String): IdClaims {
        fun fail(why: String): Nothing = throw SignInException(SignInFailure.IdToken, why)
        val pieces = idToken.split('.')
        if (pieces.size != 3) fail("not a JWT")
        val header = decodeJson(pieces[0]) ?: fail("bad header")
        val claims = decodeJson(pieces[1]) ?: fail("bad payload")
        if (header.str("alg") != "RS256") fail("alg ${header.str("alg")}")
        val kid = header.str("kid")
        val key = jwk(kid, refresh = false) ?: jwk(kid, refresh = true) ?: fail("unknown key")
        val verified = runCatching {
            val n = BigInteger(1, b64urlDecode(key.str("n")!!))
            val e = BigInteger(1, b64urlDecode(key.str("e")!!))
            val pub = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))
            Signature.getInstance("SHA256withRSA").run {
                initVerify(pub)
                update("${pieces[0]}.${pieces[1]}".toByteArray(Charsets.US_ASCII))
                verify(b64urlDecode(pieces[2]))
            }
        }.getOrDefault(false)
        if (!verified) fail("signature")
        if (claims.str("iss") != issuer) fail("issuer")
        val aud = claims["aud"]
        val audOk = when (aud) {
            is JsonPrimitive -> aud.contentOrNull == clientId
            is JsonArray -> aud.any { (it as? JsonPrimitive)?.contentOrNull == clientId }
            else -> false
        }
        if (!audOk) fail("audience")
        val exp = (claims["exp"] as? JsonPrimitive)?.longOrNull ?: fail("no exp")
        if (exp * 1000 + CLOCK_SKEW_MS < now()) fail("expired")
        if (claims.str("nonce") != nonce) fail("nonce")
        val sub = claims.str("sub")?.takeIf { it.isNotEmpty() } ?: fail("no sub")
        val email = claims.str("email") ?: (claims[PROFILE_CLAIM] as? JsonObject)?.str("email") ?: ""
        return IdClaims(sub, email)
    }

    private suspend fun jwk(kid: String?, refresh: Boolean): JsonObject? {
        if (refresh || jwks.isEmpty()) {
            val raw = try { HttpSupport.execute(http, Request.Builder().url(jwksUrl).get().build()) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                throw SignInException(SignInFailure.Network, "jwks", e)
            }
            val keys = runCatching { (json.parseToJsonElement(raw.body) as JsonObject)["keys"] as JsonArray }.getOrNull().orEmpty()
            jwks = keys.filterIsInstance<JsonObject>().filter { it.str("kty") == "RSA" }.associateBy { it.str("kid").orEmpty() }
        }
        return if (kid == null) jwks.values.singleOrNull() else jwks[kid]
    }

    private fun decodeJson(part: String): JsonObject? = runCatching { json.parseToJsonElement(String(b64urlDecode(part))) as? JsonObject }.getOrNull()

    private fun randomString(bytes: Int = 32): String = ByteArray(bytes).also(random::nextBytes).let(::b64url)

    companion object {
        const val ISSUER = "https://auth.openai.com"
        const val REGISTER_CLIENT_ID = "dynamic_agent_client"
        const val APP_NAME = "Mina"
        const val PLAN_SCOPE = "chatgpt.tokens.use.direct"
        const val SCOPES = "openid profile email offline_access resource.invoke $PLAN_SCOPE"
        const val RESOURCE = "https://api.openai.com/v1"
        /** The interface root (column model, Responses) called with the package quota. */
        const val API_BASE = "https://api.openai.com/v1"
        /** The page where users view and manage usage in ChatGPT. */
        const val USAGE_URL = "https://chatgpt.com/settings/usage"
        private const val PROFILE_CLAIM = "https://api.openai.com/profile"
        private const val DEFAULT_EXPIRES_SEC = 3600L
        private const val CLOCK_SKEW_MS = 120_000L

        /** The refresh token can no longer be used: clear the token and log in again with the original client_id. */
        val TERMINAL_REFRESH_ERRORS = setOf(
            "invalid_grant", "invalid_refresh_token", "token_expired", "refresh_token_expired",
            "refresh_token_invalidated", "refresh_token_reused",
        )

        /** Error codes such as the user did not agree or did not log in. */
        private val DENIED_ERRORS = setOf("access_denied", "consent_required", "login_required", "interaction_required")
        /** It’s an error code for a package or permission. */
        private val PLAN_ERRORS = setOf("invalid_scope", "insufficient_scope", "subscription_sharing_user_not_eligible")
        private val REGION = Regex("""unsupported_country|region|territor""", RegexOption.IGNORE_CASE)
        private val PLAN = Regex("""(?<![a-z])(plan|subscription|scope)s?(?![a-z])""", RegexOption.IGNORE_CASE)

        /**
         * The interface tells you which category the reasons for rejection fall into: blocked by
         * protection (403, 429 and the returned value is not JSON), the region is not supported, the
         * authorization code has expired or been used (`invalid_grant`), the user did not agree, the
         * package or permissions (mentioned in the code or description), and the rest are
         * [SignInFailure.Exchange].
         */
        fun failureOf(code: String, detail: String?, nonJson: Boolean = false): SignInFailure {
            val text = "$code ${detail.orEmpty()}"
            return when {
                nonJson && (code == "http_403" || code == "http_429") -> SignInFailure.Blocked
                REGION.containsMatchIn(text) -> SignInFailure.Region
                code == "invalid_grant" -> SignInFailure.CodeExpired
                code in DENIED_ERRORS -> SignInFailure.Denied
                code in PLAN_ERRORS || PLAN.containsMatchIn(text) -> SignInFailure.NoPlanScope
                else -> SignInFailure.Exchange
            }
        }

        /** Exception thrown when token exchange is rejected: The reason is classified according to [failureOf], with error code and description. */
        fun rejected(r: TokenResult.Rejected): SignInException = SignInException(
            failureOf(r.code, r.detail, r.nonJson), listOfNotNull(r.code, r.detail).joinToString(" — "), code = r.code,
        )

        fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
        fun b64urlDecode(s: String): ByteArray = Base64.getUrlDecoder().decode(s.trimEnd('='))

        /** Local identification: UUIDv4 URN, generated once for each device and used forever. */
        fun newHostId(): String = "urn:uuid:${java.util.UUID.randomUUID()}"
    }
}

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
