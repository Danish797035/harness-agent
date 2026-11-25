package com.meenai.mina.core.memory

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One change; [undoToken] is handed over to [MemoryStore.undo] to restore to before the change. */
sealed interface Change {
    val undoToken: String

    data class Added(val item: MemoryItem, override val undoToken: String) : Change
    data class Updated(val item: MemoryItem, val before: MemoryItem, override val undoToken: String) : Change
    data class Forgot(val before: MemoryItem, override val undoToken: String) : Change
}

/** The entire copy is read and written; encrypted disk transfer is implemented by the app layer. */
interface MemoryPersistence {
    fun load(): List<MemoryItem>
    fun save(items: List<MemoryItem>)
}

/** [code]:`sensitive`|`too_long`|`memory_full`|`not_found`|`unavailable` (the memory file cannot be read temporarily). */
class MemoryRejected(val code: String) : Exception(code)

/**
 * The only writing port of memory. All write methods share the same lock: write serialization for
 * parallel tasks. [SensitiveFilter] has been written before; the entire disk is changed every time; the
 * revocation token only keeps the latest [UNDO_KEEP] in the memory.
 */
class MemoryStore(
    private val persistence: MemoryPersistence,
    val clock: () -> Long,
    private val ids: () -> String,
    val limit: Int = 500,
) {
    private val lock = Any()
    // When it cannot be read (the keystore is temporarily unavailable), it will be used as empty, but
    // writing is not allowed: if written, the real memory file will be overwritten with an empty table.
    @Volatile private var loaded = false
    private val _items = MutableStateFlow(tryLoad() ?: emptyList())
    val items: StateFlow<List<MemoryItem>> = _items.asStateFlow()

    /** Have the memory files been read this time? If false, writing will be rejected (`unavailable`), [reloadIfNeeded] and try again next time. */
    val available: Boolean get() = loaded

    /** If it has not been read before, read it again (called at the beginning of each task and before each write); if it is read, it returns true. */
    fun reloadIfNeeded(): Boolean = synchronized(lock) {
        if (loaded) return true
        val got = tryLoad() ?: return false
        _items.value = got
        true
    }

    private fun tryLoad(): List<MemoryItem>? = try {
        persistence.load().also { loaded = true }
    } catch (e: Exception) {
        null
    }

    // The insertion order is the order of old and new. If it exceeds the order, the oldest is discarded.
    private val undos = LinkedHashMap<String, Change>()

    fun add(kind: MemoryKind, content: String, keywords: List<String>, source: MemorySource): Result<Change.Added> = synchronized(lock) {
        if (!reloadIfNeeded()) return Result.failure(MemoryRejected("unavailable"))
        val text = clean(content)
        val kws = cleanKeywords(keywords)
        check(text, kws, source)?.let { return Result.failure(it) }
        if (_items.value.size >= limit) return Result.failure(MemoryRejected("memory_full"))
        val now = clock()
        val item = MemoryItem(
            id = ids(), kind = kind, content = text, keywords = kws, source = source,
            status = if (kind == MemoryKind.procedure) MemoryStatus.pending else MemoryStatus.active,
            createdAt = now, updatedAt = now,
        )
        commit(_items.value + item)
        Result.success(remember { Change.Added(item, it) })
    }

    /**
     * When [keywords]/[source] is null, the original value is used. Practices must be re-approved after
     * being modified by new non-handwritten sources (model extraction, memory_save); changes made by
     * users in settings (manual) or without sources remain in their original state.
     */
    fun update(id: String, content: String, keywords: List<String>?, source: MemorySource?): Result<Change.Updated> = synchronized(lock) {
        if (!reloadIfNeeded()) return Result.failure(MemoryRejected("unavailable"))
        val before = _items.value.find { it.id == id } ?: return Result.failure(MemoryRejected("not_found"))
        val text = clean(content)
        val kws = keywords?.let(::cleanKeywords) ?: before.keywords
        val src = source ?: before.source
        check(text, kws, src)?.let { return Result.failure(it) }
        val reapprove = before.kind == MemoryKind.procedure && source != null && source.type != "manual"
        val item = before.copy(
            content = text, keywords = kws, source = src, updatedAt = clock(),
            replaces = if (text != before.content) before.content else before.replaces,
            status = if (reapprove) MemoryStatus.pending else before.status,
        )
        commit(_items.value.map { if (it.id == id) item else it })
        Result.success(remember { Change.Updated(item, before, it) })
    }

    fun forget(id: String): Result<Change.Forgot> = synchronized(lock) {
        if (!reloadIfNeeded()) return Result.failure(MemoryRejected("unavailable"))
        val before = _items.value.find { it.id == id } ?: return Result.failure(MemoryRejected("not_found"))
        commit(_items.value.filter { it.id != id })
        Result.success(remember { Change.Forgot(before, it) })
    }

    /**
     * The token is unknown, expired, or used → false. The undo of the update is only done when the entry
     * is still as it was after this change (it was later changed/forgotten → false), so as not to erase
     * all subsequent changes. Thrown when the placement fails, the token is retained and can be retried.
     */
    fun undo(token: String): Boolean = synchronized(lock) {
        val change = undos[token] ?: return false
        val cur = _items.value
        val next = when (change) {
            is Change.Added -> cur.filter { it.id != change.item.id }
            is Change.Updated -> {
                // Only look at the fields that were changed this time; subsequent usage counts and pins
                // are not considered "changed again" and will be retained when restoring.
                val now = cur.find { it.id == change.item.id }
                if (now == null || now.content != change.item.content || now.keywords != change.item.keywords || now.source != change.item.source) return false
                val restored = change.before.copy(useCount = now.useCount, lastUsedAt = now.lastUsedAt, pinned = now.pinned)
                cur.map { if (it.id == restored.id) restored else it }
            }
            is Change.Forgot -> cur.filter { it.id != change.before.id } + change.before
        }
        commit(next)
        undos.remove(token)
        true
    }

    fun approve(id: String): Boolean = synchronized(lock) {
        if (!loaded) return false
        val cur = _items.value
        if (cur.none { it.id == id && it.status == MemoryStatus.pending }) return false
        commit(cur.map { if (it.id == id) it.copy(status = MemoryStatus.active) else it })
        true
    }

    fun setPinned(id: String, pinned: Boolean): Unit = synchronized(lock) {
        if (!loaded) return
        val cur = _items.value
        if (cur.none { it.id == id }) return
        commit(cur.map { if (it.id == id) it.copy(pinned = pinned) else it })
    }

    fun markUsed(ids: List<String>): Unit = synchronized(lock) {
        if (ids.isEmpty() || !loaded) return
        val set = ids.toSet()
        val now = clock()
        commit(_items.value.map { if (it.id in set) it.copy(useCount = it.useCount + 1, lastUsedAt = now) else it })
    }

    /** The user wants to clear all: the original files that cannot be read are also overwritten with empty tables, which is exactly what the user wants. */
    fun clearAll(): Unit = synchronized(lock) {
        undos.clear()
        loaded = true
        commit(emptyList())
    }

    private fun check(content: String, keywords: List<String>, source: MemorySource): MemoryRejected? {
        if (content.isEmpty() || content.length > MAX_CONTENT) return MemoryRejected("too_long")
        val texts = listOf(content) + keywords + listOfNotNull(source.quote)
        if (texts.any { SensitiveFilter.hit(it) != null }) return MemoryRejected("sensitive")
        return null
    }

    // Line breaks, continuous blanks are pressed into one space: memory blocks are numbered according to
    // lines, and there cannot be line breaks in the content.
    private fun clean(content: String) = WHITESPACE.replace(content.trim(), " ")

    private fun cleanKeywords(keywords: List<String>) =
        keywords.map(::clean).filter { it.isNotEmpty() }.distinct().take(MAX_KEYWORDS)

    private fun commit(next: List<MemoryItem>) {
        // Bottom line: No writing can occur without reading the original file.
        check(loaded) { "memory not loaded" }
        persistence.save(next)
        _items.value = next
    }

    private fun <C : Change> remember(make: (String) -> C): C {
        val change = make(ids())
        undos[change.undoToken] = change
        while (undos.size > UNDO_KEEP) undos.remove(undos.keys.first())
        return change
    }

    companion object {
        const val MAX_CONTENT = 200
        const val MAX_KEYWORDS = 8
        const val UNDO_KEEP = 50
        private val WHITESPACE = Regex("\\s+")
    }
}
