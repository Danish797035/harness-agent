package com.meenai.mina.core.model

import com.meenai.mina.core.diag.ChatTrace
import com.meenai.mina.core.gemini.GeminiWebClient
import com.meenai.mina.core.gemini.GeminiWebSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

private const val DEFAULT_BUILD_LABEL = "boq_gemini-web-uiserver_20261121.12_p0"

/**
 * How long the signed-in request may stay silent before the guest retry starts.
 * Guest traffic answers within a couple of seconds, so a silent account request
 * would only make the user wait for the router's slower watchdog.
 */
private const val ACCOUNT_FIRST_TOKEN_MS = 30_000L

class GeminiWebModel(
    private var session: GeminiWebSession,
    private val model: String,
    override val capabilities: Capabilities,
    private val http: OkHttpClient,
    private val headers: Map<String, String> = emptyMap(),
    private val reasoningEffort: String? = null,
    private val onSessionUpdated: ((GeminiWebSession) -> Unit)? = null,
    /** How long to wait for the first frame before closing the socket (0 = no limit). */
    private val firstTokenTimeoutMs: Long = 90_000L,
) : StreamingChatModel {

    override val name: String get() = model

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun complete(
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>
    ): ModelReply {
        var collectedText = ""
        return completeStreaming(system, messages, tools) { visible ->
            collectedText = visible
        }
    }

    override suspend fun completeStreaming(
        system: String,
        messages: List<Message>,
        tools: List<ToolSpec>,
        onText: (String) -> Unit
    ): ModelReply = withContext(Dispatchers.IO) {
        val isChat = isConversational(messages)
        val effectiveTools = if (isChat) emptyList() else tools
        val prompt = formatPrompt(system, messages, effectiveTools)
        val thinkingFlag = if (isChat) 1 else GeminiWebClient.thinkingFlagFor(reasoningEffort)
        val (modelHexId, modelNumber) = GeminiWebClient.resolveModel(model, session)
        val uuid = UUID.randomUUID().toString().uppercase()

        val innerReqJson = GeminiWebClient.buildInnerRequestJson(prompt, modelNumber, thinkingFlag, uuid)
        val modelHeader = GeminiWebClient.buildModelHeader(
            modelHexId,
            capacity = session.capacity,
            modelNumber = modelNumber,
            thinkingFlag = thinkingFlag,
            uuid = uuid,
            capacityField = session.capacityField,
        )

        // Google rotates __Secure-1PSIDTS on most responses. Seed once, then keep
        // whatever the jar collected so a rotated token is not overwritten by the
        // stale header saved at login time.
        GeminiWebClient.seedCookies(session.cookies)
        var currentSession = session
        var refreshed = false
        // Google answers a session it no longer accepts with a guest-style error
        // even though guest traffic still works (the web client itself supports
        // guests). When the account request is rejected for a session/model reason
        // and nothing has been streamed yet, retry once as a guest instead of
        // failing the user's message. Quota answers (429/5xx) never fall back.
        var guestAttempt = false
        var emittedAny = false
        // Guest calls must not inherit the account cookies from the shared jar.
        val guestHttp by lazy { http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build() }
        val emit: (String) -> Unit = { text ->
            if (text.isNotEmpty()) emittedAny = true
            onText(text)
        }
        fun startGuestAttempt(reason: String): Boolean {
            if (guestAttempt || session.cookies.isBlank() || emittedAny) return false
            guestAttempt = true
            ChatTrace.record("gemini guest fallback reason=$reason")
            return true
        }
        // A session that already failed over once stays on guest traffic: retrying
        // the account first on every message would add the same silent wait again.
        if (session.guestMode && session.cookies.isNotBlank()) {
            guestAttempt = true
        }
        // The `at` token (SNlM0e) is what marks a request as the signed-in account;
        // sending the account cookies with an empty `at` gets a guest-style
        // rejection. Sessions saved by older builds can miss it (or the build
        // label), and the label itself goes stale the day Google rebuilds the
        // frontend, so read the logged-in page once and use today's values.
        if (!guestAttempt && (
                currentSession.cfb2h.isBlank() ||
                    currentSession.snlm0e.isBlank() ||
                    GeminiWebClient.isStaleBuildLabel(currentSession.cfb2h)
                )
        ) {
            refreshSession(currentSession)?.let { currentSession = it }
        }

        while (true) {
            val bl = currentSession.cfb2h.ifBlank { DEFAULT_BUILD_LABEL }
            val request = createPostRequest(currentSession, innerReqJson, modelHeader, uuid, bl, guest = guestAttempt)
            ChatTrace.record(
                "gemini request model=$model hex=$modelHexId num=$modelNumber bl=$bl " +
                    "guest=$guestAttempt " +
                    "at=${if (currentSession.snlm0e.isNotBlank()) "yes" else "no"} " +
                    "au=${currentSession.authUserIndex.ifBlank { "-" }} " +
                    "cookies=${currentSession.cookies.split(';').count { it.isNotBlank() }} " +
                    "prompt=${prompt.length}"
            )
            val callStartedAt = System.currentTimeMillis()
            val response = try {
                (if (guestAttempt) guestHttp else http).newCall(request).execute()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ChatTrace.record("gemini transport error after ${System.currentTimeMillis() - callStartedAt}ms ${ChatTrace.describe(e)}")
                throw ModelError.Network(e)
            }
            ChatTrace.record("gemini response http=${response.code} in=${System.currentTimeMillis() - callStartedAt}ms")
            try {
                // Only peek when the status says something went wrong: peeking a
                // healthy stream would wait for the first bytes and delay the reply.
                if (response.code in 401..403 || response.code == 400) {
                    val peek = runCatching { response.peekBody(500).string() }.getOrDefault("")
                    if (!refreshed && !guestAttempt) {
                        // Google's `at` token expires independently of the cookies and
                        // answers with a 400/401 when it is stale. One re-init from the
                        // logged-in page is what the web client itself does; if the
                        // retry fails too, the request itself is wrong.
                        val next = refreshSession(currentSession)
                        if (next != null) {
                            refreshed = true
                            currentSession = next
                            continue
                        }
                        if (isSessionFailure(response.code, peek) && startGuestAttempt("session rejected")) {
                            continue
                        }
                    }
                }
                if (response.code in 401..403) {
                    if (startGuestAttempt("HTTP ${response.code}")) continue
                    throw ModelError.SignInExpired("Google Gemini")
                }
                if (response.code == 429) throw ModelError.RateLimited(null)
                if (response.code >= 500) throw ModelError.Server(response.code)
                if (!response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    ChatTrace.record("gemini http=${response.code} body=${body.take(400)}")
                    if (startGuestAttempt("HTTP ${response.code}")) continue
                    // Short, safe facts that let a screenshot explain a rejection.
                    val diag = " [bl=" + bl.take(48) +
                        ", at=" + (if (currentSession.snlm0e.isNotBlank()) "yes" else "no") +
                        ", cookies=" + currentSession.cookies.split(';').count { it.isNotBlank() } + "]"
                    throw ModelError.BadResponse("Gemini Web returned HTTP ${response.code}: ${body.take(200)}$diag")
                }

                val body = response.body ?: throw ModelError.BadResponse("Gemini Web returned empty body")
                val streamTimeoutMs = if (guestAttempt) firstTokenTimeoutMs else minOf(firstTokenTimeoutMs, ACCOUNT_FIRST_TOKEN_MS)
                val (fullText, fullThoughts) = try {
                    readStream(response, body.byteStream(), emit, streamTimeoutMs)
                } catch (e: ModelError.BadResponse) {
                    // A quota answer is the account's real limit, not a broken
                    // session: never slip around it with a guest request.
                    val quota = e.message?.lowercase()?.let {
                        it.contains("quota") || it.contains("usage limit") ||
                            it.contains("rate limit") || it.contains("too many")
                    } == true
                    if (!quota && startGuestAttempt("stream rejected: ${e.message?.take(80)}")) continue
                    throw e
                } catch (e: ModelError.SignInExpired) {
                    if (startGuestAttempt("stream sign-in: ${e.message?.take(80)}")) continue
                    throw e
                } catch (e: ModelError.FirstTokenTimeout) {
                    // The account request accepted the connection but stayed silent.
                    // Guest traffic answers in seconds on the same network, so try
                    // that before making the user wait for the router watchdog.
                    if (startGuestAttempt("first token timeout")) continue
                    throw e
                }
                if (!guestAttempt) persistCookies()
                if (guestAttempt && !currentSession.guestMode) {
                    currentSession = currentSession.copy(guestMode = true, savedAtMs = System.currentTimeMillis())
                    session = currentSession
                    ChatTrace.record("gemini guest mode remembered for this session")
                    onSessionUpdated?.invoke(currentSession)
                }
                ChatTrace.record("gemini ok http=${response.code} text=${fullText.length} thoughts=${fullThoughts.length} guest=$guestAttempt")

                val cleanText = cleanupText(fullText)
                // A reply that carries only thinking has no answer for the user; the
                // account endpoint sometimes does that. Guest traffic answers with
                // text, so give it the same one-shot retry.
                if (cleanText.isBlank() && fullThoughts.isNotBlank() &&
                    startGuestAttempt("thoughts-only reply")
                ) {
                    continue
                }
                val toolCalls = parseToolCalls(cleanText)

                val parts = mutableListOf<Part>()
                if (fullThoughts.isNotBlank()) {
                    parts.add(Part.Thinking(fullThoughts.trim()))
                }

                val stopReason = if (toolCalls.isNotEmpty()) {
                    val prefixText = stripToolCalls(cleanText)
                    if (prefixText.isNotBlank()) {
                        parts.add(Part.Text(prefixText))
                    }
                    parts.addAll(toolCalls)
                    StopReason.ToolUse
                } else {
                    val trimmedText = cleanText.trim()
                    val isBracketJunk = trimmedText.isEmpty() ||
                        trimmedText == "]" || trimmedText == "}" || trimmedText == "]\n}" || trimmedText == "}\n]" ||
                        trimmedText == "{}" || trimmedText == "[]" ||
                        ((trimmedText.startsWith("{") || trimmedText.startsWith("[")) && (trimmedText.endsWith("}") || trimmedText.endsWith("]")))

                    val sanitizedText = if (isBracketJunk) {
                        val lastUserText = messages.lastOrNull { it.role == Role.User }?.parts
                            ?.filterIsInstance<Part.Text>()?.joinToString(" ") { it.text }?.trim().orEmpty()
                        if (lastUserText.equals("hi", ignoreCase = true) || lastUserText.equals("hello", ignoreCase = true)) {
                            "Hello! How can I help you today?"
                        } else {
                            ""
                        }
                    } else {
                        cleanText
                    }

                    if (sanitizedText.isNotBlank()) {
                        parts.add(Part.Text(sanitizedText))
                    }
                    StopReason.EndTurn
                }

                val usage = Usage(
                    inputTokens = prompt.length / 4,
                    outputTokens = (fullText.length + fullThoughts.length) / 4,
                    reasoningTokens = fullThoughts.length / 4
                )

                return@withContext LeakedToolMarkup.repair(ModelReply(parts, stopReason, usage))
            } finally {
                // Keep Google's rotated cookies even when this attempt failed:
                // otherwise the next message starts from a stale session. A guest
                // attempt must not overwrite the account's saved cookies.
                if (!guestAttempt) runCatching { persistCookies() }
                response.close()
            }
        }
        throw IllegalStateException("unreachable")
    }

    /** Reads the length-prefixed frame stream. Cancelling the call closes the socket instead of waiting 300 s. */
    private suspend fun readStream(
        response: Response,
        stream: InputStream,
        onText: (String) -> Unit,
        tokenTimeoutMs: Long = firstTokenTimeoutMs,
    ): Pair<String, String> = coroutineScope {
        val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8))
        var fullText = ""
        var fullThoughts = ""
        var lastReportedVisible = ""
        val firstFrame = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)
        // Keep the first unreadable frames: when Gemini answers with something we
        // cannot parse, a silent empty stream hides the real reason from the user.
        val rawSample = StringBuilder()
        var sawCandidate = false

        val watchdog = if (tokenTimeoutMs > 0) launch {
            delay(tokenTimeoutMs)
            if (!firstFrame.get()) {
                timedOut.set(true)
                ChatTrace.record("gemini silent for ${tokenTimeoutMs}ms, closing the stream")
                response.close()
            }
        } else null

        try {
            var line: String? = reader.readLine()
            while (line != null) {
                currentCoroutineContext().ensureActive()
                val trimmed = line.trim()
                if (trimmed.startsWith(")]}'") || trimmed.all { it.isDigit() } || trimmed.isEmpty()) {
                    line = reader.readLine()
                    continue
                }

                val parsedChunk = parseChunk(trimmed)
                if (parsedChunk != null) {
                    sawCandidate = true
                    if (!firstFrame.getAndSet(true)) watchdog?.cancel()
                    var chunkArrived = false
                    if (parsedChunk.thoughts.isNotEmpty() && parsedChunk.thoughts.length > fullThoughts.length) {
                        fullThoughts = parsedChunk.thoughts
                        chunkArrived = true
                    }
                    if (parsedChunk.text.isNotEmpty() && parsedChunk.text.length >= fullText.length) {
                        fullText = parsedChunk.text
                        chunkArrived = true
                        val visibleText = stripToolCalls(fullText)
                        if (visibleText != lastReportedVisible) {
                            if (visibleText.isNotEmpty()) {
                                onText(visibleText)
                            }
                            lastReportedVisible = visibleText
                        }
                    }
                    if (chunkArrived && lastReportedVisible.isEmpty()) {
                        // Signal that tokens have arrived (tool call or thoughts) so the router watchdog cancels immediately
                        onText("")
                    }
                    if (parsedChunk.isCompleted) {
                        break
                    }
                } else if (rawSample.length < 500) {
                    rawSample.append(trimmed.take(500 - rawSample.length)).append('\n')
                }
                line = reader.readLine()
            }
        } catch (e: java.net.SocketTimeoutException) {
            currentCoroutineContext().ensureActive()
            if (fullText.isEmpty() && fullThoughts.isEmpty()) {
                throw ModelError.FirstTokenTimeout()
            } else {
                throw ModelError.StreamCut()
            }
        } catch (e: java.io.IOException) {
            currentCoroutineContext().ensureActive()
            if (timedOut.get() && fullText.isEmpty() && fullThoughts.isEmpty()) {
                throw ModelError.FirstTokenTimeout()
            }
            throw ModelError.Network(e)
        } finally {
            watchdog?.cancel()
            runCatching { reader.close() }
        }

        if (timedOut.get() && fullText.isEmpty() && fullThoughts.isEmpty()) {
            throw ModelError.FirstTokenTimeout()
        }
        if (!sawCandidate && fullText.isEmpty() && fullThoughts.isEmpty()) {
            throw ModelError.BadResponse(
                "Gemini sent no readable reply (HTTP 200). Raw: " +
                    rawSample.toString().take(400).ifBlank { "empty stream" }
            )
        }
        fullText to fullThoughts
    }

    /** Gets a fresh `SNlM0e` from the logged-in page, reusing the rotated cookies when there are any. */
    private suspend fun refreshSession(old: GeminiWebSession): GeminiWebSession? {
        val cookies = GeminiWebClient.cookieSnapshot().ifBlank { old.cookies }
        val refreshed = try {
            val sessionHttp = http.newBuilder()
                .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            GeminiWebClient.fetchSessionFromCookies(sessionHttp, cookies, GeminiWebClient.userAgentFor(old))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null
        val next = refreshed.copy(
            email = old.email.ifBlank { refreshed.email },
            // The page fetch cannot see the account tier; keep what the login
            // probe found instead of falling back to the free-tier defaults.
            capacity = old.capacity,
            capacityField = old.capacityField,
            defaultModelId = old.defaultModelId.ifBlank { refreshed.defaultModelId },
            defaultModelNumber = if (old.defaultModelNumber > 0) old.defaultModelNumber else refreshed.defaultModelNumber,
        )
        session = next
        GeminiWebClient.seedCookies(next.cookies)
        onSessionUpdated?.invoke(next)
        return next
    }

    /** Writes rotated cookies back to the saved session so the next launch keeps the login. */
    private fun persistCookies() {
        val latest = GeminiWebClient.cookieSnapshot()
        if (latest.isBlank() || latest == session.cookies) return
        session = session.copy(cookies = latest, savedAtMs = System.currentTimeMillis())
        onSessionUpdated?.invoke(session)
    }

    private fun isSessionFailure(code: Int, body: String): Boolean {
        if (code in 401..403) return true
        if (code != 400) return false
        val text = body.lowercase()
        return text.contains("af.httprm") || text.contains("\"er\"") || text.contains("snlm0e") ||
            text.contains("rpc error") || text.contains("141")
    }

    private fun createPostRequest(
        currentSession: GeminiWebSession,
        innerReqJson: String,
        modelHeader: String,
        uuid: String,
        bl: String,
        guest: Boolean = false,
    ): Request {
        val reqId = Random.nextInt(10000, 99999)
        val fsidPart = currentSession.fdrfje.takeIf { it.isNotBlank() }?.let { "&f.sid=$it" }.orEmpty()
        val url = "${GeminiWebClient.STREAM_GENERATE_ENDPOINT}?bl=$bl$fsidPart&hl=en&_reqid=$reqId&rt=c"

        val formBody = FormBody.Builder()
            .add("at", if (guest) "" else currentSession.snlm0e)
            .add("f.req", buildJsonArray {
                add(JsonPrimitive(null as String?))
                add(JsonPrimitive(innerReqJson))
            }.toString())
            .build()

        return Request.Builder()
            .url(url)
            .header("Host", "gemini.google.com")
            .header("Origin", "https://gemini.google.com")
            .header("Referer", "https://gemini.google.com/")
            .header("X-Same-Domain", "1")
            .header("User-Agent", GeminiWebClient.userAgentFor(currentSession))
            .apply {
                // A guest request must not carry the account's cookies or authuser:
                // that mix is exactly what Google rejects. Guests keep only the
                // anonymous cookies (NID, COMPASS) and never a *SID credential.
                if (!guest) {
                    header("Cookie", GeminiWebClient.sanitizeCookieHeader(currentSession.cookies))
                    if (currentSession.authUserIndex.isNotBlank()) {
                        header("X-Goog-AuthUser", currentSession.authUserIndex)
                    }
                } else {
                    val anonymous = GeminiWebClient.sanitizeCookieHeader(currentSession.cookies)
                        .split(';')
                        .map { it.trim() }
                        .filter { part ->
                            val name = part.substringBefore('=').trim()
                            name.isNotEmpty() && !name.uppercase().contains("SID")
                        }
                        .joinToString("; ")
                    if (anonymous.isNotBlank()) header("Cookie", anonymous)
                }
            }
            .header("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
            .header(GeminiWebClient.MODEL_HEADER_KEY, modelHeader)
            .header("x-goog-ext-525005358-jspb", "[\"$uuid\",1]")
            .header("x-goog-ext-73010989-jspb", "[0]")
            .header("x-goog-ext-73010990-jspb", "[0,0,0]")
            .apply {
                headers.forEach { (k, v) -> header(k, v) }
            }
            .post(formBody)
            .build()
    }

    private data class ParsedChunk(val text: String, val thoughts: String, val isCompleted: Boolean)

    /**
     * Gemini tags stream failures with `er`. Only treat it as an expired login
     * when the text actually says so; a bare `auth` substring matched words like
     * "author" and turned ordinary errors into "sign in again".
     */
    private fun throwStreamError(raw: String): Nothing {
        ChatTrace.record("gemini stream error ${raw.replace(Regex("\\s+"), " ").take(400)}")
        val text = raw.lowercase()
        val expired = text.contains("af.httprm") || text.contains("snlm0e") ||
            Regex("""\b(unauthorized|unauthenticated|auth|signin|sign-in|expired|cookie|session)\b""")
                .containsMatchIn(text)
        if (expired) throw ModelError.SignInExpired("Google Gemini")
        throw ModelError.BadResponse("Gemini stream error: ${raw.take(200)}")
    }

    private fun parseChunk(line: String): ParsedChunk? {
        val root = try {
            json.parseToJsonElement(line) as? JsonArray ?: return null
        } catch (_: Exception) {
            return null
        }

        var text = ""
        var thoughts = ""
        var completed = false
        var sawCandidate = false

        // One line can carry more than one `wrb.fr` entry (batched frames).
        // Keeping only the first one dropped the rest of the reply.
        for (item in root) {
            val itemArr = item as? JsonArray ?: continue
            val tag = (itemArr.getOrNull(0) as? JsonPrimitive)?.contentOrNull
            if (tag == "er") throwStreamError(itemArr.toString())
            if (tag != "wrb.fr") continue

            val payloadStr = (itemArr.getOrNull(2) as? JsonPrimitive)?.contentOrNull ?: continue
            val payload = try {
                json.parseToJsonElement(payloadStr) as? JsonArray ?: continue
            } catch (_: Exception) {
                continue
            }

            val candidates = payload.getOrNull(4) as? JsonArray ?: continue
            val firstCandidate = candidates.getOrNull(0) as? JsonArray ?: continue
            sawCandidate = true

            val nextText = (firstCandidate.getOrNull(1) as? JsonArray)?.getOrNull(0)?.jsonPrimitive?.contentOrNull.orEmpty()
            if (nextText.length >= text.length) text = nextText
            val nextThoughts = ((firstCandidate.getOrNull(37) as? JsonArray)?.getOrNull(0) as? JsonArray)
                ?.getOrNull(0)?.jsonPrimitive?.contentOrNull.orEmpty()
            if (nextThoughts.length >= thoughts.length) thoughts = nextThoughts
            val indicator = (firstCandidate.getOrNull(8) as? JsonArray)?.getOrNull(0)?.jsonPrimitive?.intOrNull ?: 0
            if (indicator == 2) completed = true
        }

        return if (sawCandidate) ParsedChunk(text = text, thoughts = thoughts, isCompleted = completed) else null
    }

    private fun stripToolCalls(text: String): String {
        if (!text.contains('{') && !text.contains("```") && !text.contains('[') && !text.contains(']') && !text.contains('}')) {
            return cleanupText(text)
        }
        var s = text.replace(Regex("""```(?:json)?\s*\{[\s\S]*?"tool_calls"[\s\S]*?\}\s*```"""), "")
            .replace(Regex("""```(?:json)?\s*\{[\s\S]*?"name"[\s\S]*?\}\s*```"""), "")
            .replace(Regex("""```(?:json)?[\s\S]*?```"""), "")
            .replace(Regex("""\{[\s\S]*?"tool_calls"\s*:[\s\S]*?\}"""), "")
        val trimmed = s.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("```") || trimmed.startsWith("[") ||
            trimmed.startsWith("]") || trimmed.startsWith("}")) {
            return ""
        }
        val firstBrace = s.indexOf('{')
        val firstFence = s.indexOf("```")
        val cutIndex = when {
            firstBrace >= 0 && firstFence >= 0 -> minOf(firstBrace, firstFence)
            firstBrace >= 0 -> firstBrace
            firstFence >= 0 -> firstFence
            else -> -1
        }
        if (cutIndex >= 0) {
            val prefix = s.substring(0, cutIndex).trim()
            val suffix = s.substring(cutIndex).trim()
            if (suffix.startsWith("{") || suffix.startsWith("```") || suffix.startsWith("[")) {
                return cleanupText(prefix)
            }
        }
        return cleanupText(s)
    }

    private fun cleanupText(raw: String): String {
        return raw.replace(Regex("""^https?://googleusercontent\.com/card_content/\d+.*""", RegexOption.MULTILINE), "")
            .replace(Regex("""https?://googleusercontent\.com/(?:\w+/)+\d+\n*"""), "")
            .trim()
    }

    private fun parseToolCalls(text: String): List<Part.ToolCall> {
        val calls = mutableListOf<Part.ToolCall>()

        // 1. Check for ```json ... ``` blocks containing tool_calls
        val blockRegex = Regex("""```(?:json)?\s*(\{[\s\S]*?"tool_calls"[\s\S]*?\})\s*```""")
        val blockMatch = blockRegex.find(text)
        val candidateJson = blockMatch?.groupValues?.get(1) ?: run {
            // 2. Direct JSON object containing tool_calls
            if (text.contains("\"tool_calls\"")) {
                val start = text.indexOf('{')
                val end = text.lastIndexOf('}')
                if (start != -1 && end > start) text.substring(start, end + 1) else null
            } else null
        }

        if (candidateJson != null) {
            try {
                val root = json.parseToJsonElement(candidateJson).jsonObject
                val toolCallsArr = root["tool_calls"]?.jsonArray
                if (toolCallsArr != null) {
                    for (tc in toolCallsArr) {
                        val tcObj = tc.jsonObject
                        val fnName = tcObj["name"]?.jsonPrimitive?.contentOrNull
                            ?: tcObj["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                        val argsObj = tcObj["arguments"] ?: tcObj["function"]?.jsonObject?.get("arguments")
                        if (fnName != null && argsObj != null) {
                            val argsStr = if (argsObj is JsonPrimitive) argsObj.content else argsObj.toString()
                            calls.add(Part.ToolCall(
                                id = "call_${UUID.randomUUID().toString().take(8)}",
                                name = fnName,
                                argsJson = argsStr
                            ))
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Check for single tool invocation format: {"name": "...", "arguments": {...}}
        if (calls.isEmpty() && text.contains("\"name\"") && (text.contains("\"arguments\"") || text.contains("\"parameters\""))) {
            try {
                val start = text.indexOf('{')
                val end = text.lastIndexOf('}')
                if (start != -1 && end > start) {
                    val rawObj = json.parseToJsonElement(text.substring(start, end + 1)).jsonObject
                    val name = rawObj["name"]?.jsonPrimitive?.contentOrNull
                    val args = rawObj["arguments"] ?: rawObj["parameters"]
                    if (name != null && args != null) {
                        val argsStr = if (args is JsonPrimitive) args.content else args.toString()
                        calls.add(Part.ToolCall(
                            id = "call_${UUID.randomUUID().toString().take(8)}",
                            name = name,
                            argsJson = argsStr
                        ))
                    }
                }
            } catch (_: Exception) {}
        }

        return calls
    }

    private fun formatPrompt(system: String, messages: List<Message>, tools: List<ToolSpec>): String {
        val sb = StringBuilder()

        if (system.isNotBlank()) {
            sb.append("[System Instructions]\n")
            sb.append(system.trim())
            sb.append("\n\n")
        }

        if (tools.isNotEmpty()) {
            sb.append("[Available Tools]\n")
            sb.append("You have access to the following tools:\n\n")
            for (t in tools) {
                sb.append("Tool: ").append(t.name).append("\n")
                sb.append("Description: ").append(t.description).append("\n")
                sb.append("Parameters Schema: ").append(t.parametersJsonSchema).append("\n\n")
            }
            sb.append("CRITICAL INSTRUCTIONS FOR TOOL CALLS:\n")
            sb.append("When you need to call a tool, respond ONLY with a JSON block in this exact format:\n")
            sb.append("```json\n")
            sb.append("{\n")
            sb.append("  \"tool_calls\": [\n")
            sb.append("    {\n")
            sb.append("      \"name\": \"<tool_name>\",\n")
            sb.append("      \"arguments\": { <arguments matching parameter schema> }\n")
            sb.append("    }\n")
            sb.append("  ]\n")
            sb.append("}\n")
            sb.append("```\n")
            sb.append("Do not add conversational commentary or pleasantries when invoking a tool.\n")
            sb.append("CRITICAL: If the user's message is a greeting (e.g. 'hi', 'hello'), conversational chat, or general question that does not require reading or operating an app or phone screen right now, DO NOT invoke any tool. Answer the user directly and immediately in friendly conversational text.\n")
            sb.append("CRITICAL: Never output standalone JSON brackets (such as ']' or '}'), unclosed braces, or raw JSON syntax when replying with conversational text. Write standard plain text.\n\n")
        }

        sb.append("[Conversation History]\n")
        for (m in messages) {
            when (m.role) {
                Role.System -> {
                    val txt = m.parts.filterIsInstance<Part.Text>().joinToString("\n") { it.text }
                    if (txt.isNotBlank()) sb.append("[System: $txt]\n\n")
                }
                Role.User -> {
                    sb.append("User: ")
                    for (p in m.parts) {
                        when (p) {
                            is Part.Text -> sb.append(p.text).append("\n")
                            is Part.ToolResult -> sb.append("\n[Tool Result for ${p.callId}${if (p.isError) " (ERROR)" else ""}]:\n${p.content}\n")
                            is Part.Image -> sb.append("\n[Attached Image: ${p.width}x${p.height}]\n")
                            else -> {}
                        }
                    }
                    sb.append("\n")
                }
                Role.Assistant -> {
                    sb.append("Assistant: ")
                    for (p in m.parts) {
                        when (p) {
                            is Part.Text -> sb.append(p.text).append("\n")
                            is Part.Thinking -> sb.append("<thinking>${p.text}</thinking>\n")
                            is Part.ToolCall -> sb.append("\n```json\n{\"tool_calls\": [{\"name\": \"${p.name}\", \"arguments\": ${p.argsJson}}]}\n```\n")
                            else -> {}
                        }
                    }
                    sb.append("\n")
                }
                Role.Tool -> {
                    for (p in m.parts.filterIsInstance<Part.ToolResult>()) {
                        sb.append("[Tool Result for ${p.callId}${if (p.isError) " (ERROR)" else ""}]:\n${p.content}\n\n")
                    }
                }
            }
        }

        return sb.toString().trim()
    }

    private fun isConversational(messages: List<Message>): Boolean {
        if (messages.any { it.role == Role.Tool || it.parts.any { p -> p is Part.ToolCall || p is Part.ToolResult } }) {
            return false
        }
        val lastUserMsg = messages.lastOrNull { it.role == Role.User } ?: return false
        val userText = lastUserMsg.parts.filterIsInstance<Part.Text>().joinToString(" ") { it.text }.trim().lowercase()
        if (userText.isEmpty()) return false

        val greetings = setOf(
            "hi", "hello", "hey", "hii", "hiii", "hola", "namaste", "pranam", "kya haal hai", "kaise ho",
            "kaise ho aap", "how are you", "who are you", "what is your name", "what's your name",
            "what is your model name", "what model are you", "who made you", "what can you do", "help",
            "good morning", "good evening", "good afternoon", "good night", "thanks", "thank you", "shukriya"
        )
        val cleaned = userText.replace(Regex("""[^\w\s]"""), "").trim()
        if (cleaned in greetings) return true
        // "hi mina", "hello there": still a greeting, not a task.
        val words = cleaned.split(Regex("""\s+"""))
        if (words.firstOrNull() in GREETING_WORDS && words.size <= 3) return true
        // Anything else keeps the tools. The old "six words or fewer" rule
        // treated short real requests ("weather batao", "kal ka mausam") as chat
        // and silently removed every tool, so the model could not act at all.
        return false
    }

    private companion object {
        val GREETING_WORDS = setOf("hi", "hii", "hiii", "hello", "hey", "hola", "namaste", "pranam")
    }
}
