package com.meenai.mina.core.uistate

import kotlin.math.hypot

/**
 * Do not give a number and refer to an element by description: [text] is the word it displays or the
 * accessible name (it can also be the prompt of the input box when typing), [role] is optional, and when
 * [field] is true, only the input box is found (for typing).
 */
data class ElementQuery(val text: String, val role: String? = null, val field: Boolean = false)

sealed interface DescribedMatch {
    /** [exact]: name (or hint) matches the description identically; otherwise matched by prefix or substring, and the result states what actually matched. */
    data class Found(val element: UiElement, val exact: Boolean) : DescribedMatch
    /** There is more than one match; [candidates] can be operated first, and the rest are in the order of screen reading. */
    data class Ambiguous(val candidates: List<UiElement>) : DescribedMatch
    data object None : DescribedMatch
}

/**
 * Find the only element in a screen by description. Remove whitespace and case-insensitive before
 * comparison. Search in layers from strict to broad, whichever layer has the result first will be used:
 * the name (or prompt) is exactly the same → the name starts with a description → the name contains the
 * description (this layer only recognizes elements that can be operated, so as not to accidentally
 * contain these words in a paragraph of text). The one that can be operated on the same layer (typing to
 * see if it can be input, the others to see if it can be clicked; the unavailable ones are not counted)
 * takes precedence: there is exactly one of them; there are several, or there is not one that can be
 * operated but there are several that match, it is [DescribedMatch.Ambiguous], no guess. [role] Only
 * look at this character when given.
 */
object DescribedTarget {
    fun match(elements: List<UiElement>, q: ElementQuery): DescribedMatch {
        val want = norm(q.text)
        if (want.isEmpty()) return DescribedMatch.None
        val pool = elements.filter { e ->
            (!q.field || e.editable) && (q.role == null || e.role.equals(q.role.trim(), ignoreCase = true))
        }
        val tiers: List<Pair<(String) -> Boolean, Boolean>> = listOf(
            { s: String -> s == want } to false, { s: String -> s.startsWith(want) } to false, { s: String -> want in s } to true,
        )
        for ((i, tier) in tiers.withIndex()) {
            val (test, actionableOnly) = tier
            val hits = pool.filter { e -> labels(e, q.field).any(test) && (!actionableOnly || usable(e, q.field)) }
            if (hits.isEmpty()) continue
            val usable = hits.filter { usable(it, q.field) }
            return when {
                usable.size == 1 -> DescribedMatch.Found(usable[0], exact = i == 0)
                usable.isEmpty() && hits.size == 1 -> DescribedMatch.Found(hits[0], exact = i == 0)
                else -> DescribedMatch.Ambiguous(usable + (hits - usable.toSet()))
            }
        }
        return DescribedMatch.None
    }

    /** Can be operated: Type to see if you can input, and other items to see if you can click or input; pages marked as unavailable will not be counted. */
    fun usable(e: UiElement, field: Boolean): Boolean =
        UiStates.DISABLED !in e.states && (if (field) e.editable else e.clickable || e.editable)

    /** Another character with a name closest to [e] (different name from it): Use it to identify which one is the candidate with the same name. */
    fun neighbour(e: UiElement, elements: List<UiElement>): String? = elements
        .filter { it !== e && it.name.isNotBlank() && norm(it.name) != norm(e.name) }
        .minByOrNull { hypot((it.centerX - e.centerX).toDouble(), (it.centerY - e.centerY).toDouble()) }?.name

    /** The element can be described by: name; the input box also has its own prompt. */
    private fun labels(e: UiElement, field: Boolean): List<String> =
        listOfNotNull(e.name, e.placeholder.takeIf { field || e.editable }).map(::norm).filter { it.isNotEmpty() }

    private fun norm(s: String) = s.filterNot(Char::isWhitespace).lowercase()
}
