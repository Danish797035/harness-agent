package com.meenai.mina.core.memory

import kotlin.math.ln
import kotlin.math.max

/**
 * The machine's lexical segmentation: Chinese characters are divided into two adjacent characters (when
 * a single Chinese character is formed into a word, a single character is retained), and English and
 * numbers are divided into words and lowercase. [withUnigrams] also gives the single word of each
 * Chinese character (for query, so that single-word search terms such as "spicy" can be matched).
 */
object Tokenize {
    private val WORD = Regex("[A-Za-z0-9]+")

    fun terms(text: String, withUnigrams: Boolean = false): List<String> {
        val out = mutableListOf<String>()
        val han = StringBuilder()
        fun flushHan() {
            if (han.length == 1) out += han.toString()
            else {
                for (i in 0 until han.length - 1) out += han.substring(i, i + 2)
                if (withUnigrams) han.forEach { out += it.toString() }
            }
            han.clear()
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (isHan(c)) {
                han.append(c); i++; continue
            }
            flushHan()
            val m = WORD.matchAt(text, i)
            if (m != null) { out += m.value.lowercase(); i = m.range.last + 1 } else i++
        }
        flushHan()
        return out
    }

    internal fun isHan(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN

    internal fun isUnigram(term: String) = term.length == 1 && isHan(term[0])
}

/**
 * Content + keywords are built in inversion, and BM25 is scored (k1=1.2, b=0.75; the search term tf
 * counts twice). When the number of memory items is small, the IDF should be smoothed by at least
 * [MIN_CORPUS] items, otherwise nothing will pass the threshold when one or two items are memorized.
 * Only "relevant" entries are returned: the search terms match, or at least two different query terms
 * (two characters/words) are hit, or the only hit term is only this one and is its search term. Single
 * words only participate in scoring and do not count in these three judgments.
 */
class MemoryIndex(private val items: List<MemoryItem>) {
    private val docs: List<Map<String, Int>> = items.map { item ->
        val counts = HashMap<String, Int>()
        Tokenize.terms(item.content).forEach { counts.merge(it, 1, Int::plus) }
        // Single-word search terms ("spicy") are themselves broken into single words; multi-word search
        // terms are not separated into single words to prevent single-word noise from overshadowing
        // two-word hits.
        item.keywords.flatMap { Tokenize.terms(it) }.forEach { counts.merge(it, KEYWORD_TF, Int::plus) }
        counts
    }
    private val lengths = docs.map { d -> d.values.sum() }
    private val keywordTerms: List<Set<String>> = items.map { it.keywords.flatMap { k -> Tokenize.terms(k.lowercase()) }.toSet() }

    /** Sort by score from high to low. */
    fun search(query: String, limit: Int, includePending: Boolean = false): List<Pair<MemoryItem, Double>> {
        val eligible = items.indices.filter { includePending || items[it].status == MemoryStatus.active }
        if (eligible.isEmpty() || limit <= 0) return emptyList()
        val corpus = max(eligible.size, MIN_CORPUS).toDouble()
        val avgLen = eligible.sumOf { lengths[it] }.toDouble() / eligible.size
        val q = query.lowercase()
        val terms = Tokenize.terms(q, withUnigrams = true).distinct().filter { it !in STOP_TERMS }
        if (terms.isEmpty()) return emptyList()
        val termSet = terms.toSet()
        val df = terms.associateWith { t -> eligible.count { t in docs[it] } }
        val idf = df.mapValues { (_, n) -> ln(1 + (corpus - n + 0.5) / (n + 0.5)) }

        return eligible.mapNotNull { i ->
            val doc = docs[i]
            val matched = terms.filter { it in doc }
            if (matched.isEmpty()) return@mapNotNull null
            val multi = matched.filterNot(Tokenize::isUnigram)
            val keywordHit = items[i].keywords.any { kw ->
                val k = kw.lowercase()
                val kt = Tokenize.terms(k)
                q.contains(k) || (kt.isNotEmpty() && kt.all { it in termSet })
            }
            // Only hit one word: This word must only exist in this article and be the search term of
            // this article (common words such as "later" will not be counted)
            val relevant = keywordHit || multi.size >= 2 ||
                (multi.size == 1 && df.getValue(multi.single()) == 1 && multi.single() in keywordTerms[i])
            if (!relevant) return@mapNotNull null
            val norm = K1 * (1 - B + B * lengths[i] / avgLen.coerceAtLeast(1.0))
            val score = matched.sumOf { t ->
                val tf = doc.getValue(t)
                idf.getValue(t) * tf * (K1 + 1) / (tf + norm)
            }
            items[i] to score
        }.sortedByDescending { it.second }.take(limit)
    }

    private companion object {
        const val K1 = 1.2
        const val B = 0.75
        const val KEYWORD_TF = 2
        const val MIN_CORPUS = 50
        // Only applies to query words: verbal function word combinations, which are found in almost
        // every sentence. They cannot be considered relevant alone.
        val STOP_TERMS = setOf(
            "帮我", "给我", "一下", "我的", "不要", "这个", "那个", "可以", "一个", "用户",
            "今天", "这次", "现在", "什么", "怎么", "我们", "一些", "看看",
        )
    }
}
