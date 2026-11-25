package com.meenai.mina.core.uistate

import com.meenai.mina.core.prompt.Feedback

/**
 * Typing into an input box that cannot be seen in the tree (the App draws its own interface, there is no
 * input box in the tree, but it may hold the focus): the words are pasted into the clipboard, and the
 * words in the box cannot be read back. They can only compare the words read on the screen before and
 * after typing (OCR). It does not require the input method to be played: the select all and paste keys
 * are sent to the window with the focus on this screen, and the frame with the focus will be pasted
 * without going through the input method.
 */
object UnseenField {
    private const val NO_FOCUS = "no_focus"
    /** When typing at a point, the front and back points remain unchanged after pasting: that point is not a typing box. */
    const val POINT_NOT_TYPED = "point_not_typed"

    /**
     * What to report after pasting. [keyboardUp]: The input method is playing on the screen before
     * pasting (the focus is indeed on a certain box). When the input method is not played, the words on
     * the front and rear screens remain unchanged (MISMATCH): Most of the focus is not on the frame, and
     * no_focus is reported. Please click on the frame first, and no longer blindly re-paste; when the
     * input method is played, the verification results are still handed over (if it is not typed, the
     * input tool reports). [atPoint]: pasted after tapping a point given by the model; if before/after text
     * did not change at all, that point failed to accept characters (whether or not the keyboard was up),
     * so report [POINT_NOT_TYPED] and ask the model to relocate the field without auto-retrying.
     */
    fun typed(
        text: String, before: List<VisibleTextLine>?, after: List<VisibleTextLine>?, changed: Boolean, keyboardUp: Boolean, atPoint: Boolean = false,
    ): ActionResult {
        val v = verify(text, before, after, changed)
        if (v == Verification.MISMATCH && atPoint) return ActionResult.Failed(POINT_NOT_TYPED, Feedback["type.point_not_typed"])
        if (v == Verification.MISMATCH && !keyboardUp) return ActionResult.Failed(NO_FOCUS, Feedback["type.unseen_not_focused"])
        return ActionResult.Ok(changed = changed, verification = v, hint = Feedback["type.unseen_pasted"])
    }

    /** What will be reported after selecting all and adding the delete key: The input method is not played and the interface has not changed at all. It is the same as [typed]. Please click the box on the model first; hand over the rest as they are, and attach the clearing method. [atPoint] is the same as [typed]. */
    fun cleared(r: ActionResult, keyboardUp: Boolean, atPoint: Boolean = false): ActionResult = when {
        r !is ActionResult.Ok -> r
        r.changed == false && atPoint -> ActionResult.Failed(POINT_NOT_TYPED, Feedback["clear.point_not_cleared"])
        r.changed == false && !keyboardUp -> ActionResult.Failed(NO_FOCUS, Feedback["clear.unseen_not_focused"])
        else -> r.copy(hint = Feedback["clear.unseen_cleared"])
    }

    /** Click that point and it will change to [pkg] (click on the link, button): No typing, please go back to the model and find the frame again. */
    fun tapSwitched(pkg: String): ActionResult = ActionResult.Failed(POINT_NOT_TYPED, Feedback.format("type.point_switched", "pkg" to pkg))

    /** When the words to be checked (after removing blanks and punctuation) exceed this length, only look at [PROBE] characters at the beginning and end: long sentences OCR often misunderstands a few words in the middle. */
    private const val LONG = 16
    private const val PROBE = 6

    /**
     * Check results: [Verification.MATCH] appears one more time on the screen after typing than before
     * typing; MISMATCH is found when the word is read before and after, but the word is the same, and
     * the interface and screen do not change; and UNKNOWN is used for the rest (which time the word is
     * not read, the word is changed, but the word cannot be found). [changed]: There are changes in the
     * interface or screen before and after typing.
     */
    fun verify(typed: String, before: List<VisibleTextLine>?, after: List<VisibleTextLine>?, changed: Boolean): Verification {
        val want = key(typed)
        if (after != null && before != null && want.isNotEmpty()) {
            val probes = if (want.length <= LONG) listOf(want) else listOf(want.take(PROBE), want.takeLast(PROBE))
            val a = joined(after); val b = joined(before)
            if (probes.all { count(a, it) > count(b, it) }) return Verification.MATCH
        }
        if (!changed && after != null && before != null && joined(after) == joined(before)) return Verification.MISMATCH
        return Verification.UNKNOWN
    }

    /** Remove the rows whose center falls in [box] (0–999 of [l,t,r,b], such as the input method window): the words on the keyboard and candidate words are not counted in the box. */
    fun outside(lines: List<VisibleTextLine>, box: List<Int>?): List<VisibleTextLine> {
        if (box == null || box.size != 4) return lines
        return lines.filterNot { l ->
            l.bounds.size == 4 && (l.bounds[0] + l.bounds[2]) / 2 in box[0]..box[2] && (l.bounds[1] + l.bounds[3]) / 2 in box[1]..box[3]
        }
    }

    /** Connect them from top to bottom and from left to right; OCR will split a sentence into several lines, and will also misunderstand punctuation and spaces, only letters, numbers and Chinese characters. */
    private fun joined(lines: List<VisibleTextLine>): String =
        lines.sortedWith(compareBy({ it.bounds.getOrElse(1) { 0 } }, { it.bounds.getOrElse(0) { 0 } })).joinToString("") { key(it.text) }

    private fun key(s: String) = s.lowercase().filter(Char::isLetterOrDigit)

    private fun count(hay: String, needle: String): Int {
        var n = 0; var i = hay.indexOf(needle)
        while (i >= 0) { n++; i = hay.indexOf(needle, i + needle.length) }
        return n
    }
}
