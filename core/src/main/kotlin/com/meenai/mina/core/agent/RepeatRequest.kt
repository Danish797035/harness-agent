package com.meenai.mina.core.agent

/**
 * Detects a final answer that stops short of the user's request and hands the
 * work back: "send it again", "I reached my action limit", "you can type it
 * yourself". The user already gave the request, so this is not an answer. The
 * loop nudges the model once to continue with the tools it has, or to call
 * end_not_done with the real blocker.
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
        // Invented internal limits: there is no step limit in a normal task.
        Regex(
            """\b(?:reached|hit|exceeded|ran\s+out\s+of)\b[^.\n]{0,30}\b(?:action|step|tool|call)s?\s+(?:limit|budget)\b""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """\b(?:action|step|tool)\s+(?:limit|budget)\b""",
            RegexOption.IGNORE_CASE
        ),
        // "I wasn't able to complete/run the search" — continue instead of stopping.
        Regex(
            """\b(?:couldn't|could\s+not|wasn't\s+able\s+to|was\s+not\s+able\s+to|unable\s+to|can't|cannot)\b[^.\n]{0,40}\b(?:complete|finish|run|perform|do|search|open|continue)\b""",
            RegexOption.IGNORE_CASE
        ),
        // Handing the action back: "you can now type Motu Patlu directly".
        Regex(
            """\byou\s+can\s+(?:now\s+)?(?:type|search|open|enter|tap|click|do)\b[^.\n]{0,60}\b(?:directly|yourself|manually)\b""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """\b(?:type|search|open|enter)\b[^.\n]{0,40}\b(?:in|into|on)\b[^.\n]{0,30}\b(?:search\s+bar|search\s+box|address\s+bar)\b""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """\b(?:dobara|dubara|phir\s+se|ek\s+baar\s+phir)\b[^.\n]{0,40}\b(?:bhej\w*|likh\w*|bol\w*|kaho|kah\w*|search\w*|try\w*|kar\w*)""",
            RegexOption.IGNORE_CASE
        ),
        Regex("""(?:दोबारा|फिर\s+से)[^।\n]{0,40}(?:भेज|लिख|बोल|कह|खोज|कर)"""),
        Regex(
            """\b(?:limit|budget)\s+(?:aa|a)\s*gay[ai]\b|\baap\s+(?:khud|directly)\b[^.\n]{0,40}\b(?:type|search|kar|khol|likh)""",
            RegexOption.IGNORE_CASE
        ),
    )

    /** True when [answer] asks the user to redo the request, claims a limit, or hands the action back. */
    fun matches(answer: String): Boolean = PATTERNS.any { it.containsMatchIn(answer) }
}
