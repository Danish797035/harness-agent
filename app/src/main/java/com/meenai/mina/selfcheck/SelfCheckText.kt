package com.meenai.mina.selfcheck

import java.util.Locale

/**
 * The comparison used by the self-test when recognizing the text on the self-test page. The text
 * provided by the screen reader may not be exactly the same as what is written on the page: the button
 * will be displayed in all caps by the system theme by default, and the text read by the screen reader
 * will be in uppercase; there may be one more or less blank space, or it may be replaced by continuous
 * spaces. So first straighten both sides and then compare: remove the leading and trailing blanks, merge
 * the middle blanks into an ordinary space, and do not distinguish between upper and lower case.
 */
internal object SelfCheckText {
    private val SPACES = Regex("[\\s\\p{Z}]+")

    fun normalize(s: String): String = s.replace(SPACES, " ").trim().uppercase(Locale.ROOT).lowercase(Locale.ROOT)

    fun same(a: String, b: String): Boolean = normalize(a) == normalize(b)

    fun contains(text: String, part: String): Boolean = normalize(text).contains(normalize(part))

    /**
     * Text with a number ("clicked %d times" "timer %d" "line %d"). [template] is the whole sentence
     * after replacing the numbers with [sentinel]; when recognizing the numbers, compare the two
     * paragraphs before and after the numbers according to the comparison method above, and the middle
     * can only be numbers.
     */
    class Numbered(template: String, sentinel: String) {
        private val parts = normalize(template).split(normalize(sentinel))

        fun matches(s: String): Boolean {
            if (parts.size != 2) return false
            val (head, tail) = parts
            val n = normalize(s)
            if (n.length <= head.length + tail.length || !n.startsWith(head) || !n.endsWith(tail)) return false
            return n.substring(head.length, n.length - tail.length).all(Char::isDigit)
        }
    }
}
