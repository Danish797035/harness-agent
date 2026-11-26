package com.meenai.mina.core.agent

/**
 * Matches simple single-step foreground app-launch commands (e.g. "open Flipkart",
 * "please launch YouTube", "whatsapp kholo") against installed launcher apps so
 * they can be started directly via PackageManager without waiting on an LLM turn.
 */
object AppLaunchShortcut {

    private val POLITE_PREFIXES = listOf(
        "please ", "pls ", "kindly ", "can you ", "could you ", "would you ", "will you ", "kripya ",
    )

    private val POLITE_SUFFIXES = listOf(
        " please", " pls", " kripya",
    )

    private val ENGLISH_OPEN = Regex(
        """^(?:open|launch|start)\s+(?:the\s+)?(.+?)(?:\s+app)?$""",
        RegexOption.IGNORE_CASE,
    )

    private val HINGLISH_OPEN = Regex(
        """^(.+?)(?:\s+app)?\s+(?:khol|kholo|kholna|chalu\s+kar|chalu\s+karo|chalao|open\s+kar|open\s+karo)$""",
        RegexOption.IGNORE_CASE,
    )

    private val BLOCKED_MARKERS = Regex(
        """\b(?:and|then|aur|phir|search|find|send|message|msg|play|call|scroll|tap|click|check|book|order|buy|write|post|pay|set|turn|type|par|pe|mein|me|ke|ka|ki|for|from|with|to|in|on|what|why|how|who|when|where|which|kya|kaise|kyun|kab|kahan|mat|nahi|nahin|don't|dont|not|it|this|that|same|them)\b""",
        RegexOption.IGNORE_CASE,
    )

    private val URL_HINT = Regex("""(?:https?://|www\.|\.[a-z]{2,4}\b)""", RegexOption.IGNORE_CASE)

    /** Returns `(packageName, label)` if [goal] is an unambiguous 1-step launch command. */
    fun match(goal: String, installedApps: List<Pair<String, String>>): Pair<String, String>? {
        val query = extractAppQuery(goal) ?: return null
        if (installedApps.isEmpty()) return null

        val exact = installedApps.filter { (_, label) -> label.trim().equals(query, ignoreCase = true) }
        if (exact.size == 1) return exact.single()

        val normQuery = normalize(query)
        if (normQuery.length < 2) return null

        val normExact = installedApps.filter { (_, label) -> normalize(label) == normQuery }
        if (normExact.size == 1) return normExact.single()

        // Match whole word or clean prefix only when unambiguous across installed apps.
        val tokenOrPrefix = installedApps.filter { (pkg, label) ->
            val words = label.lowercase().split(Regex("""[^a-z0-9]+""")).filter { it.isNotBlank() }
            val normLabel = normalize(label)
            query.lowercase() in words ||
                normLabel.startsWith(normQuery) ||
                pkg.substringAfterLast('.').equals(normQuery, ignoreCase = true)
        }
        if (tokenOrPrefix.size == 1) return tokenOrPrefix.single()

        return null
    }

    internal fun extractAppQuery(goal: String): String? {
        val trimmed = goal.trim()
        if (trimmed.isEmpty() || trimmed.length > 80) return null
        if (trimmed.endsWith("?") || trimmed.endsWith("？")) return null
        if (trimmed.contains('[') || trimmed.contains(',') || trimmed.contains(';') || trimmed.contains('\n')) return null
        if (URL_HINT.containsMatchIn(trimmed)) return null

        var text = trimmed.lowercase().replace(Regex("""[.!।]+$"""), "").trim()
        for (p in POLITE_PREFIXES) {
            if (text.startsWith(p)) {
                text = text.removePrefix(p).trim()
                break
            }
        }
        for (s in POLITE_SUFFIXES) {
            if (text.endsWith(s)) {
                text = text.removeSuffix(s).trim()
                break
            }
        }
        if (text.isEmpty()) return null

        val rawTarget = ENGLISH_OPEN.matchEntire(text)?.groupValues?.get(1)
            ?: HINGLISH_OPEN.matchEntire(text)?.groupValues?.get(1)
            ?: return null

        val target = rawTarget.trim()
            .removePrefix("the ")
            .removeSuffix(" app")
            .trim()
        if (target.isEmpty() || target.split(Regex("""\s+""")).size > 4) return null
        if (BLOCKED_MARKERS.containsMatchIn(target)) return null
        return target
    }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("""[^a-z0-9]"""), "")
}
