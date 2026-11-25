package com.meenai.mina.cap.web

import com.meenai.mina.core.workspace.SandboxTree
import android.util.Log
import com.meenai.mina.core.workspace.GatedFile
import com.meenai.mina.core.workspace.SandboxError
import com.meenai.mina.core.workspace.WorkspaceFileGate
import com.meenai.mina.core.cap.web.DownloadAddressPolicy
import com.meenai.mina.core.cap.web.DownloadInfo
import com.meenai.mina.core.cap.web.WebUrlPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Web page download: The file falls under downloads/ in the workspace of this conversation (what you see
 * in the sandbox is /workspace/downloads/<name>). The workspace is just a folder in the App's private
 * directory, and downloading does not rely on the sandbox. The downloaded file is only saved, not opened
 * or installed (the same goes for APK).
 *
 * - - The name takes Content-Disposition (priority filename*), otherwise takes the name given by the
 * page on the link (`<a download>`, asked by the page script), otherwise takes the last paragraph of the
 * URL (The URL of blob:/data: is not counted); remove the path, control characters and bidirectional
 * control characters, and the leading dot, up to 80 characters, truncate by bytes (retain the
 * extension), use download for empty names, and add "(2)" for repeated names. The name is given by the
 * web page, and it is marked as web page data when handed to the model.
 * - - Download instructions [gate]: Create a new file in downloads/ (never overwrite the existing name),
 * verify by handle and write on the same handle; if it fails, just delete the file itself. When
 * downloads/ is replaced with a link (commands in the sandbox can do this), it is rejected and marked as
 * unsafe_path.
 * - - Request the cookie with the URL (each jump is taken according to the address after the jump), UA
 * and source page; only http/https are taken. Each hop passes through [addressRefusal] (same rule as
 * web_navigate, except local and LAN are not allowed) and jumps to an address marked blocked_redirect;
 * domain name resolution to the intranet is also rejected ([dns]).
 * - - The upper limit of a single file is [maxBytes], and the content limit of [maxBlobBytes] is
 * exceeded by aborting, deleting half of the file, and marking too_large;blob:/data:.
 * - - One task can contain at most [maxFiles] files, totaling [maxTotalBytes] (download_limit); one
 * download (including all jumps) can contain at most [timeoutMs] (timed_out).
 */
class WebDownloads(
    private val workspaceDir: File,
    private val gate: WorkspaceFileGate,
    /** The lock of the tree where the workspace is located: the download disk is written in its permission. */
    private val tree: SandboxTree,
    http: OkHttpClient,
    private val cookies: (String) -> String?,
    private val scope: CoroutineScope,
    private val maxBytes: Long = 200L shl 20,
    private val maxBlobBytes: Long = 20L shl 20,
    private val maxFiles: Int = 50,
    private val maxTotalBytes: Long = 1L shl 30,
    private val timeoutMs: Long = 10 * 60_000L,
    /** The reason why it cannot be obtained (null = can be obtained); the local test server is allowed in the test. */
    private val addressRefusal: (String) -> String? = DownloadAddressPolicy::refusal,
    dns: Dns = DownloadAddressPolicy.guardedDns(),
) {
    private class Entry(val file: String, val mime: String?) {
        @Volatile var bytes = 0L
        @Volatile var status = RUNNING
        @Volatile var error: String? = null
        /** The handle used for writing (passed the gate verification); closed when completed (completion or failure). */
        var gated: GatedFile? = null
        /** Occupies task quota (one file + [charged] written bytes). */
        var counted = false
        var charged = 0L
    }

    private val entries = mutableListOf<Entry>()
    /** Quota: The number of files occupied and the number of bytes written are both changed in synchronized(entries). */
    private var files = 0
    private var totalBytes = 0L

    private val client = http.newBuilder()
        // Jump one by one and check the address at each jump.
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(dns)
        // Each request after the jump carries a cookie according to its own address.
        .addNetworkInterceptor { chain ->
            val req = chain.request()
            val c = cookies(req.url.toString())
            chain.proceed(if (c.isNullOrEmpty()) req else req.newBuilder().header("Cookie", c).build())
        }
        .build()

    /** The page is asking for a name and has not yet been registered for download. */
    private val naming = AtomicInteger()

    /**
     * The name given to the download by the page (`<a download>`) is only in the page and cannot be
     * obtained by the download monitor: first use [pageName] to ask the page, and after asking (or not
     * being able to ask), hand it over to [then] for registration. The moment when the name is asked,
     * the download has started. [awaitNamed] waits for them to be registered. [then] runs with the
     * permission written for `files/sandbox/` (see SandboxTree).
     */
    fun withPageName(pageName: suspend () -> String?, then: (String?) -> Unit) {
        naming.incrementAndGet()
        scope.launch {
            try {
                val name = try { pageName() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
                tree.shared { then(name) }
            } finally {
                naming.decrementAndGet()
            }
        }
    }

    /** The downloads whose names are being asked are all registered, at least [capMs]. */
    suspend fun awaitNamed(capMs: Long) {
        withTimeoutOrNull(capMs) { while (naming.get() > 0) delay(NAMING_POLL_MS) }
    }

    /**
     * Start downloading [url]; immediately return to the sandbox path, the status is running first.
     * [suggested] is the name given by the page. [viaPage]: When you cannot connect due to TLS/handshake
     * errors (no bytes have been written yet), the page uses the browser's own network stack to retrieve
     * it (up to the given number of bytes); if it is retrieved, it is (content, type), if it is not
     * retrieved, it is null. If you don't give it, it won't be replaced. The caller only gives it when
     * the URL and the page have the same origin (the page only takes the origin and does not follow the
     * jump); it goes through the address rules again before changing the path.
     */
    fun start(
        url: String, ua: String, contentDisposition: String?, mime: String?, referer: String?, suggested: String? = null,
        viaPage: (suspend (Long) -> Pair<ByteArray, String?>?)? = null,
    ): String {
        val name = fileName(contentDisposition, url, mime, suggested)
        if (WebUrlPolicy.checkNavigable(url) != null || !(url.startsWith("http://", true) || url.startsWith("https://", true))) {
            return failed(name, mime, "not_a_web_address")
        }
        if (addressRefusal(url) != null) return failed(name, mime, BLOCKED_ADDRESS)
        val entry = reserve(name, mime)
        if (entry.status == FAILED) return entry.file
        scope.launch {
            val unreached = try {
                tree.shared { fetch(url, ua, referer, entry) }
            } catch (e: CancellationException) {
                fail(entry, "cancelled")
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "download failed", e)
                networkError(e)
            }
            if (unreached != null) fallBack(entry, url, unreached, viaPage)
        }
        return entry.file
    }

    /**
     * Not connected by itself ([reason] with real error type): It is a TLS/handshake type. If [viaPage]
     * is given and the address still passes the rules, it is fetched from the page. If fetched, it will
     * be saved; otherwise, it will be recorded as failed according to the reason.
     */
    private suspend fun fallBack(entry: Entry, url: String, reason: String, viaPage: (suspend (Long) -> Pair<ByteArray, String?>?)?) {
        val usable = viaPage?.takeIf { reason.startsWith(TLS_ERROR) && addressRefusal(url) == null }
        val got = usable?.let { read ->
            try { read(maxBlobBytes) } catch (e: CancellationException) { fail(entry, "cancelled"); throw e } catch (e: Exception) { null }
        }
        if (got == null) return fail(entry, reason)
        if (got.first.size > maxBlobBytes) return fail(entry, TOO_LARGE)
        tree.shared { write(entry, got.first) }
    }

    /** The blob: content read out by the page script, or the data: content decoded, is saved directly. */
    fun save(sourceUrl: String, contentDisposition: String?, mime: String?, bytes: ByteArray, suggested: String? = null): String {
        val name = localName(sourceUrl, contentDisposition, mime, suggested)
        if (bytes.size > maxBlobBytes) return failed(name, mime, TOO_LARGE)
        val entry = reserve(name, mime)
        if (entry.status == FAILED) return entry.file
        write(entry, bytes)
        return entry.file
    }

    /**
     * blob: Download: first register (running), and then use [read] to let the page read the content (at
     * most [maxBlobBytes]); if it cannot be read, give the reason. [read] returns (content, type of page
     * report) or failure reason.
     */
    fun startFrom(
        sourceUrl: String, contentDisposition: String?, mime: String?, suggested: String? = null,
        read: suspend (Long) -> Pair<ByteArray, String?>?,
    ): String {
        val name = localName(sourceUrl, contentDisposition, mime, suggested)
        val entry = reserve(name, mime)
        if (entry.status == FAILED) return entry.file
        scope.launch {
            try {
                val got = read(maxBlobBytes) ?: return@launch fail(entry, "unreadable")
                if (got.first.size > maxBlobBytes) return@launch fail(entry, TOO_LARGE)
                tree.shared { write(entry, got.first) }
            } catch (e: CancellationException) {
                fail(entry, "cancelled")
                throw e
            } catch (e: BlobRefused) {
                fail(entry, e.reason)
            } catch (e: Exception) {
                Log.w(TAG, "blob download failed", e)
                fail(entry, "unreadable")
            }
        }
        return entry.file
    }

    /** The reader of [startFrom] reported a clear reason (such as too_large). */
    class BlobRefused(val reason: String) : Exception(reason)

    private fun localName(sourceUrl: String, contentDisposition: String?, mime: String?, suggested: String?) =
        fileName(contentDisposition, sourceUrl.takeUnless { it.startsWith("blob:", true) || it.startsWith("data:", true) }.orEmpty(), mime, suggested)

    private fun write(entry: Entry, bytes: ByteArray) {
        try {
            if (!charge(entry, bytes.size.toLong())) return fail(entry, DOWNLOAD_LIMIT)
            entry.gated!!.write(bytes)
            entry.bytes = bytes.size.toLong()
            done(entry)
        } catch (e: IOException) {
            fail(entry, "write_failed")
        }
    }

    /**
     * data: URL: decode it using base64 or percent encoding and save it; if it cannot be decoded, it
     * will be recorded as failed. Before decoding, judge according to the length: every 4 words in
     * base64 is 3 bytes, and each byte in percent encoding is at least 1 word. If it exceeds the upper
     * limit, it is puzzling.
     */
    fun dataUrl(url: String, contentDisposition: String?, suggested: String? = null): String {
        val comma = url.indexOf(',')
        val header = if (comma < 0) "" else url.substring(0, comma)
        val mime = header.removePrefix("data:").substringBefore(';').ifBlank { null }
        val base64 = header.endsWith(";base64", true)
        val payloadLength = if (comma < 0) 0L else (url.length - comma - 1).toLong()
        val limit = if (base64) maxBlobBytes * 4 / 3 + 8 else maxBlobBytes * 3
        if (payloadLength > limit) return failed(fileName(contentDisposition, "", mime, suggested), mime, TOO_LARGE)
        val payload = if (comma < 0) "" else url.substring(comma + 1)
        val bytes = runCatching {
            if (base64) Base64.getDecoder().decode(payload) else URLDecoder.decode(payload, "UTF-8").toByteArray()
        }.getOrNull() ?: return failed(fileName(contentDisposition, "", mime, suggested), mime, "bad_data_url")
        return save(url, contentDisposition, mime, bytes, suggested)
    }

    fun list(): List<DownloadInfo> = synchronized(entries) {
        entries.map { DownloadInfo(it.file, it.bytes, it.mime, it.status, it.error) }
    }

    /** Newly started downloads after this time; used to mention a sentence in the result of the action that triggered the download. */
    fun count(): Int = synchronized(entries) { entries.size }

    fun since(count: Int): List<DownloadInfo> = list().drop(count)

    /**
     * Fetch hop by hop: Check the address first for each hop; the entire download (including jumps and
     * text reading) does not exceed [timeoutMs]. If the connection cannot be made (no bytes have been
     * written yet), the failure will not be recorded, and the return reason will be left to the caller
     * to change the path; in other cases, it will be recorded by itself and null will be returned.
     */
    private fun fetch(url: String, ua: String, referer: String?, entry: Entry): String? {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        var current = url.toHttpUrlOrNull() ?: return fail(entry, "not_a_web_address").let { null }
        var hops = 0
        while (true) {
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) return fail(entry, TIMED_OUT).let { null }
            val req = Request.Builder().url(current).header("User-Agent", ua).apply { referer?.let { header("Referer", it) } }.build()
            val call = client.newCall(req)
            call.timeout().timeout(left, TimeUnit.MILLISECONDS)
            val resp = try {
                call.execute()
            } catch (e: IOException) {
                val reason = failureOf(e, hops, deadline)
                // TLS / handshake type errors, and they are still on the first hop: leave it to the
                // caller to decide whether to fetch from the page instead; errors such as address
                // rejection, timeout, and jump after the jump are recorded as they are
                if (reason.startsWith(TLS_ERROR) && hops == 0) return reason
                return fail(entry, reason).let { null }
            }
            try {
                if (resp.isRedirect) {
                    val next = resp.header("Location")?.let { resp.request.url.resolve(it) } ?: return fail(entry, "http_${resp.code}").let { null }
                    if (++hops > MAX_REDIRECTS) return fail(entry, "too_many_redirects").let { null }
                    if (addressRefusal(next.toString()) != null) return fail(entry, BLOCKED_REDIRECT).let { null }
                    current = next
                    continue
                }
                if (!resp.isSuccessful) return fail(entry, "http_${resp.code}").let { null }
                val body = resp.body ?: return fail(entry, "empty_response").let { null }
                if (body.contentLength() > maxBytes) return fail(entry, TOO_LARGE).let { null }
                val out = entry.gated ?: return null
                val input = body.byteStream()
                val buf = ByteArray(BUFFER)
                var total = 0L
                while (true) {
                    val n = try { input.read(buf) } catch (e: IOException) { return fail(entry, failureOf(e, hops, deadline)).let { null } }
                    if (n < 0) break
                    total += n
                    if (total > maxBytes) return fail(entry, TOO_LARGE).let { null }
                    if (!charge(entry, n.toLong())) return fail(entry, DOWNLOAD_LIMIT).let { null }
                    out.write(if (n == buf.size) buf else buf.copyOf(n))
                    entry.bytes = total
                    if (System.nanoTime() > deadline) return fail(entry, TIMED_OUT).let { null }
                }
                return done(entry).let { null }
            } finally {
                resp.close()
            }
        }
    }

    private fun failureOf(e: IOException, hops: Int, deadline: Long): String = when {
        generateSequence<Throwable>(e) { it.cause }.any { it is DownloadAddressPolicy.BlockedAddress } -> if (hops == 0) BLOCKED_ADDRESS else BLOCKED_REDIRECT
        System.nanoTime() >= deadline || e is java.io.InterruptedIOException -> TIMED_OUT
        else -> networkError(e)
    }

    /**
     * Network errors bring the real error type (the innermost cause), such as tls_error
     * (SSLHandshakeException:...), network_error (UnknownHostException:...); Certificates and handshakes
     * (SSLException) are individually called tls_error.
     */
    private fun networkError(e: Throwable): String {
        val chain = generateSequence(e) { it.cause }.toList()
        val root = chain.last()
        val detail = root.message?.lineSequence()?.firstOrNull()?.take(MAX_ERROR_DETAIL)?.let { ": $it" }.orEmpty()
        val kind = if (chain.any { it is javax.net.ssl.SSLException }) TLS_ERROR else NETWORK_ERROR
        return "$kind (${root.javaClass.simpleName}$detail)"
    }

    /**
     * Account for a name and a share: Create a new empty file in downloads/ via the gateway (do not
     * follow the link or overwrite the existing one), and leave the handle for writing. If the quota is
     * insufficient or the directory is not secure, a failed record will be returned.
     */
    private fun reserve(name: String, mime: String?): Entry = synchronized(entries) {
        if (files >= maxFiles) return failedEntry(name, mime, DOWNLOAD_LIMIT)
        // The gateway is rejected (downloads/ is replaced by a link, etc.) or cannot be built: remember
        // unsafe_path
        val opened = try {
            workspaceDir.mkdirs()
            gate.createNumbered(workspaceDir, DIR, name, MAX_SAME_NAME)
        } catch (e: SandboxError) {
            null
        } catch (e: IOException) {
            null
        } ?: return failedEntry(name, mime, UNSAFE_PATH)
        files++
        Entry("$SANDBOX_DIR/${opened.first}", mime).also {
            it.gated = opened.second
            it.counted = true
            entries += it
        }
    }

    /** Record the [n] bytes just written; the total number of tasks exceeded is false. */
    private fun charge(entry: Entry, n: Long): Boolean = synchronized(entries) {
        if (totalBytes + n > maxTotalBytes) return false
        totalBytes += n
        entry.charged += n
        true
    }

    private fun done(entry: Entry) = synchronized(entry) {
        if (entry.status != RUNNING) return
        entry.status = DONE
        entry.gated?.close()
        entry.gated = null
    }

    private fun failed(name: String, mime: String?, error: String): String = failedEntry(name, mime, error).file

    private fun failedEntry(name: String, mime: String?, error: String): Entry =
        Entry("$SANDBOX_DIR/$name", mime).also { it.status = FAILED; it.error = error; synchronized(entries) { entries += it } }

    /** Failure: Delete the file you created by downloading it (the one whose handle has been verified will not change if the name is changed to something else), and the credit will be refunded. */
    private fun fail(entry: Entry, error: String) {
        synchronized(entry) {
            if (entry.status != RUNNING) return
            entry.status = FAILED
            entry.error = error
            entry.gated?.let { g -> runCatching { g.delete() }; g.close() }
            entry.gated = null
        }
        synchronized(entries) {
            if (entry.counted) {
                entry.counted = false
                files--
                totalBytes -= entry.charged
            }
        }
    }

    companion object {
        const val RUNNING = "running"
        const val DONE = "done"
        const val FAILED = "failed"
        const val TOO_LARGE = "too_large"
        const val UNSAFE_PATH = "unsafe_path"
        const val BLOCKED_ADDRESS = "blocked_address"
        const val BLOCKED_REDIRECT = "blocked_redirect"
        const val DOWNLOAD_LIMIT = "download_limit"
        const val TIMED_OUT = "timed_out"
        const val NETWORK_ERROR = "network_error"
        const val TLS_ERROR = "tls_error"
        private const val MAX_ERROR_DETAIL = 120
        private const val MAX_REDIRECTS = 10
        /** The name is given by the web page, and the length is limited before it is handed over to the model. */
        const val MAX_NAME_CHARS = 80
        private const val TAG = "MinaDownloads"
        private const val DIR = "downloads"
        private const val SANDBOX_DIR = "/workspace/downloads"
        private const val BUFFER = 64 * 1024
        private const val MAX_SAME_NAME = 999
        private const val MAX_NAME_BYTES = 200
        private const val MAX_EXT_BYTES = 20
        private const val FALLBACK = "download"
        private const val NAMING_POLL_MS = 20L

        private val FILENAME_STAR = Regex("""filename\*\s*=\s*([^']*)'[^']*'([^;]+)""", RegexOption.IGNORE_CASE)
        private val FILENAME_QUOTED = Regex("""filename\s*=\s*"((?:[^"\\]|\\.)*)"""", RegexOption.IGNORE_CASE)
        private val FILENAME_TOKEN = Regex("""filename\s*=\s*([^;\s]+)""", RegexOption.IGNORE_CASE)
        /** Control characters, bidirectional control characters and zero-width characters: can display "exe" as "pdf" or the like. */
        private val UNSAFE_CHARS = Regex("[\\u0000-\\u001f\\u007f-\\u009f\\u061c\\u200b-\\u200f\\u202a-\\u202e\\u2066-\\u2069\\ufeff]")
        private val MIME_EXT = mapOf(
            "application/pdf" to "pdf", "text/csv" to "csv", "text/plain" to "txt", "application/json" to "json",
            "application/zip" to "zip", "image/png" to "png", "image/jpeg" to "jpg", "image/gif" to "gif", "image/webp" to "webp",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
            "application/vnd.ms-excel" to "xls", "application/msword" to "doc", "text/html" to "html",
        )

        /** Downloaded file name: See class description. [suggested] is the name given by the page (`<a download>`), ranked after the response header and before the URL. */
        fun fileName(contentDisposition: String?, url: String, mime: String?, suggested: String? = null): String {
            val fromHeader = contentDisposition?.let { cd ->
                FILENAME_STAR.find(cd)?.let { m ->
                    val charset = m.groupValues[1].ifBlank { "UTF-8" }
                    runCatching { URLDecoder.decode(m.groupValues[2].trim().replace("+", "%2B"), charset) }.getOrNull()
                } ?: FILENAME_QUOTED.find(cd)?.groupValues?.get(1)?.replace(Regex("""\\(.)"""), "$1")
                    ?: FILENAME_TOKEN.find(cd)?.groupValues?.get(1)
            }
            val fromUrl = runCatching { URI(url).rawPath }.getOrNull()?.substringAfterLast('/')
                ?.let { runCatching { URLDecoder.decode(it.replace("+", "%2B"), "UTF-8") }.getOrNull() }
            var name = clean(fromHeader ?: "").ifEmpty { clean(suggested ?: "") }.ifEmpty { clean(fromUrl ?: "") }.ifEmpty { FALLBACK }
            if (!name.contains('.')) MIME_EXT[mime?.substringBefore(';')?.trim()?.lowercase()]?.let { name = "$name.$it" }
            return fitBytes(name)
        }

        private fun clean(raw: String): String {
            // Only the last paragraph is left: forward slash and backslash are considered separators.
            val last = raw.substringAfterLast('/').substringAfterLast('\\')
            return last.replace(UNSAFE_CHARS, "").trim().trimStart('.').trim().takeUnless { it == "." || it == ".." }.orEmpty()
        }

        private fun fitBytes(name: String): String {
            fun chars(t: String) = t.codePointCount(0, t.length)
            if (name.toByteArray().size <= MAX_NAME_BYTES && chars(name) <= MAX_NAME_CHARS) return name
            val ext = name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.toByteArray().size <= MAX_EXT_BYTES }?.let { ".$it" }.orEmpty()
            var base = name.removeSuffix(ext)
            // Truncate by code point, do not split proxy pairs
            while (((base + ext).toByteArray().size > MAX_NAME_BYTES || chars(base + ext) > MAX_NAME_CHARS) && base.isNotEmpty()) {
                base = base.substring(0, base.offsetByCodePoints(base.length, -1))
            }
            return (base + ext).ifEmpty { FALLBACK }
        }
    }
}
