package com.meenai.mina.cap.web.cdp

import com.meenai.mina.core.cap.web.cdp.CdpException
import com.meenai.mina.core.cap.web.cdp.CdpSession
import com.meenai.mina.core.cap.web.cdp.CdpTimeoutException
import com.meenai.mina.core.cap.web.cdp.mainFrameNavigated
import com.meenai.mina.core.cap.web.DEVTOOLS_UNAVAILABLE
import com.meenai.mina.core.cap.web.PageScriptProtocol
import com.meenai.mina.core.cap.web.ScriptAbort
import com.meenai.mina.core.cap.web.ScriptBridge
import com.meenai.mina.core.cap.web.ScriptCall
import com.meenai.mina.core.cap.web.ScriptRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Create an isolated world and run the page script (web_script) in the main frame of a tag. action
 * path), and the result is returned in the same world with `Runtime.evaluate`. Bottom line: The network
 * request initiated by the script and the jump of the main frame when there is no mina action (the
 * latter starts with `Page.stopLoading`) all cause the script to end with an error. A full page jump
 * will destroy the quarantine world and the script will stop there (returning to [ScriptRun.Navigated]).
 * The time limit only counts the script's own time: the clock does not run while Mina is doing an action
 * (including waiting for the lock screen level); when the time limit is reached, wait for the action in
 * hand to be completed before finishing. [preflight] Check the tag before each mina call (taking over,
 * crashing), and end the script if it is not null.
 */
class PageScriptRunner(
    private val session: CdpSession,
    private val sessionId: String? = null,
    private val preflight: suspend () -> ScriptRun.Failed? = { null },
) {

    suspend fun run(code: String, timeoutMs: Long, bridge: ScriptBridge): ScriptRun = coroutineScope {
        val world = PageScriptProtocol.WORLD_PREFIX + UUID.randomUUID()
        val outcome = CompletableDeferred<ScriptRun>()
        val calls = Channel<ScriptCall>(Channel.UNLIMITED)
        // There is an action being performed while holding it; you must wait for it to finish before
        // finishing, and the action cannot be interrupted in the middle.
        val busy = Mutex()
        val context = AtomicInteger(-1)
        val gone = AtomicBoolean(false)
        val mainFrame = AtomicReference<String?>(null)
        val reader = AtomicInteger(-1)

        // Subscribe first and then build the world: the event stream does not replay
        launch(start = CoroutineStart.UNDISPATCHED) {
            session.events.collect { e ->
                if (e.sessionId != sessionId) return@collect
                val p = e.params
                when (e.method) {
                    "Runtime.executionContextDestroyed" -> if (p.int("executionContextId") == context.get()) gone.set(true)
                    "Runtime.executionContextsCleared" -> gone.set(true)
                    // There is no context destruction event when Runtime is not enabled: if the main
                    // frame changes the document, the world will be gone.
                    "Page.frameNavigated" -> if (mainFrameNavigated(p, mainFrame.get())) gone.set(true)
                    "Network.requestWillBeSent" -> if (context.get() >= 0 && PageScriptProtocol.requestFromScript(p)) {
                        outcome.complete(ScriptRun.Failed(BLOCKED_NETWORK, "the script sent a network request (${p.requestUrl()}); scripts may only read the page"))
                    }
                    "Page.frameRequestedNavigation" -> {
                        val frame = mainFrame.get() ?: return@collect
                        if (context.get() >= 0 && !outcome.isCompleted && PageScriptProtocol.navigationOutsideAction(p, frame, busy.isLocked)) {
                            launch { runCatching { session.send("Page.stopLoading", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS) } }
                            outcome.complete(ScriptRun.Failed(BLOCKED_NAVIGATION, "the page tried to navigate to ${p.str("url").orEmpty()} while no mina action was running; it was stopped. Use mina.navigate or mina.tap to move between pages"))
                        }
                    }
                }
            }
        }

        try {
            val contextId = try {
                setUp(world).let { w -> mainFrame.set(w.frameId); reader.set(w.reader); context.set(w.script); w.script }
            } catch (e: CdpException) {
                return@coroutineScope ScriptRun.Failed(DEVTOOLS_UNAVAILABLE, "could not prepare the page for scripts: ${e.errorMessage}")
            } catch (e: CdpTimeoutException) {
                return@coroutineScope ScriptRun.Failed(DEVTOOLS_UNAVAILABLE, "the page did not answer in time")
            }

            // There is always a pickup: the mina call of the script is returned one by one; the ending
            // (__minaEnd) returns null, the world is gone, the evaluation fails, and everything stops
            // here
            launch {
                while (!outcome.isCompleted) {
                    val r = try {
                        session.send("Runtime.evaluate", buildJsonObject {
                            put("expression", PageScriptProtocol.TAKE); put("contextId", contextId)
                            put("awaitPromise", true); put("returnByValue", true)
                        }, sessionId, Long.MAX_VALUE)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        break
                    }
                    val payload = ((r["result"] as? JsonObject)?.get("value") as? JsonPrimitive)?.takeIf { it.isString }?.content ?: break
                    val call = PageScriptProtocol.parseCall(payload) ?: continue
                    if (call.op == "log") call.args.str("text")?.let(bridge::log) else calls.trySend(call)
                }
            }

            launch {
                outcome.complete(try {
                    // No upper limit: the time limit is managed by the watch below (the watch does not
                    // run while the action is being performed)
                    val r = session.send("Runtime.evaluate", buildJsonObject {
                        put("expression", PageScriptProtocol.run(code)); put("contextId", contextId)
                        put("awaitPromise", true); put("returnByValue", true)
                    }, sessionId, Long.MAX_VALUE)
                    val details = r["exceptionDetails"] as? JsonObject
                    when {
                        gone.get() -> ScriptRun.Navigated
                        details != null -> PageScriptProtocol.error(details).let { (m, line) -> ScriptRun.Failed("script_error", m, line) }
                        else -> ScriptRun.Done((r["result"] as? JsonObject)?.get("value")?.let { it as? JsonPrimitive }?.takeIf { it.isString }?.content)
                    }
                } catch (e: CdpException) {
                    // The world jumps with the whole page and the Promise of the script is gone.
                    if (gone.get() || CONTEXT_GONE.containsMatchIn(e.errorMessage)) ScriptRun.Navigated else ScriptRun.Failed("script_error", e.errorMessage)
                })
            }

            launch {
                for (c in calls) {
                    val stop = busy.withLock {
                        if (outcome.isCompleted) return@withLock true
                        preflight()?.let { outcome.complete(it); return@withLock true }
                        val result = try {
                            bridge.call(c.op, c.args).let { r -> (r["read"] as? JsonObject)?.let { readPage(reader.get(), it) } ?: r }
                        } catch (a: ScriptAbort) {
                            outcome.complete(ScriptRun.Failed(a.error, a.message.orEmpty()))
                            return@withLock true
                        }
                        if (gone.get()) { outcome.complete(ScriptRun.Navigated); return@withLock true }
                        try {
                            session.send("Runtime.evaluate", buildJsonObject {
                                put("expression", PageScriptProtocol.settle(c.id, result)); put("contextId", contextId)
                            }, sessionId, STEP_TIMEOUT_MS)
                            false
                        } catch (e: CdpException) {
                            outcome.complete(ScriptRun.Navigated)
                            true
                        } catch (e: CdpTimeoutException) {
                            outcome.complete(ScriptRun.Failed("timeout", "the page stopped answering"))
                            true
                        }
                    }
                    if (stop) break
                }
            }

            // Only run the watch when there is no action being done
            launch {
                var left = timeoutMs
                while (left > 0) {
                    delay(TICK_MS)
                    if (!busy.isLocked) left -= TICK_MS
                }
                outcome.complete(ScriptRun.Failed("timeout", "the script did not finish within $timeoutMs ms"))
            }

            val result = outcome.await()
            busy.withLock { }
            result
        } finally {
            withContext(NonCancellable) {
                if (context.get() >= 0 && !gone.get()) {
                    val ended = try {
                        session.send("Runtime.evaluate", buildJsonObject { put("expression", PageScriptProtocol.END); put("contextId", context.get()) }, sessionId, STEP_TIMEOUT_MS)
                        true
                    } catch (e: CdpTimeoutException) {
                        false
                    } catch (e: Exception) {
                        true
                    }
                    // I can't even wait for a response at the end: the script is running in a
                    // synchronization infinite loop, occupying the page. Only interrupt at this time
                    // (not interrupting while running will accidentally damage the next script on the
                    // page)
                    if (!ended) runCatching { session.send("Runtime.terminateExecution", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS) }
                }
            }
            coroutineContext.cancelChildren()
        }
    }

    private class Worlds(val frameId: String, val script: Int, val reader: Int)

    /** In the reading world, read the DOM according to the verified parameters and only get back pure data. */
    private suspend fun readPage(ctx: Int, spec: JsonObject): JsonObject {
        val r = session.send("Runtime.evaluate", buildJsonObject {
            put("expression", PageScriptProtocol.read(spec)); put("contextId", ctx); put("returnByValue", true)
        }, sessionId, STEP_TIMEOUT_MS)
        val v = (r["result"] as? JsonObject)?.get("value") as? JsonObject
        if (r["exceptionDetails"] != null || v == null) return buildJsonObject { put("ok", false); put("error", "read_failed"); put("message", "the page could not be read") }
        if (v.toString().length > READ_RESULT_MAX_CHARS) return buildJsonObject {
            put("ok", false); put("error", "too_large"); put("message", "the result is too large; ask for fewer items (limit), fewer fields or a smaller maxChars")
        }
        return v
    }

    /**
     * Build two isolated worlds: the script world (outbox, `mina`, running model code, cannot get the
     * page object) and the reading world (only Mina fixed reader).
     */
    private suspend fun setUp(world: String): Worlds {
        // Do not turn on Runtime: the page can detect the debugger when it is turned on (some websites
        // are always reloaded because of this); it is not needed for isolation of the world and
        // evaluation. Events to look at: jump requests, network requests (it doesn't hurt to open it
        // again if it is already turned on)
        session.send("Page.enable", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS)
        session.send("Network.enable", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS)
        val frameId = session.send("Page.getFrameTree", JsonObject(emptyMap()), sessionId, STEP_TIMEOUT_MS)["frameTree"]
            ?.jsonObject?.get("frame")?.jsonObject?.get("id")?.jsonPrimitive?.content
            ?: throw CdpException(0, "no main frame", "Page.getFrameTree")
        val ctx = session.send("Page.createIsolatedWorld", buildJsonObject {
            put("frameId", frameId); put("worldName", world)
            // This is how the parameter names in the protocol are spelled.
            put("grantUniveralAccess", false)
        }, sessionId, STEP_TIMEOUT_MS)["executionContextId"]?.jsonPrimitive?.intOrNull
            ?: throw CdpException(0, "no execution context", "Page.createIsolatedWorld")
        install(ctx, PageScriptProtocol.PRELUDE)
        val readerCtx = session.send("Page.createIsolatedWorld", buildJsonObject {
            put("frameId", frameId); put("worldName", PageScriptProtocol.READER_WORLD_PREFIX + world.removePrefix(PageScriptProtocol.WORLD_PREFIX))
            put("grantUniveralAccess", false)
        }, sessionId, STEP_TIMEOUT_MS)["executionContextId"]?.jsonPrimitive?.intOrNull
            ?: throw CdpException(0, "no execution context", "Page.createIsolatedWorld")
        install(readerCtx, PageScriptProtocol.READER)
        return Worlds(frameId, ctx, readerCtx)
    }

    /** If it cannot be installed, the model code will not be run. */
    private suspend fun install(ctx: Int, source: String) {
        val r = session.send("Runtime.evaluate", buildJsonObject {
            put("expression", source); put("contextId", ctx); put("returnByValue", true)
        }, sessionId, STEP_TIMEOUT_MS)
        (r["exceptionDetails"] as? JsonObject)?.let { throw CdpException(0, PageScriptProtocol.error(it).first, "Runtime.evaluate") }
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun JsonObject.int(k: String) = (this[k] as? JsonPrimitive)?.intOrNull
    private fun JsonObject.requestUrl() = ((this["request"] as? JsonObject)?.get("url") as? JsonPrimitive)?.contentOrNull?.take(200).orEmpty()

    private companion object {
        const val STEP_TIMEOUT_MS = 3_000L
        const val TICK_MS = 100L
        const val READ_RESULT_MAX_CHARS = 200_000
        const val BLOCKED_NETWORK = "blocked_network"
        const val BLOCKED_NAVIGATION = "blocked_navigation"
        val CONTEXT_GONE = Regex("context|destroyed|Inspected target navigated", RegexOption.IGNORE_CASE)
    }
}
