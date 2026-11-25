package com.meenai.mina.browser

import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.i18n.trPlural
import com.meenai.mina.i18n.uiLocale
import com.meenai.mina.core.cap.web.WebUrlPolicy
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * "Recently opened" line: The search is written as "Search: <word>" (subtitle "Bing"), and the web page is
 * the title (use the website name if it cannot be read) and the website name. [host] is the host name of
 * the web page (used for drawing icons), and the search is null.
 */
data class RecentItem(val url: String, val title: String, val subtitle: String, val host: String?, val search: Boolean, val openedAtMs: Long)

/** When typing, enter the following association: [search]. If this sentence is really entered using Bing search, otherwise open [url]. */
data class Suggestion(val title: String, val subtitle: String, val url: String, val search: Boolean, val host: String?)

/** Arrangement on the start page: recently opened, input association, relative time. They are all pure functions, and the names are given by the caller according to the host name (see [SiteLooks.face]). */
object StartPageModel {
    val SEARCH_ENGINE: String get() = tr(R.string.browser_engine)
    val RECENT_SOURCE: String get() = tr(R.string.browser_recent_source)
    /** There are at most a few recently opened items in Lenovo. */
    const val MAX_RECENT_SUGGESTIONS = 5

    /** Recently opened in a row: only the most recent one of the same URL (not counting the #) is kept, with the most recent first. */
    fun recentRows(pages: List<RecentPage>, nameOf: (String) -> String): List<RecentItem> {
        val seen = HashSet<String>()
        return pages.sortedByDescending { it.openedAtMs }.filter { seen.add(it.url.trim().substringBefore('#')) }.map { p ->
            val q = AddressInput.searchQuery(p.url)
            if (q != null) {
                RecentItem(p.url, tr(R.string.browser_search_colon, q), SEARCH_ENGINE, null, true, p.openedAtMs)
            } else {
                val host = WebUrlPolicy.host(p.url)
                val site = host?.let(nameOf) ?: p.url
                RecentItem(p.url, p.title.ifBlank { site }, site, host, false, p.openedAtMs)
            }
        }
    }

    /**
     * Association when typing: The first item is the sentence itself - open it like a URL, and use Bing
     * to search for the rest; the second item is the recently opened title or URL containing this
     * sentence (regardless of case). Empty input has no association.
     */
    fun suggestions(input: String, recent: List<RecentItem>): List<Suggestion> {
        val q = input.trim()
        if (q.isEmpty()) return emptyList()
        val target = AddressInput.resolve(q) ?: return emptyList()
        val first = if (AddressInput.searchQuery(target) != null) Suggestion(tr(R.string.browser_search_q, q), SEARCH_ENGINE, target, true, null)
        else Suggestion(q, tr(R.string.browser_open_url), target, false, WebUrlPolicy.host(target))
        val hits = recent.filter { it.title.contains(q, ignoreCase = true) || it.url.contains(q, ignoreCase = true) }
            .filter { it.url != target }
            .take(MAX_RECENT_SUGGESTIONS)
            .map { Suggestion(it.title, RECENT_SOURCE, it.url, false, it.host) }
        return listOf(first) + hits
    }

    /** Relative time: "Just now" "10 minutes ago" "Today 11:20" "Yesterday" "October 21st", which does not include the year of this year. */
    fun relativeTime(atMs: Long, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val diff = nowMs - atMs
        if (diff in 0 until 60_000) return tr(R.string.browser_just_now)
        if (diff in 60_000 until 3_600_000) return trPlural(R.plurals.browser_minutes_ago, (diff / 60_000).toInt())
        val then = Instant.ofEpochMilli(atMs).atZone(zone)
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val days = ChronoUnit.DAYS.between(then.toLocalDate(), now.toLocalDate())
        return when {
            days == 0L -> tr(R.string.browser_today_at, "%02d:%02d".format(then.hour, then.minute))
            days == 1L -> tr(R.string.drawer_yesterday)
            then.year == now.year -> tr(R.string.date_month_day, then.monthValue, then.month.getDisplayName(java.time.format.TextStyle.SHORT, uiLocale()), then.dayOfMonth)
            else -> tr(R.string.date_year_month_day, then.year, then.monthValue, then.month.getDisplayName(java.time.format.TextStyle.SHORT, uiLocale()), then.dayOfMonth)
        }
    }
}
