package com.meenai.mina.core.cap.web.cdp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** A bidirectional byte stream. After closing, both directions end, and the blocked read must be able to return or throw an error. */
interface CdpStream : Closeable {
    val input: InputStream
    val output: OutputStream
}

/**
 * Byte channel to the DevTools endpoint. Endpoints are opaque strings (local abstract socket names,
 * addresses relayed through the shell, etc.), which are interpreted by the implementation itself; each
 * [open] is a new connection. The caller calls on the IO thread and can block.
 */
fun interface CdpTransport {
    fun open(endpoint: String): CdpStream
}

@Serializable
data class CdpTargetInfo(
    val id: String,
    val type: String,
    val url: String = "",
    val title: String = "",
    /** DevTools does not provide this field when there are other clients connected to this target. */
    val webSocketDebuggerUrl: String? = null,
)

@Serializable
data class CdpBrowserVersion(
    @SerialName("Browser") val browser: String = "",
    @SerialName("Protocol-Version") val protocolVersion: String = "",
    @SerialName("User-Agent") val userAgent: String = "",
    val webSocketDebuggerUrl: String? = null,
)

/** DevTools' HTTP discovery interface: `/json/version`, `/json/list`. A single connection is opened for each request. */
object CdpDiscovery {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun version(transport: CdpTransport, endpoint: String, host: String = DEFAULT_HOST, timeoutMs: Long = DEFAULT_TIMEOUT_MS): CdpBrowserVersion =
        parseVersion(get(transport, endpoint, "/json/version", host, timeoutMs))

    suspend fun list(transport: CdpTransport, endpoint: String, host: String = DEFAULT_HOST, timeoutMs: Long = DEFAULT_TIMEOUT_MS): List<CdpTargetInfo> =
        parseList(get(transport, endpoint, "/json/list", host, timeoutMs))

    fun parseVersion(body: String): CdpBrowserVersion = json.decodeFromString(body)

    fun parseList(body: String): List<CdpTargetInfo> = json.decodeFromString(body)

    /** `ws://localhost/devtools/page/ID` → `/devtools/page/ID`; The transport layer does not care about the host, only the path. */
    fun webSocketPath(webSocketUrl: String): String {
        val rest = webSocketUrl.substringAfter("://", webSocketUrl)
        val slash = rest.indexOf('/')
        return if (slash < 0) "/" else rest.substring(slash)
    }

    /** The entire request is at most [timeoutMs]: the connection is closed at this point, and the blocked read and write will return and fail with [CdpTimeoutException]. */
    suspend fun get(transport: CdpTransport, endpoint: String, path: String, host: String = DEFAULT_HOST, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String =
        withStreamDeadline(timeoutMs, "GET $path", open = { transport.open(endpoint) }) { s ->
            s.use {
                s.output.write("GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                s.output.flush()
                val input = s.input.buffered()
                val status = Http.readLine(input) ?: throw IOException("no HTTP response for $path")
                val code = status.split(' ').getOrNull(1)?.toIntOrNull()
                val headers = Http.readHeaders(input)
                val body = when {
                    headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> Http.readChunked(input)
                    headers["content-length"] != null -> {
                        val n = headers.getValue("content-length").trim().toInt()
                        Http.readUpTo(input, n).also { if (it.size < n) throw IOException("HTTP body truncated") }
                    }
                    else -> input.readBytes()
                }
                if (code != 200) throw IOException("GET $path: $status")
                body.toString(Charsets.UTF_8)
            }
        }

    /** DevTools only accepts requests with hostname localhost or IP (anti-DNS rebinding). */
    const val DEFAULT_HOST = "localhost"
    const val DEFAULT_TIMEOUT_MS = 5_000L
}

private val deadlines = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Open the stream on the IO thread and do a blocking read and write, at most [timeoutMs]. When the point
 * is reached (or the caller is canceled, see closeOnCancel), the stream is closed, and the blocked read
 * and write will throw an error and return, which will not keep the caller and the lock it holds stuck.
 * Timeout failed with [CdpTimeoutException]. The blocked section runs in an independent coroutine, and
 * the caller can return without waiting for it to end.
 */
internal suspend fun <S : Closeable, T> withStreamDeadline(
    timeoutMs: Long,
    what: String,
    open: () -> S,
    /** When the caller is canceled, the stream will be closed immediately; when it is false, the reading and writing will be completed by itself (still subject to [timeoutMs] restrictions). */
    closeOnCancel: Boolean = true,
    block: (S) -> T,
): T {
    val fired = AtomicBoolean(false)
    val opened = java.util.concurrent.atomic.AtomicReference<Closeable?>(null)
    val work = deadlines.async {
        val s = open()
        opened.set(s)
        if (fired.get()) {
            runCatching { s.close() }
            throw CdpTimeoutException(what, timeoutMs)
        }
        block(s)
    }
    val watchdog = deadlines.launch {
        delay(timeoutMs)
        fired.set(true)
        opened.get()?.let { runCatching { it.close() } }
    }
    // The watchdog follows this reading and writing, not the caller: the caller left first, and there is
    // still an upper limit for reading and writing.
    work.invokeOnCompletion { watchdog.cancel() }
    try {
        return work.await()
    } catch (e: CancellationException) {
        if (closeOnCancel) opened.get()?.let { runCatching { it.close() } }
        throw e
    } catch (e: IOException) {
        if (fired.get() && e !is CdpTimeoutException) throw CdpTimeoutException(what, timeoutMs)
        throw e
    }
}

internal object Http {
    private const val MAX_LINE = 16 * 1024

    /** Read a line (removing CRLF); return null if the stream ends at the beginning of the line. */
    fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (buf.size() == 0) return null
                throw IOException("unexpected end of stream in header")
            }
            if (b == '\n'.code) break
            buf.write(b)
            if (buf.size() > MAX_LINE) throw IOException("header line too long")
        }
        val s = String(buf.toByteArray(), Charsets.ISO_8859_1)
        return s.removeSuffix("\r")
    }

    /** Head names should always be converted to lowercase. */
    fun readHeaders(input: InputStream): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: throw IOException("unexpected end of stream in headers")
            if (line.isEmpty()) return headers
            val colon = line.indexOf(':')
            if (colon <= 0) throw IOException("bad header line: $line")
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
    }

    /**
     * After reading [n] bytes, the stream ends first and the read part is returned. No need for
     * InputStream.readNBytes: it is only available in Android 13. Also no need for
     * ByteArrayOutputStream.toString(Charset).
     */
    fun readUpTo(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(out, got, n - got)
            if (r < 0) break
            got += r
        }
        return if (got == n) out else out.copyOf(got)
    }

    fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = readLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: throw IOException("bad chunk size")
            if (size == 0) {
                while (!readLine(input).isNullOrEmpty()) Unit
                return out.toByteArray()
            }
            val chunk = readUpTo(input, size)
            if (chunk.size < size) throw IOException("HTTP chunk truncated")
            out.write(chunk)
            readLine(input)
        }
    }
}
