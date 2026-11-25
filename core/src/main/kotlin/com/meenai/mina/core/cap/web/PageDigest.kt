package com.meenai.mina.core.cap.web

import com.meenai.mina.core.uistate.ScreenCheck
import com.meenai.mina.core.uistate.ScreenDiagnosis
import com.meenai.mina.core.uistate.ScreenFacts
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.time.LocalDate
import java.time.LocalTime

/**
 * What does a web_read_page require: If [url] is null, the current tag is read, and it is read in the
 * temporary background tag; when [unfold] is true, the collapsed list block is also given in full (the
 * offset of the segments is calculated according to the same unfold).
 */
data class PageRead(
    val maxChars: Int,
    val offset: Int,
    val unfold: Boolean = false,
    val url: String? = null,
    /** Scroll once before reading to trigger lazy loading and wait for the text to stabilize; when checking for evidence, it is false and the page will only be read without moving. */
    val lazy: Boolean = true,
)

/** How does this page read: is the main text sufficient, do you need to log in first, is it rejected, is the content mainly in the pictures? [wire] is the way to write the model. */
enum class ReadVerdict(val wire: String) {
    OK("ok"), THIN("thin"), NEEDS_SIGN_IN("needs_sign_in"), REFUSED("refused"), IMAGE_TEXT("image_text"),
}

/**
 * The quality signal of page reading: [chars] is the number of text in the whole page (according to the
 * folding method this time), [textDensity] is the proportion of non-link text in the text, [cause] is a
 * brief description of the basis for judgment; [published]/[updated] is the time declared by the page
 * itself; [dialogs] is the number of open dialog boxes (placed at the front of the text), [folded] Is
 * the number of list blocks collapsed into one line.
 */
data class ReadQuality(
    val chars: Int,
    val textDensity: Double,
    val verdict: ReadVerdict,
    val cause: String?,
    val published: String? = null,
    val updated: String? = null,
    val dialogs: Int = 0,
    val folded: Int = 0,
)

/**
 * The full page read result of the page script (the digest of page.js) is handed here: deciding which
 * list blocks to collapse, spelling out the text, judging quality, and picking out the time of the
 * statement. Page scripts are only collected based on structure (landmark roles, link proportions,
 * repeated substructures, image positions), and the judgments are all here, not differentiated by
 * website. The threshold used by the page script to select candidate blocks is also given here
 * ([scriptOptions]), and there is only one copy on both sides.
 */
object PageDigest {
    /** Text shorter than that is considered thin. */
    const val THIN_CHARS = 200
    /** Candidate block: at least this many links, and the link text occupies at least this much (the page script selects candidates according to it, and we will judge it carefully here). */
    const val CANDIDATE_MIN_LINKS = 5
    const val CANDIDATE_LINK_SHARE = 0.5
    /** The ratio of link text to a block of text is here, which is a list of links (recommendations, directories). */
    const val LINK_LIST_SHARE = 0.7
    /** When sub-elements repeat the same structure (card list), the link ratio is enough here. */
    const val REPEATED_LINK_SHARE = 0.5
    const val MIN_REPEAT = 5
    /** The landmark block (navigation, footer, sidebar) links should occupy at least this much before they are collapsed; text-based sidebars and footers should be read accordingly. */
    const val LANDMARK_LINK_SHARE = 0.3
    /** A list block occupies the proportion of text other than the landmark. It is the main text itself (catalog page, search results page) and is not collapsed. */
    const val DOMINANT_SHARE = 0.4
    /** After folding, at least this proportion of text should be left; if it is not enough, put it back starting from the largest block. */
    const val MIN_LEFT_SHARE = 0.1
    /** The proportion of pictures and canvas covering the beginning of the page is here, and there are few words, so it is considered image_text. */
    const val PICTURE_SHARE = 0.5
    const val PICTURE_MAX_CHARS = THIN_CHARS * 4
    /** Images smaller than this side length (CSS pixels) are not counted: icons, avatars, dividers. */
    const val MIN_PICTURE_PX = 48
    /** The proportion of the text in the login box (where the password box is located) to the text on the entire page reaches this point, and this page is the login page. */
    const val SIGN_IN_BOX_SHARE = 0.5
    private const val COVER_GRID = 64
    private const val PREVIEW_ITEMS = 6
    private const val PREVIEW_CHARS = 120
    private val LANDMARKS = setOf("navigation", "footer", "complementary")

    /** These words appear in the login box, title, and dialog box, and there is a password box, which means you must log in first. Blank spaces are removed and case-insensitive when comparing. */
    private val SIGN_IN_WORDS = listOf("登录", "登入", "登陆", "signin", "login", "logon", "账号", "帐号", "密码", "password")

    /** Parameters for page script digest (candidate block threshold). */
    val scriptOptions: String get() = """{"minLinks":$CANDIDATE_MIN_LINKS,"linkShare":$CANDIDATE_LINK_SHARE}"""

    /** A link: [group] is the list block it is in (-1 if not in a block). */
    data class Link(val text: String, val url: String, val group: Int)

    /** A paragraph of text: a line of text, or a collapsible list block. */
    sealed interface Part {
        data class Line(val text: String) : Part
        /**
         * [kind] is navigation/footer/complementary (by landmark) or links (list recognized by link
         * proportion); [lines] is the text in the block, [chars]/[linkChars] is the number of words of
         * all text and link text in the block, [repeat] is the number of sub-elements of the same
         * structure, [links] is the number of links in the block.
         */
        data class Group(
            val kind: String, val label: String, val lines: List<String>,
            val chars: Int, val linkChars: Int, val repeat: Int, val links: Int,
        ) : Part {
            val landmark: Boolean get() = kind in LANDMARKS
            val linkShare: Double get() = if (chars > 0) linkChars.toDouble() / chars else 0.0
        }
    }

    /** The position of an image on the page (document coordinates, CSS pixels). */
    data class Box(val x: Int, val y: Int, val w: Int, val h: Int)

    /** The page signs used for judgment (all come from page scripts). */
    data class Signals(
        val passwordFields: Int = 0,
        /** The ratio of the login box's own text to the entire page's text (the largest area from the password box up, excluding text, and stops when it encounters a form or dialog box). */
        val signInBoxShare: Double = 0.0,
        /** Where to find the word login: the text of the login box, the page title, and the beginning of the dialog box. */
        val signInText: List<String> = emptyList(),
        /** Text in eye-catching places (title, h1-h3, beginning of dialog box) is used for interception and determination. */
        val prominent: List<String> = emptyList(),
        /** The area at the beginning of the page (width, height) and the pictures, canvas, and videos inside. */
        val regionW: Int = 0,
        val regionH: Int = 0,
        val pictures: List<Box> = emptyList(),
        val frames: List<String> = emptyList(),
        val textChars: Int = 0,
        val linkChars: Int = 0,
        /** The time declared by the page: (source, original value), the source is such as ld:datePublished, meta:article:published_time, itemprop:dateModified, time. */
        val dates: List<Pair<String, String>> = emptyList(),
    )

    data class Digest(
        val url: String,
        val title: String,
        val dialogs: List<List<String>>,
        val parts: List<Part>,
        val links: List<Link>,
        val images: List<Pair<String, String>>,
        val signals: Signals,
    )

    /** The result of the puzzle; [folded] is that I put away a few pieces this time. */
    data class Rendered(val text: String, val links: List<Pair<String, String>>, val folded: Int)

    // ---------- Analysis ----------

    fun parse(o: JsonObject): Digest {
        fun str(e: JsonElement?) = (e as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        fun num(e: JsonElement?) = (e as? JsonPrimitive)?.doubleOrNull ?: 0.0
        fun int(e: JsonElement?) = (e as? JsonPrimitive)?.doubleOrNull?.toInt() ?: 0
        fun strings(e: JsonElement?) = (e as? JsonArray).orEmpty().map(::str).filter { it.isNotEmpty() }
        val parts = (o["parts"] as? JsonArray).orEmpty().mapNotNull { p ->
            when (p) {
                is JsonPrimitive -> str(p).takeIf { it.isNotEmpty() }?.let(Part::Line)
                is JsonObject -> Part.Group(str(p["g"]), str(p["label"]), strings(p["lines"]), int(p["chars"]), int(p["linkChars"]), int(p["repeat"]), int(p["links"]))
                else -> null
            }
        }
        val links = (o["links"] as? JsonArray).orEmpty().mapNotNull { e ->
            val m = e as? JsonObject ?: return@mapNotNull null
            val url = str(m["url"]).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Link(str(m["text"]), url, (m["p"] as? JsonPrimitive)?.intOrNull ?: -1)
        }
        val images = (o["images"] as? JsonArray).orEmpty().mapNotNull { e ->
            val m = e as? JsonObject ?: return@mapNotNull null
            val url = str(m["url"]).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            str(m["alt"]) to url
        }
        val s = o["signals"] as? JsonObject
        val dates = (s?.get("dates") as? JsonArray).orEmpty().mapNotNull { e ->
            val m = e as? JsonObject ?: return@mapNotNull null
            val v = str(m["v"]).trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            str(m["k"]) to v
        }
        val region = (s?.get("region") as? JsonArray).orEmpty().map(::int)
        val pictures = (s?.get("pictures") as? JsonArray).orEmpty().mapNotNull { e ->
            val a = (e as? JsonArray)?.map(::int)?.takeIf { it.size == 4 } ?: return@mapNotNull null
            Box(a[0], a[1], a[2], a[3])
        }
        return Digest(
            url = str(o["url"]), title = str(o["title"]),
            dialogs = (o["dialogs"] as? JsonArray).orEmpty().map(::strings).filter { it.isNotEmpty() },
            parts = parts, links = links, images = images,
            signals = Signals(
                passwordFields = int(s?.get("password")),
                signInBoxShare = num(s?.get("signInShare")),
                signInText = strings(s?.get("signInText")),
                prominent = strings(s?.get("prominent")),
                regionW = region.getOrElse(0) { 0 }, regionH = region.getOrElse(1) { 0 },
                pictures = pictures,
                frames = strings(s?.get("frames")),
                textChars = int(s?.get("textChars")),
                linkChars = int(s?.get("linkChars")),
                dates = dates,
            ),
        )
    }

    // ----------Collapse ----------

    /**
     * Which blocks are folded (according to the serial number of the block in [Digest.parts], only the
     * blocks are counted). If there are enough links in the landmark block, it will be collected; the
     * link list will take up more links, and it cannot occupy a large part of the text other than the
     * landmark (that is the text itself); if the largest block is the link list, and it is more than the
     * text outside the block, it will be the main text and will not be collected; when the remaining
     * text is less than [MIN_LEFT_SHARE], it will be put back starting from the largest block.
     */
    fun foldSet(parts: List<Part>): Set<Int> {
        val groups = parts.filterIsInstance<Part.Group>()
        if (groups.isEmpty()) return emptySet()
        fun Part.size() = when (this) { is Part.Line -> text.length + 1; is Part.Group -> lines.sumOf { it.length + 1 } }
        val total = parts.sumOf { it.size() }
        val free = parts.filterIsInstance<Part.Line>().sumOf { it.size() }
        val outsideLandmarks = free + groups.filter { !it.landmark }.sumOf { it.size() }
        val largest = groups.indices.maxBy { groups[it].size() }
        val fold = groups.indices.filter { i ->
            val g = groups[i]
            val wanted = if (g.landmark) g.linkShare >= LANDMARK_LINK_SHARE
            else (g.linkShare >= LINK_LIST_SHARE || (g.repeat >= MIN_REPEAT && g.linkShare >= REPEATED_LINK_SHARE)) &&
                g.size() < outsideLandmarks * DOMINANT_SHARE
            // If the largest block is a link list and there is more text than the text outside the
            // block, it is the main text (the landmarks are accepted according to the page's own
            // declaration)
            wanted && !(i == largest && !g.landmark && g.size() > free)
        }.toMutableSet()
        while (fold.isNotEmpty() && total - fold.sumOf { groups[it].size() } < total * MIN_LEFT_SHARE) {
            fold.remove(fold.maxBy { groups[it].size() })
        }
        return fold
    }

    /** To spell the text: the dialog box is at the front, and the collapsed blocks occupy one line each. As long as the link table is still visible, the same address is only left once. */
    fun render(d: Digest, unfold: Boolean): Rendered {
        val folded = if (unfold) emptySet() else foldSet(d.parts)
        val lines = ArrayList<String>()
        for (dl in d.dialogs) {
            lines += "<<dialog>>"
            lines += dl
            lines += "<<end of dialog>>"
        }
        var groupIndex = -1
        for (p in d.parts) when (p) {
            is Part.Line -> lines += p.text
            is Part.Group -> {
                groupIndex++
                if (groupIndex in folded) lines += collapsed(p) else lines += p.lines
            }
        }
        val links = d.links.filter { it.group < 0 || it.group !in folded }.distinctBy { it.url }.map { it.text to it.url }
        return Rendered(lines.joinToString("\n"), links, folded.size)
    }

    private fun collapsed(g: Part.Group): String {
        val name = g.kind + (g.label.takeIf { it.isNotBlank() }?.let { " \"${it.take(60)}\"" } ?: "")
        val sample = g.lines.take(PREVIEW_ITEMS).joinToString(" | ").let { if (it.length > PREVIEW_CHARS) it.take(PREVIEW_CHARS) + "…" else it }
        val count = if (g.links > 0) "${g.links} links" else "${g.lines.size} lines"
        return "<<collapsed $name · $count · $sample>>"
    }

    // ---------- Judgment ----------

    /**
     * Determine how well the page reads. [check] is the webpage obstacle determination (made with
     * eye-catching text, cross-origin framework, and HTTP status, see [barrierFacts]), and [httpStatus]
     * is the HTTP error status of the main document. First look at the rejection, then look at the
     * login, then look at whether it is a picture, and finally look at the word count.
     */
    fun verdict(chars: Int, s: Signals, check: ScreenCheck?, httpStatus: Int?): Pair<ReadVerdict, String?> {
        val reason = check?.takeoverReason
        if (reason == "captcha" || reason == "access_blocked") return ReadVerdict.REFUSED to (check.cause ?: reason)
        if (reason == ScreenDiagnosis.UNSUPPORTED_BROWSER) return ReadVerdict.REFUSED to "the site refuses this browser"
        if (httpStatus != null && httpStatus >= 400 && chars < PICTURE_MAX_CHARS) return ReadVerdict.REFUSED to "HTTP $httpStatus"
        if (s.passwordFields > 0) {
            val cue = signInCue(s.signInText)
            if (cue != null && (s.signInBoxShare >= SIGN_IN_BOX_SHARE || chars < PICTURE_MAX_CHARS)) return ReadVerdict.NEEDS_SIGN_IN to "password field and \"$cue\""
        } else if (reason == "login" && chars < PICTURE_MAX_CHARS) {
            return ReadVerdict.NEEDS_SIGN_IN to (check.cause ?: "login")
        }
        val cover = pictureShare(s)
        if (cover >= PICTURE_SHARE && chars < PICTURE_MAX_CHARS) return ReadVerdict.IMAGE_TEXT to "pictures cover ${(cover * 100).toInt()}% of the page top"
        if (chars < THIN_CHARS) return ReadVerdict.THIN to "$chars characters of text"
        return ReadVerdict.OK to null
    }

    private fun signInCue(texts: List<String>): String? {
        val text = texts.joinToString("\n").lowercase().filterNot(Char::isWhitespace)
        return SIGN_IN_WORDS.firstOrNull { it in text }
    }

    /**
     * The proportion of the area at the beginning of the page covered by pictures: spread a grid over
     * the area and count which picture the center of the grid falls on; overlapping pictures are only
     * counted once, and those with side lengths less than [MIN_PICTURE_PX] are not counted.
     */
    fun pictureShare(s: Signals): Double {
        val w = s.regionW
        val h = s.regionH
        if (w <= 0 || h <= 0) return 0.0
        val boxes = s.pictures.filter { it.w >= MIN_PICTURE_PX && it.h >= MIN_PICTURE_PX }
        if (boxes.isEmpty()) return 0.0
        val cols = COVER_GRID
        val rows = maxOf(1, (COVER_GRID.toLong() * h / w).toInt())
        var covered = 0
        for (r in 0 until rows) for (c in 0 until cols) {
            val x = (c + 0.5) * w / cols
            val y = (r + 0.5) * h / rows
            if (boxes.any { x >= it.x && x < it.x + it.w && y >= it.y && y < it.y + it.h }) covered++
        }
        return covered.toDouble() / (rows * cols)
    }

    /** Facts for [WebBarrier.classify]: Only the text in eye-catching places (the mention of "verification code" in the article does not count), the password box will be judged separately. */
    fun barrierFacts(d: Digest, httpStatus: Int?, host: String?): WebFacts = WebFacts(
        base = ScreenFacts(
            currentApp = host, hasWindow = true, readableElements = 1, changing = false, blackFrames = emptyList(),
            texts = d.signals.prominent + d.dialogs.flatten(), passwordField = false,
        ),
        httpStatus = httpStatus, frameUrls = d.signals.frames, host = host,
    )

    /** The proportion of text in the main text that is not a link, two decimal places; no words are 0. */
    fun density(s: Signals): Double {
        if (s.textChars <= 0) return 0.0
        val share = 1.0 - s.linkChars.coerceAtMost(s.textChars).toDouble() / s.textChars
        return Math.round(share * 100) / 100.0
    }

    // ---------- time ----------

    /**
     * The release time and update time of the page declaration: first look at the structured declaration
     * (JSON-LD, then meta, then itemprop), and finally look at <time> in the text; in the same category,
     * follow the order of appearance. Unreadable values are skipped.
     */
    fun dates(s: Signals): Pair<String?, String?> {
        fun rank(src: String) = when {
            src.startsWith("ld:") -> 0
            src.startsWith("meta:") -> 1
            src.startsWith("itemprop:") -> 2
            else -> 3
        }
        fun pick(kind: (String) -> Boolean) = s.dates.withIndex()
            .filter { kind(it.value.first.lowercase()) }
            .sortedWith(compareBy({ rank(it.value.first) }, { it.index }))
            .firstNotNullOfOrNull { DeclaredTime.read(it.value.second) }
        val published = pick { k -> PUBLISHED_HINTS.any { it in k } || k == "time" || k == "time:pubdate" || k in PLAIN_DATE_KEYS }
        val updated = pick { k -> UPDATED_HINTS.any { it in k } }
        return published to updated
    }

    private val PUBLISHED_HINTS = listOf("published", "datecreated", "issued", "created", "pubdate", "publishdate", "publish_date")
    private val UPDATED_HINTS = listOf("modified", "updated", "lastmod", "revised")
    private val PLAIN_DATE_KEYS = setOf("meta:date", "meta:dc.date")

    /** Full page read quality. */
    fun quality(d: Digest, r: Rendered, check: ScreenCheck?, httpStatus: Int?): ReadQuality {
        val (v, cause) = verdict(r.text.length, d.signals, check, httpStatus)
        val (pub, upd) = dates(d.signals)
        return ReadQuality(r.text.length, density(d.signals), v, cause, pub, upd, d.dialogs.size, r.folded)
    }
}

/**
 * The time written on the page reads ISO-8601. Take out the number fields in order: four-digit year,
 * month, day, and possibly hour, minute, and second (fractional seconds are discarded). The time
 * followed by Z or ±hour[:minute] is the time zone. Any symbols between the year, month and day are
 * acceptable (-, /,., year, month and day). If the year, month, and day cannot be recognized, the value
 * is null if the value is not valid.
 */
object DeclaredTime {
    private class Scan(val s: String) {
        var i = 0
        fun skipNonDigits() { while (i < s.length && !s[i].isDigit()) i++ }
        fun digits(): String {
            val start = i
            while (i < s.length && s[i].isDigit()) i++
            return s.substring(start, i)
        }
        fun peek(): Char? = s.getOrNull(i)
    }

    fun read(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty() || text.length > 60) return null
        val sc = Scan(text)
        sc.skipNonDigits()
        val year = sc.digits().takeIf { it.length == 4 }?.toInt() ?: return null
        val nums = ArrayList<Int>()
        // Month and day: separated by a separator
        repeat(2) {
            val sep = sc.peek() ?: return null
            if (sep.isDigit() || sep.isWhitespace() && nums.isEmpty()) return null
            sc.i++
            val n = sc.digits().takeIf { it.length in 1..2 } ?: return null
            nums += n.toInt()
        }
        val date = runCatching { LocalDate.of(year, nums[0], nums[1]) }.getOrNull() ?: return null
        if (sc.peek() == '日') sc.i++
        // Time: T or hours: minutes [: seconds] after a space
        val tStart = sc.i
        if (sc.peek() == 'T' || sc.peek() == 't' || sc.peek() == ' ') sc.i++ else return date.toString()
        val hour = sc.digits().takeIf { it.length in 1..2 }?.toInt()
        if (hour == null || sc.peek() != ':') { sc.i = tStart; return date.toString() }
        sc.i++
        val minute = sc.digits().takeIf { it.length == 2 }?.toInt() ?: return date.toString()
        var second = 0
        if (sc.peek() == ':') {
            sc.i++
            second = sc.digits().takeIf { it.length == 2 }?.toInt() ?: return null
        }
        if (sc.peek() == '.' || sc.peek() == ',') { sc.i++; sc.digits() }
        val time = runCatching { LocalTime.of(hour, minute, second) }.getOrNull() ?: return null
        val local = "$date" + "T" + "%02d:%02d:%02d".format(time.hour, time.minute, time.second)
        return local + (zone(sc) ?: "")
    }

    /** Time zones such as Z, +08:00, +0800, -05; null if not. */
    private fun zone(sc: Scan): String? {
        while (sc.peek() == ' ') sc.i++
        val c = sc.peek() ?: return null
        if (c == 'Z' || c == 'z') return "Z"
        if (c != '+' && c != '-') return null
        sc.i++
        val a = sc.digits()
        val (hh, mm) = when {
            a.length == 4 -> a.substring(0, 2) to a.substring(2)
            a.length in 1..2 && sc.peek() == ':' -> { sc.i++; a to sc.digits() }
            a.length in 1..2 -> a to "00"
            else -> return null
        }
        val h = hh.toIntOrNull() ?: return null
        val m = mm.toIntOrNull() ?: return null
        if (h > 14 || m > 59) return null
        if (h == 0 && m == 0) return "Z"
        return "%c%02d:%02d".format(c, h, m)
    }
}

