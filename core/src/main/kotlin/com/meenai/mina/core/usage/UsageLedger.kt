package com.meenai.mina.core.usage

import com.meenai.mina.core.model.Usage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** The ownership of a grid: which service provider, which model, and which purpose. */
data class UsageKey(val providerId: String, val model: String, val purpose: UsagePurpose)

/** The accumulated numbers in one grid: input (including cache hits), output, cache hit tokens, number of calls, of which the usage is not reported. */
data class UsageCounts(
    val input: Long = 0,
    val output: Long = 0,
    val cached: Long = 0,
    val calls: Long = 0,
    val callsWithoutUsage: Long = 0,
) {
    operator fun plus(o: UsageCounts) =
        UsageCounts(input + o.input, output + o.output, cached + o.cached, calls + o.calls, callsWithoutUsage + o.callsWithoutUsage)

    companion object {
        fun of(usage: Usage?): UsageCounts =
            if (usage == null) UsageCounts(calls = 1, callsWithoutUsage = 1)
            else UsageCounts(usage.inputTokens.toLong(), usage.outputTokens.toLong(), usage.cachedInputTokens.toLong(), calls = 1)
    }
}

/** [hour] is the UTC hourly bucket: the hour since 1970-01-01T00:00Z. */
data class UsageRow(val hour: Long, val key: UsageKey, val counts: UsageCounts)

/** The entire content of the ledger at this moment; [providerNames] is the name of each service provider at the time of its latest accounting (the service provider will retain it even if it is deleted). */
data class UsageSnapshot(val rows: List<UsageRow>, val providerNames: Map<String, String>) {
    companion object {
        val EMPTY = UsageSnapshot(emptyList(), emptyMap())
    }
}

/**
 * The local token usage ledger: accumulated according to UTC hour × service provider × model × purpose,
 * only exists on this mobile phone ([file], JSON).
 *
 * Accounting ([record]) only adds one entry to the memory, and it is safe for multiple threads to record
 * at the same time; after the change, [flushDelayMs] will be written out in full by a background thread
 * (the temporary file will be written first and then renamed, and the original file will not be damaged
 * if the power is turned off in the middle). When the process is killed, at most the last few seconds
 * will be lost. Only the most recent [keepDays] days are kept, older ones are discarded when reading in
 * and writing out. When the file cannot be read (is damaged), the ledger will be restarted as an empty
 * account.
 */
class UsageLedger(
    private val file: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val flushDelayMs: Long = 2_000,
    private val keepDays: Int = KEEP_DAYS,
) : UsageSink {
    private data class Cell(val hour: Long, val key: UsageKey)

    private val lock = Any()
    private val writeLock = Any()
    private val cells = HashMap<Cell, UsageCounts>()
    private val names = HashMap<String, String>()
    private var dirty = false
    private var flushQueued = false

    private val writer: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "usage-ledger").apply { isDaemon = true } }
    }

    private val _version = MutableStateFlow(0L)
    /** Each entry is incremented by one; the interface will re-calculate according to it. */
    val version: StateFlow<Long> = _version.asStateFlow()

    init {
        load()
    }

    override fun record(source: UsageSource, usage: Usage?) {
        val cell = Cell(Math.floorDiv(now(), HOUR_MS), UsageKey(source.providerId, source.model, source.purpose))
        val queue = synchronized(lock) {
            cells[cell] = (cells[cell] ?: UsageCounts()) + UsageCounts.of(usage)
            if (source.providerName.isNotEmpty()) names[source.providerId] = source.providerName
            dirty = true
            !flushQueued.also { flushQueued = true }
        }
        _version.update { it + 1 }
        if (queue) writer.schedule({ runCatching { flush() } }, flushDelayMs, TimeUnit.MILLISECONDS)
    }

    fun snapshot(): UsageSnapshot = synchronized(lock) {
        UsageSnapshot(cells.map { (c, n) -> UsageRow(c.hour, c.key, n) }, HashMap(names))
    }

    /** If there are any unwritten changes, write them out on the spot (the background thread will adjust them regularly; you can also adjust them directly before testing and exiting). When writing fails, keep the changes and write again next time. */
    fun flush() {
        synchronized(writeLock) {
            val text = synchronized(lock) {
                flushQueued = false
                if (!dirty) return
                dirty = false
                prune()
                serialize()
            }
            try {
                writeAtomically(text)
            } catch (e: Exception) {
                synchronized(lock) { dirty = true }
                throw e
            }
        }
    }

    private fun cutoffHour(): Long = Math.floorDiv(now(), HOUR_MS) - keepDays * 24L

    private fun prune() {
        val cutoff = cutoffHour()
        cells.keys.removeAll { it.hour < cutoff }
    }

    private fun serialize(): String {
        val keys = cells.keys.map { it.key }.distinct()
        val index = keys.withIndex().associate { (i, k) -> k to i }
        return buildJsonObject {
            put("version", FORMAT)
            putJsonObject("names") { names.forEach { (id, n) -> put(id, n) } }
            putJsonArray("keys") {
                keys.forEach { k -> addJsonObject { put("provider", k.providerId); put("model", k.model); put("purpose", k.purpose.key) } }
            }
            putJsonArray("rows") {
                cells.entries.sortedBy { it.key.hour }.forEach { (c, n) ->
                    addJsonArray {
                        add(JsonPrimitive(c.hour)); add(JsonPrimitive(index.getValue(c.key)))
                        add(JsonPrimitive(n.input)); add(JsonPrimitive(n.output)); add(JsonPrimitive(n.cached))
                        add(JsonPrimitive(n.calls)); add(JsonPrimitive(n.callsWithoutUsage))
                    }
                }
            }
        }.toString()
    }

    private fun load() {
        if (!file.isFile) return
        runCatching {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            root["names"]?.jsonObject?.forEach { (id, n) -> names[id] = n.jsonPrimitive.content }
            val keys = root["keys"]?.jsonArray.orEmpty().map { e ->
                val o = e.jsonObject
                UsageKey(
                    o.getValue("provider").jsonPrimitive.content, o.getValue("model").jsonPrimitive.content,
                    UsagePurpose.of(o.getValue("purpose").jsonPrimitive.content),
                )
            }
            val cutoff = cutoffHour()
            root["rows"]?.jsonArray.orEmpty().forEach { e ->
                val r = (e as JsonArray).map { it.jsonPrimitive }
                val hour = r[0].long
                if (hour < cutoff) return@forEach
                val cell = Cell(hour, keys[r[1].int])
                val n = UsageCounts(r[2].long, r[3].long, r[4].long, r[5].long, r[6].long)
                cells[cell] = (cells[cell] ?: UsageCounts()) + n
            }
        }.onFailure {
            cells.clear()
            names.clear()
        }
    }

    private fun writeAtomically(text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "cannot write ${file.name}" }
        }
    }

    companion object {
        const val KEEP_DAYS = 400
        const val HOUR_MS = 3_600_000L
        private const val FORMAT = 1
    }
}
