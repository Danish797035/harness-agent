package com.meenai.mina.core.general

/**
 * Strips raw markdown formatting (headers, bold, italics, inline code, code blocks,
 * markdown links) from notification and status text to keep system notifications
 * clean, readable, and free of markdown syntax.
 */
object MarkdownSanitizer {
    fun strip(text: String): String {
        if (text.isBlank()) return text
        return text
            .replace(Regex("(?m)^#{1,6}\\s+"), "")
            .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
            .replace(Regex("__([^_]+)__"), "$1")
            .replace(Regex("(?<!\\*)\\*([^*\\n]+)\\*(?!\\*)"), "$1")
            .replace(Regex("(?<!_)_([^_\\n]+)_(?!_)"), "$1")
            .replace(Regex("`{1,3}([^`]+)`{1,3}"), "$1")
            .replace(Regex("\\[([^\\]]+)\\]\\([^)]+\\)"), "$1")
            .replace(Regex("(?m)^[-*+]\\s+"), "• ")
            .trim()
    }
}
