package com.meenai.mina.core.uistate

/**
 * After the action, a new saying "reached the upper limit" appears on the interface: today's number of
 * times is used up, the operation is too frequent, try again tomorrow, etc. Only recognize short text
 * that appears after the action (prompt bars, pop-up windows, and page pop-ups): text that is on the
 * screen before the action (explanatory text, help pages) does not count, and long paragraphs do not
 * count. Don’t recognize it by website or app, only by the method.
 */
object LimitNotice {
    /** Blank spaces are removed and case-insensitive when comparing. */
    val WORDS = listOf(
        "次数已用完", "次数用完", "机会已用完", "额度已用完", "已达上限", "已达到上限", "达到上限", "超过上限", "超出上限",
        "明天再来", "明日再来", "明天再试", "明日再试", "操作过于频繁", "操作太频繁", "操作频繁", "请求过于频繁", "访问过于频繁",
        "limitreached", "reachedthelimit", "reachedyourlimit", "reachedthedailylimit", "reachedyourdailylimit", "dailylimit",
        "tryagaintomorrow", "comebacktomorrow", "toomanyrequests", "doingthattoooften",
    )

    /** "The communication limit has been reached" and "the daily delivery limit has been exceeded" are several words sandwiched in between. */
    private val REACHED = Regex("(已达|达到|超过|超出)[^，,。.!！?？]{0,6}上限")

    /** Calculate the upper limit of "short text" (number of words after removing blanks): one or two sentences in prompts and pop-up windows. */
    private const val MAX_CHARS = 120

    /** [before] is the screen before the action (if it cannot be read, it is null, and everything on the screen is considered new); if it returns the newly appeared sentence, it is null if it is not. */
    fun find(before: ScreenSnapshot?, after: ScreenSnapshot?): String? {
        after ?: return null
        val earlier = before?.let(::texts).orEmpty().map(::compact).toSet()
        return texts(after).firstOrNull { t ->
            val c = compact(t)
            c.length <= MAX_CHARS && c !in earlier && (WORDS.any { it in c } || REACHED.containsMatchIn(c))
        }?.trim()
    }

    private fun texts(s: ScreenSnapshot): List<String> =
        s.elements.filter { !it.editable && !it.hint && it.name.isNotBlank() }.map { it.name } + listOfNotNull(s.web?.dialog?.message)

    private fun compact(t: String) = t.filterNot(Char::isWhitespace).lowercase()
}

