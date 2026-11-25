package com.meenai.mina.core.uistate

import kotlin.math.hypot

/**
 * Characteristics used to recognize the "same element" in the new interface. [context] is the most
 * recent element with a name before it in the traversal order to prevent buttons with the same name
 * elsewhere from being mistakenly recognized after scrolling. [hint]: The name is the prompt/preset word
 * of the input box (it will rotate); [inList]: The element is in the list container (the list items will
 * be reused, and the same position may be replaced by other items).
 */
data class ElementLocator(
    val role: String,
    val name: String,
    val resourceId: String?,
    val centerX: Int,
    val centerY: Int,
    val context: String?,
    val hint: Boolean = false,
    val inList: Boolean = false,
)

sealed interface Relocation {
    /** [index] is the index in the new list. */
    data class Found(val index: Int) : Relocation
    /** Still there, but the context has changed or moved too far. */
    data object Moved : Relocation
    /** There is no name or resource ID, or there are several of the same one. */
    data object Ambiguous : Relocation
    /** It is no longer available in the new interface. */
    data object Gone : Relocation
}

/** Maximum allowed center offset when retrieving: This ratio of the short side of the screen. */
const val RELOCATE_MAX_SHIFT = 0.15

/** The upper limit of "still in place" offset: the ratio of the short side of the screen. */
const val SAME_SPOT_SHIFT = 0.02

/**
 * After the number expires, look for the unique counterpart of [previous] in [after]. It is found only
 * when the following conditions are met: the type, name, and resource ID are the same, and there is only
 * this one in the old list [before] and the new list; the context is the same; the center movement does
 * not exceed the short side [RELOCATE_MAX_SHIFT].
 *
 * When the entire page is not moving (see [stationary]), relax two points to deal with pages such as
 * carousels and countdowns that only partially change:
 * - - If the context changes but the element is still in place and is not in the list container (the
 * list items will be reused), it will still be found;
 * - - The name has changed, but the old and new resources have the same resource ID, the same type, are
 * still in place and are the only ones in place, and the only difference between the names is numbers
 * and spaces.
 * ("Skip 3" → "Skip 2"), or the old name is the preset word (hint) of the input box rotation, it is also
 * found. "Follow" → "Already followed", "Next step" → "Confirm payment" and other things that have
 * changed meanings are not counted.
 */
fun relocate(previous: ElementLocator, before: List<ElementLocator>, after: List<ElementLocator>, shortSide: Int): Relocation {
    if (previous.name.isBlank() && previous.resourceId.isNullOrBlank()) return Relocation.Ambiguous
    val spot = shortSide * SAME_SPOT_SHIFT
    val still by lazy { stationary(before, after, spot) }
    fun same(e: ElementLocator) = e.role == previous.role && e.name == previous.name && e.resourceId == previous.resourceId
    val candidates = after.withIndex().filter { same(it.value) }
    if (candidates.isEmpty()) {
        if (previous.resourceId.isNullOrBlank() || !still) return Relocation.Gone
        val inPlace = after.withIndex().filter { (_, e) ->
            e.role == previous.role && e.resourceId == previous.resourceId && shift(e, previous) <= spot
        }
        val found = inPlace.singleOrNull() ?: return Relocation.Gone
        val renamedAlike = previous.hint || stripDigits(found.value.name) == stripDigits(previous.name)
        return if (renamedAlike) Relocation.Found(found.index) else Relocation.Gone
    }
    if (candidates.size > 1 || before.count(::same) > 1) return Relocation.Ambiguous
    val (index, found) = candidates.single()
    val moved = shift(found, previous)
    if (moved > shortSide * RELOCATE_MAX_SHIFT) return Relocation.Moved
    if (found.context != previous.context && !(still && moved <= spot && !previous.inList)) return Relocation.Moved
    return Relocation.Found(index)
}

private fun stripDigits(s: String) = s.filterNot { it.isDigit() || it.isWhitespace() }

private fun shift(a: ElementLocator, b: ElementLocator) =
    hypot((a.centerX - b.centerX).toDouble(), (a.centerY - b.centerY).toDouble())

/**
 * The page as a whole has not moved: there are at least two famous elements that appear before and after
 * (there are very few page elements such as open-screen pages), and more than 80% of them are still in
 * place. When scrolling the list or changing pages, most of the elements are moved or disappeared, which
 * is not satisfactory.
 */
internal fun stationary(before: List<ElementLocator>, after: List<ElementLocator>, spot: Double): Boolean {
    fun key(e: ElementLocator) = Triple(e.role, e.name, e.resourceId)
    val afterByKey = after.filter { it.name.isNotBlank() }.groupBy(::key)
    var shared = 0
    var inPlace = 0
    for ((k, list) in before.filter { it.name.isNotBlank() }.groupBy(::key)) {
        val a = afterByKey[k]?.singleOrNull() ?: continue
        if (list.size != 1) continue
        shared++
        if (shift(list[0], a) <= spot) inPlace++
    }
    return shared >= 2 && inPlace >= shared * 0.8
}

/** Input box reading and writing. [read] returns the actual text visible to the user (an empty string when the prompt is displayed), and null if it cannot be read (the password box and node are no longer there). */
interface TextField {
    fun read(): String?
    fun write(text: String): Boolean
}

/** Remove all spaces before comparison: The input box may automatically add spaces to the number groups. */
private fun normText(s: String) = s.filterNot { it.isWhitespace() }

/**
 * Write [text] and read it back for verification. If there is any inconsistency, try again, up to
 * [attempts] times; [settle] waits for the input box to be updated after each write. It only recognizes
 * "the text read after writing happens to be the target"; if it is already the target before writing,
 * clear it first. If it cannot be cleared, it will not be able to distinguish whether it is written or
 * not, and UNKNOWN will be returned. An empty string means clearing, and reading it back as empty means
 * MATCH. Some input boxes always report the same text (such as prompts) for accessibility, and the
 * readback never matches: each readback is the same as before writing, and at least one write is
 * successful. However, [reacted] says that when new text (associative words, etc.) containing the
 * entered text appears on the interface after input, the App actually receives the input and returns
 * UNKNOWN instead of MISMATCH. [secret] (password box) only writes once, does not retry, and does not
 * read back: the password box cannot be read back to the original text, and retrying will only write the
 * same paragraph into the box again.
 */
suspend fun typeAndVerify(
    field: TextField,
    text: String,
    attempts: Int = 2,
    settle: suspend () -> Unit = {},
    reacted: () -> Boolean = { false },
    secret: Boolean = false,
): Verification {
    if (secret) {
        field.write(text)
        return Verification.UNKNOWN
    }
    val want = normText(text)
    var baseline = field.read() ?: run {
        // If you can’t read it, write it as usual, but you can’t check it.
        field.write(text)
        return Verification.UNKNOWN
    }
    if (want.isNotEmpty() && normText(baseline) == want) {
        field.write("")
        settle()
        baseline = field.read() ?: return Verification.UNKNOWN
        if (normText(baseline) == want) {
            field.write(text)
            return Verification.UNKNOWN
        }
    }
    var stuck = true
    var wrote = false
    repeat(attempts) {
        if (!field.write(text)) return@repeat
        wrote = true
        settle()
        val actual = field.read() ?: return Verification.UNKNOWN
        if (normText(actual) == want) return Verification.MATCH
        if (actual != baseline) stuck = false
    }
    return if (wrote && stuck && reacted()) Verification.UNKNOWN else Verification.MISMATCH
}
