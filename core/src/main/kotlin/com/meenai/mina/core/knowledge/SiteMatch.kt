package com.meenai.mina.core.knowledge

/**
 * Knowledge of which site the host name belongs to: According to the domains of the entry, the host name
 * is equal to a certain domain name or its subdomain name; if several matches are matched, the longest
 * matching domain name will be used (the most specific, such as shop.example.com is better than
 * example.com). It is used for both injecting site knowledge (KnowledgeStore.byDomain) and finding site
 * commands (GuideCatalog.siteCommands), and both sides recognize the same copy.
 */
object SiteMatch {
    /** [host] matches the length of the longest domain name in [domains]; if not, it is null. */
    fun score(host: String, domains: List<String>): Int? {
        val h = host.lowercase().removePrefix("www.")
        return domains.map { it.lowercase() }.filter { h == it || h.endsWith(".$it") }.maxOfOrNull { it.length }
    }

    /** The most specific ones in [items] that match [host] (the ones that are parallel are kept, and the order remains unchanged); if they don't match, they are empty. */
    fun <T> best(host: String, items: List<T>, domains: (T) -> List<String>): List<T> {
        val scored = items.mapNotNull { i -> score(host, domains(i))?.let { i to it } }
        val top = scored.maxOfOrNull { it.second } ?: return emptyList()
        return scored.filter { it.second == top }.map { it.first }
    }
}
