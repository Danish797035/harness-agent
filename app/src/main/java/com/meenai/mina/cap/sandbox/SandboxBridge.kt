package com.meenai.mina.cap.sandbox

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections
import kotlin.concurrent.thread

private const val NAME_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
private const val MAX_CONNECTIONS = 4
/** Clients that do not speak when connected, or stop mid-sentence, will be disconnected after 30 seconds. */
private const val CLIENT_TIMEOUT_MS = 30_000
private const val WAKE_DEADLINE_MS = 1_000L
private const val WAKE_RETRY_MS = 50L
private const val TAG = "SandboxBridge"

/**
 * Sandbox command callback Mina's bridge: Each time it runs, a Unix socket (with a random name) in the
 * abstract namespace is opened and a one-time token is given to the command via environment variables.
 * It is closed when the command ends (including timeout, killing, and cancellation). The network
 * disconnection filter only blocks AF_INET/AF_INET6, so the bridge can still be used when the network is
 * disconnected. Only accept connections with the same identity (uid) as Mina; see [BridgeProtocol] for
 * the protocol.
 */
class SandboxBridge(
    private val onProgress: (String) -> Unit,
    private val onShare: suspend (sandboxPath: String, name: String?) -> String,
    private val info: () -> JsonObject = { JsonObject(emptyMap()) },
    private val onPhone: (suspend (String, JsonObject) -> JsonObject)? = null,
) {
    fun open(): Session {
        val rnd = SecureRandom()
        val name = "mina-bridge-" + String(CharArray(16) { NAME_CHARS[rnd.nextInt(NAME_CHARS.length)] })
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(rnd::nextBytes))
        return Session(name, token, LocalServerSocket(name), onProgress, onShare, info, onPhone)
    }

    class Session internal constructor(
        val name: String, val token: String,
        private val server: LocalServerSocket,
        private val onProgress: (String) -> Unit,
        private val onShare: suspend (String, String?) -> String,
        private val info: () -> JsonObject,
        private val onPhone: (suspend (String, JsonObject) -> JsonObject)?,
    ) : Closeable {
        @Volatile private var closed = false
        private val clients = Collections.synchronizedSet(mutableSetOf<LocalSocket>())
        private val gate = ProgressGate()
        /** All exports in progress are here; when the session is closed, they will be canceled together and will not be written further. */
        private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        /** The tool context belongs to the same task, and mobile requests from multiple CLI connections are executed sequentially. */
        private val phoneLock = Mutex()
        private val acceptor = thread(isDaemon = true, name = "mina-bridge-accept") { acceptLoop() }

        private fun acceptLoop() {
            while (!closed) {
                val s = try { server.accept() } catch (e: IOException) { break }
                if (closed) { runCatching { s.close() }; break }
                // Only accept connections with the same identity; reject if there are too many
                // connections (one command does not require many)
                val uid = runCatching { s.peerCredentials.uid }.getOrNull()
                if (uid != Process.myUid() || clients.size >= MAX_CONNECTIONS) { runCatching { s.close() }; continue }
                runCatching { s.soTimeout = CLIENT_TIMEOUT_MS }
                clients += s
                thread(isDaemon = true, name = "mina-bridge-conn") { serve(s) }
            }
        }

        private fun serve(s: LocalSocket) {
            try {
                val input = s.inputStream.buffered()
                val output = s.outputStream
                while (!closed) {
                    val reply = when (val line = BridgeProtocol.readLine(input)) {
                        BridgeProtocol.Line.End -> break
                        BridgeProtocol.Line.TooLarge -> BridgeReply("""{"ok":false,"error":"too_large"}""", close = true)
                        is BridgeProtocol.Line.Text -> runBlocking {
                            BridgeProtocol.handle(line.text, token, ::progress, ::share, info, ::phone) { Log.w(TAG, "bridge request failed", it) }
                        }
                    }
                    output.write((reply.json + "\n").toByteArray(Charsets.UTF_8))
                    output.flush()
                    if (reply.close) break
                }
            } catch (_: IOException) {
                // Peer disconnected or session closed
            } catch (_: CancellationException) {
                // Export in progress canceled when session closed
            } finally {
                clients -= s
                runCatching { s.close() }
            }
        }

        private suspend fun share(path: String, name: String?): String {
            if (closed) throw CancellationException("bridge session closed")
            return work.async { onShare(path, name) }.await()
        }

        private suspend fun phone(tool: String, args: JsonObject): JsonObject {
            if (closed) throw CancellationException("bridge session closed")
            val handler = onPhone ?: return kotlinx.serialization.json.buildJsonObject {
                put("ok", false); put("error", "phone_unavailable")
            }
            return work.async { phoneLock.withLock { handler(tool, args) } }.await()
        }

        private fun progress(text: String): Boolean {
            if (closed || !gate.offer()) return false
            onProgress(text)
            return true
        }

        override fun close() {
            if (closed) return
            closed = true
            work.cancel()
            // accept() does not necessarily return due to close: connect yourself and wake it up (if you
            // can't connect, try again) until the expiration date; close it again
            val deadline = System.currentTimeMillis() + WAKE_DEADLINE_MS
            while (acceptor.isAlive && System.currentTimeMillis() < deadline) {
                runCatching { LocalSocket().use { it.connect(LocalSocketAddress(name)) } }
                acceptor.join(WAKE_RETRY_MS)
            }
            runCatching { server.close() }
            synchronized(clients) { clients.toList() }.forEach { c ->
                runCatching { c.shutdownInput() }
                runCatching { c.close() }
            }
        }
    }
}
