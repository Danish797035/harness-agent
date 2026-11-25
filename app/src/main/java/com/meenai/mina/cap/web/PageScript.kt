package com.meenai.mina.cap.web

import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.meenai.mina.core.cap.web.PageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.coroutines.resume

/** Check by number before clicking: the click is obtained ([x], [y] is the CSS pixel of the top viewport), it is covered by other elements, it is outside the viewport, and the element is no longer there. */
sealed interface HitResult {
    data class Hit(val x: Int, val y: Int) : HitResult
    data class Obscured(val byName: String) : HitResult
    data object Offscreen : HitResult
    data object Gone : HitResult
}

/** Obstacles are determined by: whether there is a password box on the page and the complete address of the cross-origin frame. */
data class PageFacts(val passwordField: Boolean, val frameUrls: List<String>)

/** The current value of the input box; the password box only gives the length. */
sealed interface FieldValue {
    data class Text(val value: String) : FieldValue
    data class Password(val length: Int) : FieldValue
    data object NotAField : FieldValue
}

/**
 * App side of page script assets/web/page.js: inject, call, and poll asynchronous results. The scripts
 * written by the model do not go through here (reading scripts and page scripts each run in their own
 * isolated world), only our own fixed code is called here. When the system supports it, it is injected
 * at the beginning of each document (before the page's own script, the original entry will not be
 * changed by the page first), otherwise it is injected after the loading is completed. There is only a
 * bridge function with a random name on the page, and you need to use a key known only here to get the
 * interface; the page cannot get any entrance to the App, and all the results are retrieved from here
 * through evaluateJavascript. When the main document is changed and the script is not there, the next
 * time it is called, it will be automatically injected.
 */
/** When [useDocumentStart] is false, it is only injected after loading is completed (this is the case when the system does not support document injection at the beginning; it is also used for testing). */
class PageScript(private val tab: WebTab, private val useDocumentStart: Boolean = true) {
    /** The name of the bridge function on the page; change one for each document (together with the key) when injecting after loading is completed, and when the document starts to be injected, the pair at the time of registration is fixed. */
    @Volatile internal var bridgeName = newBridgeName()
        private set
    @Volatile private var secret = UUID.randomUUID().toString()
    private val source: String by lazy {
        tab.view.context.assets.open(ASSET).bufferedReader().use { it.readText() }
    }
    private val bootstrap: String get() = "(\n$source\n)(${quote(bridgeName)},${quote(secret)})"
    private var documentStartRegistered = false

    /** Whether to start injecting from the document; when it is false, the page script may change the global object before this script. */
    var documentStart: Boolean = false
        private set

    /** Register the document to start injecting (only do it once), and inject it if it is not already in the current document. */
    suspend fun install() {
        withContext(Dispatchers.Main) {
            if (!documentStartRegistered) {
                documentStartRegistered = true
                if (useDocumentStart && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    WebViewCompat.addDocumentStartJavaScript(tab.view, bootstrap, setOf("*"))
                    documentStart = true
                }
            }
        }
        if (!present()) inject()
    }

    private suspend fun present(): Boolean =
        raw("(function(){var g=window[${quote(bridgeName)}];return typeof g==='function'&&!!g(${quote(secret)});})()") == "true"

    // Injection after loading: Each new document uses a new name and key. The name leaked in the
    // previous document is useless for the new document.
    private suspend fun inject() {
        if (!documentStart) {
            bridgeName = newBridgeName()
            secret = UUID.randomUUID().toString()
        }
        raw(bootstrap)
    }

    /**
     * JSON of visible elements in the viewport (input format of PageTree); an empty tree is given when
     * the page cannot be read. The number table is saved as [key] (snapshot number), and the most recent
     * ones are retained on the page; when [key] is null, it is read-only and not registered.
     */
    suspend fun tree(key: String? = DEFAULT_KEY): String =
        call("tree(${key?.let(::quote) ?: "null"})")?.takeIf { it is JsonObject }?.toString() ?: EMPTY_TREE

    /**
     * An expression evaluated by DevTools in the main environment of the page: returns an array of
     * elements in the number table [key] (null is the last screen read), or null when the script is not
     * available. Only used by DevTools (returns element object), does not pass [call].
     */
    internal fun refNodesExpression(key: String?): String =
        "(function(){var g=window[${quote(bridgeName)}];var m=typeof g==='function'&&g(${quote(secret)});" +
            "return m?m.refNodes(${key?.let(::quote) ?: "null"}):null;})()"

    suspend fun facts(): PageFacts {
        val r = call("facts()") as? JsonObject ?: return PageFacts(false, emptyList())
        val frames = (r["frames"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        return PageFacts(r["password"]?.jsonPrimitive?.booleanOrNull == true, frames)
    }

    suspend fun mutationCount(): Long = (call("mutationCount()") as? JsonPrimitive)?.long ?: 0L

    /** First scroll the element into the center of the viewport, then give it the center of the visible part (top viewport CSS pixels); the element is no longer null. */
    suspend fun centerOf(ref: String, key: String = DEFAULT_KEY): Pair<Int, Int>? = pair(call("centerOf(${quote(ref)},${quote(key)})"))

    suspend fun hitTest(ref: String, key: String = DEFAULT_KEY): HitResult {
        val r = call("hitTest(${quote(ref)},${quote(key)})") as? JsonObject ?: return HitResult.Gone
        return when (r["k"]?.jsonPrimitive?.content) {
            "hit" -> HitResult.Hit(r["x"]!!.jsonPrimitive.int, r["y"]!!.jsonPrimitive.int)
            "obscured" -> HitResult.Obscured(r["by"]?.jsonPrimitive?.content.orEmpty())
            "offscreen" -> HitResult.Offscreen
            else -> HitResult.Gone
        }
    }

    /** When [ref] is null, read the input box where the current focus is. */
    /** A blob in the page: the content of the URL; give the reason when it cannot be read or exceeds [maxBytes]. */
    sealed interface Blob {
        class Ok(val mime: String?, val bytes: ByteArray) : Blob
        class Failed(val error: String) : Blob
    }

    /**
     * Let the page script read the blob: content into base64, retrieve it in segments and then decode
     * it. The length of the page report and each piece of content are checked according to the upper
     * limit of [BlobChunks]; when the result times out or an error occurs, the page will discard the
     * content (the page also has expiration and self-clearing).
     */
    suspend fun readBlob(url: String, maxBytes: Long): Blob {
        val token = nextToken()
        try {
            if (call("startBlob(${quote(token)},${quote(url)},$maxBytes)") == null) return Blob.Failed(BlobChunks.UNREADABLE)
            val meta = poll(token, BLOB_TIMEOUT_MS) as? JsonObject ?: return Blob.Failed(BlobChunks.UNREADABLE)
            if ((meta["ok"] as? JsonPrimitive)?.booleanOrNull != true) return Blob.Failed((meta["error"] as? JsonPrimitive)?.content ?: BlobChunks.UNREADABLE)
            val length = (meta["length"] as? JsonPrimitive)?.intOrNull ?: return Blob.Failed(BlobChunks.UNREADABLE)
            val chunks = BlobChunks(length, maxBytes)
            chunks.refusal()?.let { return Blob.Failed(it) }
            while (!chunks.complete) {
                val part = (call("blobChunk(${quote(token)},${chunks.received})") as? JsonPrimitive)?.content ?: return Blob.Failed(BlobChunks.UNREADABLE)
                if (part.isEmpty()) break
                chunks.add(part)?.let { return Blob.Failed(it) }
            }
            return when (val o = chunks.finish()) {
                is BlobChunks.Outcome.Ok -> Blob.Ok((meta["mime"] as? JsonPrimitive)?.content?.ifBlank { null }, o.bytes)
                is BlobChunks.Outcome.Failed -> Blob.Failed(o.error)
            }
        } finally {
            call("blobDone(${quote(token)})")
        }
    }

    /**
     * The download name (`<a download="…">`) given by the page on the link pointing to [url]: recorded
     * by the page script when clicked, or on the link pointing to it in the document. None is null. The
     * name is given by the web page and is cleaned by the downloader according to the file name rules.
     * Very long data: URLs cannot be compared.
     */
    suspend fun downloadName(url: String): String? {
        if (url.length > MAX_NAMED_URL_CHARS) return null
        return (call("downloadName(${quote(url)})") as? JsonPrimitive)?.takeIf { it.isString }?.content
    }

    /** The HTTP status of the main document response; null if not available. */
    suspend fun navStatus(): Int? = (call("navStatus()") as? JsonPrimitive)?.intOrNull

    /** Is the current focus on this element (or in its editable area). */
    suspend fun isActive(ref: String, key: String = DEFAULT_KEY): Boolean =
        (call("isActive(${quote(ref)},${quote(key)})") as? JsonPrimitive)?.booleanOrNull == true

    suspend fun fieldValue(ref: String?, key: String = DEFAULT_KEY): FieldValue {
        val r = call("fieldValue(${ref?.let(::quote) ?: "null"},${quote(key)})") as? JsonObject ?: return FieldValue.NotAField
        return when (r["k"]?.jsonPrimitive?.content) {
            "password" -> FieldValue.Password(r["len"]!!.jsonPrimitive.int)
            "text" -> FieldValue.Text(r["v"]?.jsonPrimitive?.content.orEmpty())
            else -> FieldValue.NotAField
        }
    }

    /**
     * Full page of readable text (PageText's JSON). When [scroll] is true, scroll down up to 5 screens
     * before reading the first page to trigger lazy loading and reset after reading; use false when
     * verifying the result, without touching the scroll position of the page.
     */
    suspend fun readPage(maxChars: Int, offset: Int, scroll: Boolean = true): String {
        val token = nextToken()
        val max = maxChars.coerceIn(1, MAX_READ_CHARS)
        if (call("startRead(${quote(token)},$max,${offset.coerceAtLeast(0)},$scroll)") == null) return error("read_failed", "page script unavailable")
        val r = poll(token, READ_TIMEOUT_MS)
        if (r == null) {
            call("discard(${quote(token)})")
            return error("read_failed", "page did not finish reading")
        }
        return r.toString()
    }

    /** web_read_page Full page read (segments, collapsible list blocks, quality indication; see PageDigest for format), no scrolling; null if not read. */
    suspend fun digest(): JsonObject? {
        val token = nextToken()
        if (call("startDigest(${quote(token)},${PageDigest.scriptOptions})") == null) return null
        val r = poll(token, READ_TIMEOUT_MS)
        if (r == null) call("discard(${quote(token)})")
        return (r as? JsonObject)?.takeIf { it["error"] == null }
    }

    /** The (length, abstract) of the text, used to determine whether the text is stable; it is null if it cannot be read. */
    suspend fun contentSample(): Pair<Int, Int>? = runCatching { pair(call("contentSample()")) }.getOrNull()

    /**
     * Scroll down a few screens to trigger lazy loading, and then return after scrolling; after that,
     * you must adjust [endLazy] to reset the scroll position. Returns the token; null if page script is
     * not present.
     */
    suspend fun startLazy(): String? {
        val token = nextToken()
        if (call("startLazy(${quote(token)})") == null) return null
        poll(token, LAZY_TIMEOUT_MS)
        return token
    }

    suspend fun endLazy(token: String) {
        call("endLazy(${quote(token)})")
    }

    /** Scroll position (CSS pixels); when [ref] is null, it is the page itself. The element is no longer null. */
    suspend fun scrollState(ref: String?, key: String = DEFAULT_KEY): Pair<Int, Int>? =
        pair(call("scrollState(${ref?.let(::quote) ?: "null"},${quote(key)})"))

    private suspend fun poll(token: String, timeoutMs: Long): JsonElement? {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val r = call("take(${quote(token)})")
            if (r != null && r !is JsonNull) return r
            if (tab.crashed) return null
            delay(POLL_MS)
        }
        return null
    }

    /** A method to call the page script; when the script is no longer available (the main document has been changed), inject it and try again. If the result cannot be obtained, it is null. */
    private suspend fun call(expr: String): JsonElement? {
        repeat(2) { attempt ->
            val js = "(function(){var g=window[${quote(bridgeName)}];var m=typeof g==='function'&&g(${quote(secret)});" +
                "if(!m)return {missing:1};return {v:m.$expr};})()"
            val r = raw(js) ?: return null
            if (r == "null") return null
            val o = runCatching { Json.parseToJsonElement(r) }.getOrNull() as? JsonObject ?: return null
            if (o["missing"] == null) return o["v"] ?: JsonNull
            if (attempt == 0) inject()
        }
        return null
    }

    /** The raw callback string for evaluateJavascript; null on timeout or tag has been invalidated. */
    private suspend fun raw(js: String, timeoutMs: Long = CALL_TIMEOUT_MS): String? {
        if (tab.crashed) return null
        return withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Main) {
                suspendCancellableCoroutine { c -> tab.view.evaluateJavascript(js) { c.resume(it) } }
            }
        }
    }

    private fun pair(e: JsonElement?): Pair<Int, Int>? {
        val a = e as? JsonArray ?: return null
        if (a.size != 2) return null
        return a[0].jsonPrimitive.int to a[1].jsonPrimitive.int
    }

    private fun nextToken() = UUID.randomUUID().toString()

    private fun error(code: String, message: String) = buildJsonObject {
        put("ok", false)
        put("error", code)
        put("message", message)
    }.toString()

    private fun quote(s: String) = JsonPrimitive(s).toString()

    companion object {
        private fun newBridgeName() = "_" + UUID.randomUUID().toString().replace("-", "").take(16)
        private const val ASSET = "web/page.js"
        private const val MAX_NAMED_URL_CHARS = 100_000
        /** The numbering table used by the page script when the snapshot number is not specified. */
        const val DEFAULT_KEY = "default"
        private const val MAX_READ_CHARS = 30_000
        private const val READ_TIMEOUT_MS = 15_000L
        /** Scroll a few screens and wait a while for each screen. Normally, the scroll will be completed within two seconds; when the page is stuck, do not hold it back while reading the page. */
        private const val LAZY_TIMEOUT_MS = 5_000L
        private const val BLOB_TIMEOUT_MS = 30_000L
        private const val CALL_TIMEOUT_MS = 5_000L
        private const val POLL_MS = 100L
        private const val EMPTY_TREE = """{"els":[],"vw":0,"vh":0,"dpr":1,"trunc":false,"mut":0}"""
    }
}
