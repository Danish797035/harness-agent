package com.meenai.mina.core.record

/**
 * Read the saved running records (read-only): summary (run.json) and event (events.jsonl). The record
 * has been desensitized and truncated when placed on the disk, and is read as it is. When the record has
 * been cleared, the number is unknown or cannot be read, null/empty table is returned and no exception
 * is thrown. It is realized by pressing the record on the side of the app.
 */
interface RunRecords {
    suspend fun summary(runId: String): RunSummary?
    suspend fun events(runId: String): List<RunEvent>
}
