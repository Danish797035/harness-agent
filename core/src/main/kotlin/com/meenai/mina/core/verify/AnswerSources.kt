package com.meenai.mina.core.verify

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

/**
 * One source of answer: refers back to an observation in the evidence ledger ([obsId], see
 * [com.meenai.mina.core.observation.Observation.id]), whatever it is (an interface, a page, a file, a
 * piece of command output, a piece of reply). [kind] is the type of observation
 * ([com.meenai.mina.core.observation.ObsKind.wire]; in the old record it is screen / web / app_ai /
 * output / fact); [source] is the ability to give it (the old record does not have it); [place] is where
 * it is read (App package name, "website domain name" or URL, command or file), [title] is the title of
 * that page; [step] Which step was read; [mediaIds] is the screen number saved in that step (not empty).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class AnswerSource(
    @JsonNames("entryId") val obsId: Int, val kind: String, val place: String?, val title: String?, val step: Int,
    val mediaIds: List<Int> = emptyList(), val source: String? = null,
)

/** The source of the answer is found in the code, not written in the model: the few observations in the evidence ledger that match the facts of the answer. */
object AnswerSources {
    /** At most, there are so many places listed. */
    const val MAX = 6

    /**
     * [media] is the screen number saved in each step (step → number). If there is a valid fact in the
     * answer, list these observations; if there is no valid fact in the answer ("Bluetooth is on"), use
     * the last observation, which is where the result stated in the answer lies. Empty if nothing has
     * been read.
     */
    fun of(ledger: EvidenceLedger, goal: String, answer: String, media: Map<Int, List<Int>>): List<AnswerSource> {
        val hits = ledger.supporting(goal, answer).ifEmpty { listOfNotNull(ledger.all.lastOrNull()) }
        return hits.map { AnswerSource(it.id, it.kind.wire, it.where, it.title, it.step, media[it.step].orEmpty(), it.source) }
            .takeLast(MAX)
    }
}
