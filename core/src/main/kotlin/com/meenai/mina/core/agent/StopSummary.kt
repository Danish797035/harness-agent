package com.meenai.mina.core.agent

import com.meenai.mina.core.model.Author
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import com.meenai.mina.core.observation.ObsKind
import com.meenai.mina.core.prompt.Prompts
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.tool.PACKAGE_NAME_RE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * After the user stops midway, he writes a few lines of explanation based on what he has done this time
 * (what he did, what he saw, how many things were done in batches, and which ones were not completed).
 * This is shown to the user as this round of answers and recorded in the conversation (the next sentence
 * "continue" is mentioned above). Only read the running record this time, and no longer operate
 * anything: please ask for the model once ([writer] takes priority, if there is an error, change to
 * [fallback]), no tools will be given; in total, the maximum wait is [timeoutMs], if there is no model,
 * error, or timeout, just press the record and directly spell a section ([plain]). The ending is still
 * cancelled, but this paragraph is added.
 */
class StopSummary(
    private val writer: ChatModel?,
    private val fallback: ChatModel? = null,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    /**
     * [events] is the event recorded during this run (at least it is active if there is no record);
     * [draft] is the word being written when stopped (can be empty). [label] replaces the tool name with
     * the one on the interface, and [appLabel] replaces the package name with the App name (only the
     * domain name is left in "website domain name").
     */
    suspend fun summarize(
        goal: String, events: List<RunEvent>, draft: String?,
        label: (String) -> String = { it }, appLabel: (String) -> String = { it },
    ): String {
        val input = facts(goal, events, draft, label, appLabel)
        val messages = listOf(Message(Role.User, listOf(Part.Text(input)), Author.User))
        val written = withTimeoutOrNull(timeoutMs) {
            for (candidate in listOfNotNull(writer, fallback?.takeUnless { it === writer })) {
                val text = try {
                    candidate.complete(Prompts.role("stop_summary"), messages, emptyList())
                        .parts.filterIsInstance<Part.Text>().joinToString("") { it.text }.trim()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                if (!text.isNullOrBlank()) return@withTimeoutOrNull text.clip(MAX_REPLY)
            }
            null
        }
        return written ?: plain(goal, events, draft, label, appLabel)
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 12_000L
        private const val MAX_REPLY = 1200
        private const val MAX_LINES = 60
        private const val MAX_READS = 2
        private const val READ_CHARS = 800
        private const val NOTE_CHARS = 120
        private const val DRAFT_CHARS = 1200

        /** Is there anything to explain: I have done at least one thing, or I have written something when I stopped. If not, it just says "stopped". */
        fun worthSaying(events: List<RunEvent>, draft: String?): Boolean =
            events.any { it is RunEvent.Activity } || !draft.isNullOrBlank()

        /**
         * Materials handed to the model (with an upper limit, without pictures): user requirements,
         * things done (one line at a time, with the intention of the model writing at that step),
         * recently read content, half of the words written when I stopped. It's all wrapped up in the
         * label; the words inside are material, not instructions to the person who wrote it.
         */
        fun facts(goal: String, events: List<RunEvent>, draft: String?, label: (String) -> String = { it }, appLabel: (String) -> String = { it }): String {
            val notes = notes(events)
            val lines = mutableListOf<String>()
            val said = mutableSetOf<Int>()
            var lastObserve: String? = null
            for (a in events.filterIsInstance<RunEvent.Activity>()) {
                val where = a.target?.let { where(it, appLabel) }
                val what = listOfNotNull(label(a.label), where, a.title?.takeIf(String::isNotBlank)).joinToString(" · ")
                val tags = listOfNotNull("sent out".takeIf { a.outward && !a.failed }, "failed".takeIf { a.failed })
                val line = "Step ${a.step} $what" + if (tags.isEmpty()) "" else "(${tags.joinToString(", ")})"
                // Read the same place consecutively and remember only one line
                if (a.kind == "observe" && line == lastObserve) continue
                lastObserve = if (a.kind == "observe") line else null
                val note = notes[a.step]?.takeIf { said.add(a.step) }
                lines += if (note == null) line else "$line -- intent: $note"
            }
            val shown = if (lines.size <= MAX_LINES) lines
                else lines.take(20) + "…(${lines.size - MAX_LINES} omitted)…" + lines.takeLast(MAX_LINES - 20)
            val reads = events.filterIsInstance<RunEvent.Observed>().filter { it.kind == ObsKind.Read.wire && it.text.isNotBlank() }
                .takeLast(MAX_READS).map { it.text.clip(READ_CHARS) }
            return buildString {
                append("<request>\n").append(untag(goal)).append("\n</request>\n\n")
                append("<actions>\n").append(untag(shown.joinToString("\n").ifEmpty { "(nothing done yet)" })).append("\n</actions>")
                if (reads.isNotEmpty()) append("\n\n<last_read>\n").append(untag(reads.joinToString("\n----\n"))).append("\n</last_read>")
                draft?.trim()?.takeIf(String::isNotEmpty)?.let { append("\n\n<draft>\n").append(untag(it.clip(DRAFT_CHARS))).append("\n</draft>") }
            }
        }

        /**
         * Without explanation from the model, just record according to the records: which apps and
         * websites have been visited, how many times various operations have been performed, how many
         * times have been sent to the outside world (and which ones, according to the intention of that
         * step), how many steps have not been successful, and half of the words were written when
         * stopping; in the end, the unfinished part was not continued.
         */
        fun plain(goal: String, events: List<RunEvent>, draft: String?, label: (String) -> String = { it }, appLabel: (String) -> String = { it }): String {
            val acts = events.filterIsInstance<RunEvent.Activity>()
            val notes = notes(events)
            val lines = mutableListOf<String>()
            val places = acts.mapNotNull { a -> a.target?.let { placeOf(it, appLabel) } }.distinct()
            val seen = acts.filter { it.kind == "observe" && !it.failed && !it.title.isNullOrBlank() }
                .map { listOfNotNull(it.target?.let { t -> where(t, appLabel) }, it.title).joinToString(": ") }.distinct()
            val done = acts.filter { it.kind != "observe" && !it.failed && !it.outward }
                .groupingBy { label(it.label) }.eachCount()
            val outward = acts.filter { it.outward && !it.failed }
            val failed = acts.count { it.failed }
            if (places.isNotEmpty()) lines += "Went to:" + list(places, 4)
            if (seen.isNotEmpty()) lines += "Looked at:" + list(seen, 4)
            if (done.isNotEmpty()) lines += "Did:" + done.entries.joinToString(", ") { (what, n) -> if (n > 1) "$what x$n" else what }
            if (outward.isNotEmpty()) {
                val where = outward.mapNotNull { a -> a.target?.let { placeOf(it, appLabel) } }.distinct()
                val which = outward.map { it.step }.distinct().mapNotNull(notes::get).distinct()
                lines += "Sent out ${outward.size} times" + (if (where.isEmpty()) "" else "(${where.joinToString(", ")})") +
                    (if (which.isEmpty()) "" else ":" + list(which, 5))
            }
            if (failed > 0) lines += "$failed steps failed"
            draft?.trim()?.takeIf(String::isNotEmpty)?.let { lines += "Was writing when stopped:" + it.clip(200) }
            val head = if (acts.isEmpty()) "Stopped. Nothing was done yet." else "Stopped. Here is what it did before stopping:"
            val body = lines.joinToString("\n") { "- $it" }
            val tail = "\u201c${goal.trim().clip(40)}\u201d was not finished, and nothing else was done on it."
            return listOf(head, body, tail).filter(String::isNotEmpty).joinToString("\n")
        }

        /** The description of the active object for people to see: "website domain name" only leaves the domain name, the package name is replaced with the app name, and the other (website address, query terms) are left as they are; the empty ones are null. */
        private fun where(target: String, appLabel: (String) -> String): String? = when {
            target.isBlank() -> null
            target.startsWith(SITE_PREFIX) -> target.removePrefix(SITE_PREFIX).trim()
            target.startsWith("网站 ") -> target.removePrefix("网站 ").trim()
            PACKAGE_NAME_RE.matches(target) -> appLabel(target)
            else -> target
        }.takeUnless { it.isNullOrBlank() }

        /** The name of the active object if it is an App or website; otherwise (URL, query, file) it is null. */
        private fun placeOf(target: String, appLabel: (String) -> String): String? =
            if (target.startsWith(SITE_PREFIX) || target.startsWith("网站 ") || PACKAGE_NAME_RE.matches(target)) where(target, appLabel) else null

        /** The object on the web page in the running record is written as "Site <域名>". */
        private const val SITE_PREFIX = "Site "

        private fun list(items: List<String>, max: Int) =
            items.take(max).joinToString(", ") + if (items.size > max) " and ${items.size} more" else ""

        /** When writing the model for each step: first use the progress description in the reply instead of the reason in the calling parameters. */
        private fun notes(events: List<RunEvent>): Map<Int, String> {
            val out = mutableMapOf<Int, String>()
            for (r in events.filterIsInstance<RunEvent.ModelReply>()) {
                if (!r.acted || r.tool == null) continue
                val said = r.text.trim().ifEmpty { null } ?: reason(r.argsJson)
                said?.takeIf(String::isNotBlank)?.let { out[r.step] = it.replace('\n', ' ').clip(NOTE_CHARS) }
            }
            return out
        }

        private fun reason(argsJson: String?): String? = argsJson?.let {
            runCatching { (Json.parseToJsonElement(it) as? JsonObject)?.get("reason") as? JsonPrimitive }.getOrNull()?.contentOrNull?.trim()
        }

        private val TAG = Regex("</?\\s*(request|actions|last_read|draft)\\s*>", RegexOption.IGNORE_CASE)

        /** Remove the tags that come with the material to prevent it from being closed in advance and pretending that the following words are the input of the person who wrote it. */
        private fun untag(s: String) = s.replace(TAG, "")

        private fun String.clip(max: Int) = if (length <= max) this else take(max) + "…"
    }
}

