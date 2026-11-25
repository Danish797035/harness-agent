package com.meenai.mina.core.verify

/**
 * Code back-up: After the verifier passes the test, it uses a rule that has nothing to do with the model
 * to check the textual evidence again. The code only verifies what it verifies - whether it exists
 * literally, and does not determine whether a value belongs to what the answer says (that is a matter of
 * verifying the model, see the verifier prompt word). In the body of the answer (before "Based on:"):
 * - - The original text in quotation marks ("..." "..." "..." "..." '...', except what is already in the
 * title) must be found in a certain source;
 * Use a letter in the quotation marks to replace the number you wrote in the answer ("Today + N" refers
 * to "Today + 2,271" in the text), which refers to the way you write the answer, not the words on the
 * screen;
 * - - Numbers with units, currencies, percent signs or magnitudes (thousands/billions/thousands),
 * decimals, integers with three or more digits, numbers sandwiched in letters, must be found in some
 * material as a whole word;
 * - - The date and time (October 23, 2026-10-23, 14:05) must also be found.
 * In "Basis:", only the citations are checked: each quotation must be found in a certain source of
 * material (the... in the quotation is divided into several paragraphs, and each paragraph must be in
 * the same place); the rest of the words in the basis (source, website, reading time, count in the
 * narrative) are not facts to be checked. If they are correct, they can help locate it, and if they are
 * not correct, they will not be rejected. Integers within two digits without units are not required
 * anywhere. The numbers in the question and the original text are considered known. The other two
 * sources are also known, and each only proves what it has proven: "Tomorrow" and "Tuesday" are written
 * in the question or evidence, and the day is calculated according to the running clock; the result of
 * the calculation formula (a operator b = c, a, b are all matched in the evidence) stated in the answer,
 * and it is stated that it is the rounding of a divisor (approximate, ≈). The first two sides of the
 * comparison are written in the same way: full-width and half-width characters (NFKC), zero-width
 * characters, reference numbers and footnotes, thousandths, million/billion conversion, punctuation and
 * blanks; both sides are compared on a line basis. One line of the answer is not spelled with the next
 * line (the list number at the beginning of the line is removed by line), and the materials are not
 * spelled into a whole string; the line "Current App:" is not counted as on-screen text. The boundary of
 * the reference number should be explicitly asserted before and after, without using \b: Java's \b
 * treats Chinese characters as word characters, and the 46 in "@e46 display" will be regarded as the
 * number in the answer.
 */
internal object Grounding {
    private val QUOTED = Regex("「([^」]*)」|『([^』]*)』|“([^”]*)”|\"([^\"]*)\"|‘([^’]*)’")
    private val EVIDENCE = Regex("依据\\**\\s*[:：]")
    /**
     * The hundreds of millions/millions/thousands after the number are orders of magnitude (you can only
     * leave a blank in between, and the App often arranges them as "15.61 million heat"; if there are
     * punctuation points, it is not); the thousands in "kilometers", "kilograms" and "kilowatts" are
     * part of the unit, not the order of magnitude. The serial number at the beginning of the line and
     * the beginning of the quotation ("5 Spirited Away") is first removed by pressing [ORDINAL].
     */
    private val NUMBER = Regex("(?<!\\d)(?<!\\d[.,])\\d+(?:[.,]\\d+)*(?!\\d)([ \\t]*(?:[亿万]|千(?![米克瓦卡帕焦])))?")
    private val THOUSANDS = Regex("^\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?$")
    private val DIGITS = Regex("\\d+")
    /** Words that follow the number and indicate that it is a quantity (they will not be followed by the serial number): unit, magnitude, quantifier. */
    private const val UNIT_WORDS = "公里|千米|米|厘米|毫米|公斤|千克|斤|克|吨|元|块|角|小时|分钟|秒|岁|度|分|人|次|个|条|件|万|亿|千[米克瓦卡帕焦]|[A-Za-z%℃°]"

    /** The serial number is not a number to be checked: Nth item/step/name..., first N items/item..., Top N, No.N, #N, list number at the beginning of the line (1. 2, 3)). "The 2026th" is a value, but it doesn't count. */
    private val ORDINAL = Regex(
        "第[ \\t]*\\d+[ \\t]*(?=[条个集页项张篇步名位行章节轮次题])|前[ \\t]*\\d+[ \\t]*(?=[个条名位项篇张])|(?i:top)[ \\t]*\\d+|(?i:no)\\.[ \\t]*\\d+|#\\d+" +
            "|(?m)^[ \\t]*\\d+[ \\t]*[.、)）](?!\\d)" +
            // The name separated by a space after the serial number at the beginning of the line or the
            // beginning of the quotation ("5 Spirited Away..."): is not a value
            "|(?m)(?:^|(?<=[「『“\"‘]))[ \\t]*\\d{1,3}[ \\t]*[.、](?!\\d)|(?m)(?:^|(?<=[「『“\"‘]))[ \\t]*\\d{1,3}[ \\t]+(?=\\p{L})(?!$UNIT_WORDS)",
    )
    /** Date and time; separated by., only the three segments of year, month and day are recognized, so as not to regard 1561.5 as the date. */
    private val DATE = Regex(
        "(?<!\\d)(?:\\d{4}\\s*[-/年]\\s*\\d{1,2}(?:\\s*[-/月]\\s*\\d{1,2}\\s*[日号]?)?|\\d{4}\\.\\d{1,2}\\.\\d{1,2}" +
            "|\\d{1,2}\\s*月\\s*\\d{1,2}\\s*[日号]?|\\d{1,2}\\s*:\\s*\\d{2}(?:\\s*:\\s*\\d{2})?)(?!\\d)",
    )
    /** Immediately after the number, it indicates that it is a unit of magnitude (the quantifiers "items" and "pieces" used for counting do not count: they are answered by counting by yourself). */
    private val UNIT_AFTER = Regex(
        "^\\s*(?:%|‰|元|块|角|美元|欧元|日元|港元|英镑|℃|°|度|公里|千米|米|厘米|毫米|公斤|千克|斤|克|吨|小时|分钟|秒|毫秒|岁|英寸|寸|折|星" +
            "|(?:km|cm|mm|kg|mAh|GB|MB|KB|TB|Hz|ms|[kKwWmgGM])(?![A-Za-z]))",
    )
    private val CURRENCY_BEFORE = Regex("[¥$€£]\\s*$")
    private val ZERO_WIDTH = Regex("[\\u200B\\u200C\\u200D\\uFEFF\\u2060]")
    /** The emphasis and code marks (**bold**, __bold__, ~~delete~~, `code`) in the answer are just layout, not the content; a single * may be a multiplication sign and does not move. */
    private val MARKUP = Regex("\\*\\*|__|~~|`")
    private val FRAGMENT_BREAK = Regex("[^\\p{L}]+")
    private val PIECE_BREAK = Regex("[^\\p{L}\\p{N}]+")
    /**
     * How to write references to different models: element numbers (@e16, @v2, possibly in brackets),
     * square bracket footnotes ([1], [^2], [3]). If it is not the content of the answer, remove it
     * before comparison. The boundary is asserted before and after: it must also be recognized if it is
     * followed by Chinese characters (shown by @e46).
     */
    private val REF_CITATION = Regex(
        "[(\\[【]\\s*@[ev]\\d+(?:\\s*[,、]\\s*@[ev]\\d+)*\\s*[)\\]】]|(?<![A-Za-z0-9_])@[ev]\\d+(?!\\d)",
    )
    private val FOOTNOTE = Regex("\\[\\^?\\d{1,3}]|【\\d{1,3}】")
    private val MAGNITUDE = mapOf("亿" to 8, "万" to 4, "千" to 3)
    const val APP_HEADER = "当前 App:"
    /** If the text fragment in the basis is too short and will happen to fit on any screen ("on screen" "settings"), it will not be counted. */
    private const val MIN_FRAGMENT = 4
    /** When a line on the screen appears in the basis, this line must have at least several words. */
    private const val MIN_LINE_IN_BASIS = 2

    private data class Num(val value: String, val magnitude: String) {
        /** The value after unit conversion (53,000 is the same as 53,000); if not calculated, it is null. */
        val scaled: String? = value.toBigDecimalOrNull()?.let { v ->
            (MAGNITUDE[magnitude]?.let { v.movePointRight(it) } ?: v).stripTrailingZeros().toPlainString()
        }
        fun matches(screen: Num) = (value == screen.value && (magnitude.isEmpty() || magnitude == screen.magnitude)) ||
            (scaled != null && scaled == screen.scaled)
    }

    /** [Token]: The numbers sandwiched in English letters (G7541, a3f899c) are compared by one word, not by numbers. [Quote] contains citations in the basis. */
    enum class Kind { Quote, Number, Date, Token }

    /** A specific fact in the answer. Numbers and dates are values that can be written as text; quotations without numbers may only appear on the screen (such as when a radio button is selected). */
    data class Claim(val kind: Kind, val text: String) {
        val visualOnly: Boolean get() = kind == Kind.Quote && text.none(Char::isDigit)
    }

    /**
     * The result of getting to the bottom of things. [unmatched] is a fact that cannot be found in any
     * evidence source; [anchored] means that at least one fact or one of the basis matches. [found]:
     * True, not false, there is nothing to check in the answer (or only the number is correct, no
     * citation or basis positioning) null. [items] is the number of well-documented entries in the
     * answer (used by answer_grounded for evaluation, see [check]).
     */
    class Result(
        val found: Boolean?, val claims: List<Claim>, val unmatched: List<Claim>, val anchored: Boolean, val located: Boolean = false, val items: Int = 0,
    )

    /** After NFKC... has been.... */
    private const val CUT = "..."
    /** The words next to... in the answer (at least a few words are needed to compare). */
    private val BEFORE_CUT = Regex("([^\\n.「」『』“”\"]{2,20})\\.\\.\\.")

    private fun isCut(line: String) = line.trimEnd().endsWith(CUT)

    /**
     * Is the answer based on truncated text: The words in the answer next to... and the original text in
     * quotation marks (removing the first and last...) only appear on the screen in the truncated line,
     * and are connected to the truncation point. The truncated text is only part of the value and cannot
     * be verified as the complete value. Just look at whether the words on the screen are truncated:
     * answer yourself with... Omit the second half of the quotation, and the line on the screen is
     * complete and does not count as truncation.
     */
    fun builtOnTruncated(answer: String, screens: List<String>): Boolean {
        val a = canon(answer)
        val raw = screens.flatMap { canon(it).lines() }.filter { it.isNotBlank() && !it.startsWith(APP_HEADER) }
        fun onlyOnCutLines(piece: String): Boolean {
            val p = norm(piece)
            if (p.length < 2) return false
            val hits = raw.filter { p in norm(it) }
            return hits.isNotEmpty() && hits.all { l -> isCut(l) && norm(l.trimEnd().removeSuffix(CUT)).endsWith(p) }
        }
        // The quotation begins with... (the first half of the line on the screen is cut off): this word
        // only appears in lines that begin with...
        fun onlyOnHeadCutLines(piece: String): Boolean {
            val p = norm(piece)
            if (p.length < 2) return false
            val hits = raw.filter { p in norm(it) }
            return hits.isNotEmpty() && hits.all { l -> l.trimStart().startsWith(CUT) && norm(l.trimStart().removePrefix(CUT)).startsWith(p) }
        }
        val quoted = quotes(a).map(String::trim)
        if (quoted.any { q -> q.endsWith(CUT) && onlyOnCutLines(q.removeSuffix(CUT)) }) return true
        if (quoted.any { q -> q.startsWith(CUT) && onlyOnHeadCutLines(q.removePrefix(CUT).removeSuffix(CUT)) }) return true
        if (quoted.any(::onlyOnCutLines)) return true
        // The word next to... may be preceded by your own answer ("requires Kotlin + Jetpack..."): Take
        // the longest tail that has ever appeared on the screen.
        return BEFORE_CUT.findAll(a).any { m ->
            val cap = m.groupValues[1]
            val tail = cap.indices.map { cap.substring(it) }.firstOrNull { t -> norm(t).length >= 2 && raw.any { norm(t) in norm(it) } }
            tail != null && onlyOnCutLines(tail)
        }
    }

    /** Remove reference numbers and footnotes (not part of the answer). */
    fun stripRefs(s: String): String = s.replace(REF_CITATION, " ").replace(FOOTNOTE, " ")

    /** Before comparing, both sides should be written in the same way: full-width and half-width (NFKC), zero-width characters, typesetting marks, reference numbers and footnotes. */
    private fun canon(s: String): String =
        stripRefs(java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFKC).replace(ZERO_WIDTH, "").replace(MARKUP, ""))

    private fun norm(s: String) = s.filter { it.isLetterOrDigit() }

    private fun quotes(s: String) = QUOTED.findAll(s).map { m -> m.groupValues.drop(1).first { it.isNotEmpty() } }.toList()

    private fun num(m: MatchResult): Num {
        val raw = m.value.substring(0, m.value.length - m.groupValues[1].length)
        return Num(if (THOUSANDS.matches(raw)) raw.replace(",", "") else raw, m.groupValues[1].trim())
    }

    private fun numbers(s: String): List<Num> = NUMBER.findAll(s).map(::num).toList()

    /**
     * Numbers counted as facts in [s]: numbers with magnitude, unit or currency sign, decimals, and
     * integers with more than three digits. Integers without units within two digits are counts, serial
     * numbers, and rankings, and do not count.
     */
    private fun valueNumbers(s: String, known: Set<String>): List<Num> = NUMBER.findAll(s).mapNotNull { m ->
        val n = num(m)
        if (n.scaled != null && n.scaled in known) return@mapNotNull null
        val after = s.substring(m.range.last + 1, minOf(s.length, m.range.last + 9))
        val unit = n.magnitude.isNotEmpty() || UNIT_AFTER.containsMatchIn(after) || CURRENCY_BEFORE.containsMatchIn(s.substring(0, m.range.first))
        val decimal = '.' in n.value
        val long = n.value.substringBefore('.').substringBefore(',').length >= 3
        n.takeIf { unit || decimal || long }
    }.toList()

    private fun components(s: String) = DIGITS.findAll(s).map { it.value.trimStart('0').ifEmpty { "0" } }.toList()

    private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
    private fun Char.isAsciiAlnum() = isAsciiLetter() || this in '0'..'9'

    /**
     * Is the number [start] to [end] in [s] on the screen a complete word? The decimal point and
     * thousandth place in the number are not affected.
     */
    private fun wholeToken(s: String, start: Int, end: Int): Boolean {
        if (s.getOrNull(start - 1)?.isAsciiAlnum() == true) return false
        var i = end
        while (i < s.length && s[i].isAsciiLetter()) i++
        return i == end || i == s.length || s[i] !in '0'..'9'
    }

    /**
     * Which lines in [text] (according to the line number of [text]) match the facts in the answer: When
     * showing the excerpt to the verification model, these lines must be left
     * ([EvidenceLedger.excerpt]). Not checking the rules.
     */
    fun hitLines(goal: String, answer: String, text: String, today: java.time.LocalDate? = null): Set<Int> {
        val r = check(goal, answer, listOf(text), today)
        val ok = r.claims.filter { it !in r.unmatched }
        if (ok.isEmpty()) return emptySet()
        val pieces = ok.filter { it.kind == Kind.Quote }.flatMap { q -> q.text.split(CUT).flatMap { it.split(PIECE_BREAK) } }.map(::norm).filter { it.length >= 2 }
        val raw = canon(text).lines()
        return raw.indices.filter { i ->
            val l = raw[i]
            if (l.isBlank() || l.startsWith(APP_HEADER)) return@filter false
            val line = lines(listOf(l)).single()
            pieces.any { it in line.text } || ok.any { c ->
                when (c.kind) {
                    Kind.Quote -> false
                    Kind.Number -> numbers(c.text).firstOrNull()?.let { n -> line.nums.any(n::matches) } == true
                    Kind.Date -> components(c.text).let { v -> v.isNotEmpty() && line.digits.windowed(v.size).any { it == v } } ||
                        components(c.text).drop(1).let { v -> v.size == 2 && line.digits.windowed(2).any { it == v } }
                    Kind.Token -> c.text.lowercase() in line.tokens
                }
            }
        }.toSet()
    }

    /** A complete number in one line on the screen (see [wholeToken]). */
    private fun screenNumbers(s: String): List<Num> = NUMBER.findAll(s).mapNotNull { m ->
        val n = num(m)
        n.takeIf { wholeToken(s, m.range.first, m.range.first + m.value.length - m.groupValues[1].length) }
    }.toList()

    /** A complete string of numbers in one line on the screen (for dates). */
    private fun screenComponents(s: String) = DIGITS.findAll(s).filter { wholeToken(s, it.range.first, it.range.last + 1) }
        .map { it.value.trimStart('0').ifEmpty { "0" } }.toList()

    private class Line(val text: String, val nums: List<Num>, val digits: List<String>, val tokens: Set<String>)

    private val ALNUM_RUN = Regex("[A-Za-z0-9]+")

    /** A string of English alphanumeric characters in a line (used instead of numbers, not case-sensitive). */
    private fun alnumRuns(s: String) = ALNUM_RUN.findAll(s).map { it.value.lowercase() }.toSet()

    /**
     * The number sandwiched between English letters in [s] (the number is not a complete word, see
     * [wholeToken]), and the text after replacing them with equal-length blanks: the entire number is
     * checked as one value, and the numbers inside are no longer counted.
     */
    private fun idTokens(s: String): Pair<List<String>, String> {
        val ids = NUMBER.findAll(s).filterNot { m -> wholeToken(s, m.range.first, m.range.first + m.value.length - m.groupValues[1].length) }
            .mapNotNull { m ->
                var a = m.range.first
                var b = m.range.last + 1
                while (a > 0 && s[a - 1].isAsciiAlnum()) a--
                while (b < s.length && s[b].isAsciiAlnum()) b++
                a until b
            }.distinct().toList()
        var t = s
        ids.forEach { r -> t = t.replaceRange(r, " ".repeat(r.last - r.first + 1)) }
        return ids.map { s.substring(it) } to t
    }

    private fun lines(screens: List<String>) = screens.flatMap { canon(it).lines() }
        .filter { it.isNotBlank() && !it.startsWith(APP_HEADER) }.map { Line(norm(it), screenNumbers(it), screenComponents(it), alnumRuns(it)) }

    /** At least one line of the quotation (which may span several lines) can be found on one of the lines on the screen. */
    fun quoteInReads(quote: String, screens: List<String>): Boolean {
        val ls = lines(screens)
        return canon(quote).lines().map(::norm).filter { it.length >= 2 }.any { q -> ls.any { q in it.text } }
    }

    /** Returns true if it matches, false if it doesn't match; null if there is nothing to check in the answer. */
    fun found(goal: String, answer: String, screens: List<String>): Boolean? = check(goal, answer, screens).found

    /** Relative days: today, tomorrow, the day after tomorrow, yesterday, week X / week X. */
    private val RELATIVE_DAY = Regex("今天|今日|明天|明日|后天|昨天|昨日|(?:周|星期|礼拜)([一二三四五六日天])")
    private val WEEKDAY_CHARS = "一二三四五六日"
    /** Week X The range to look at when converting to a specific date: yesterday to one week later. */
    private const val WEEK_AHEAD = 7L

    /** [word] is the date converted by [today] (week X may have two values between yesterday and a week later). */
    private fun resolve(word: MatchResult, today: java.time.LocalDate): List<java.time.LocalDate> = when (word.value) {
        "今天", "今日" -> listOf(today)
        "明天", "明日" -> listOf(today.plusDays(1))
        "后天" -> listOf(today.plusDays(2))
        "昨天", "昨日" -> listOf(today.minusDays(1))
        else -> {
            val c = word.groupValues[1].replace("天", "日")
            val dow = java.time.DayOfWeek.of(WEEKDAY_CHARS.indexOf(c) + 1)
            (-1L..WEEK_AHEAD).map(today::plusDays).filter { it.dayOfWeek == dow }
        }
    }

    /** The year, month, and day in the date writing method; it is null when there is only the year, month, or time. */
    private fun dateParts(d: String): Triple<Int?, Int, Int>? {
        if (':' in d) return null
        val c = DIGITS.findAll(d).map { it.value.toInt() }.toList()
        return when {
            c.size == 3 && c[0] >= 1000 -> Triple(c[0], c[1], c[2])
            c.size == 2 && !Regex("^\\d{4}").containsMatchIn(d.trim()) -> Triple(null, c[0], c[1])
            else -> null
        }
    }

    /**
     * The date that the running clock can prove: A certain line in the question or evidence contains
     * expressions such as "tomorrow" and "Tuesday", and the day is converted according to [today]. When
     * the specific date is written in the text of the answer (except for quotations and "basis:"), the
     * clock can prove it. Relative statements that only appear in the answer prove nothing; the time,
     * the date in the quotation, and the date in the basis (the answer says it was read from the page)
     * are not corroborated by the clock.
     */
    private fun clockDates(body: String, basis: String, said: List<String>, today: java.time.LocalDate?): Set<String> {
        today ?: return emptySet()
        val meant = said.flatMap { t -> RELATIVE_DAY.findAll(t).flatMap { resolve(it, today) }.toList() }.toSet()
        if (meant.isEmpty()) return emptySet()
        val plain = QUOTED.replace(body) { " ".repeat(it.value.length) }
        val quotedOrRead = quotes(body).joinToString("\n") + "\n" + basis
        return plain.lines().flatMap { DATE.findAll(it).toList() }.mapNotNull { m ->
            val text = m.value.trim()
            val (y, mo, d) = dateParts(text) ?: return@mapNotNull null
            if (DATE.findAll(quotedOrRead).any { it.value.trim() == text }) return@mapNotNull null
            text.takeIf { meant.any { it.monthValue == mo && it.dayOfMonth == d && (y == null || it.year == y) } }
        }.toSet()
    }

    private const val NUM_TOKEN = "(\\d+(?:,\\d{3})*(?:\\.\\d+)?)"
    /** An operand: can have a currency symbol, the following unit (letter, ℃, °, %), and the source written in parentheses ("399 (product page)"). */
    private const val OPERAND = "([¥$€£]?)\\s*$NUM_TOKEN\\s*([\\p{L}℃°%]{0,4})\\s*(?:[(（][^()（）\\n]{1,20}[)）])?\\s*"
    /**
     * The one-step operation stated in the answer: a operator b = c (or ≈ c,= about c); the operator is
     * + - − × * ÷ /. When c is followed by %, the results of ÷ and / are calculated as percentages.
     */
    private val EXPRESSION = Regex(
        OPERAND + "([+\\-−×*÷/])\\s*" + OPERAND + "(=|≈)\\s*(约|≈)?\\s*([¥$€£]?)\\s*$NUM_TOKEN(\\s*%)?\\s*([\\p{L}℃°]{0,4})",
    )

    /** A standard unit: Dimensions and multiples converted to the base unit. */
    private class Unit(val dimension: String, val factor: java.math.BigDecimal)

    /**
     * Synonymous writing and conversion of standard units (a closed table, no other guesses):
     * kilometers/km/km, meters/m, yuan/block/¥/RMB/renminbi, grams/g, kilograms/kg/kg, hours/h,
     * minutes/min, %/percent. The longest way of writing is recognized first.
     */
    private val UNITS: List<Pair<String, Unit>> = listOf(
        "公里" to Unit("length", java.math.BigDecimal(1000)), "千米" to Unit("length", java.math.BigDecimal(1000)), "km" to Unit("length", java.math.BigDecimal(1000)),
        "米" to Unit("length", java.math.BigDecimal.ONE), "m" to Unit("length", java.math.BigDecimal.ONE),
        "人民币" to Unit("cny", java.math.BigDecimal.ONE), "RMB" to Unit("cny", java.math.BigDecimal.ONE), "元" to Unit("cny", java.math.BigDecimal.ONE),
        "块" to Unit("cny", java.math.BigDecimal.ONE), "¥" to Unit("cny", java.math.BigDecimal.ONE),
        "千克" to Unit("mass", java.math.BigDecimal(1000)), "公斤" to Unit("mass", java.math.BigDecimal(1000)), "kg" to Unit("mass", java.math.BigDecimal(1000)),
        "克" to Unit("mass", java.math.BigDecimal.ONE), "g" to Unit("mass", java.math.BigDecimal.ONE),
        "小时" to Unit("time", java.math.BigDecimal(3600)), "h" to Unit("time", java.math.BigDecimal(3600)),
        "分钟" to Unit("time", java.math.BigDecimal(60)), "min" to Unit("time", java.math.BigDecimal(60)),
        "百分之" to Unit("percent", java.math.BigDecimal.ONE), "%" to Unit("percent", java.math.BigDecimal.ONE),
    ).sortedByDescending { it.first.length }

    /** The standard unit recognized in the words after the currency symbol and number; if it is not recognized, it is null (the unit is not written). */
    private fun unitOf(before: String, after: String): Unit? =
        UNITS.firstOrNull { (k, _) -> before == k }?.second ?: UNITS.firstOrNull { (k, _) -> after.startsWith(k, ignoreCase = k.all { it.isAsciiLetter() }) && !after.drop(k.length).take(1).any { it.isAsciiLetter() } }?.second

    /** The units of the operands in the calculation formula: use standard units for those that are in the table; write down those that are not in the table (dollar signs, miles...) and calculate them in one dimension, which is the same as your own. */
    private fun operandUnit(before: String, after: String): Unit? =
        unitOf(before, after) ?: (before + after.trim()).takeIf { it.isNotBlank() }?.let { Unit("other:" + it.lowercase(), java.math.BigDecimal.ONE) }
    /** A sign immediately before a number that indicates it is a divisor. */
    private val APPROX_BEFORE = Regex("(?:约|≈)\\s*[¥$€£]?\\s*$")

    /**
     * Returns whether [x] is a rounded result of [d]: when not approximate, only rounding to the last
     * written digit of [x] counts (integers must match, decimals by decimal places); when explicitly
     * approximate ("approx." / `≈`), trailing zeros also count as precision (900 to the hundreds
     * place) and relative error must not exceed [MAX_ROUND_ERROR].
     */
    private fun roundsTo(x: java.math.BigDecimal, d: java.math.BigDecimal, approx: Boolean): Boolean {
        val diff = (x - d).abs()
        if (diff.signum() == 0) return true
        val power = when {
            x.scale() > 0 -> -x.scale()
            approx -> minOf(-x.stripTrailingZeros().scale(), MAX_ROUND_POWER).coerceAtLeast(0)
            else -> 0
        }
        val half = java.math.BigDecimal.ONE.scaleByPowerOfTen(power).divide(java.math.BigDecimal(2))
        if (diff > half) return false
        return !approx || (d.signum() != 0 && diff.divide(d.abs(), java.math.MathContext.DECIMAL64) <= MAX_ROUND_ERROR)
    }

    private const val MAX_ROUND_POWER = 6
    private val MAX_ROUND_ERROR = java.math.BigDecimal("0.05")
    private val HUNDRED = java.math.BigDecimal(100)

    private fun plainValue(s: String) = s.replace(",", "").toBigDecimalOrNull()

    /**
     * The answer states the calculation formula and the result of both operands matching in the evidence
     * ("6363−5464=899"): the writing method of the return result (the writing method of the number in
     * the answer is consistent). [grounded] Determine whether a number matches the evidence (excluding
     * the number in the question).
     */
    private fun computed(body: String, grounded: (String) -> Boolean): Set<String> = body.lines().flatMap { EXPRESSION.findAll(it).toList() }.mapNotNull { m ->
        val g = m.groupValues
        val (aCur, aRaw, aUnit, op) = listOf(g[1], g[2], g[3], g[4])
        val (bCur, bRaw, bUnit, eq) = listOf(g[5], g[6], g[7], g[8])
        val (approxWord, cCur, cRaw, pct) = listOf(g[9], g[10], g[11], g[12])
        val cUnitText = g[13]
        if (!grounded(aRaw) || !grounded(bRaw)) return@mapNotNull null
        var a = plainValue(aRaw) ?: return@mapNotNull null
        var b = plainValue(bRaw) ?: return@mapNotNull null
        var c = plainValue(cRaw) ?: return@mapNotNull null
        val mc = java.math.MathContext.DECIMAL64
        // Addition and subtraction must have the same dimension: if the unit is written, convert it
        // according to the standard unit (kilometer and kilometer are the same thing, the difference
        // between kilometer and meter is 1000 times); formulas with different dimensions do not count
        if (op in setOf("+", "-", "−")) {
            val ua = operandUnit(aCur, aUnit)
            val ub = operandUnit(bCur, bUnit)
            val uc = unitOf(cCur, if (pct.isNotBlank()) "%" else cUnitText)
            val units = listOfNotNull(ua, ub, uc)
            if (units.map { it.dimension }.distinct().size > 1) return@mapNotNull null
            val base = ua ?: ub ?: uc
            if (base != null) {
                a = a * (ua ?: base).factor; b = b * (ub ?: base).factor
                c = c * (uc ?: ua ?: base).factor
            }
        }
        val r = when (op) {
            "+" -> a + b
            "-", "−" -> a - b
            "×", "*" -> a * b
            else -> if (b.signum() == 0) return@mapNotNull null else a.divide(b, mc)
        }
        val result = if (pct.isNotBlank() && (op == "÷" || op == "/")) r * HUNDRED else r
        val approx = eq == "≈" || approxWord.isNotEmpty()
        cRaw.replace(",", "").takeIf { roundsTo(c, result, approx) }
    }.toSet()

    /** A letter that replaces a number in a quotation (the N in "Today +N"). */
    private val PLACEHOLDER = Regex("(?<![A-Za-z])[A-Za-z](?![A-Za-z])")

    /**
     * The quotation [q] refers to the way you write your own answer: there are letters in it that
     * replace numbers. After replacing the letters with numbers, it happens to be a paragraph written in
     * the text of the answer (except quotation marks) ("Today +N" versus "Today +2,271").
     */
    private fun template(q: String, plain: String): Boolean {
        if (!PLACEHOLDER.containsMatchIn(q)) return false
        val pattern = q.trim().split(Regex("\\s+")).joinToString("\\s*") { w ->
            PLACEHOLDER.split(w).joinToString("\\d+(?:[.,]\\d+)*") { Regex.escape(it) }
        }
        return Regex(pattern).containsMatchIn(plain)
    }

    private val EMPHASIS = Regex("\\*\\*|__|`")
    /** List token at the beginning of the line (1. 2, 3) ① - * •). */
    private val LIST_MARK = Regex("^[ \\t]*(?:\\(?\\d{1,3}[.、)）:：]|（\\d{1,3}）|[①-⑳]|[-*•·])[ \\t]*")
    /** The value of "tag:value" in the list. */
    private val LABEL_VALUE = Regex("^[^:：]{1,12}[:：]\\s*(.+)$")
    /** An entry must have at least a few words (single words and punctuation are available on all screens and cannot be used to make up the number). */
    private const val MIN_ITEM = 2

    fun check(goal: String, answer: String, screens: List<String>, today: java.time.LocalDate? = null): Result {
        val a = canon(answer)
        val entries = screens.map { lines(listOf(it)) }
        val ls = entries.flatten()
        val g = canon(goal)
        val goalNums = numbers(g).map { it.value }.toSet()
        // The numbers in the question are compared by value (1000.0 and 1000 are the same number)
        val goalValues = numbers(g).mapNotNull { it.scaled }.toSet()
        val goalText = norm(g)
        val at = EVIDENCE.find(a)
        val basis = at?.let { a.substring(it.range.last + 1) }.orEmpty()
        val body = at?.let { a.substring(0, it.range.first) } ?: a

        fun numOnScreen(n: Num) = ls.any { l -> l.nums.any(n::matches) }
        /** [q] in [where] In these lines: the whole paragraph is on a certain line, or each paragraph separated by punctuation spaces is on a certain line (the number in it is proportional to the whole number). */
        fun textIn(q: String, where: List<Line>): Boolean {
            val t = norm(q)
            if (t.isEmpty() || where.any { t in it.text }) return true
            val pieces = q.split(PIECE_BREAK).map(::norm).filter(String::isNotEmpty)
            // The serial number at the beginning of the line ("5 Spirited Away") and the number between
            // the letters (AQ3A) are not numbers and cannot be compared.
            return pieces.size > 1 && pieces.all { p -> where.any { p in it.text } } &&
                screenNumbers(ORDINAL.replace(q.trim(), " ")).all { n -> where.any { l -> l.nums.any(n::matches) } }
        }
        /** The quotation is in a certain place in the material: the paragraphs separated by... match each other and are in the same place. */
        fun quoteFound(q: String): Boolean {
            val parts = q.split(CUT).filter { norm(it).isNotEmpty() }.ifEmpty { listOf(q) }
            return entries.any { e -> parts.all { textIn(it, e) } }
        }
        fun dateOnScreen(d: String): Boolean {
            val c = components(d)
            val variants = listOfNotNull(c, c.takeIf { it.size == 3 && it[0].length == 4 }?.drop(1))
            return variants.any { v -> ls.any { l -> l.digits.windowed(v.size).any { it == v } } }
        }

        // Values are obtained line by line: the answer line is not combined with the next line, and
        // the list number at the beginning of the line is removed line by line.
        fun values(line: String): List<Claim> {
            val dates = DATE.findAll(line).map { it.value }.filter { d -> components(d).any { it !in goalNums } }.toList()
            val (ids, plain) = idTokens(ORDINAL.replace(DATE.replace(line, " "), " "))
            return dates.map { Claim(Kind.Date, it.trim()) } + ids.filter { norm(it) !in goalText }.map { Claim(Kind.Token, it) } +
                valueNumbers(plain, goalValues).map { Claim(Kind.Number, it.value + it.magnitude) }
        }
        val plainBody = QUOTED.replace(body, " ")
        val bodyQuotes = quotes(body).filter { norm(it).length >= 2 && norm(it) !in goalText && !template(it, plainBody) }
        val basisQuotes = quotes(basis).filter { norm(it).length >= 2 }
        val bodyLines = body.lines().map { it.replace(EMPHASIS, "") }
        val lineClaims = bodyLines.map(::values)
        val claims = (bodyQuotes.map { Claim(Kind.Quote, it) } + lineClaims.flatten() + basisQuotes.map { Claim(Kind.Quote, it) }).distinct()

        // Where "tomorrow" and "Tuesday" are written in the evidence: each line in the title and
        // evidence
        val byClock = clockDates(body, basis, listOf(g) + ls.map { it.text }, today)
        fun matched(c: Claim) = when (c.kind) {
            Kind.Quote -> quoteFound(c.text)
            Kind.Number -> numbers(c.text).firstOrNull()?.let(::numOnScreen) ?: false
            Kind.Date -> dateOnScreen(c.text) || c.text in byClock
            Kind.Token -> ls.any { c.text.lowercase() in it.tokens }
        }
        // Matching numbers in the evidence (numbers in the question do not count: the result of the
        // calculation of the number given in the question still has to be read on the screen)
        fun grounded(n: Num) = (n.scaled == null || n.scaled !in goalValues) && numOnScreen(n)
        fun groundedRaw(raw: String) = numbers(raw).firstOrNull()?.let(::grounded) == true
        val known = a.lines().flatMap { numbers(ORDINAL.replace(DATE.replace(it, " "), " ")) }.filter(::grounded).mapNotNull { it.scaled?.toBigDecimalOrNull() }
        // State the result of the calculation, and state that it is a divisor (approximate, ≈), a number
        // that, after being rounded to the precision written, equals a certain number that matches
        val derivedNums = computed(body, ::groundedRaw) + body.lines().flatMap { line ->
            NUMBER.findAll(line).mapNotNull { m ->
                val n = num(m)
                val x = n.scaled?.toBigDecimalOrNull() ?: return@mapNotNull null
                if (!APPROX_BEFORE.containsMatchIn(line.substring(0, m.range.first))) return@mapNotNull null
                (n.value + n.magnitude).takeIf { known.any { roundsTo(x, it, approx = true) } }
            }.toList()
        }
        val unmatched = claims.filterNot { matched(it) || (it.kind == Kind.Number && it.text in derivedNums) }

        // The words other than the quotation in the basis only help positioning (just match the previous
        // one), if they don’t match, they will not be rejected.
        val basisNorm = norm(basis)
        val fragments = QUOTED.replace(basis, " ").split(FRAGMENT_BREAK).map(::norm).filter { it.length >= MIN_FRAGMENT }
        val located = (basisQuotes.isNotEmpty() && basisQuotes.all(::quoteFound)) || fragments.any { f -> ls.any { f in it.text } } ||
            ls.any { it.text.length >= MIN_LINE_IN_BASIS && it.text in basisNorm }

        val quoteMatched = claims.any { it.kind == Kind.Quote && it !in unmatched }
        val found = when {
            unmatched.isNotEmpty() -> false
            quoteMatched || located -> true
            // There are no citations, no basis, and no location: only the numbers are correct. The
            // numbers may happen to be on the screen. They alone do not count.
            else -> null
        }

        // Well-documented entries: lines with matching facts in the answer (rows with the same content
        // are only counted once), and list entries without values where the entire paragraph is in a
        // certain line; take the larger of the number of different facts in the answer
        val okClaims = claims.filter { it !in unmatched }.toSet()
        val rows = mutableSetOf<String>()
        val bare = mutableSetOf<String>()
        bodyLines.forEachIndexed { i, raw ->
            val key = norm(raw)
            if (key.isEmpty()) return@forEachIndexed
            val inLine = lineClaims[i] + quotes(raw).map { Claim(Kind.Quote, it) }
            if (inLine.any { it in okClaims }) { rows += key; return@forEachIndexed }
            if (inLine.isNotEmpty() || !LIST_MARK.containsMatchIn(raw)) return@forEachIndexed
            val item = raw.replace(LIST_MARK, "").trim().let { t -> LABEL_VALUE.find(t)?.groupValues?.get(1) ?: t }
            if (norm(item).length >= MIN_ITEM && norm(item) !in goalText && textIn(item, ls)) bare += norm(item)
        }
        val okInBody = (bodyQuotes.map { Claim(Kind.Quote, it) } + lineClaims.flatten()).filter { it in okClaims }.toSet()
        val items = maxOf(rows.size, okInBody.size) + bare.size
        return Result(found, claims, unmatched, anchored = located || claims.size > unmatched.size, located = located, items = items)
    }
}
