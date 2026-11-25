package com.meenai.mina.core.cap.web.cdp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** protocol event. [sessionId] non-empty indicates subsessions from flattened attachments (`Target.setAutoAttach` and `flatten=true`). */
data class CdpEvent(val method: String, val params: JsonObject, val sessionId: String? = null)

/** The peer returned an error object for a certain call. */
class CdpException(val code: Int, val errorMessage: String, val method: String, val data: String? = null) :
    RuntimeException("$method failed ($code): $errorMessage" + (data?.let { " — $it" } ?: ""))

class CdpTimeoutException(val method: String, val timeoutMs: Long) : IOException("$method timed out after ${timeoutMs}ms")

/** The connection has been disconnected or closed; any calls that have not been returned when disconnected will fail. */
class CdpConnectionClosedException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * A JSON-RPC session over DevTools WebSocket. A reading coroutine is responsible for receiving the
 * packet: returning the response by id, and sending the rest as events; sending it on the IO thread, and
 * writing the frame serially. The same connection can have multiple flat sub-sessions, distinguished by
 * sessionId.
 *
 * The event stream is not replayed, and events are discarded directly when there are no subscribers;
 * [eventBuffer] items are buffered, and the oldest ones are discarded when subscribers cannot keep up.
 * The read coroutine is never blocked by events (otherwise the response will also be stuck). Subscribers
 * who require a complete sequence of events must consume in time and start subscribing before issuing
 * the command that triggers the event.
 */
class CdpSession(
    private val ws: WebSocketConnection,
    eventBuffer: Int = DEFAULT_EVENT_BUFFER,
) : Closeable {
    private class Pending(val method: String, val result: CompletableDeferred<JsonObject>)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<Int, Pending>()
    private val nextId = AtomicInteger(1)
    @Volatile private var closeError: CdpConnectionClosedException? = null

    private val _events = MutableSharedFlow<CdpEvent>(extraBufferCapacity = eventBuffer, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<CdpEvent> = _events.asSharedFlow()

    /**
     * The ratio of input field (mouse, drag and drop) coordinates to CSS pixels: emitted coordinates =
     * CSS pixels × it. Usually it is 1; when the viewport simulation is zoomed (desktop-width page is
     * indented into the mobile view), the Input is interpreted according to the view coordinates before
     * zooming, and will be changed by the party setting up the simulation.
     */
    @Volatile var inputScale: Double = 1.0

    /** Where the last mouse event landed (CSS pixel); it has not been sent yet and is null. The next move follows the trajectory from here. */
    @Volatile var pointer: Pair<Double, Double>? = null

    private val _closed = MutableStateFlow(false)
    val closed: StateFlow<Boolean> = _closed.asStateFlow()

    private val reader = scope.launch { readLoop() }

    suspend fun send(
        method: String,
        params: JsonObject = EMPTY,
        sessionId: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): JsonObject {
        closeError?.let { throw CdpConnectionClosedException(it.message ?: "closed", it.cause) }
        val id = nextId.getAndIncrement()
        val p = Pending(method, CompletableDeferred())
        pending[id] = p
        // The reading coroutine first sets closeError and then clears pending: put it here and check it
        // again to avoid missing it.
        closeError?.let {
            pending.remove(id)
            throw CdpConnectionClosedException(it.message ?: "closed", it.cause)
        }
        val msg = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("method", JsonPrimitive(method))
            put("params", params)
            if (sessionId != null) put("sessionId", JsonPrimitive(sessionId))
        }.toString()
        try {
            // There is also an upper limit for writing: when the peer does not read and the buffer is
            // full, the connection is disconnected at a certain point to prevent the caller from being
            // stuck in writing.
            withStreamDeadline(timeoutMs, method, open = { Closeable { ws.abort() } }, closeOnCancel = false) { ws.sendText(msg) }
        } catch (e: IOException) {
            pending.remove(id)
            ws.abort()
            throw CdpConnectionClosedException("CDP connection lost while sending $method", e)
        }
        try {
            return withTimeoutOrNull(timeoutMs) { p.result.await() } ?: throw CdpTimeoutException(method, timeoutMs)
        } finally {
            pending.remove(id)
        }
    }

    /** Send a close frame and disconnect; outstanding calls fail immediately. */
    override fun close() {
        if (closeError != null) return
        shutdown(CdpConnectionClosedException("CDP session closed"))
        runCatching { ws.sendClose() }
        // Give the peer some time to close the frame, and then hard disconnect
        scope.launch {
            withTimeoutOrNull(CLOSE_GRACE_MS) { reader.join() }
            ws.abort()
            scope.cancel()
        }
    }

    private fun readLoop() {
        var cause: Throwable? = null
        try {
            while (true) {
                val text = ws.receive() ?: break
                val obj = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: continue
                val id = (obj["id"] as? JsonPrimitive)?.intOrNull
                if (id != null) {
                    val p = pending[id] ?: continue
                    val error = obj["error"] as? JsonObject
                    if (error != null) {
                        p.result.completeExceptionally(
                            CdpException(
                                code = error["code"]?.jsonPrimitive?.intOrNull ?: 0,
                                errorMessage = error["message"]?.jsonPrimitive?.contentOrNull ?: "",
                                method = p.method,
                                data = error["data"]?.let { (it as? JsonPrimitive)?.contentOrNull ?: it.toString() },
                            ),
                        )
                    } else {
                        p.result.complete(obj["result"] as? JsonObject ?: EMPTY)
                    }
                    continue
                }
                val method = (obj["method"] as? JsonPrimitive)?.contentOrNull ?: continue
                _events.tryEmit(
                    CdpEvent(method, obj["params"] as? JsonObject ?: EMPTY, (obj["sessionId"] as? JsonPrimitive)?.contentOrNull),
                )
            }
        } catch (t: Throwable) {
            cause = t
        }
        val reason = when {
            closeError != null -> null
            ws.closeCode != null -> "CDP connection closed by peer (${ws.closeCode}${ws.closeReason.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""})"
            else -> "CDP connection lost: ${cause?.message ?: "end of stream"}"
        }
        if (reason != null) shutdown(CdpConnectionClosedException(reason, cause))
        ws.abort()
    }

    private fun shutdown(error: CdpConnectionClosedException) {
        synchronized(this) {
            if (closeError != null) return
            closeError = error
        }
        for (id in pending.keys.toList()) {
            pending.remove(id)?.result?.completeExceptionally(
                CdpConnectionClosedException(error.message ?: "closed", error.cause),
            )
        }
        _closed.value = true
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val DEFAULT_EVENT_BUFFER = 1024
        private const val CLOSE_GRACE_MS = 1_000L
        private val EMPTY = JsonObject(emptyMap())
        private val json = Json { ignoreUnknownKeys = true }

        /** Open the transport, do the WebSocket handshake, and return to the session. [path] is taken from the target's webSocketDebuggerUrl. Throw [CdpTimeoutException] when timeout. */
        suspend fun connect(
            transport: CdpTransport,
            endpoint: String,
            path: String,
            host: String = CdpDiscovery.DEFAULT_HOST,
            maxMessageBytes: Int = WebSocketConnection.DEFAULT_MAX_MESSAGE_BYTES,
            eventBuffer: Int = DEFAULT_EVENT_BUFFER,
            handshakeTimeoutMs: Long = CdpDiscovery.DEFAULT_TIMEOUT_MS,
        ): CdpSession {
            // Handshake at most [handshakeTimeoutMs]: The stream is closed at this point, and the stuck
            // handshake fails.
            val ws = withStreamDeadline(handshakeTimeoutMs, "WebSocket handshake $path", open = { transport.open(endpoint) }) { s ->
                WebSocketConnection.connect(s, path, host, maxMessageBytes)
            }
            return CdpSession(ws, eventBuffer)
        }
    }
}
