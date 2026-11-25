package com.meenai.mina.core.cap.web.cdp

import com.meenai.mina.core.cap.web.DEVTOOLS_UNAVAILABLE
import com.meenai.mina.core.cap.web.EVAL_RESULT_MAX_CHARS
import com.meenai.mina.core.cap.web.EvalJs
import com.meenai.mina.core.cap.web.ReadScriptProtocol
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A tag reading script (see [ReadScriptProtocol]): runs in the main frame's reading world, one at a
 * time. Read the world and build it once and keep it (state is retained across calls). If the page is
 * changed and the document is changed, it will be lost. Build it again next time (fresh=true in the
 * result). Bottom line: Any network request the script makes while running or the main frame jumps
 * (first `Page.stopLoading`) will end with an error. When the time limit is reached, let the sleep after
 * the script fail first; even this can't wait for a response (the synchronization infinite loop occupies
 * the page) before interrupting the execution.
 */
class ReadScriptRunner(val session: CdpSession, private val sessionId: String? = null) {
    private val lock = Mutex()
    private val worldName = ReadScriptProtocol.WORLD_PREFIX + UUID.randomUUID()
    @Volatile private var context: Int? = null

    private class World(val frameId: String, val context: Int, val origin: String?, val fresh: Boolean)

    /** When the connection is broken, throw [CdpConnectionClosedException](it is up to the caller to determine whether the label is broken). */
    suspend fun run(code: String, args: JsonObject?, timeoutMs: Long): EvalJs = lock.withLock {
        coroutineScope {
            val outcome = CompletableDeferred<EvalJs>()
            val running = AtomicBoolean(false)
            val answered = AtomicBoolean(false)
            var world: World? = null

            // Subscribe first and act later: event stream does not replay
            launch(start = CoroutineStart.UNDISPATCHED) {
                session.events.collect { e ->
                    if (e.sessionId != sessionId) return@collect
                    val w = world ?: return@collect
                    val p = e.params
                    when (e.method) {
                        "Runtime.executionContextDestroyed" -> if ((p["executionContextId"] as? JsonPrimitive)?.intOrNull == w.context) gone(outcome)
                        "Runtime.executionContextsCleared" -> gone(outcome)
                        // There is no context destruction event when Runtime is not enabled: if the main
                        // frame changes the document, the world will be gone.
                        "Page.frameNavigated" -> if (mainFrameNavigated(p, w.frameId)) gone(outcome)
                        "Network.requestWillBeSent" -> if (running.get()) ReadScriptProtocol.requestViolation(p)?.let {
                            outcome.complete(EvalJs.Failed(BLOCKED_NETWORK, it))
                        }
                        "Page.frameRequestedNavigation" -> if (running.get() && (p["frameId"] as? JsonPrimitive)?.contentOrNull == w.frameId) {
                            launch { runCatching { session.send("Page.stopLoading", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS) } }
                            outcome.complete(EvalJs.Failed(BLOCKED_NAVIGATION,
                                "the page tried to navigate to ${(p["url"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(200)} while the read script ran; it was stopped. Read scripts cannot navigate: use web_navigate or web_tap"))
                        }
                    }
                }
            }

            try {
                val w = try {
                    ensureWorld()
                } catch (e: CdpConnectionClosedException) {
                    throw e
                } catch (e: CdpTimeoutException) {
                    return@coroutineScope EvalJs.Failed(DEVTOOLS_UNAVAILABLE, "the page did not answer in time")
                } catch (e: CdpException) {
                    return@coroutineScope EvalJs.Failed(DEVTOOLS_UNAVAILABLE, "could not prepare the page for read scripts: ${e.errorMessage}")
                }
                world = w
                running.set(true)
                launch {
                    val r = try {
                        session.send("Runtime.evaluate", buildJsonObject {
                            put("expression", ReadScriptProtocol.run(code, args, EVAL_RESULT_MAX_CHARS + 1)); put("contextId", w.context)
                            put("awaitPromise", true); put("returnByValue", true)
                        }, sessionId, timeoutMs + STEP_TIMEOUT_MS)
                    } catch (e: CdpTimeoutException) {
                        outcome.complete(EvalJs.Failed(TIMEOUT, "the script did not finish within $timeoutMs ms")); return@launch
                    } catch (e: CdpConnectionClosedException) {
                        outcome.completeExceptionally(e); return@launch
                    } catch (e: CdpException) {
                        if (CONTEXT_GONE.containsMatchIn(e.errorMessage)) gone(outcome) else outcome.complete(EvalJs.Failed("script_error", e.errorMessage))
                        return@launch
                    }
                    answered.set(true)
                    val details = r["exceptionDetails"] as? JsonObject
                    outcome.complete(if (details != null) EvalJs.Failed("script_error", exceptionText(details))
                        else ReadScriptProtocol.result((r["result"] as? JsonObject)?.get("value") as? JsonObject, w.fresh))
                }
                launch {
                    delay(timeoutMs)
                    outcome.complete(EvalJs.Failed(TIMEOUT, "the script did not finish within $timeoutMs ms"))
                }
                outcome.await()
            } finally {
                running.set(false)
                val w = world
                withContext(NonCancellable) {
                    if (w != null && !answered.get() && context == w.context) stop(w.context)
                }
                coroutineContext.cancelChildren()
            }
        }
    }

    private fun gone(outcome: CompletableDeferred<EvalJs>) {
        context = null
        outcome.complete(EvalJs.Failed(NAVIGATED, "the page loaded a new document while the script ran, so the script stopped and its state is gone; read the new page"))
    }

    /** The script ends before it has finished running: first let the subsequent sleep fail; it does not wait until the response is received and it is running in a synchronous infinite loop before interrupting. */
    private suspend fun stop(ctx: Int) {
        val answeredStop = try {
            session.send("Runtime.evaluate", buildJsonObject { put("expression", ReadScriptProtocol.STOP); put("contextId", ctx) }, sessionId, STEP_TIMEOUT_MS)
            true
        } catch (e: CdpTimeoutException) {
            false
        } catch (e: Exception) {
            true
        }
        if (!answeredStop) runCatching { session.send("Runtime.terminateExecution", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS) }
    }

    /** Use the remaining read world if it is still there, build one and install the pre-script when it is not (or for the first time). */
    private suspend fun ensureWorld(): World {
        // Do not turn on Runtime: when it is turned on, the page can detect the debugger (some websites
        // are always reloaded because of this); it is not needed for isolation of the world and
        // evaluation. Events to look at: jump requests, network requests (it doesn't hurt to open it
        // again if it is already turned on)
        session.send("Page.enable", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS)
        session.send("Network.enable", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS)
        val frameId = session.send("Page.getFrameTree", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS)["frameTree"]
            ?.jsonObject?.get("frame")?.jsonObject?.get("id")?.jsonPrimitive?.content
            ?: throw CdpException(0, "no main frame", "Page.getFrameTree")
        context?.let { c -> probe(c)?.let { origin -> return World(frameId, c, origin, fresh = false) } }
        context = null
        val ctx = session.send("Page.createIsolatedWorld", buildJsonObject {
            put("frameId", frameId); put("worldName", worldName)
            // This is how the parameter names in the protocol are spelled.
            put("grantUniveralAccess", false)
        }, sessionId, STEP_TIMEOUT_MS)["executionContextId"]?.jsonPrimitive?.intOrNull
            ?: throw CdpException(0, "no execution context", "Page.createIsolatedWorld")
        val r = session.send("Runtime.evaluate", buildJsonObject {
            put("expression", ReadScriptProtocol.PRELUDE); put("contextId", ctx); put("returnByValue", true)
        }, sessionId, STEP_TIMEOUT_MS)
        (r["exceptionDetails"] as? JsonObject)?.let { throw CdpException(0, exceptionText(it), "Runtime.evaluate") }
        val origin = probe(ctx) ?: throw CdpException(0, "the read world did not start", "Runtime.evaluate")
        context = ctx
        return World(frameId, ctx, origin, fresh = true)
    }

    /** When the world is loaded, it is read as the source of the page; when the world is gone or not installed, it is null. */
    private suspend fun probe(ctx: Int): String? = try {
        val r = session.send("Runtime.evaluate", buildJsonObject {
            put("expression", ReadScriptProtocol.PROBE); put("contextId", ctx); put("returnByValue", true)
        }, sessionId, STEP_TIMEOUT_MS)
        ((r["result"] as? JsonObject)?.get("value") as? JsonPrimitive)?.takeIf { it.isString }?.content
    } catch (e: CdpException) {
        null
    }

    private fun exceptionText(details: JsonObject): String {
        val exception = details["exception"] as? JsonObject
        val description = (exception?.get("description") as? JsonPrimitive)?.contentOrNull
        val text = (details["text"] as? JsonPrimitive)?.contentOrNull
        return description?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: text ?: "script failed"
    }

    private companion object {
        const val STEP_TIMEOUT_MS = 3_000L
        const val TIMEOUT = "timeout"
        const val NAVIGATED = "navigated"
        const val BLOCKED_NETWORK = "blocked_network"
        const val BLOCKED_NAVIGATION = "blocked_navigation"
        val CONTEXT_GONE = Regex("context|destroyed|Inspected target navigated", RegexOption.IGNORE_CASE)
    }
}
