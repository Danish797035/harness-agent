package com.meenai.mina.core.guard

import com.meenai.mina.core.tool.Verb
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Reading screens, viewing pictures, and diagnosing screens: Guards are repeated based on tools, not parameters. */
internal val DIAGNOSTIC_VERBS = setOf(Verb.Observe, Verb.ListElements, Verb.Screenshot, Verb.Describe, Verb.Diagnose, Verb.ViewPastScreens)
/** Read-only and non-moving pages: use them repeatedly to read when the page has not changed, managed by [OperationGuards.read]. */
internal val PAGE_READ_VERBS = DIAGNOSTIC_VERBS + setOf(Verb.ReadPage, Verb.EvaluateJs, Verb.SiteCommand)

/** The fingerprint of the tool result (excluding the screen reading number that is changed every time). */
internal fun resultFingerprint(content: JsonObject): Long {
    val text = JsonObject(content - "snapshotId" - "guard" - "advice").toString()
    var h = -0x340d631b7bdddcdbL
    for (b in text.toByteArray(Charsets.UTF_8)) h = (h xor (b.toLong() and 0xff)) * 0x100000001b3L
    return h
}

/** Screen fingerprint: 64-bit FNV-1a of element list text. Even if there is no screenshot of the pure text model, it can be judged that "the screen has not changed". */
internal fun elementsHash(elements: JsonArray?): Long? {
    elements ?: return null
    // Excluding numbering: When stable numbering is on, repeated elements with the same name on the same
    // screen will have new numbers every time they are read, and should not be regarded as changing the
    // screen.
    val text = JsonArray(elements.map { e -> (e as? JsonObject)?.let { JsonObject(it - "ref") } ?: e }).toString()
    var h = -0x340d631b7bdddcdbL
    for (b in text.toByteArray(Charsets.UTF_8)) {
        h = (h xor (b.toLong() and 0xff)) * 0x100000001b3L
    }
    return h
}
