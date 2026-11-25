package com.meenai.mina.core.observation

/**
 * Untrustworthy content (webpage text and script results, network responses, other AI answers, words in
 * the platform) is a fixed mark when it is passed to the model. Only apply it once to the place where
 * you can make this observation and write the tool results. It will not be applied for subsequent
 * cropping and compression. The meaning of the mark will be stated once in the system prompt word. The
 * closing tag in the text is escaped, and the text cannot be closed at this level in advance. What is
 * recorded in the ledger is the original text without markings.
 */
object Untrusted {
    const val OPEN = "<untrusted>"
    const val CLOSE = "</untrusted>"
    const val OVERHEAD = 2 + OPEN.length + CLOSE.length

    /** Set the tag; return the empty one as is. */
    fun wrap(text: String): String = if (text.isEmpty()) text else "$OPEN\n" + text.replace(CLOSE, "<\\/untrusted>") + "\n$CLOSE"

    /** The entire field after wrapping must still meet the maximum length declared by the tool. */
    fun wrapWithin(text: String, maxChars: Int): String {
        require(maxChars >= OVERHEAD)
        val escaped = text.replace(CLOSE, "<\\/untrusted>")
        return if (escaped.isEmpty()) escaped else "$OPEN\n${escaped.take(maxChars - OVERHEAD)}\n$CLOSE"
    }
}
