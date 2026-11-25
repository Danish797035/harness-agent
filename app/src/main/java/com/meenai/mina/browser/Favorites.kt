package com.meenai.mina.browser

import com.meenai.mina.cap.web.SiteEntry
import com.meenai.mina.core.cap.web.WebUrlPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/** One box in "Frequently Used Websites": the host name when opening, and whether it is logged in in Mina (it does not matter if any host name of this website is logged in). */
data class FavoriteSite(val host: String, val loggedIn: Boolean)

/** Changes made by users to "frequently used websites": those added by themselves ([pinned], in order of addition) and those removed from it ([hidden]). */
data class FavoriteEdits(val pinned: List<String> = emptyList(), val hidden: Set<String> = emptySet())

/**
 * Browser page "frequently used websites": websites added by the user are first, followed by websites in
 * the site registration list (those visited by the Agent and the user; those logged in first). The same
 * website (the same registrable domain name) only occupies one space. When opening, go to the shortest
 * host name among them.
 *
 * Only the user's changes are recorded, and the site registration form is not moved: it is not displayed
 * when removed from the general menu, and the login status, cookie and agent registration are still the
 * same (log out in the "Website Login" of the settings page); it is displayed again when added back. It
 * is stored in a JSON file in the App's private directory; if the file is damaged, start over from the
 * empty table.
 */
class BrowserFavorites(dir: File) {
    private val file = File(dir, FILE)
    private val _edits = MutableStateFlow(load())

    val edits: StateFlow<FavoriteEdits> = _edits.asStateFlow()

    /** Add frequently used: Put it at the end of what you add; the website that has been removed before will be redisplayed. */
    @Synchronized
    fun add(host: String) {
        val h = norm(host) ?: return
        val site = siteKey(h)
        val e = _edits.value
        save(FavoriteEdits(e.pinned.filter { siteKey(it) != site } + h, e.hidden - site))
    }

    /** Remove from common use: remove the ones added by yourself, and those from the registration list will no longer be displayed. */
    @Synchronized
    fun remove(host: String) {
        val h = norm(host) ?: return
        val site = siteKey(h)
        val e = _edits.value
        save(FavoriteEdits(e.pinned.filter { siteKey(it) != site }, e.hidden + site))
    }

    private fun load(): FavoriteEdits {
        if (!file.isFile) return FavoriteEdits()
        return try {
            val o = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return FavoriteEdits()
            fun list(k: String) = (o[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.let(::norm) }
            FavoriteEdits(list("pinned").distinct(), list("hidden").toSet())
        } catch (e: SerializationException) {
            FavoriteEdits()
        }
    }

    private fun save(e: FavoriteEdits) {
        _edits.value = e
        file.parentFile?.mkdirs()
        val json = buildJsonObject {
            put("pinned", JsonArray(e.pinned.map(::JsonPrimitive)))
            put("hidden", JsonArray(e.hidden.sorted().map(::JsonPrimitive)))
        }.toString()
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
        /** The grid can have at most this many cells (plus one "+" cell, so four in a row are exactly three rows). */
        const val LIMIT = 11
        private const val FILE = "browser_favorites.json"

        private fun norm(host: String): String? {
            val s = host.trim()
            if (s.isEmpty()) return null
            return WebUrlPolicy.host(if ("://" in s) s else "https://$s")
        }

        /** The mark of the same website: the registrable domain name, sometimes the host name itself. */
        fun siteKey(host: String): String = WebUrlPolicy.registrable(host) ?: host

        /** Synthetic grid: Add the ones you add first (in the order of adding), then follow the order of the registration form; there is one grid for the same website, and the removed grids will not be displayed, up to [limit] grids. */
        fun combine(edits: FavoriteEdits, registry: List<SiteEntry>, limit: Int = LIMIT): List<FavoriteSite> {
            val bySite = registry.groupBy { siteKey(it.host) }
            fun loggedIn(site: String) = bySite[site].orEmpty().any { it.loggedInByUser }
            val out = LinkedHashMap<String, FavoriteSite>()
            for (h in edits.pinned) {
                val site = siteKey(h)
                if (site !in out) out[site] = FavoriteSite(h, loggedIn(site))
            }
            for (e in registry) {
                val site = siteKey(e.host)
                if (site in out || site in edits.hidden) continue
                val host = bySite[site].orEmpty().minBy { it.host.length }.host
                out[site] = FavoriteSite(host, loggedIn(site))
            }
            return out.values.take(limit)
        }

        /** Is the website where [host] is logged in in Mina (any host name of this website in the registration table is logged in). */
        fun loggedIn(registry: List<SiteEntry>, host: String): Boolean {
            val site = siteKey(host)
            return registry.any { it.loggedInByUser && siteKey(it.host) == site }
        }

        /** Is the website where [host] is located in [sites]? */
        fun contains(sites: List<FavoriteSite>, host: String): Boolean {
            val site = siteKey(host)
            return sites.any { siteKey(it.host) == site }
        }
    }
}
