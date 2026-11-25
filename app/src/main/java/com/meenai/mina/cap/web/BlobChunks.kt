package com.meenai.mina.cap.web

/**
 * The blob content (base64) returned by the page segments. The page's reported length, each segment
 * length, and cumulative length are not believed: if the reported length exceeds the upper limit of
 * [maxBytes] converted to base64 (4 words per 3 bytes, plus padding margin), it will be rejected before
 * allocation; it is not pre-allocated according to the reported length; each segment can be up to
 * [chunk] words, and the cumulative length does not exceed the reported length.
 */
internal class BlobChunks(private val declared: Int, private val maxBytes: Long, private val chunk: Int = CHUNK) {
    sealed interface Outcome {
        class Ok(val bytes: ByteArray) : Outcome
        class Failed(val error: String) : Outcome
    }

    private val sb = StringBuilder()

    /** Can the reported length be accepted? If not, give the reason. */
    fun refusal(): String? = when {
        declared < 0 -> UNREADABLE
        declared > maxBase64(maxBytes) -> WebDownloads.TOO_LARGE
        else -> null
    }

    val received: Int get() = sb.length
    val complete: Boolean get() = sb.length >= declared

    /** Accept the next paragraph; if it is too long or exceeds the reported length, it will be a failure reason. */
    fun add(part: String): String? {
        if (part.length > chunk) return UNREADABLE
        if (sb.length.toLong() + part.length > declared) return UNREADABLE
        sb.append(part)
        return null
    }

    fun finish(): Outcome {
        val bytes = runCatching { java.util.Base64.getDecoder().decode(sb.toString()) }.getOrNull() ?: return Outcome.Failed(UNREADABLE)
        if (bytes.size > maxBytes) return Outcome.Failed(WebDownloads.TOO_LARGE)
        return Outcome.Ok(bytes)
    }

    companion object {
        /** Consistent with BLOB_CHUNK of page.js. */
        const val CHUNK = 1 shl 20
        const val UNREADABLE = "unreadable"

        fun maxBase64(maxBytes: Long): Long = maxBytes * 4 / 3 + 8
    }
}
