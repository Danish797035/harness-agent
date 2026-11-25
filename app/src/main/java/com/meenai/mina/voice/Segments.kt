package com.meenai.mina.voice

/**
 * During the press and hold period, the recognition service ends by itself for a period and then
 * restarts to put the segments together. Chinese and Japanese paragraphs are directly connected, and a
 * space is added between paragraphs in other texts (English, etc.). Pure logic, no touching the system.
 */
class Segments {
    private var done = ""
    private var pending = ""

    /** The intermediate result of the current paragraph; return to the full text at this moment. */
    fun partial(text: String): String {
        pending = text.trim()
        return full()
    }

    /** The current paragraph has been recognized, and [text] is the final text of this paragraph. */
    fun segmentDone(text: String) {
        done = join(done, text.trim())
        pending = ""
    }

    /** The completed sections plus the intermediate results of the unfinished section; use it after releasing it when you cannot wait for the final result. */
    fun full(): String = join(done, pending).trim()

    companion object {
        /** So many characters at the end (by code points) may be changed by the recognition service, and the interface will be painted lighter. */
        private const val TAIL = 4

        fun split(full: String): SpeechEvent.Partial {
            val n = full.codePointCount(0, full.length)
            if (n <= TAIL) return SpeechEvent.Partial("", full)
            val cut = full.offsetByCodePoints(0, n - TAIL)
            return SpeechEvent.Partial(full.substring(0, cut), full.substring(cut))
        }

        private fun join(a: String, b: String): String = when {
            a.isEmpty() -> b
            b.isEmpty() -> a
            noSpace(a.codePointBefore(a.length)) || noSpace(b.codePointAt(0)) || b.codePointAt(0).toChar() in ATTACHED -> a + b
            else -> "$a $b"
        }

        /** Punctuation that immediately follows the previous text and is not preceded by a space. */
        private const val ATTACHED = ",.!?;:)"

        /** It is a blank space itself, or it is a text that does not use spaces as participles (kanji, kana and full-width punctuation). */
        private fun noSpace(cp: Int): Boolean {
            if (Character.isWhitespace(cp)) return true
            when (Character.UnicodeScript.of(cp)) {
                Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> return true
                else -> {}
            }
            val block = Character.UnicodeBlock.of(cp)
            return block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
                block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
        }
    }
}
