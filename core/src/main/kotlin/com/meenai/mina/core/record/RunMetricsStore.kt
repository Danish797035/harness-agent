package com.meenai.mina.core.record

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Each time a summary ([RunMetrics]) is run, one copy per line is stored in [file] (JSON Lines), and
 * only the most recent [keep] copies are kept; only the last 30 times are kept in the running record,
 * the summary is not cleared, and statistics can be seen for more times. Make a note at the end of the
 * run, hand over the answers and then add the ending after judging the outcome ([update]). Lines that
 * cannot be read are skipped, and if the entire file is damaged, it will be counted as empty; writing is
 * to write the entire file to the temporary file and then replace it, and if it cannot be written, it
 * will only be lost this time. There may be several instances of the same file (one for each page and
 * each run), and the same lock in the process is used for reading and writing.
 */
class RunMetricsStore(private val file: File, private val keep: Int = DEFAULT_KEEP) {
    init { require(keep > 0) { "keep must be positive" } }

    /** Old first. */
    fun all(): List<RunMetrics> = synchronized(LOCK) { read() }

    /** The most recent [n] copies (oldest first); all if [n] is null. */
    fun recent(n: Int?): List<RunMetrics> = all().let { if (n == null) it else it.takeLast(n) }

    /** Make a note of it; replace the existing one in the same run. */
    fun append(m: RunMetrics) = synchronized(LOCK) {
        write(read().filter { m.runId.isEmpty() || it.runId != m.runId } + m)
    }

    /** Change the one that runs once; if not, it won't move. */
    fun update(runId: String, change: (RunMetrics) -> RunMetrics) = synchronized(LOCK) {
        val list = read()
        if (list.none { it.runId == runId }) return@synchronized
        write(list.map { if (it.runId == runId) change(it) else it })
    }

    private fun read(): List<RunMetrics> {
        if (!file.isFile) return emptyList()
        val lines = runCatching { file.readLines() }.getOrDefault(emptyList())
        return lines.filter { it.isNotBlank() }.mapNotNull { runCatching { JSON.decodeFromString(RunMetrics.serializer(), it) }.getOrNull() }
    }

    private fun write(list: List<RunMetrics>) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(list.takeLast(keep).joinToString("") { JSON.encodeToString(RunMetrics.serializer(), it) + "\n" })
            if (!tmp.renameTo(file)) error("rename failed")
        }
    }

    companion object {
        const val DEFAULT_KEEP = 500
        const val FILE_NAME = "run-metrics.jsonl"
        private val LOCK = Any()
        val JSON = Json { ignoreUnknownKeys = true }

        /** Exported for human analysis: a JSON array, oldest first. */
        fun export(list: List<RunMetrics>): String = EXPORT.encodeToString(ListSerializer(RunMetrics.serializer()), list)

        private val EXPORT = Json { encodeDefaults = true }
    }
}
