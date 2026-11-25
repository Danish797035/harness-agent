package com.meenai.mina.core.userscreen

/**
 * How to write when the user's sentence refers to his current screen: a single line `[[screen:<number>]]`,
 * and the other lines are postscript (optional). This sentence is stored in the dialogue, handed over to
 * the task, as an interruption or answer as usual; the dialogue page and card are drawn as screenshot
 * bubbles accordingly, and are converted into instructions when given to the model (see
 * [UserScreenPrompt]).
 */
object UserScreenMessage {
    private val LINE = Regex("^\\[\\[screen:([A-Za-z0-9_-]{1,64})]]$")

    /** Break it down in one sentence: [screens] is the screen number inside (in order of appearance), [words] is the postscript (remove the leading and trailing blanks, if not, it will be an empty string). */
    data class Parsed(val screens: List<String>, val words: String) {
        val hasScreen: Boolean get() = screens.isNotEmpty()
    }

    fun line(id: String): String {
        require(LINE.matches("[[screen:$id]]")) { "bad screen id" }
        return "[[screen:$id]]"
    }

    /** The line on the screen is in front, and the postscript is in the back; when the postscript is empty, there is only that line. */
    fun compose(id: String, words: String): String = listOf(line(id), words.trim()).filter(String::isNotEmpty).joinToString("\n")

    fun parse(text: String): Parsed {
        val screens = mutableListOf<String>()
        val rest = mutableListOf<String>()
        for (l in text.lines()) {
            val m = LINE.matchEntire(l.trim())
            if (m != null) screens += m.groupValues[1] else rest += l
        }
        return Parsed(screens, if (screens.isEmpty()) text.trim() else rest.joinToString("\n").trim())
    }

    fun has(text: String): Boolean = text.lineSequence().any { LINE.matches(it.trim()) }
}
