package com.meenai.mina.core.knowledge

import com.meenai.mina.core.net.UrlHost

/**
 * Facts to use when selecting a channel: installed package names, site hostnames logged into Mina,
 * whether the built-in browser can be used, and site hostnames that have recently encountered
 * human-computer authentication or access blocking in Mina.
 */
data class ChannelFacts(
    val installed: Set<String>,
    val loggedInSites: Set<String>,
    val webAvailable: Boolean,
    val challengedSites: Set<String> = emptySet(),
)

/** Give the line "available channels" at the beginning of the task: the code only gives facts, and which one to choose is determined by the model according to the rules of the web page. */
object ChannelAdvisor {
    private const val HEADER = "Available channels: "
    /** Only the top few are listed, which is equivalent to the maximum number of reference materials injected into each task. */
    private const val MAX_CHANNELS = 3

    /**
     * Example: "Available channels: Xiaohongshu - App: installed (com.xingin.xhs), with App practices;
     * web page: xiaohongshu.com, with web page practices, logged in in Mina (m.xiaohongshu.com)".
     * [entries] According to the matching order, only the first [MAX_CHANNELS] channels are taken.
     * Nothing to say is null.
     */
    fun line(entries: List<KnowledgeEntry>, skills: GuideCatalog?, facts: ChannelFacts): String? {
        val parts = entries.groupBy { it.service }.mapNotNull { (name, same) -> channel(name, same, skills, facts) }.take(MAX_CHANNELS)
        if (parts.isEmpty()) return null
        return HEADER + parts.joinToString(" | ")
    }

    private fun channel(name: String, same: List<KnowledgeEntry>, skills: GuideCatalog?, facts: ChannelFacts): String? {
        val packages = same.flatMap { it.packages }.distinct()
        // The site name associated with the App entry is the main domain name of the site
        val domains = same.flatMap { it.domains + if (it.surface == Surface.Android) it.related else emptyList() }.map { it.trim().lowercase().removePrefix("www.") }.filter { it.isNotEmpty() }.distinct()
        val app = if (packages.isEmpty()) null else {
            val state = packages.firstOrNull { it in facts.installed }?.let { "app: installed ($it)" } ?: "app: not installed"
            if (skills?.hasRoute(name, "android") == true) "$state, known way to do it in the app" else state
        }
        val web = domains.firstOrNull()?.takeIf { facts.webAvailable }?.let { d ->
            fun ofSite(hosts: Set<String>) = hosts.firstOrNull { s -> domains.any { dd -> UrlHost.sameSite("https://$dd", "https://$s") } }
            val loggedIn = ofSite(facts.loggedInSites)
            val challenged = ofSite(facts.challengedSites)
            val notes = listOfNotNull(
                "known way to do it on the web".takeIf { skills?.hasRoute(name, "browser") == true },
                loggedIn?.let { "signed in within Harness Agent ($it)" },
                challenged?.let { "recently hit a verification or block here ($it); the user can clear this site's data in settings" },
            )
            (listOf("web: $d") + notes).joinToString(", ")
        }
        if (app == null && web == null) return null
        return "${same.first().display} — " + listOfNotNull(app, web).joinToString("; ")
    }
}
