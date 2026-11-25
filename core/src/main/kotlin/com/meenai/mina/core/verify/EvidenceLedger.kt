package com.meenai.mina.core.verify

import com.meenai.mina.core.observation.InputHints
import com.meenai.mina.core.observation.ObsKind
import com.meenai.mina.core.observation.Observation
import com.meenai.mina.core.record.Redactor

/**
 * All materials actually read by the assistant in a task (evidence ledger): screen reading, web page
 * reading, command output and read files, and facts in tool results. The runtime verifier and the
 * evaluation verification use the same set of ledgers and the same selection rules ([forAnswer]), so
 * both sides will not look at each other.
 *
 * - - Desensitization before deposit ([Redactor.evidence]), each article is truncated according to the
 * source; only the most recent version of the same original article will be kept (the source will be
 * updated accordingly).
 * Words in the input box read after taking over that are not entered by the assistant (filled in by the
 * user when taking over) will not be remembered; lines in the command output that appear as they are in
 * the command itself (echoed) will not be remembered.
 * - - The total amount has an upper limit ([capChars]): if it exceeds the amount, the oldest one will be
 * thrown away. This is a safe upper limit on memory that cannot be touched by normal tasks.
 * - - Items submitted for verification ([forAnswer]): each item containing a certain fact in the answer
 * (not missing a single item), the item closest to each App or URL mentioned in the answer
 * (What I read later may be inconsistent with the answer), and the [RECENT] items I read recently.
 * Arrange in order of reading. The code verification looks at the full text of these entries; the
 * verification model only looks at their excerpts ([excerpt]: the line that corresponds to the fact and
 * the lines before and after, the total number of words has an upper limit), and the source (which step,
 * which source, which App or website, web page title). The selection only determines which ones to show,
 * and the verification rules ([Grounding]) remain unchanged: things that have not been read before are
 * not in the ledger, and no matter how much you choose, they cannot be verified.
 * - - The words entered by the assistant into the input box are not supported by the answer: the words
 * in the input box, the input box, and the associated lines starting with these words when the focus is
 * on will be removed during selection;
 * Titles that happen to be the same as the search terms in the results are still retained. I don’t know
 * which ones are in the input boxes (old records). I’ll delete the entire line equal to the typed words.
 */
class EvidenceLedger(private val capChars: Int = STORE_CHARS) {
    private val entries = ArrayDeque<Observation>()
    private val typed = mutableSetOf<String>()
    private var total = 0
    private var seq = 0
    private var afterTakeover = false

    val all: List<Observation> get() = entries.toList()
    val isEmpty: Boolean get() = entries.isEmpty()

    /**
     * Remember an observation; if it is empty after desensitization and truncation, do not remember it.
     * Return to the one you wrote down (fill in the number, step, and time; [step] and [atMs] are at
     * this moment). [Observation.inputs] in the observation is the word in the input box at that time;
     * [Observation.authored] is what the assistant wrote himself (the running command): the lines that
     * appear as they are are echoed and not recorded.
     */
    fun add(o: Observation, step: Int = o.step, atMs: Long = o.atMs): Observation? {
        val ins = o.inputs?.names?.map(::norm)?.filter { it.isNotEmpty() }
        var lines = Redactor.evidence(o.text).lines()
        // The words filled in by the user themselves during takeover (not typed by the assistant in the
        // input box) are not retained.
        if (afterTakeover && ins != null) lines = lines.filterNot { l -> norm(l).let { n -> n in ins && typed.none { it in n } } }
        o.authored?.let(::norm)?.takeIf { it.isNotEmpty() }?.let { a -> lines = lines.filterNot { l -> norm(l).let { it.isNotEmpty() && it in a } } }
        val t = clip(o.kind, lines.joinToString("\n"))
        if (t.isBlank()) return null
        val place = o.place?.let { it.copy(name = Redactor.evidence(it.name)) }
        // The echo has been removed, and the item written down no longer contains what the assistant
        // wrote himself.
        return insert(o.copy(text = t, place = place, step = step, atMs = atMs, id = ++seq, authored = null,
            inputs = ins?.let { InputHints(it, o.inputs.focused?.let(::norm)?.takeIf { f -> f.isNotEmpty() }) },
            title = o.title?.let(Redactor::evidence)?.takeIf { it.isNotBlank() }))
    }

    /**
     * Put back an observation that has been recorded during the run as it is (the observed in the run
     * record has been desensitized, truncated, and the echo has been removed, and the number is also the
     * same at that time): No more filtering, just remove the same old one as when it was entered, and
     * keep the total upper limit. It is used to reconstruct the ledger offline, which is the same as the
     * ledger at runtime.
     */
    fun restore(o: Observation): Observation {
        seq = maxOf(seq, o.id)
        return insert(o)
    }

    private fun insert(stored: Observation): Observation {
        entries.indexOfFirst { it.text == stored.text && it.place == stored.place }.takeIf { it >= 0 }?.let { total -= entries.removeAt(it).text.length }
        entries.addLast(stored)
        total += stored.text.length
        while (total > capChars && entries.size > 1) total -= entries.removeFirst().text.length
        return stored
    }

    /** This original text (after being desensitized and truncated as when it was entered into the account) is already in the account book. */
    fun has(kind: ObsKind, text: String): Boolean = clip(kind, Redactor.evidence(text)).let { t -> entries.any { it.text == t } }

    /** The assistant uses type_text to type in the words; just note that they are not in the password box. */
    fun noteTyped(text: String) {
        norm(text).takeIf { it.isNotEmpty() }?.let { typed += it }
    }

    /** Handed over to the user: after that, words entered in the input box in the screen reading that were not typed by the assistant will no longer be credited. */
    fun noteTakeover() {
        afterTakeover = true
    }

    /** A copy of the same ledger (used when handing the ledger to the verifier at runtime, subsequent screen readings will not affect this copy). */
    fun copy(): EvidenceLedger = EvidenceLedger(capChars).also { c ->
        c.entries.addAll(entries); c.typed.addAll(typed); c.total = total; c.seq = seq; c.afterTakeover = afterTakeover
    }

    /** Items submitted for verification: See class description. The input box of each item that has been removed will be displayed; if there is no word after being removed, it will not be selected. */
    fun forAnswer(goal: String, answer: String): List<Observation> {
        val cands = entries.mapNotNull(::withoutEcho)
        val holds = cands.indices.filter { i -> Grounding.check(goal, answer, listOf(cands[i].text)).let { it.claims.size > it.unmatched.size } }
        val said = (goal + "\n" + answer).lowercase()
        val places = holds.mapNotNull { cands[it].where } + cands.mapNotNull { it.where }.filter { w -> placeNames(w).any { it in said } }
        val latest = places.distinct().map { w -> cands.indexOfLast { it.where == w } }
        val recent = (cands.size - RECENT until cands.size).filter { it >= 0 }
        return (holds + latest + recent).distinct().sorted().map { cands[it] }
    }

    /** The number of the most recently credited item (0 if it has not yet been credited); together with [readSince], it is judged whether it has been re-read after a certain time. */
    val lastId: Int get() = seq

    /** Things have been read since number [id] (reading screens, web pages, command output, and files; facts reported by tools do not count as reading). */
    fun readSince(id: Int): Boolean = entries.any { it.id > id && it.kind != ObsKind.Fact }

    /** Entries where the facts in the answer match (the input box echo has been removed, in order of reading); it is empty when there are no verifiable facts in the answer. The source of the answer comes from it. */
    fun supporting(goal: String, answer: String): List<Observation> =
        entries.mapNotNull(::withoutEcho).filter { e -> Grounding.check(goal, answer, listOf(e.text)).let { it.claims.size > it.unmatched.size } }

    private fun withoutEcho(e: Observation): Observation? {
        if (typed.isEmpty() || e.kind != ObsKind.Read) return e
        val lines = e.text.lines()
        val ins = e.inputs?.names
        val kept = if (ins == null) lines.filterNot { norm(it) in typed } else {
            // In the input box containing the typed words, remove one line from each one (result titles
            // with the same words remain as they are)
            val left = ins.filter { n -> typed.any { it in n } }.groupingBy { it }.eachCount().toMutableMap()
            // There are also words typed in the focused input box: lines starting with these words and
            // longer are association
            val suggesting = e.inputs?.focused?.let { f -> typed.filter { it in f } }.orEmpty()
            lines.filterNot { l ->
                val n = norm(l)
                val k = left[n] ?: 0
                if (k > 0) { left[n] = k - 1; true } else n !in ins && suggesting.any { n.startsWith(it) && n.length > it.length }
            }
        }
        return if (kept.size == lines.size) e else kept.joinToString("\n").takeIf { it.isNotBlank() }?.let { e.copy(text = it) }
    }

    companion object {
        /** The safe upper limit for the total number of words in the ledger. */
        const val STORE_CHARS = 400_000
        /** The target upper limit for the total number of words in the excerpts submitted to the verification model (no entries containing answers to facts will be lost, and each entry will be shortened if it cannot fit). */
        const val EXCERPT_CHARS = 6_000
        /** When excerpting, leave a few lines before and after the actual line (gradually reduce it to 0 when it cannot fit). */
        const val EXCERPT_CONTEXT = 2
        /** Entries that do not correspond to facts (most recent somewhere, most recently read) are only given so many words at the beginning. */
        const val HEAD_CHARS = 600
        /** If you can’t fit it all in, leave at least this many words for each excerpt. */
        const val MIN_EXCERPT = 300
        /** Submit the most recent items for verification. */
        const val RECENT = 1
        /** Answers from screen readers, web pages, and other AIs can have at most this many words each. */
        const val READ_CHARS = 6_000
        /** Command output, file content, and tool facts can each contain at most this many words (truncated from beginning to end). */
        const val OUTPUT_CHARS = 4_000

        private val WS = Regex("\\s+")
        private fun norm(s: String) = s.replace(WS, "")
        private val PLACE_SPLIT = Regex("[^\\p{L}\\p{N}]+")
        private val PLACE_NOISE = setOf("com", "cn", "net", "org", "www", "http", "https", "android", "app", "html", "m")

        /** Words in the app or website that can be mentioned in questions and answers (paragraphs of package names and domain names, those that are too short and too common will not be counted). */
        private fun placeNames(where: String): List<String> =
            where.lowercase().split(PLACE_SPLIT).filter { it.length >= 3 && it !in PLACE_NOISE }

        /**
         * Excerpt for the verification model: the lines [hits] in [text] and the lines [context] before
         * and after. Write a line "[N lines omitted]" in the omitted place; if there is no corresponding
         * line (or [withHead]), add the word [HEAD_CHARS] at the beginning. [cap] is the maximum number
         * of characters in this sentence (truncated by line, the truncated parts are also marked).
         */
        fun excerpt(text: String, hits: Set<Int>, context: Int, cap: Int = Int.MAX_VALUE, withHead: Boolean = false): String {
            val ls = text.lines()
            var size = 0
            val head = ls.indices.takeWhile { i -> (size < HEAD_CHARS).also { size += ls[i].length + 1 } }.toSet()
            val near = hits.flatMap { h -> (h - context)..(h + context) }.filter { it in ls.indices }.toSet()
            val keep = if (hits.isEmpty() || withHead) head + near else near
            val out = StringBuilder()
            var skipped = 0
            fun flush() { if (skipped > 0) { out.append("[省略 $skipped 行]\n"); skipped = 0 } }
            var room = cap
            for (i in ls.indices) {
                if (i !in keep || room <= 0) { skipped++; continue }
                flush()
                val l = ls[i]
                if (l.length <= room) { out.append(l).append('\n'); room -= l.length + 1 }
                else { out.append(l.take(room)).append("[本行后面省略 ${l.length - room} 字]\n"); room = 0 }
            }
            flush()
            return out.toString().trimEnd()
        }

        private fun clip(kind: ObsKind, t: String): String = when (kind) {
            ObsKind.Read, ObsKind.Reply, ObsKind.UserSaid, ObsKind.Attachment -> t.take(READ_CHARS)
            ObsKind.Output, ObsKind.Fact ->
                if (t.length <= OUTPUT_CHARS) t else t.take(OUTPUT_CHARS * 3 / 4) + "\n…(中间省略)…\n" + t.takeLast(OUTPUT_CHARS / 4)
        }
    }
}
