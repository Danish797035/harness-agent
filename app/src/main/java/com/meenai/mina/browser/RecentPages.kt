package com.meenai.mina.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/** Recently opened page: URL, title (blank if unreadable), opening time. */
data class RecentPage(val url: String, val title: String, val openedAtMs: Long)

/**
 * Browser page "recently opened": web pages that the user has opened in Mina's browser page, the most
 * recent first, up to [limit] pages. Only one URL of the same URL (excluding the part after #) is left.
 * When it is opened again, it will be moved to the front and the title will be replaced with a new one.
 * It is stored in a JSON file in the App's private directory; if the file is damaged, start over from
 * the empty table.
 */
class RecentPages(dir: File, private val limit: Int = LIMIT, private val clock: () -> Long = System::currentTimeMillis) {
    private val file = File(dir, FILE)
    private val _entries = MutableStateFlow(load())

    /** nearest first. */
    val entries: StateFlow<List<RecentPage>> = _entries.asStateFlow()

    @Synchronized
    fun add(url: String, title: String?) {
        val key = key(url)
        if (key.isEmpty()) return
        val page = RecentPage(url.trim(), title?.trim().orEmpty(), clock())
        val rest = _entries.value.filter { key(it.url) != key }
        save((listOf(page) + rest).take(limit))
    }

    /** Add the title after the current page when the time comes: only change the existing one, do not move the position. */
    @Synchronized
    fun retitle(url: String, title: String) {
        val key = key(url)
        val t = title.trim()
        if (t.isEmpty() || _entries.value.none { key(it.url) == key && it.title != t }) return
        save(_entries.value.map { if (key(it.url) == key) it.copy(title = t) else it })
    }

    @Synchronized
    fun clear() {
        save(emptyList())
    }

    private fun key(url: String) = url.trim().substringBefore('#')

    private fun load(): List<RecentPage> {
        if (!file.isFile) return emptyList()
        return try {
            (Json.parseToJsonElement(file.readText()) as? JsonArray)?.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val url = (o["url"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@mapNotNull null
                RecentPage(url, (o["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty(), (o["at"] as? JsonPrimitive)?.longOrNull ?: 0L)
            }.orEmpty().take(limit)
        } catch (e: SerializationException) {
            emptyList()
        }
    }

    private fun save(list: List<RecentPage>) {
        _entries.value = list
        file.parentFile?.mkdirs()
        val json = JsonArray(list.map { buildJsonObject { put("url", it.url); put("title", it.title); put("at", it.openedAtMs) } }).toString()
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
        const val LIMIT = 10
        private const val FILE = "browser_recent.json"
    }
}
