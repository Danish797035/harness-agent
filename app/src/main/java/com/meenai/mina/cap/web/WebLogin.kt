package com.meenai.mina.cap.web

import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import com.meenai.mina.core.cap.web.WebUrlPolicy

/**
 * Exiting the Site: Do your best. The system interface can only read the name of the cookie according to
 * the URL, but cannot read the path, domain and HttpOnly details. Therefore, for each cookie read by
 * this host name and all levels of upper-level domains (until the domain name can be registered), write
 * an expiration record according to "belongs to the host only" and "belongs to the entire domain"
 * respectively, and then clears the web page storage from these sources. The login of some websites may
 * still be there, please use [logoutAll] to completely clear it. [cookies] and [storage] are configured
 * using the mina web page (see [WebTab.cookieManager]); called by the main thread.
 */
class WebLogin(private val cookies: CookieManager, private val storage: WebStorage, private val registry: SiteRegistry?) {

    /** Returns the number of expired cookies (by name). */
    fun logout(host: String): Int {
        val h = WebUrlPolicy.host("https://${host.trim()}") ?: return 0
        var expired = 0
        for (d in domainsFor(h)) {
            for (scheme in listOf("https", "http")) {
                val url = "$scheme://$d/"
                val names = cookieNames(cookies.getCookie(url))
                for (name in names) expiryLines(name, d).forEach { cookies.setCookie(url, it) }
                if (scheme == "https") expired += names.size
                storage.deleteOrigin("$scheme://$d")
            }
            storage.deleteOrigin("https://www.$d")
        }
        cookies.flush()
        registry?.remove(h)
        return expired
    }

    /**
     * Exit all and clear web page data: all cookies (including session cookies), all web page storage,
     * the cache of [views] (the cache is shared by the entire web page configuration, clear all by
     * giving one view), the HTTP authentication account remembered in [database], and the site
     * registration form is cleared. Still try your best.
     */
    fun logoutAll(views: List<WebView> = emptyList(), database: WebViewDatabase? = null) {
        cookies.removeSessionCookies(null)
        cookies.removeAllCookies(null)
        cookies.flush()
        storage.deleteAllData()
        views.forEach { runCatching { it.clearCache(true) } }
        database?.let { runCatching { it.clearHttpAuthUsernamePassword() } }
        registry?.clear()
    }

    companion object {
        /** The host name and its upper-level domains at all levels, up to the registrable domain name; when the registrable domain name cannot be recognized, only itself. */
        fun domainsFor(host: String): List<String> {
            val top = WebUrlPolicy.registrable(host) ?: return listOf(host)
            val out = mutableListOf<String>()
            var d = host
            while (true) {
                out += d
                if (d == top || !d.contains('.')) break
                d = d.substringAfter('.')
            }
            return out
        }

        /** The name in the cookie header is removed and the order is maintained. */
        fun cookieNames(header: String?): List<String> =
            header.orEmpty().split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }.distinct()

        /** There are two ways to expire a cookie: only belonging to the host, and belonging to the entire domain. */
        fun expiryLines(name: String, domain: String) = listOf(
            "$name=; Max-Age=0; Path=/",
            "$name=; Max-Age=0; Path=/; Domain=.$domain",
        )
    }
}
