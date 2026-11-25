package com.meenai.mina.browser

import com.meenai.mina.chat.ConversationSummary
import com.meenai.mina.chat.PageOrigin
import com.meenai.mina.core.cap.web.WebUrlPolicy

/**
 * When asking Mina to a page on a browser page, which conversation should follow (pure logic). Rules: 1.
 * Asked on the same page (same URL, excluding `#`), then follow the most recent conversation, no matter
 * how long the interval is; 2. Otherwise, ask on the same website (same host name) in [SAME_SITE_MS],
 * then follow the most recent one - click all the way through a website (search results → product page →
 * shopping cart) and still the same thing; 3. Open a new conversation if there is no such thing. Users
 * can select "New Conversation" at any time. Coming back to another page of the same website after a
 * long time is probably about another matter, so Article 2 has a time limit; there is no time limit for
 * the same page. Returning to this page is just the continuation of this page.
 */
object PageConversations {
    /** The time limit for "continue asking" on the same website: the latest change is within this. */
    const val SAME_SITE_MS = 2 * 60 * 60 * 1000L

    /** [all] is the conversation list (most recently modified first, see [com.meenai.mina.chat.ConversationStore.list]); [url] is the page you are looking at at the moment. */
    fun pick(all: List<ConversationSummary>, url: String, now: Long): ConversationSummary? {
        val page = pageKey(url)
        val host = WebUrlPolicy.host(url)
        val fromPages = all.filter { it.page != null }.sortedByDescending { it.updatedAt }
        return fromPages.firstOrNull { pageKey(it.page!!.url) == page }
            ?: fromPages.firstOrNull { host != null && it.page!!.host == host && now - it.updatedAt in 0..SAME_SITE_MS }
    }

    /** This page records the source of the conversation: URL, hostname, title; if not the web address is null. */
    fun origin(url: String, title: String): PageOrigin? {
        val host = WebUrlPolicy.host(url)?.takeIf { url.startsWith("http", ignoreCase = true) } ?: return null
        return PageOrigin(url, host, title.trim())
    }

    /** To identify the URL used for "same page": remove the slashes after and at the end of `#`. */
    fun pageKey(url: String): String = url.substringBefore('#').trimEnd('/')
}
