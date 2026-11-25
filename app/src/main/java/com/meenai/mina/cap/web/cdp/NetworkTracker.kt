package com.meenai.mina.cap.web.cdp

import android.util.Log
import com.meenai.mina.core.cap.web.cdp.CdpSession
import com.meenai.mina.core.cap.web.CapturedResponse
import com.meenai.mina.core.cap.web.NetworkLedger
import com.meenai.mina.cap.web.WebTab
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.WeakHashMap

/**
 * Network recording of a tag: Open Network only when it is used for the first time (automatic wait after
 * action, web_capture), subscribe to the `Network.*` event of this tag session and feed it to
 * [NetworkLedger]. It will stop when the session is disconnected or the label is closed. Click on the
 * new session button to recreate it the next time you use it. Only events from one session come in, so
 * there is no need to filter by label; requests for sub-sessions (sessionId is not empty) are recorded
 * separately by session.
 */
class NetworkTracker private constructor(
    private val session: CdpSession,
    private val clock: () -> Long,
) {
    private val ledger = NetworkLedger()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bodyLock = Mutex()
    @Volatile var enabled = false
        private set

    // Subscribe first and then send Network.enable: event stream does not replay
    private suspend fun start(): Boolean {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            session.events.collect { e ->
                if (e.method.startsWith(NETWORK)) synchronized(ledger) { ledger.onEvent(e.method, e.params, clock(), e.sessionId) }
            }
        }
        scope.launch { session.closed.first { it }; close() }
        enabled = try {
            // The text left by the rendering process for getResponseBody also has an upper limit: the
            // individual amount is consistent with the upper limit of the ledger (larger ones are not
            // taken anyway); the total amount is twice that of the ledger. Small resources such as image
            // scripts also occupy this buffer, leaving some room for the interface response to not be
            // squeezed out.
            session.send(
                "Network.enable",
                buildJsonObject {
                    put("maxResourceBufferSize", ledger.maxBodyBytes)
                    put("maxTotalBufferSize", ledger.maxTotalBodyBytes * 2)
                },
                timeoutMs = CALL_TIMEOUT_MS,
            )
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Network.enable failed: ${e.message}")
            false
        }
        if (!enabled) scope.cancel()
        return enabled
    }

    val alive: Boolean get() = enabled && !session.closed.value

    fun markAction() = synchronized(ledger) { ledger.markAction() }

    fun idle(): Boolean = synchronized(ledger) { ledger.isIdle(clock()) }

    /** The request issued by this action is still in progress (or has just ended); the session is considered not busy if it is disconnected. */
    fun actionBusy(): Boolean = alive && synchronized(ledger) { ledger.actionBusy(clock()) }

    /** Wait until the network is idle (no requests in transit and quiet [NetworkLedger.quietMs]), at most [capMs]; if it is not idle then return false. */
    suspend fun awaitIdle(capMs: Long): Boolean {
        val deadline = clock() + capMs
        while (true) {
            if (!alive) return true
            if (idle()) return true
            if (clock() >= deadline) return false
            delay(POLL_MS)
        }
    }

    fun list(urlContains: String?, method: String?, sinceLastAction: Boolean): List<CapturedResponse> =
        synchronized(ledger) { ledger.list(urlContains, method, sinceLastAction) }

    /** Get a response; use `Network.getResponseBody` to retrieve it if there is a body that has not yet been retrieved (if the body cannot be retrieved, the body will not be included). */
    suspend fun get(id: String): CapturedResponse? = bodyLock.withLock {
        val fetch = synchronized(ledger) { ledger.bodyToFetch(id) }
        if (fetch != null && alive) {
            try {
                val r = session.send(
                    "Network.getResponseBody",
                    buildJsonObject { put("requestId", fetch.requestId) },
                    fetch.sessionId,
                    CALL_TIMEOUT_MS,
                )
                val body = (r["body"] as? JsonPrimitive)?.content
                val b64 = (r["base64Encoded"] as? JsonPrimitive)?.boolean == true
                if (body != null) synchronized(ledger) { ledger.putBody(id, body, b64) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The page has lost this response (common after navigation), or the call has timed out:
                // return as usual, without text
                Log.i(TAG, "getResponseBody $id failed: ${e.message}")
            }
        }
        synchronized(ledger) { ledger.get(id) }
    }

    fun close() {
        if (!scope.isActive) return
        val s = session
        if (enabled && !s.closed.value) {
            CoroutineScope(Dispatchers.IO).launch { runCatching { s.send("Network.disable", timeoutMs = CALL_TIMEOUT_MS) } }
        }
        enabled = false
        scope.cancel()
    }

    companion object {
        private const val TAG = "MinaNetTrack"
        private const val NETWORK = "Network."
        private const val POLL_MS = 100L
        private const val CALL_TIMEOUT_MS = 3_000L

        private val trackers = WeakHashMap<WebTab, NetworkTracker>()
        private val creating = Mutex()

        /**
         * The network record of this tag opens Network when it is first called. Null if there is no
         * DevTools session, or if opening fails (the caller takes the old route). If the session is
         * changed (reconnected) or the label is closed, the session will be rebuilt according to the
         * current session.
         */
        suspend fun of(tab: WebTab, clock: () -> Long): NetworkTracker? {
            if (tab.destroyed || tab.crashed) {
                release(tab)
                return null
            }
            val session = tab.devtools.session(tab) ?: return null
            return creating.withLock {
                synchronized(trackers) { trackers[tab] }?.takeIf { it.session === session && it.alive }?.let { return@withLock it }
                synchronized(trackers) { trackers.remove(tab) }?.close()
                val t = NetworkTracker(session, clock)
                if (!t.start()) return@withLock null
                synchronized(trackers) { trackers[tab] = t }
                t
            }
        }

        /** Tag is closed: Stop its recording. */
        fun release(tab: WebTab) {
            synchronized(trackers) { trackers.remove(tab) }?.close()
        }
    }
}
