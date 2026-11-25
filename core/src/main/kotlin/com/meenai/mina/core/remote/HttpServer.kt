package com.meenai.mina.core.remote

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean

/** An HTTP request. The first name should always be lowercase; [remote] is the address of the other party (current limit and recent connections are calculated according to it). */
class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray,
    val remote: InetAddress,
    val query: String = "",
    /** If the cooldown fails, click it to record it. The local monitoring is the other party's address; what is forwarded by the gateway is the caller's address seen by the gateway, which is recorded separately from the local address. */
    val source: String = remote.hostAddress ?: "?",
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

class HttpResponse(val status: Int, val body: ByteArray = ByteArray(0), val headers: Map<String, String> = emptyMap()) {
    companion object {
        fun json(status: Int, text: String, extra: Map<String, String> = emptyMap()) =
            HttpResponse(status, text.toByteArray(Charsets.UTF_8), mapOf("Content-Type" to "application/json") + extra)

        fun empty(status: Int, extra: Map<String, String> = emptyMap()) = HttpResponse(status, ByteArray(0), extra)
    }
}

/** The request itself does not comply with HTTP rules (too large, wrong format): directly return [status] and disconnect. */
class HttpProtocolException(val status: Int, message: String) : IOException(message)

/**
 * A minimalist HTTP/1.1 service only for computer connections: one port, one thread per connection, an
 * upper limit on the number of connections, and an upper limit on the size of the request header and
 * body. Only recognizes Content-Length and chunked text; does not use TLS (only used in LAN and private
 * networks, and is controlled by access tokens). [accept] determines whether to accept the connection
 * based on the addresses at both ends (if not accepted, it will be closed on the spot without returning
 * any bytes).
 */
class MiniHttpServer(
    private val bind: InetSocketAddress,
    private val handler: (HttpRequest) -> HttpResponse,
    private val accept: (local: InetAddress, remote: InetAddress) -> Boolean = { _, _ -> true },
    private val maxConnections: Int = 16,
    private val readTimeoutMs: Int = 30_000,
    private val onError: (Throwable) -> Unit = {},
) {
    private val running = AtomicBoolean(false)
    private val slots = Semaphore(maxConnections)
    private val open = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<Socket, Boolean>())
    @Volatile private var server: ServerSocket? = null

    /** The actual listening port (selected by the system when 0 is given). */
    val port: Int get() = server?.localPort ?: -1

    /** Bind the port and start collecting connections; if the binding fails, an exception will be thrown (the port is occupied, etc.). */
    @Synchronized fun start() {
        check(!running.get()) { "already running" }
        val s = ServerSocket()
        s.reuseAddress = true
        try { s.bind(bind, 32) } catch (e: IOException) { s.close(); throw e }
        server = s
        running.set(true)
        Thread({ acceptLoop(s) }, "remote-accept").apply { isDaemon = true }.start()
    }

    @Synchronized fun stop() {
        running.set(false)
        runCatching { server?.close() }
        server = null
        open.toList().forEach { runCatching { it.close() } }
        open.clear()
    }

    private fun acceptLoop(s: ServerSocket) {
        while (running.get()) {
            val socket = try { s.accept() } catch (e: IOException) { if (running.get()) onError(e); break }
            if (!accept(socket.localAddress, socket.inetAddress)) { runCatching { socket.close() }; continue }
            if (!slots.tryAcquire()) {
                runCatching { socket.soTimeout = 2_000; write(socket.getOutputStream(), HttpResponse.empty(503), close = true); socket.close() }
                continue
            }
            open += socket
            Thread({
                try { serve(socket) } finally {
                    open -= socket
                    runCatching { socket.close() }
                    slots.release()
                }
            }, "remote-conn").apply { isDaemon = true }.start()
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = readTimeoutMs
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            while (running.get()) {
                val request = try { readRequest(input, socket.inetAddress, output) ?: return } catch (e: HttpProtocolException) {
                    write(output, HttpResponse.empty(e.status), close = true)
                    return
                }
                val response = try { handler(request) } catch (e: Exception) { onError(e); HttpResponse.empty(500) }
                val close = request.header("connection").equals("close", ignoreCase = true)
                write(output, response, close)
                if (close) return
            }
        } catch (_: SocketException) {
        } catch (_: java.net.SocketTimeoutException) {
        } catch (e: IOException) {
            onError(e)
        }
    }

    companion object {
        const val MAX_HEAD = 16 * 1024
        const val MAX_BODY = 256 * 1024

        /**
         * Read a request; returns null if the other party closed the connection before the request
         * started. When the other party first asks if the text can be sent (`Expect: 100-continue`, this
         * happens when the command line tool sends a slightly larger text) and gives [output], answer
         * "continue" first to avoid waiting.
         */
        fun readRequest(input: InputStream, remote: InetAddress, output: OutputStream? = null): HttpRequest? {
            val head = readHead(input) ?: return null
            val lines = head.split("\r\n")
            val parts = lines.first().split(' ')
            if (parts.size != 3 || !parts[2].startsWith("HTTP/1.")) throw HttpProtocolException(400, "bad request line")
            val headers = HashMap<String, String>()
            for (line in lines.drop(1)) {
                if (line.isEmpty()) continue
                val colon = line.indexOf(':')
                if (colon <= 0) throw HttpProtocolException(400, "bad header")
                val name = line.substring(0, colon).trim().lowercase()
                val value = line.substring(colon + 1).trim()
                // Headers with the same name are merged; Content-Length is repeated and not broken at
                // the same time.
                headers[name] = headers[name]?.let { old ->
                    if (name == "content-length" && old != value) throw HttpProtocolException(400, "conflicting length")
                    if (name == "content-length") old else "$old, $value"
                } ?: value
            }
            if (output != null && headers["expect"].equals("100-continue", ignoreCase = true)) {
                output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                output.flush()
            }
            val body = when {
                headers["transfer-encoding"]?.lowercase()?.contains("chunked") == true -> readChunked(input)
                headers["content-length"] != null -> {
                    val n = headers["content-length"]!!.toLongOrNull() ?: throw HttpProtocolException(400, "bad length")
                    if (n < 0) throw HttpProtocolException(400, "bad length")
                    if (n > MAX_BODY) throw HttpProtocolException(413, "body too large")
                    readExactly(input, n.toInt())
                }
                else -> ByteArray(0)
            }
            val target = parts[1]
            return HttpRequest(parts[0], target.substringBefore('?'), headers, body, remote, target.substringAfter('?', ""))
        }

        private fun readHead(input: InputStream): String? {
            val buf = ByteArrayOutputStream()
            var matched = 0
            val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
            while (matched < 4) {
                val b = input.read()
                if (b < 0) {
                    if (buf.size() == 0) return null
                    throw HttpProtocolException(400, "truncated head")
                }
                // Extra blank lines between requests are ignored.
                if (buf.size() == 0 && (b == '\r'.code || b == '\n'.code)) continue
                buf.write(b)
                if (buf.size() > MAX_HEAD) throw HttpProtocolException(431, "head too large")
                matched = if (b.toByte() == end[matched]) matched + 1 else if (b.toByte() == end[0]) 1 else 0
            }
            return String(buf.toByteArray(), 0, buf.size() - 4, Charsets.ISO_8859_1)
        }

        private fun readExactly(input: InputStream, n: Int): ByteArray {
            val out = ByteArray(n)
            var off = 0
            while (off < n) {
                val r = input.read(out, off, n - off)
                if (r < 0) throw HttpProtocolException(400, "truncated body")
                off += r
            }
            return out
        }

        private fun readLine(input: InputStream): String {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) throw HttpProtocolException(400, "truncated chunk")
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
                if (sb.length > 1024) throw HttpProtocolException(400, "chunk line too long")
            }
        }

        private fun readChunked(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            while (true) {
                val size = readLine(input).substringBefore(';').trim().toIntOrNull(16) ?: throw HttpProtocolException(400, "bad chunk")
                if (size < 0 || out.size() + size > MAX_BODY) throw HttpProtocolException(413, "body too large")
                if (size == 0) {
                    while (readLine(input).isNotEmpty()) Unit
                    return out.toByteArray()
                }
                out.write(readExactly(input, size))
                readLine(input)
            }
        }

        private val REASONS = mapOf(
            200 to "OK", 201 to "Created", 202 to "Accepted", 400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden",
            404 to "Not Found", 405 to "Method Not Allowed", 406 to "Not Acceptable", 409 to "Conflict", 413 to "Payload Too Large",
            415 to "Unsupported Media Type", 429 to "Too Many Requests", 431 to "Request Header Fields Too Large",
            500 to "Internal Server Error", 503 to "Service Unavailable",
        )

        fun write(output: OutputStream, r: HttpResponse, close: Boolean) {
            val head = StringBuilder("HTTP/1.1 ${r.status} ${REASONS[r.status] ?: "Status"}\r\n")
            r.headers.forEach { (k, v) -> head.append(k).append(": ").append(v).append("\r\n") }
            head.append("Content-Length: ").append(r.body.size).append("\r\n")
            head.append("Cache-Control: no-store\r\n")
            if (close) head.append("Connection: close\r\n")
            head.append("\r\n")
            output.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            output.write(r.body)
            output.flush()
        }
    }
}
