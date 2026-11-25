package com.meenai.mina.core.cap.web

/**
 * The CSS selector handed to the page cannot select elements based on the value attribute:
 * `input[value^="h"]` Try one by one to guess the value of the password box and hidden field. If you
 * only look at the literal words, you will miss it, so first restore it to the way it is seen by the
 * browser according to the rules of the CSS read selector: remove comments, escape escapes (`v\61 lue`
 * is value), and remove the entire paragraph in quotation marks (the attribute name is not written in
 * quotation marks, and the comment mark and `[value` in quotation marks do not count); the attribute
 * name can be preceded by a namespace (`*|value`, `|value`). It is better to recognize more when
 * restoring: the escaped square brackets (class name `.\5b value`) are also counted as selecting by
 * value. Anything with `url(` will be counted as selecting by value: url( without quotation marks after
 * it is a mark up to `)`. The comment marks and quotes inside neither start the comment nor start the
 * string. The above restoration does not match; `:is()` only discards the wrong item, and the following
 * `[value` still takes effect. It is not used in the selector. Web_script mina.query is searched in
 * Kotlin ([selectsByValue]); the search method of the read-only script is called in the page, using the
 * JS version of the same set of rules ([JS]).
 */
object SelectorPolicy {
    fun selectsByValue(selector: String): Boolean = plain(selector).let { URL_FUNCTION.containsMatchIn(it) || BY_VALUE.containsMatchIn(it) }

    /** `[`, optional namespace, value, no more words in the name will follow. Only CSS whitespace (spaces, tabs, line breaks) is recognized, which is the same as the JS version. */
    private val BY_VALUE = Regex("""\[[ \t\n]*(?:[^ \t\n\[\]|]*[ \t\n]*\|[ \t\n]*)?value(?![\w\x{80}-\x{10FFFF}-])""", RegexOption.IGNORE_CASE)

    /** After restoration, `url(` appears (the name can be spelled out with escape): CSS starts a url mark here. */
    private val URL_FUNCTION = Regex("""url\(""", RegexOption.IGNORE_CASE)

    /** Restore according to CSS pronunciation: first unify line breaks into \n; change comments into a space; change the entire paragraph in quotation marks into `""`; convert into the word it represents. */
    internal fun plain(selector: String): String {
        val s = selector.replace("\r\n", "\n").replace('\r', '\n').replace('\u000C', '\n')
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '/' && i + 1 < s.length && s[i + 1] == '*' -> {
                    val end = s.indexOf("*/", i + 2)
                    i = if (end < 0) s.length else end + 2
                    out.append(' ')
                }
                c == '"' || c == '\'' -> {
                    i++
                    // In quotation marks: a backslash plus a newline is a line continuation, and other
                    // escapes are eaten as usual; an unescaped newline ends the paragraph (this is also
                    // the case with browsers)
                    while (i < s.length && s[i] != c && s[i] != '\n') {
                        i = when {
                            s[i] != '\\' -> i + 1
                            i + 1 < s.length && s[i + 1] == '\n' -> i + 2
                            else -> escape(s, i).second
                        }
                    }
                    if (i < s.length && s[i] == c) i++
                    out.append("\"\"")
                }
                c == '\\' && (i + 1 >= s.length || s[i + 1] != '\n') -> {
                    val (ch, next) = escape(s, i)
                    out.append(ch)
                    i = next
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** [i] is a backslash: return the escaped word and the next position. 1–6 hexadecimal digits (the following space counts as an escape), otherwise the next word itself. */
    private fun escape(s: String, i: Int): Pair<String, Int> {
        if (i + 1 >= s.length) return "\uFFFD" to s.length
        var j = i + 1
        while (j < s.length && j - i <= 6 && s[j].isHex()) j++
        if (j == i + 1) {
            val cp = s.codePointAt(j)
            return String(Character.toChars(cp)) to j + Character.charCount(cp)
        }
        val n = s.substring(i + 1, j).toInt(16)
        val ch = if (n == 0 || n in 0xD800..0xDFFF || n > 0x10FFFF) "\uFFFD" else String(Character.toChars(n))
        return ch to if (j < s.length && s[j] in " \t\n") j + 1 else j
    }

    private fun Char.isHex() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    /** JS version: Define `selectsByValue(selector string)` in the page, corresponding to the above line by line. */
    val JS: String = """
  const selectsByValue = (() => {
    const HEX = /^[0-9a-fA-F]${'$'}/;
    const escape = (s, i) => {
      if (i + 1 >= s.length) return ['\ufffd', s.length];
      let j = i + 1;
      while (j < s.length && j - i <= 6 && HEX.test(s[j])) j++;
      if (j === i + 1) { const cp = s.codePointAt(j); return [String.fromCodePoint(cp), j + (cp > 0xffff ? 2 : 1)]; }
      const n = parseInt(s.slice(i + 1, j), 16);
      const ch = n === 0 || (n >= 0xd800 && n <= 0xdfff) || n > 0x10ffff ? '\ufffd' : String.fromCodePoint(n);
      return [ch, j < s.length && ' \t\n'.includes(s[j]) ? j + 1 : j];
    };
    const plain = (raw) => {
      const s = raw.replace(/\r\n?|\f/g, '\n');
      let out = '', i = 0;
      while (i < s.length) {
        const c = s[i];
        if (c === '/' && s[i + 1] === '*') { const end = s.indexOf('*/', i + 2); i = end < 0 ? s.length : end + 2; out += ' '; }
        else if (c === '"' || c === "'") {
          i++;
          while (i < s.length && s[i] !== c && s[i] !== '\n') i = s[i] !== '\\' ? i + 1 : s[i + 1] === '\n' ? i + 2 : escape(s, i)[1];
          if (s[i] === c) i++;
          out += '""';
        } else if (c === '\\' && s[i + 1] !== '\n') { const [ch, next] = escape(s, i); out += ch; i = next; }
        else { out += c; i++; }
      }
      return out;
    };
    const BY_VALUE = /\[[ \t\n]*(?:[^ \t\n\[\]|]*[ \t\n]*\|[ \t\n]*)?value(?![\w\u0080-\uffff-])/i;
    const URL_FUNCTION = /url\(/i;
    return (s) => { const p = plain(s); return URL_FUNCTION.test(p) || BY_VALUE.test(p); };
  })();
"""
}
