package com.meenai.mina.cap.web

import com.meenai.mina.core.cap.web.WebUrlPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * An entry in the site registry: host name, last visit time, and whether signed in within Mina
 * (marked after login verification or when the page shows a signed-in state, cleared when signed
 * out). [challengedAtMs] / [challenge]: time and kind (`captcha` / `access_blocked`) of the most
 * recent human verification or access block on this site, indicating the session may be flagged;
 * expires after [SiteRegistry.CHALLENGE_TTL_MS] or when clearing site data.
 */
data class SiteEntry(
    val host: String,
    val lastVisitMs: Long,
    val loggedInByUser: Boolean,
    val challengedAtMs: Long? = null,
    val challenge: String? = null,
) {
    /** At this moment ([now]), it can be considered as "encountered an interception". */
    fun challenged(now: Long): Boolean = challengedAtMs?.let { now - it < SiteRegistry.CHALLENGE_TTL_MS } == true
}

/**
 * Site registration form: Record the websites visited by the Agent and the user in the built-in browser.
 * The system interface cannot list all sites that store cookies, so it is the only source that can be
 * displayed on the settings page "Website Login". It is stored in a JSON file in the App's private
 * directory; if the file is damaged, move it aside (not overwrite it with new content) and start over
 * from an empty table.
 */
class SiteRegistry(private val dir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val file = File(dir, FILE)
    private val sites = LinkedHashMap<String, SiteEntry>()
    private var corrupt = false
    private val _entries = MutableStateFlow<List<SiteEntry>>(emptyList())

    /** The same content as [list], updated when it changes, for interface use. */
    val entries: StateFlow<List<SiteEntry>> = _entries.asStateFlow()

    init {
        load()
        publish()
    }

    @Synchronized
    fun visited(host: String) {
        val h = normalize(host) ?: return
        sites[h] = sites[h]?.copy(lastVisitMs = clock()) ?: SiteEntry(h, clock(), false)
        save()
    }

    /** The login status seen on the page: logged in is recorded, logged out is removed (the session has expired), and it cannot be seen that it is still. Returns whether the record has changed. */
    @Synchronized
    fun loginObserved(host: String, state: LoginState): Boolean {
        val h = normalize(host) ?: return false
        val want = when (state) { LoginState.SignedIn -> true; LoginState.SignedOut -> false; LoginState.Unknown -> return false }
        val cur = sites[h]
        if ((cur?.loggedInByUser ?: false) == want) return false
        sites[h] = (cur ?: SiteEntry(h, clock(), false)).copy(loggedInByUser = want)
        save()
        return true
    }

    /**
     * Human-computer authentication or access blocking blocked the task ([kind]: captcha /
     * access_blocked): Note the time and type. The same type will not be rewritten if it is recorded in
     * [CHALLENGE_REFRESH_MS].
     */
    @Synchronized
    fun challenged(host: String, kind: String) {
        val h = normalize(host) ?: return
        val cur = sites[h]
        if (cur?.challenge == kind && cur.challengedAtMs?.let { clock() - it < CHALLENGE_REFRESH_MS } == true) return
        sites[h] = (cur ?: SiteEntry(h, clock(), false)).copy(challengedAtMs = clock(), challenge = kind)
        save()
    }

    /** Now I have encountered blocked websites. */
    @Synchronized
    fun challengedSites(): Set<String> {
        val now = clock()
        return sites.values.filter { it.challenged(now) }.map { it.host }.toSet()
    }

    @Synchronized
    fun markLoggedIn(host: String) {
        val h = normalize(host) ?: return
        sites[h] = (sites[h] ?: SiteEntry(h, clock(), false)).copy(loggedInByUser = true)
        save()
    }

    /** Those who have logged in first, then click Recent Visits. */
    @Synchronized
    fun list(): List<SiteEntry> = sorted()

    @Synchronized
    fun remove(host: String) {
        val h = normalize(host) ?: return
        if (sites.remove(h) != null) save()
    }

    @Synchronized
    fun clear() {
        sites.clear()
        save()
    }

    private fun sorted() = sites.values.sortedWith(compareByDescending<SiteEntry> { it.loggedInByUser }.thenByDescending { it.lastVisitMs })

    private fun publish() {
        _entries.value = sorted()
    }

    private fun load() {
        if (!file.isFile) return
        val parsed = try {
            (Json.parseToJsonElement(file.readText()) as? JsonArray)?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val host = (o["host"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(::normalize) ?: return@mapNotNull null
                SiteEntry(
                    host, (o["lastVisitMs"] as? JsonPrimitive)?.longOrNull ?: 0L, (o["loggedIn"] as? JsonPrimitive)?.booleanOrNull == true,
                    (o["challengedAt"] as? JsonPrimitive)?.longOrNull, (o["challenge"] as? JsonPrimitive)?.takeIf { it.isString }?.content,
                )
            }
        } catch (e: SerializationException) {
            null
        }
        if (parsed == null) {
            corrupt = true
            return
        }
        parsed.forEach { sites[it.host] = it }
    }

    private fun save() {
        dir.mkdirs()
        if (corrupt) {
            // Move the damaged files aside and do not overwrite them with new content.
            file.renameTo(File(dir, "$FILE.corrupt-${clock()}"))
            corrupt = false
        }
        val json = JsonArray(sites.values.map {
            buildJsonObject {
                put("host", it.host); put("lastVisitMs", it.lastVisitMs); put("loggedIn", it.loggedInByUser)
                it.challengedAtMs?.let { t -> put("challengedAt", t) }
                it.challenge?.let { k -> put("challenge", k) }
            }
        }).toString()
        // Write the temporary file first and then rename it. If it is killed in the middle of writing,
        // half of the file will not be left.
        val tmp = File(dir, "$FILE.tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(file)) {
            file.writeText(json)
            tmp.delete()
        }
        publish()
    }

    companion object {
        /** Marks that are blocked are retained for this long (after that the website will probably no longer be looking at the session). */
        const val CHALLENGE_TTL_MS = 7L * 24 * 60 * 60 * 1000
        /** If the same type of interception is encountered again within such a long time, the record will not be rewritten. */
        const val CHALLENGE_REFRESH_MS = 10L * 60 * 1000
        /** Count as the reason for taking over "the website is targeting this session". */
        val CHALLENGE_KINDS = setOf("captcha", "access_blocked")
        private const val FILE = "sites.json"

        /** Unify the host name into lower case and remove www.; the URL passed in will also be recognized. Unlike the host name which is not registered. */
        private fun normalize(raw: String): String? {
            val s = raw.trim()
            if (s.isEmpty()) return null
            return WebUrlPolicy.host(if (s.contains("://")) s else "https://$s")?.takeIf { h -> h.none { it.isWhitespace() } }
        }
    }
}
