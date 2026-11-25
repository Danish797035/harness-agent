package com.meenai.mina.core.knowledge

enum class Surface { Android, Web, Desktop }

/** A copy of App(APP.md,[Surface.Android]) or site(SITE.md,[Surface.Web]) knowledge. */
data class KnowledgeEntry(
    val name: String,
    val display: String,
    val surface: Surface,
    val packages: List<String>,
    val domains: List<String>,
    val aliases: List<String>,
    val open: String?,
    val description: String,
    val body: String?,
    /** When the same App has multiple package names, the aliases correspond to the corresponding package names (such as "Meituan Takeout" → independent package for takeout). */
    val packageAliases: Map<String, String> = emptyMap(),
    /** A page that has been verified on real devices and can be accessed directly through open_link; the header is written as `links: ["name | uri", "name | uri | package name",...]`. */
    val links: List<Link> = emptyList(),
    /** The entry name of another running surface of the same service: the App entry writes its site, and the site entry writes its App. */
    val related: List<String> = emptyList(),
    /** Declaration of site command (only available on site); script text is taken from [GuideCatalog.siteCommands]. */
    val commands: List<SiteCommandDecl> = emptyList(),
    /** Attached to the text is a trial draft of Mina’s self-study and unverified App knowledge. */
    val trial: Boolean = false,
) {
    /** The name shared by apps and sites in the same service: the name of the app when the site is associated with the app. */
    val service: String get() = if (surface == Surface.Web) related.firstOrNull() ?: name else name
}

/** A direct link; the web link must contain [packageName] (which App opened it from). */
data class Link(val name: String, val uri: String, val packageName: String? = null)
