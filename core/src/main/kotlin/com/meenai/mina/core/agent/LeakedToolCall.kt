package com.meenai.mina.core.agent

/**
 * Tool calls leaked in the reply text: Some inference services occasionally fail to parse the tool calls
 * of the model, and the calls are returned as text. Such a reply is not an answer and cannot be given to
 * the user.
 *
 * Only common tool call tags are recognized, and a certain tool name (whole word) of Mina must appear
 * after the tag: these formats are not counted in the answer. It only judges, does not parse or execute
 * the calls in the text.
 */
internal object LeakedToolCall {
    /** Tags commonly used when calling model writing tools. */
    private val MARKERS = listOf(
        "<tool_call>", "<function=",
        "<|python_tag|>",
        "[TOOL_CALLS]",
        "<｜tool▁calls▁begin｜>", "<｜tool▁call▁begin｜>",
        "<|tool_calls_section_begin|>", "<|tool_call_begin|>",
        "\"tool_calls\"",
        "\"name\":",
        "\"function\":",
    )

    /**
     * When a tool in [toolNames] is called, the tool name is returned in [text]; if not, it is null.
     * Only look at the tool name that appears after the first call mark, and take the first one: words
     * with the same name as the tool such as wait and find in the words before the mark are not counted.
     */
    fun find(text: String, toolNames: Collection<String>): String? {
        val start = MARKERS.mapNotNull { m -> text.indexOf(m).takeIf { it >= 0 } }.minOrNull() ?: return null
        return toolNames.filter { it.isNotEmpty() }
            .mapNotNull { name -> wholeWord(name).find(text, start)?.let { name to it.range.first } }
            .minByOrNull { it.second }?.first
    }

    private fun wholeWord(name: String) = Regex("(?<![A-Za-z0-9_])" + Regex.escape(name) + "(?![A-Za-z0-9_])")
}
