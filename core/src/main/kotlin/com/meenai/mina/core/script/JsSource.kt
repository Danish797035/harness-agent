package com.meenai.mina.core.script

/**
 * Lexical checking of JS source code written by model or site commands.
 *
 * [usesImport]: Is there any word `import` besides strings, comments, and regular literals (dynamic
 * import will run the module in the global scope, bypassing the read-only packaging). Whether `/` is a
 * division sign or the beginning of a regular expression, sometimes it is not clear just by looking at
 * the lexicon (after `)`, `}`, `++`, and after words such as of/yield/await, which may be keywords or
 * variable names): At this time, go through both readings, and any reading of import in the code counts.
 * The reading method is (position, reading method of `/`, bracket stack) to remove duplication and will
 * not be doubled; the reading method that cannot be read (regular or the string is not closed and
 * reaches the end of the line) is invalid. It is better to block by mistake than to miss.
 */
object JsSource {
    private enum class Slash { Regex, Divide, Both }

    private data class State(val pos: Int, val slash: Slash, val stack: List<Char>)

    /** The following `/` must be the keyword starting with the regular expression. */
    private val BEFORE_EXPRESSION = setOf("return", "typeof", "instanceof", "in", "new", "delete", "void", "throw", "case", "do", "else")
    /** It may be a keyword or a variable name: the following `/` can be read in both ways. */
    private val EITHER = setOf("of", "yield", "await", "let", "async", "get", "set", "static")

    fun usesImport(code: String): Boolean {
        val seen = HashSet<State>()
        val work = ArrayDeque<State>()
        work += State(0, Slash.Regex, emptyList())
        while (work.isNotEmpty()) {
            val s = work.removeLast()
            if (!seen.add(s)) continue
            if (Lexer(code, s) { work += it }.run()) return true
        }
        return false
    }

    /** Start reading one reading from [start]. When encountering the ambiguous `/`, hand over the two readings to [fork] and stop. Read import returns true. */
    private class Lexer(private val code: String, start: State, private val fork: (State) -> Unit) {
        private var i = start.pos
        private var slash = start.slash
        private val stack = start.stack.toMutableList()
        private val n = code.length

        fun run(): Boolean {
            while (i < n) {
                val c = code[i]
                when {
                    c.isWhitespace() -> i++
                    c == '/' && peek(1) == '/' -> { while (i < n && !lineEnd(code[i])) i++ }
                    c == '/' && peek(1) == '*' -> { val e = code.indexOf("*/", i + 2); i = if (e < 0) n else e + 2 }
                    c == '/' -> when (slash) {
                        Slash.Divide -> { i++; slash = Slash.Regex }
                        Slash.Regex -> { if (!regex()) return false }
                        Slash.Both -> {
                            fork(State(i, Slash.Regex, stack.toList()))
                            fork(State(i, Slash.Divide, stack.toList()))
                            return false
                        }
                    }
                    c == '\'' || c == '"' -> { if (!string(c)) return false; slash = Slash.Divide }
                    c == '`' -> { i++; if (!template()) return false }
                    c == '{' -> { stack += '{'; i++; slash = Slash.Regex }
                    c == '}' -> {
                        i++
                        if (stack.lastOrNull() == '`') { stack.removeAt(stack.lastIndex); if (!template()) return false }
                        else { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex); slash = Slash.Both }
                    }
                    c == ')' -> { i++; slash = Slash.Both }
                    c == ']' -> { i++; slash = Slash.Divide }
                    (c == '+' || c == '-') && peek(1) == c -> { i += 2; slash = Slash.Both }
                    c.isDigit() || (c == '.' && peek(1)?.isDigit() == true) -> { while (i < n && (code[i].isLetterOrDigit() || code[i] == '.' || code[i] == '_')) i++; slash = Slash.Divide }
                    identStart(c) -> {
                        val word = word()
                        if (word == "import") return true
                        slash = when (word) {
                            in BEFORE_EXPRESSION -> Slash.Regex
                            in EITHER -> Slash.Both
                            else -> Slash.Divide
                        }
                    }
                    else -> { i++; slash = Slash.Regex }
                }
            }
            return false
        }

        private fun peek(k: Int): Char? = code.getOrNull(i + k)
        private fun lineEnd(c: Char) = c == '\n' || c == '\r' || c == ' ' || c == ' '
        private fun identStart(c: Char) = c == '$' || c == '_' || c == '\\' || c.isLetter()
        private fun identPart(c: Char) = identStart(c) || c.isDigit() || c == '‌' || c == '‍'

        /** An identifier; `\uXXXX`, `\u{…}` is escaped and then compared. */
        private fun word(): String {
            val out = StringBuilder()
            while (i < n && identPart(code[i])) {
                if (code[i] == '\\' && peek(1) == 'u') {
                    val braced = peek(2) == '{'
                    val from = i + if (braced) 3 else 2
                    val to = if (braced) code.indexOf('}', from).takeIf { it > 0 } ?: n else minOf(from + 4, n)
                    code.substring(from, to).toIntOrNull(16)?.let { out.appendCodePoint(it) }
                    i = if (braced) minOf(to + 1, n) else to
                } else out.append(code[i++])
            }
            return out.toString()
        }

        /** String literal; if it has not been closed to the end of the line (and is not a continuation of the line), it is false. */
        private fun string(q: Char): Boolean {
            i++
            while (i < n) {
                val c = code[i]
                when {
                    c == '\\' -> i += 2
                    c == q -> { i++; return true }
                    lineEnd(c) -> return false
                    else -> i++
                }
            }
            return false
        }

        /** The text part of the template string is read from the current position to the backtick (end) or `${` (pushed on the stack and returned to the code). */
        private fun template(): Boolean {
            while (i < n) {
                val c = code[i]
                when {
                    c == '\\' -> i += 2
                    c == '`' -> { i++; slash = Slash.Divide; return true }
                    c == '$' && peek(1) == '{' -> { stack += '`'; i += 2; slash = Slash.Regex; return true }
                    else -> i++
                }
            }
            return false
        }

        /** Regular literals (including `/` in character classes) and flags; if not closed at the end of the line, it is false. */
        private fun regex(): Boolean {
            i++
            var inClass = false
            while (i < n) {
                val c = code[i]
                when {
                    lineEnd(c) -> return false
                    c == '\\' -> i += 2
                    c == '[' -> { inClass = true; i++ }
                    c == ']' -> { inClass = false; i++ }
                    c == '/' && !inClass -> {
                        i++
                        while (i < n && identPart(code[i])) i++
                        slash = Slash.Divide
                        return true
                    }
                    else -> i++
                }
            }
            return false
        }
    }
}

