package com.meenai.mina.core.oauth

import com.meenai.mina.core.adb.OwnListeningPorts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLDecoder

/**
 * The local small service called by the login callback (shared with ChatGPT and OpenRouter login): only
 * bind 127.0.0.1 (other devices cannot connect), and the system selects an idle port. After logging in,
 * the browser jumps back to `http://127.0.0.1:<port>/auth/callback?...` (or written as localhost), where the
 * parameters are read, and the login is completed (token exchange, verification) and then a page of
 * results is returned; during the process, the browser is waiting for this connection. Other paths (such
 * as browser icons) return 404. Only one callback is collected; [close] is required to use it up or give
 * up.
 */
class LoopbackCallback private constructor(private val server: ServerSocket) : Closeable {
    val port: Int get() = server.localPort

    // When looking for the wireless debugging port, scan this machine. Don’t scan your own monitor.
    init { OwnListeningPorts.add(server.localPort) }
    val redirectUri: String get() = redirectUri(HOST)

    /** The callback address is written as [host] (`127.0.0.1` or `localhost`, both refer to this machine): some services only recognize one of these writing methods. */
    fun redirectUri(host: String): String = "http://$host:$port$PATH"

    /**
     * Waiting for callback, up to [timeoutMs]; timeout is null. After receiving the callback, first call
     * [page] (can hang: complete the login inside), and the web page it gives will be returned to the
     * browser, so the final result can be written on the web page. Return immediately when the coroutine
     * is canceled (the connection is closed). [accept] If the callback is not recognized (such as the
     * state does not match: it was not initiated by this login), it will return 400, and then wait.
     */
    suspend fun await(
        timeoutMs: Long,
        accept: (Map<String, String>) -> Boolean = { true },
        page: suspend (Map<String, String>) -> String,
    ): Map<String, String>? = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        server.soTimeout = POLL_MS
        while (System.currentTimeMillis() < deadline) {
            ensureActive()
            val socket = try { server.accept() } catch (e: SocketTimeoutException) { continue } catch (e: IOException) { return@withContext null }
            socket.use { s ->
                s.soTimeout = READ_TIMEOUT_MS
                val reader = s.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                val requestLine = try { reader.readLine() } catch (e: IOException) { null } ?: return@use
                // Read the request header
                while (true) {
                    val line = try { reader.readLine() } catch (e: IOException) { null }
                    if (line.isNullOrEmpty()) break
                }
                val parts = requestLine.split(' ')
                val target = parts.getOrNull(1).orEmpty()
                val path = target.substringBefore('?')
                if (parts.firstOrNull() != "GET" || path != PATH) {
                    respond(s.getOutputStream(), 404, "Not Found", "text/plain", "Not Found")
                    return@use
                }
                // Escape badly written characters (such as % for placing orders) and treat them as not
                // this callback.
                val params = runCatching { parseQuery(target.substringAfter('?', "")) }.getOrNull()
                if (params == null || !accept(params)) {
                    respond(s.getOutputStream(), 400, "Bad Request", "text/plain", "Bad Request")
                    return@use
                }
                respond(s.getOutputStream(), 200, "OK", "text/html; charset=utf-8", page(params))
                return@withContext params
            }
        }
        null
    }

    override fun close() {
        OwnListeningPorts.remove(server.localPort)
        runCatching { server.close() }
    }

    private fun respond(out: java.io.OutputStream, code: Int, reason: String, type: String, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        runCatching {
            out.write(head.toByteArray(Charsets.ISO_8859_1))
            out.write(bytes)
            out.flush()
        }
    }

    companion object {
        const val HOST = "127.0.0.1"
        const val PATH = "/auth/callback"
        private const val POLL_MS = 300
        private const val READ_TIMEOUT_MS = 5_000

        /** Open a monitor; when [port] is 0, it is picked by the system. */
        fun open(port: Int = 0): LoopbackCallback = LoopbackCallback(ServerSocket(port, 4, InetAddress.getByName(HOST)))

        fun parseQuery(query: String): Map<String, String> = query.split('&').filter { it.isNotEmpty() }.associate { kv ->
            val k = kv.substringBefore('=')
            val v = kv.substringAfter('=', "")
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
    }
}
