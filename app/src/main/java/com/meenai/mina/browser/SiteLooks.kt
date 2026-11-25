package com.meenai.mina.browser

import com.meenai.mina.core.cap.web.WebUrlPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * What a website looks like on the browser page: name ([name], null if not read) and its origin ([rank],
 * see [SiteNames]), the time the website icon was saved ([iconAtMs], null if there is no icon; the
 * interface uses this to determine whether to reread the icon file).
 */
data class SiteLook(val name: String?, val rank: Int, val iconAtMs: Long?)

/** Everything you need to draw a website on the interface: name, first character of the name, background color (used when there is no icon), icon file and its version. */
data class SiteFace(val host: String, val name: String, val initial: String, val color: Long, val icon: File?, val iconAtMs: Long)

/**
 * The name and icon of the website: When the user opens the web page in the browser, write it down (the
 * name is taken from the page, and the icon is taken from the website icon received by WebView) for
 * "frequently used websites" and "recently opened". According to the host name, remember it on the
 * registrable domain name (if there is no better one there), and the pages of other sub-domain names of
 * the same website will also be recognized. The name is stored in a JSON file in the App private
 * directory, and the icon is a PNG in the icons directory next to it; if the file is damaged, start over
 * from the empty list.
 */
class SiteLooks(dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val file = File(dir, FILE)
    private val iconDir = File(dir, ICONS)
    private val _entries = MutableStateFlow(load())

    /** hostname → looks like. */
    val entries: StateFlow<Map<String, SiteLook>> = _entries.asStateFlow()

    /** Note down the name given on the page: change it only if the origin is no worse than the existing one. */
    @Synchronized
    fun named(host: String, name: String, rank: Int) {
        val n = name.trim().takeIf { it.isNotEmpty() } ?: return
        var map = _entries.value
        keys(host).forEachIndexed { i, h ->
            val cur = map[h]
            // The host name itself: also changes to new names that are equally reliable; registrable
            // domain names: only change when more reliable (pages with other subdomains do not compete
            // for the name of the homepage)
            val replace = cur?.name == null || (if (i == 0) rank >= cur.rank else rank > cur.rank)
            if (replace && cur?.name != n) map = map + (h to SiteLook(n, rank, cur?.iconAtMs))
        }
        if (map != _entries.value) save(map)
    }

    /**
     * Before writing the icon into [iconFile], ask here: which host names should be saved to. Save when
     * the host name itself has no icon or exceeds [ICON_REFRESH_MS]; also save a copy when the
     * registrable domain name does not have an icon. You will receive the icon every time you open a
     * page, so you don't have to rewrite it every time.
     */
    @Synchronized
    fun iconTargets(host: String): List<String> = keys(host).filterIndexed { i, h ->
        val at = _entries.value[h]?.iconAtMs
        at == null || (i == 0 && clock() - at >= ICON_REFRESH_MS)
    }

    /** The icon file for this hostname (may not exist yet). */
    fun iconFile(host: String): File = File(iconDir, "${host.lowercase()}.png")

    /** The icon has been written into [iconFile]. */
    @Synchronized
    fun iconSaved(host: String) {
        val h = host.lowercase()
        val cur = _entries.value[h]
        save(_entries.value + (h to SiteLook(cur?.name, cur?.rank ?: 0, clock())))
    }

    /** Draw the desired look of this website: first find the host name itself, and then find the registrable domain name; if neither the name nor the background color is found, push it according to the host name. */
    fun face(host: String): SiteFace = faceOf(host, _entries.value, ::iconFile)

    @Synchronized
    fun clear() {
        iconDir.listFiles()?.forEach { it.delete() }
        save(emptyMap())
    }

    private fun load(): Map<String, SiteLook> {
        if (!file.isFile) return emptyMap()
        return try {
            (Json.parseToJsonElement(file.readText()) as? JsonObject)?.mapNotNull { (h, e) ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val rank = (o["rank"] as? JsonPrimitive)?.intOrNull ?: 0
                // The name based on the title of other pages may be wrong (see SiteNames): throw it away
                // and wait for the home page or the declared website name to be renamed.
                val name = (o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { rank >= SiteNames.RANK_HOME_TITLE }
                h to SiteLook(name, if (name == null) 0 else rank, (o["iconAt"] as? JsonPrimitive)?.longOrNull)
            }?.toMap().orEmpty()
        } catch (e: SerializationException) {
            emptyMap()
        }
    }

    private fun save(map: Map<String, SiteLook>) {
        _entries.value = map
        file.parentFile?.mkdirs()
        val json = JsonObject(map.mapValues { (_, l) ->
            buildJsonObject {
                l.name?.let { put("name", it) }
                put("rank", l.rank)
                l.iconAtMs?.let { put("iconAt", it) }
            }
        }).toString()
        // Write the temporary file first and then rename it. If it is killed in the middle of writing,
        // half of the file will not be left.
        val tmp = File(file.parentFile, "$FILE.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) {
            file.writeText(json)
            tmp.delete()
        }
    }

    companion object {
        private const val FILE = "browser_sites.json"
        private const val ICONS = "browser_icons"
        /** The icon of the same host name is only re-saved after such a long time (when the website changes the icon). */
        const val ICON_REFRESH_MS = 24L * 60 * 60 * 1000

        /** The hostname itself, plus its registrable domain name (not simultaneously). */
        fun keys(host: String): List<String> {
            val h = host.trim().lowercase().removePrefix("www.")
            if (h.isEmpty()) return emptyList()
            val site = WebUrlPolicy.registrable(h)
            return if (site == null || site == h) listOf(h) else listOf(h, site)
        }

        fun faceOf(host: String, looks: Map<String, SiteLook>, iconFile: (String) -> File?): SiteFace {
            val found = keys(host).mapNotNull { looks[it] }
            val name = found.firstNotNullOfOrNull { it.name } ?: SiteNames.fallback(host)
            val iconKey = keys(host).firstOrNull { looks[it]?.iconAtMs != null }
            return SiteFace(
                host, name, SiteNames.initial(name), SiteTiles.color(host),
                iconKey?.let(iconFile), iconKey?.let { looks[it]?.iconAtMs } ?: 0L,
            )
        }
    }
}

/**
 * Where does the name of the website come from? The larger the [rank], the more reliable it is: the
 * website name declared on the page (og:site_name, application-name) is the most reliable; the title of
 * the homepage is second (mostly starting with the website name). The titles of other pages are not used
 * for naming: it is uncertain which paragraph is the website name. The last paragraph of the title of
 * pages such as login and error is often the page name such as "Unified Login", which will change the
 * name of the website. If there are none, use the host name to recommend one.
 */
object SiteNames {
    const val RANK_DECLARED = 3
    /** The name whose origin is below it (recorded earlier according to the title of another page) is no longer used and is discarded when read (see [SiteLooks]). */
    const val RANK_HOME_TITLE = 2
    /** So many characters are cut off when the name is too long (no more can fit in one box in the grid). */
    const val MAX_LEN = 12

    /** Commonly used separations between the website name and the page name in the title: horizontal lines with spaces on both sides, colons, or vertical lines, underlines, and spacers. */
    private val SEPARATORS = Regex("""\s+[-–—:：]\s+|\s*[|｜_·•]\s*""")

    /** Select a name and its origin from something read on the page: the declared website name, or the first paragraph of the home page title; null if not selected. */
    fun pick(declared: String?, title: String?, homePage: Boolean): Pair<String, Int>? {
        clean(declared)?.let { return it to RANK_DECLARED }
        if (!homePage) return null
        val first = title?.split(SEPARATORS)?.firstNotNullOfOrNull(::clean) ?: return null
        return first to RANK_HOME_TITLE
    }

    /** A read-only script that reads the website name and title in the main frame of the page. The result is the JSON of {site, title} (wrapped with a layer of string through evaluateJavascript). */
    val SCRIPT = """(function(){try{function m(s){var e=document.querySelector(s);return e&&e.content?String(e.content):'';}
return JSON.stringify({site:m('meta[property="og:site_name"]')||m('meta[name="application-name"]')||m('meta[name="apple-mobile-web-app-title"]'),title:String(document.title||'')});}catch(x){return null;}})()"""

    /** The result of parsing [SCRIPT]: (declared website name, title); if not read, it is null. */
    fun parse(raw: String?): Pair<String?, String?>? {
        val inner = runCatching { (Json.parseToJsonElement(raw ?: return null) as? JsonPrimitive)?.takeIf { it.isString }?.content }.getOrNull() ?: return null
        val o = runCatching { Json.parseToJsonElement(inner) as? JsonObject }.getOrNull() ?: return null
        fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
        return str("site") to str("title")
    }

    /** When the path is empty or /, the homepage is calculated. */
    fun isHomePage(url: String): Boolean {
        val path = runCatching { java.net.URI(url.trim()).rawPath }.getOrNull().orEmpty()
        return path.isEmpty() || path == "/"
    }

    /** When the name is not read: you can register the domain name and remove the suffix (shop.example.com → example); the IP and so on remain unchanged. */
    fun fallback(host: String): String {
        val h = host.trim().lowercase().removePrefix("www.")
        if ('.' !in h || h.startsWith("[") || h.all { it.isDigit() || it == '.' }) return h
        WebUrlPolicy.registrable(h)?.let { return it.substringBefore('.') }
        return h.substringBeforeLast('.')
    }

    /** The first character of the name (capitalized in Spanish). */
    fun initial(name: String): String {
        val cp = name.trim().codePointAt0() ?: return "?"
        return String(Character.toChars(cp)).uppercase()
    }

    private fun String.codePointAt0(): Int? = if (isEmpty()) null else codePointAt(0)

    private fun clean(s: String?): String? {
        val t = s?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return if (t.length > MAX_LEN) t.take(MAX_LEN).trimEnd() else t
    }
}

/** The background color of the website box when there is no icon: Select one according to the host name (excluding www), and it will be the same every time for the same website. */
object SiteTiles {
    /** There are several colors (ARGB) that can hold up white characters in both dark and light colors. */
    val PALETTE = listOf(
        0xFFE5533D, 0xFFF08A24, 0xFFC9A100, 0xFF2E9E5B, 0xFF16A0A0,
        0xFF2F7DE1, 0xFF5B5BD6, 0xFF9A4FD0, 0xFFD6457F, 0xFF6B7280,
    )

    fun color(host: String): Long {
        val key = host.trim().lowercase().removePrefix("www.")
        // FNV-1a: Similar host names can be separated, and the results will be the same every time.
        var h = 0x811C9DC5.toInt()
        for (ch in key) h = (h xor ch.code) * 0x01000193
        return PALETTE[Math.floorMod(h, PALETTE.size)]
    }
}
