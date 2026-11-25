package com.meenai.mina.core.knowledge

import com.meenai.mina.core.skills.PersonalSkills
import com.meenai.mina.core.skills.SkillStatus

/** Built-in App knowledge (kind `app`), site knowledge (`site`), skills (`skill`), or the user's personal skills (`personal`); [files] are readable files (main file first, the rest are attachments). */
data class GuideInfo(
    val kind: String,
    val name: String,
    val display: String,
    val description: String,
    val aliases: List<String>,
    val terms: List<String>,
    val files: List<String>,
    /** Associated App/site name. */
    val apps: List<String> = emptyList(),
    val sites: List<String> = emptyList(),
)

sealed interface GuideView {
    data class Found(
        val info: GuideInfo, val file: String, val content: String, val truncated: Boolean, val totalChars: Int,
    ) : GuideView

    data class Failed(val error: String, val message: String) : GuideView
}

private const val SKILL_FILE = "SKILL.md"
/** Documentation may mention tools not available natively; a reminder at the boundary of injection and knowledge_read / skill_read. */
const val UNAVAILABLE_TOOLS_NOTE = "(Note: scripts mentioned in the text and tools this phone does not have are not available; use only the tools in your tool list.)"
/** Note when the device supports sandbox: Skill scripts can be run according to the list. */
const val SANDBOX_TOOLS_NOTE = "(Note: scripts marked runnable can be run with run_bundled_script; other scripts and tools this phone does not have are not available.)"

/** Notes when injecting reference materials and knowledge_read / skill_read, depending on whether the sandbox tool provides them. */
fun toolsNote(sandboxAvailable: Boolean): String = if (sandboxAvailable) SANDBOX_TOOLS_NOTE else UNAVAILABLE_TOOLS_NOTE
const val VIEW_LIMIT = 12_000
const val FIND_LIMIT = 10
/** The task starts by giving the App/Site Knowledge Index a score threshold based on the task text: name/display name/alias at least partially matched. */
const val RECOMMEND_MIN_SCORE = 60
const val PERSONAL_KIND = "personal"
/** App and website knowledge (controlled by knowledge_search / knowledge_read); skills are another group (built-in skills and personal skills). */
val KNOWLEDGE_KINDS = setOf(KnowledgeIndex.APP, KnowledgeIndex.SITE)
val SKILL_KINDS = setOf(KnowledgeIndex.SKILL, PERSONAL_KIND)

/**
 * Read-only directory: search and read text by name/alias/description. The built-in part comes from the
 * [KnowledgeIndex.RESOURCE] generated during the build and their respective directories, and then merges
 * the user's personal skills (kind = `personal`, only those in use are counted; the whole with the same
 * name covers the built-in).
 */
class GuideCatalog private constructor(
    private val loader: ClassLoader,
    internal val rows: List<IndexEntry>,
    private val personal: PersonalSkills? = null,
    /** The rewriting was done when the personal skill text was loaded (the tool name before the renaming was changed to the current one); the default is as is. */
    private val rewrite: (String) -> String = { it },
) {
    private val bundledItems: List<GuideInfo> = rows.map(::info)

    /** Personal skills can be read every time: they can be accepted or deactivated at any time. */
    val items: List<GuideInfo>
        get() {
            val mine = personalEntries().map { it.info }
            val names = mine.map { it.name }.toSet()
            return mine + bundledItems.filter { it.name !in names }
        }

    /**
     * [route] means `android`: there is knowledge of the App named [name]; for `browser`: there is
     * knowledge of the site named [name], or the App named [name] is associated with the site.
     */
    fun hasRoute(name: String, route: String): Boolean = when (route) {
        "android" -> bundledItems.any { it.kind == KnowledgeIndex.APP && it.name == name }
        "browser" -> bundledItems.any { it.name == name && (it.kind == KnowledgeIndex.SITE || (it.kind == KnowledgeIndex.APP && it.sites.isNotEmpty())) }
        else -> false
    }

    /**
     * Scoring: The name/display name/alias is the same as the whole word > contains (or appears entirely
     * in the query word) > search term > description; each blank-separated word is scored separately,
     * and at least one word is hit and the total score is not lower than [minScore]. When [among] is
     * given, only check these types (see [KNOWLEDGE_KINDS], [SKILL_KINDS]).
     */
    fun find(query: String, kind: String? = null, limit: Int = FIND_LIMIT, minScore: Int = 0, among: Set<String>? = null): List<GuideInfo> {
        val tokens = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()
        return items.filter { (kind == null || it.kind == kind) && (among == null || it.kind in among) }
            .mapNotNull { s -> score(s, tokens).takeIf { it > 0 && it >= minScore }?.let { s to it } }
            .sortedByDescending { it.second }
            .take(limit).map { it.first }
    }

    private fun score(s: GuideInfo, tokens: List<String>): Int {
        val names = (listOf(s.name, s.display) + s.aliases).map { it.lowercase() }
        val terms = s.terms.map { it.lowercase() }
        val desc = s.description.lowercase()
        return tokens.map { t ->
            when {
                names.any { it == t } -> 100
                // Chinese targets usually do not have spaces, and the entire sentence is one word: if
                // the name appears in the sentence, it is also considered a partial match.
                names.any { it.contains(t) || (it.length >= 2 && t.contains(it)) } -> 60
                terms.any { it == t } -> 50
                terms.any { it.contains(t) } -> 40
                desc.contains(t) -> 20
                else -> 0
            }
        }.sum()
    }

    /** [name] can be written as `app:meituan` / `site:jd.com` / `skill:documents` / `personal:xxx`, or just the name can be written (the same name will cause ambiguity). */
    /** [note] is a note placed before the text (see [toolsNote]). When [among] is given, only these types can be read. When the name is in another group, please indicate which tool should be used to read it. */
    fun view(name: String, file: String? = null, note: String = UNAVAILABLE_TOOLS_NOTE, among: Set<String>? = null): GuideView {
        val n = name.trim()
        val prefixed = n.substringBefore(':', "").takeIf { it in KnowledgeIndex.KINDS || it == PERSONAL_KIND }
        val id = if (prefixed != null) n.substringAfter(':') else n
        val named = items.filter { it.name == id && (prefixed == null || it.kind == prefixed) }
        val hits = named.filter { among == null || it.kind in among }
        val knowledge = among == KNOWLEDGE_KINDS
        val info = when (hits.size) {
            0 -> return GuideView.Failed(if (knowledge) "knowledge_not_found" else "skill_not_found", when {
                named.isNotEmpty() && knowledge -> "'$n' is a skill, not App or website knowledge; read it with skill_read."
                named.isNotEmpty() -> "'$n' is App or website knowledge, not a skill; read it with knowledge_read."
                knowledge -> "No App or website knowledge named '$n'. Use knowledge_search to find it."
                else -> "No skill named '$n'. Use skill_search to find it."
            })
            1 -> hits.single()
            else -> return GuideView.Failed(if (knowledge) "ambiguous_name" else "ambiguous_skill",
                "'$n' exists as ${hits.joinToString { it.kind }}; use ${hits.joinToString(" or ") { "${it.kind}:${it.name}" }}.")
        }
        val main = info.files.first()
        val path = (file?.trim()?.takeIf { it.isNotEmpty() } ?: main).removePrefix("./")
        if (info.kind == PERSONAL_KIND) return viewPersonal(info, path)
        if (path !in info.files)
            return GuideView.Failed("file_not_found", "No file '$path' in ${info.kind}:${info.name}. Available: ${info.files.joinToString()}.")
        val text = (if (path == main) fullText(info) else read(info, path))
            ?: return GuideView.Failed("file_not_found", "File '$path' could not be read.")
        val cut = text.length > VIEW_LIMIT
        return GuideView.Found(info, path, note + "\n" + (if (cut) text.take(VIEW_LIMIT) + "\n…(truncated; use file_path to view the other files)" else text), cut, text.length)
    }

    // Broken personal skills will clearly report an error and will not fall back to the built-in skill
    // with the same name.
    private fun viewPersonal(info: GuideInfo, path: String): GuideView {
        if (path != SKILL_FILE) return GuideView.Failed("file_not_found", "Personal skill ${info.name} has only $SKILL_FILE.")
        val body = personalEntries().find { it.info.name == info.name }?.body
            ?: return GuideView.Failed("personal_skill_invalid",
                "The user's personal skill '${info.name}' is damaged and cannot be read; do not rely on it. The user can fix or delete it in Memory & skills.")
        val cut = body.length > VIEW_LIMIT
        return GuideView.Found(info, SKILL_FILE, if (cut) body.take(VIEW_LIMIT) + "\n…(truncated)" else body, cut, body.length)
    }

    /** The main text of the main file (APP.md / SITE.md / SKILL.md), remove the header. Broken personal skills are null. */
    fun fullText(info: GuideInfo): String? {
        if (info.kind == PERSONAL_KIND) return personalEntries().find { it.info.name == info.name }?.body
        return read(info, KnowledgeIndex.fileOf(info.kind))
    }

    private fun read(info: GuideInfo, file: String): String? =
        resource("${KnowledgeIndex.dirOf(info.kind)}/${info.name}/$file")?.let { FrontMatter.parse(it.toString(Charsets.UTF_8)).second }

    /** The directory in the sandbox (under `/opt/mina/skills`) is also the source parameter of run_bundled_script, such as `skills/documents`, `skills/research`. */
    fun scriptSource(info: GuideInfo): String = "${KnowledgeIndex.dirOf(info.kind).substringAfterLast('/')}/${info.name}"

    /**
     * Scripts to be synchronized into the sandbox: each entry with `scripts/manifest.json`, the manifest
     * itself plus the files listed in the manifest. The key is a relative path under `/opt/mina/skills`.
     */
    fun scriptFiles(): Map<String, ByteArray> {
        val out = sortedMapOf<String, ByteArray>()
        for (info in bundledItems) {
            val src = "${KnowledgeIndex.dirOf(info.kind)}/${info.name}"
            val manifest = resource("$src/scripts/manifest.json") ?: continue
            val dir = scriptSource(info)
            out["$dir/scripts/manifest.json"] = manifest
            for (e in ScriptManifest.parse(manifest.toString(Charsets.UTF_8))) resource("$src/${e.file}")?.let { out["$dir/${e.file}"] = it }
        }
        return out
    }

    /** Scripts marked as executable (and of a executable script type) in the list are `directory/file`, such as `skills/documents/scripts/extract_pdf.py`. */
    fun runnableScripts(): Set<String> {
        val out = sortedSetOf<String>()
        for (info in bundledItems) {
            val manifest = resource("${KnowledgeIndex.dirOf(info.kind)}/${info.name}/scripts/manifest.json") ?: continue
            for (e in ScriptManifest.parse(manifest.toString(Charsets.UTF_8))) {
                if (e.runnable && ScriptManifest.interpreterFor(e.file) != null) out += "${scriptSource(info)}/${e.file}"
            }
        }
        return out
    }

    /** Are there any site commands declared in the built-in site knowledge (if not, the tool for running site commands will not be provided). */
    fun hasSiteCommands(): Boolean = rows.any { it.kind == KnowledgeIndex.SITE && it.commands.isNotEmpty() }

    /**
     * The site command declared by the site to which host [host] belongs, with script text; if not, it
     * is empty. The site and the site knowledge injected are recognized according to the same rules
     * ([SiteMatch], the most specific domain name takes precedence). If there is no command for the
     * recognized site, it means it does not exist, and it will not be returned to other sites.
     */
    fun siteCommands(host: String): List<SiteCommand> {
        val site = SiteMatch.best(host, rows.filter { it.kind == KnowledgeIndex.SITE }) { it.domains }.firstOrNull()
            ?: return emptyList()
        val dir = "${KnowledgeIndex.dirOf(KnowledgeIndex.SITE)}/${site.name}"
        return site.commands.mapNotNull { d ->
            resource("$dir/${d.file}")?.toString(Charsets.UTF_8)?.let { SiteCommand(site.name, d, SiteCommandFile.parse(d.file, it).body) }
        }
    }

    /** The pace written by the knowledge of the site to which host [host] belongs (the rules for identifying the site are the same as [siteCommands]); if not, it is null. */
    fun sitePace(host: String): String? =
        SiteMatch.best(host, rows.filter { it.kind == KnowledgeIndex.SITE }) { it.domains }.firstOrNull()?.pace

    /** The package name [packageName] is written in pace by the knowledge of the App it belongs to (the package name is in its packages); if not, it is null. */
    fun appPace(packageName: String): String? =
        rows.firstOrNull { it.kind == KnowledgeIndex.APP && packageName in it.packages }?.pace

    private fun resource(rel: String): ByteArray? = loader.getResourceAsStream(rel)?.use { it.readBytes() }

    /** [body] is null: SKILL.md header is missing, the name does not match, or the body is empty. */
    private class PersonalEntry(val info: GuideInfo, val body: String?)

    private fun personalEntries(): List<PersonalEntry> =
        personal?.list().orEmpty().filter { it.status == SkillStatus.active || it.status == SkillStatus.trial }.map { s ->
            // Change the tool name written before the name change to the current one, and the model will
            // read all the usable names.
            val (meta, body) = FrontMatter.parse(s.markdown).let { (m, b) -> m to rewrite(b) }
            fun one(k: String) = meta[k] as? String
            fun many(k: String) = (meta[k] as? List<*>)?.filterIsInstance<String>() ?: listOfNotNull(one(k)?.takeIf { it.isNotEmpty() })
            val valid = one("name") == s.name && body.isNotBlank()
            PersonalEntry(
                GuideInfo(
                    kind = PERSONAL_KIND, name = s.name, display = one("display")?.takeIf { it.isNotEmpty() } ?: s.name,
                    description = (if (s.status == SkillStatus.trial) "[unverified: check the result when trying it] " else "") + (one("description") ?: ""), aliases = many("aliases"),
                    terms = many("apps") + many("domains"), files = listOf(SKILL_FILE),
                    apps = many("apps"), sites = many("sites") + many("domains"),
                ),
                body.takeIf { valid }?.let { if (s.status == SkillStatus.trial) "[Unverified personal skill draft: use it only as a trial; the result must be checked.]\n$it" else it },
            )
        }

    companion object {
        fun loadBundled(loader: ClassLoader = GuideCatalog::class.java.classLoader): GuideCatalog {
            val rows = loader.getResourceAsStream(KnowledgeIndex.RESOURCE)?.use { KnowledgeIndex.decode(it.readBytes().toString(Charsets.UTF_8)) }
            return GuideCatalog(loader, rows.orEmpty())
        }

        /** Built-in directory + personal skills; [rewrite] is the rewrite when the personal skill text is loaded (the tool name before the rename is changed to the current one). */
        fun withPersonal(bundled: GuideCatalog, personal: PersonalSkills, rewrite: (String) -> String = { it }): GuideCatalog =
            GuideCatalog(bundled.loader, bundled.rows, personal, rewrite)

        private fun info(e: IndexEntry) = GuideInfo(
            kind = e.kind, name = e.name, display = e.display, description = e.description,
            aliases = e.aliases, terms = e.terms, files = listOf(KnowledgeIndex.fileOf(e.kind)) + e.files,
            apps = e.apps, sites = e.sites,
        )
    }
}
