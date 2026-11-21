package com.meenai.mina.core.agent

/**
 * Detects a final answer that asks the user to repeat a request they already
 * gave ("send it again", "phir se bhejo"). That is not an answer: the words are
 * already in the conversation, and asking again costs the user a whole turn.
 * The loop nudges the model once to continue with the tools it has instead.
 */
internal object RepeatRequest {
    private val PATTERNS = listOf(
        Regex(
            """\b(?:send|type|write)\s+(?:it|that|this|the\s+(?:request|message|words?)|your\s+(?:request|message))\s+(?:again|once\s+more)\b""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """\b(?:repeat|resend|re-send)\s+(?:it|that|this|the\s+(?:request|message)|your\s+(?:request|message))\b""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """\b(?:dobara|dubara|phir\s+se|ek\s+baar\s+phir)\b[^.\n]{0,40}\b(?:bhej\w*|likh\w*|bol\w*|kaho|kah\w*|search\w*|try\w*|kar\w*)""",
            RegexOption.IGNORE_CASE
        ),
        Regex("""(?:दोबारा|फिर\s+से)[^।\n]{0,40}(?:भेज|लिख|बोल|कह|खोज|कर)"""),
    )

    /** True when [answer] explicitly asks the user to send the same request again. */
    fun matches(answer: String): Boolean = PATTERNS.any { it.containsMatchIn(answer) }
}
