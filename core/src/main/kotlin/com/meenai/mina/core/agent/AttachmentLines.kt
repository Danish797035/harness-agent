package com.meenai.mina.core.agent

/**
 * How to write attachments in the user's words: At the end of the message, each attachment has one line
 * "Attachment:/workspace/inbox/<name> (<size>)". These lines are saved into the conversation and handed over
 * to the task as they are. Accordingly, the conversation page draws attachments into photo grids and
 * file cards (app's AttachmentMessage); language prompts, language check and memory of answers, and
 * quotations of skills are not included in these lines (see [UserWords]). The messages in old
 * conversations are also written this way.
 */
object AttachmentLines {
    private const val PREFIX = "附件:"

    /** There can be brackets in the name ("Report (1).csv"): the size only recognizes the "number + B/KB/MB" in the last pair of brackets; the path is under /workspace in the sandbox. */
    private val LINE = Regex("^附件:(/workspace/.+)\\((\\d+(?:\\.\\d+)? (?:B|KB|MB))\\)$")

    /** An unpacked sentence: [files] is the path of each attachment and the size written in the message ("2 KB"), in order of appearance; [words] is the words other than the attachment line (remove the leading and trailing blanks). */
    data class Parsed(val files: List<Pair<String, String>>, val words: String)

    fun line(path: String, size: String): String = "$PREFIX$path($size)"

    /** The words come first, and one line for each attachment follows; when the words are empty, there are only attachment lines. */
    fun compose(text: String, files: List<Pair<String, String>>): String =
        (listOf(text).filter { it.isNotEmpty() } + files.map { (p, s) -> line(p, s) }).joinToString("\n")

    fun parse(text: String): Parsed {
        val files = mutableListOf<Pair<String, String>>()
        val rest = mutableListOf<String>()
        for (l in text.lines()) {
            val m = LINE.matchEntire(l.trim())
            if (m != null) files += m.groupValues[1] to m.groupValues[2] else rest += l
        }
        if (files.isEmpty()) return Parsed(emptyList(), text.trim())
        return Parsed(files, rest.joinToString("\n").trim())
    }

    /** If the attachment line is removed, it will be returned without the attachment line. */
    fun words(text: String): String = parse(text).let { if (it.files.isEmpty()) text else it.words }
}

