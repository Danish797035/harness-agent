package com.meenai.mina.core.knowledge

import com.meenai.mina.core.prompt.Feedback
import java.io.File

private val GENERIC = setOf("app", "apps", "web", "www", "site", "browser")

// Chinese words that are too general: only "open/enter/go"+words will count if they are explicitly named
// ("set an alarm" does not count)
private val GENERIC_ZH = setOf(
    "设置", "浏览器", "搜索", "地图", "相机", "相册", "日历", "时钟", "天气", "音乐",
    "视频", "文件", "短信", "电话", "邮件", "商城", "应用", "手机",
)
private val NAMING_PREFIXES = listOf("打开", "进入", "去")

// Common system app names. When the brand word is followed immediately by it ("a certain brand's
// application market"), it is talking about this App, not the brand's own mall and other items.
private val APP_KINDS = listOf(
    "应用市场", "应用商店", "浏览器", "钱包", "日历", "时钟", "相机", "图库", "文件管理", "主题", "云空间",
    "运动健康", "计算器", "便签", "备忘录", "录音机", "游戏中心", "手机管家", "天气",
)

/**
 * Built-in knowledge + user supplement: [notes] is based on the item name (App name or site domain
 * name), attached to the text of the corresponding item and marked as written by the user;
 * [learnedNotes] is Mina’s self-taught App knowledge supplement, attached to the end and marked as being
 * in trial or verified. The match is a deterministic string match and does not call the model.
 */
class KnowledgeStore(private val bundled: List<KnowledgeEntry>, notes: Map<String, String> = emptyMap(),
    learnedNotes: Map<String, LearnedNote> = emptyMap()) {
    private val entries: List<KnowledgeEntry> = bundled.map { e ->
        val personal = notes[e.name]
        val learned = learnedNotes[e.name]
        if (personal == null && learned == null) return@map e
        e.copy(body = listOfNotNull(e.body, personal?.let { "$NOTES_HEADER\n$it" },
            learned?.let { Feedback.format(if (it.trial) "knowledge.learned_trial" else "knowledge.learned", "notes" to it.text) }).joinToString("\n\n"),
            trial = learned?.trial == true)
    }

    /** The built-in text of the entry [name] itself (without user supplements and self-study supplements); use it as the "existing description" when drafting the self-study supplements. */
    fun builtIn(name: String): String? = bundled.firstOrNull { it.name == name && it.body != null }?.body

    private fun pick(c: List<KnowledgeEntry>): KnowledgeEntry? = c.firstOrNull { it.body != null } ?: c.firstOrNull()

    fun byPackage(pkg: String): KnowledgeEntry? = pick(entries.filter { pkg in it.packages })

    /** Site knowledge on hostname pairs (most specific domain name first, see [SiteMatch]). */
    fun byDomain(host: String): KnowledgeEntry? = pick(SiteMatch.best(host, entries) { it.domains })

    /**
     * The entry that hits the task text. Entries with installed apps are ranked first, followed by those
     * with text, and then by the length of the hit word (the longer, the more specific). [installed] is
     * an installed App (package name, display name): When the hit word is followed by the name of an
     * installed App, it refers to that App (such as "a certain brand" + "App Market"), and it is not
     * counted as a hit.
     */
    fun matchTask(text: String, limit: Int = 2, installed: List<Pair<String, String>> = emptyList()): List<KnowledgeEntry> {
        val t = text.lowercase()
        val packages = installed.map { it.first }.toSet()
        val followers = (APP_KINDS + installed.map { it.second.trim().lowercase() }.filter { it.length >= 2 }).distinct()
        return entries.mapNotNull { e -> score(e, t, followers)?.let { e to it } }
            .sortedWith(compareBy({ it.first.packages.none(packages::contains) }, { it.first.body == null }, { -it.second }))
            .map { it.first }
            .distinctBy { it.service } // For Apps and sites of the same service, only the one with the highest ranking will be given; when the other copy enters the App/site, it will be given based on the package name/domain name.
            .take(limit)
    }

    /** "Little Red Book" → com.xingin.xhs; Exact match by name/display name/alias (ignore case), only look at Android entries. */
    fun packageFor(nameOrAlias: String): String? {
        val q = nameOrAlias.trim().lowercase()
        if (q.isEmpty()) return null
        return entries.filter { it.packages.isNotEmpty() }
            .filter { e -> (listOf(e.name, e.display) + e.aliases).any { it.lowercase() == q } }
            .let { c -> c.firstOrNull { it.body != null } ?: c.firstOrNull() }
            ?.let { e -> e.packageAliases.entries.firstOrNull { it.key.lowercase() == q }?.value ?: e.packages.first() }
    }

    private fun score(e: KnowledgeEntry, text: String, followers: List<String>): Int? {
        var best = -1
        val terms = (listOf(e.display, e.name) + e.aliases).map { it.trim().lowercase() }
        // The name of the entry (such as "a certain brand mall") does not count as "another App"
        val others = followers.filter { f -> terms.none { it.endsWith(f) } }
        for (x in terms) {
            if (x.length < 2 || x in GENERIC || (x.all { it.isDigit() } && x.length < 3)) continue
            val hit = if (x in GENERIC_ZH) NAMING_PREFIXES.any { text.contains(it + x) } else containsTerm(text, x, others)
            if (hit) best = maxOf(best, x.length)
        }
        for (d in e.domains) {
            val x = d.trim().lowercase()
            if (x.length >= 3 && text.contains(x)) best = maxOf(best, x.length)
        }
        return best.takeIf { it >= 0 }
    }

    // English/numeric words require word boundaries to prevent "mm" from hitting "command"; the Chinese
    // direct substring [others] is the name of another App: when the hit word is followed by it, the hit
    // word is only part of the name of that App
    private fun containsTerm(text: String, term: String, others: List<String> = emptyList()): Boolean {
        var from = 0
        while (true) {
            val i = text.indexOf(term, from)
            if (i < 0) return false
            val before = text.getOrNull(i - 1)
            val after = text.getOrNull(i + term.length)
            val leftOk = !term.first().isAsciiAlnum() || before == null || !before.isAsciiAlnum()
            val rightOk = !term.last().isAsciiAlnum() || after == null || !after.isAsciiAlnum()
            val namesOther = others.any { text.startsWith(it, i + term.length) }
            if (leftOk && rightOk && !namesOther) return true
            from = i + 1
        }
    }

    private fun Char.isAsciiAlnum() = this in 'a'..'z' || this in '0'..'9'

    companion object {
        /** A title for user additions to append to app/site knowledge. */
        const val NOTES_HEADER = "## The user's own notes (written by the user; when they differ from the text above or the current screen, the current screen takes precedence)"

        /**
         * Read the App (APP.md, Android) and site (SITE.md, web page) knowledge in the index generated
         * during construction; the main text is taken from the main file and the header is removed, and
         * the App and site are related to each other through [KnowledgeEntry.related].
         */
        fun loadBundled(loader: ClassLoader = KnowledgeStore::class.java.classLoader): List<KnowledgeEntry> {
            val catalog = GuideCatalog.loadBundled(loader)
            val infos = catalog.items.associateBy { it.kind to it.name }
            return catalog.rows.filter { it.kind == KnowledgeIndex.APP || it.kind == KnowledgeIndex.SITE }.map { e ->
                val app = e.kind == KnowledgeIndex.APP
                KnowledgeEntry(
                    name = e.name, display = e.display, surface = if (app) Surface.Android else Surface.Web,
                    packages = e.packages, domains = e.domains, aliases = e.aliases,
                    open = e.open, description = e.description,
                    body = catalog.fullText(infos.getValue(e.kind to e.name))?.trim()?.ifEmpty { null },
                    packageAliases = e.packageAliases,
                    links = e.links.map { raw -> raw.split('|').map(String::trim).let { Link(it[0], it[1], it.getOrNull(2)) } },
                    related = if (app) e.sites else e.apps,
                    commands = e.commands,
                )
            }
        }

        /** User's additions to an App/site: `<dir>/apps/<id>/NOTES.md`, `<dir>/sites/<id>/NOTES.md`, by entry name. */
        fun loadNotes(dir: File): Map<String, String> =
            listOf("apps", "sites").flatMap { sub ->
                File(dir, sub).listFiles { f -> f.isDirectory }.orEmpty().sortedBy { it.name }.mapNotNull { d ->
                    File(d, "NOTES.md").takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }?.let { d.name to it }
                }
            }.toMap()
    }
}

