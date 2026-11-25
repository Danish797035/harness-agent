package com.meenai.mina.core.verify

import com.meenai.mina.core.observation.InputHints
import com.meenai.mina.core.observation.ObsKind
import com.meenai.mina.core.observation.Observation
import com.meenai.mina.core.observation.Place

/** For testing: press the given screen reading, command output, and tool facts in order to record them in a ledger (the steps are numbered in sequence). */
fun ledgerOf(screens: List<String> = emptyList(), outputs: List<String> = emptyList(), facts: List<String> = emptyList()): EvidenceLedger =
    EvidenceLedger().also { l ->
        var step = 0
        screens.forEach { l.add(++step, ObsKind.Read, null, 0, it) }
        outputs.forEach { l.add(++step, ObsKind.Output, null, 0, it) }
        facts.forEach { l.add(++step, ObsKind.Fact, null, 0, it) }
    }

/** Write the URL or package name in the test: the one starting with http is the web page, and the rest is the App. */
fun placeOf(where: String?): Place? = where?.let { if (it.startsWith("http")) Place.page(it) else Place.app(it) }

/** Abbreviation for testing: Step [step] reads [text] from [where]. */
fun EvidenceLedger.add(
    step: Int, kind: ObsKind, where: String?, atMs: Long, text: String,
    inputs: List<String>? = null, focused: String? = null, authored: String? = null, title: String? = null,
): Observation? = add(obs(step, kind, where, text, inputs, focused, authored, title), step, atMs)

/** For testing: an observation. */
fun obs(
    step: Int, kind: ObsKind, where: String?, text: String,
    inputs: List<String>? = null, focused: String? = null, authored: String? = null, title: String? = null,
) = Observation(kind, text, place = placeOf(where), title = title, authored = authored, inputs = inputs?.let { InputHints(it, focused) }, step = step)

/** For testing: Take another look at the text you read (why the empty string was not read). */
fun freshOf(text: String): List<Observation> = if (text.isEmpty()) emptyList() else listOf(Observation(ObsKind.Read, text, inputs = InputHints(emptyList())))
