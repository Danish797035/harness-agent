package com.meenai.mina.record

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.meenai.mina.core.record.Redactor
import com.meenai.mina.core.record.RunMetrics
import com.meenai.mina.core.record.RunMetricsStore
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.record.RunRecords
import com.meenai.mina.core.record.RunSink
import com.meenai.mina.core.record.RunSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

private const val TAG = "RunStore"
private const val MAX_WIDTH = 720
private const val JPEG_QUALITY = 70

/**
 * Run the record to disk: `files/runs/<yyyyMMdd-HHmmss>-<6 digits>/`, download run.json (summary),
 * events.jsonl (one event per line), screens/NNNN.jpg (screen reading screen). Only the most recent
 * [keep] times are retained. If a disk writing error occurs, only the log will be recorded and the task
 * will not be affected. The summary of each run ([RunMetrics]) is saved in `files/run-metrics.jsonl`
 * ([metrics]) and will not be cleared along with the record.
 */
class RunStore internal constructor(
    filesDir: File,
    private val externalDir: () -> File?,
    private val keep: Int,
    private val clock: () -> Long,
    private val encode: (ByteArray) -> ByteArray?,
) {
    constructor(context: Context, keep: Int = 30) : this(
        context.applicationContext.filesDir,
        { context.applicationContext.getExternalFilesDir(null) },
        keep,
        System::currentTimeMillis,
        ::toJpeg,
    )

    init { require(keep > 0) { "keep must be positive" } }

    private val root = File(filesDir, "runs")

    /** A summary of each run; a copy will be recorded by the writing thread when the run record is turned off (see [RunHandle]). */
    val metrics = RunMetricsStore(File(filesDir, RunMetricsStore.FILE_NAME))

    fun open(goal: String): RunHandle {
        val start = clock()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(start))
        val suffix = (1..6).map { ALPHABET[Random.nextInt(ALPHABET.length)] }.joinToString("")
        val id = "$stamp-$suffix"
        val dir = File(root, id)
        dir.mkdirs()
        val handle = RunHandle(id, dir, RunSummary(id = id, goal = Redactor.text(goal), startedAtMs = start), encode, metrics::append)
        prune()
        return handle
    }

    /** New first. */
    fun list(): List<RunSummary> = runDirs().reversed().mapNotNull { d ->
        runCatching { RunEvent.json.decodeFromString(RunSummary.serializer(), File(d, RUN_JSON).readText()) }.getOrNull()
    }

    /** Summary of a run; records have been cleared or cannot be read as null. */
    fun summary(runId: String): RunSummary? = dirOf(runId)?.let { d ->
        runCatching { RunEvent.json.decodeFromString(RunSummary.serializer(), File(d, RUN_JSON).readText()) }.getOrNull()
    }

    /** Read-only to the core (check the records of previous rounds of this conversation, see history_read); read on the IO thread. */
    fun reader(): RunRecords = object : RunRecords {
        override suspend fun summary(runId: String) = withContext(Dispatchers.IO) { this@RunStore.summary(runId) }
        override suspend fun events(runId: String) = withContext(Dispatchers.IO) { this@RunStore.events(runId) }
    }

    fun events(runId: String): List<RunEvent> {
        val f = File(dirOf(runId) ?: return emptyList(), EVENTS)
        if (!f.isFile) return emptyList()
        return f.readLines().filter { it.isNotBlank() }.mapNotNull { runCatching { RunEvent.decode(it) }.getOrNull() }
    }

    /** Get the usage fields from existing JSONL in original order. Null values for old records, multiple replies for the same step, and compression events without step numbers are left intact. */
    fun usageLog(runId: String): String {
        val dir = dirOf(runId) ?: throw FileNotFoundException("Run is unavailable")
        return File(dir, EVENTS).useLines { lines ->
            buildString {
                for ((index, line) in lines.withIndex()) {
                    if (line.isBlank()) continue
                    val event = try {
                        RunEvent.json.parseToJsonElement(line) as? JsonObject
                    } catch (_: SerializationException) { null }
                    val type = event?.get("type") as? JsonPrimitive
                    if (event == null || type == null) {
                        // A process interruption may leave an unfinished line of records. Mark the gaps
                        // and keep the remaining events.
                        appendLine("""{"type":"unreadable_event","line":${index + 1}}""")
                        continue
                    }
                    val fields = USAGE_FIELDS[type.content] ?: continue
                    val metadata = event.filter { (key, value) ->
                        value is JsonPrimitive && (key == "type" || key == "atMs" || key in fields)
                    }
                    appendLine(JsonObject(metadata).toString())
                }
            }.ifEmpty { throw IOException("No run usage events") }
        }
    }

    /**
     * After the running record is closed, an additional event will be recorded (the ending will be
     * judged after handing in the answer): it will be added to events.jsonl and included in the summary
     * of run.json. If the record has been cleared or disk writing fails, only the log will be recorded
     * and the task will not be affected.
     */
    @Synchronized
    fun append(runId: String, e: RunEvent) {
        val dir = dirOf(runId) ?: return
        runCatching {
            File(dir, EVENTS).appendText(RunEvent.encode(e) + "\n")
            val summaryFile = File(dir, RUN_JSON)
            val summary = RunEvent.json.decodeFromString(RunSummary.serializer(), summaryFile.readText()).with(e)
            val tmp = File(dir, "$RUN_JSON.tmp")
            tmp.writeText(RunEvent.json.encodeToString(RunSummary.serializer(), summary))
            if (!tmp.renameTo(summaryFile)) error("rename failed")
            if (e is RunEvent.OutcomeJudged) metrics.update(runId) { it.copy(judged = e.outcome) }
        }.onFailure { runCatching { Log.w(TAG, "could not append to run $runId", it) } }
    }

    fun screen(runId: String, id: Int): File? = dirOf(runId)?.let { File(it, screenPath(id)) }?.takeIf { it.isFile }

    /** Copy it to runs/ in the external file directory to facilitate pulling it to the development machine (for debugging and evaluation purposes); only the most recent [keep] times are retained there. */
    fun mirrorToExternal(runId: String) {
        val ext = File(externalDir() ?: return, "runs")
        if (copyTo(runId, File(ext, runId))) runCatching { dirsIn(ext).dropLast(keep).forEach { it.deleteRecursively() } }
    }

    /** Copy the entire directory of a run to [dest](evaluation puts the records of each question into its own results directory). */
    fun copyTo(runId: String, dest: File): Boolean {
        val src = dirOf(runId) ?: return false
        return runCatching { src.copyRecursively(dest, overwrite = true) }
            .onFailure { runCatching { Log.w(TAG, "could not copy run $runId", it) } }
            .getOrDefault(false)
    }

    /** Only accept real operations in this directory to prevent spelling out other paths. */
    private fun dirOf(runId: String): File? =
        File(root, runId).takeIf { ID_RE.matches(runId) && it.isDirectory }

    private fun runDirs(): List<File> = dirsIn(root)

    private fun dirsIn(parent: File): List<File> =
        parent.listFiles { f -> f.isDirectory && ID_RE.matches(f.name) }.orEmpty().sortedBy { it.name }

    private fun prune() {
        runDirs().dropLast(keep).forEach { it.deleteRecursively() }
    }

    companion object {
        internal const val RUN_JSON = "run.json"
        internal const val EVENTS = "events.jsonl"
        private val USAGE_FIELDS = mapOf(
            "task_started" to setOf("model"),
            "model_reply" to setOf("step", "model", "role", "tool", "inputTokens", "cachedTokens",
                "outputTokens", "repliedAtMs", "latencyMs", "retryWaitMs"),
            "compressed" to setOf("foldedSteps"),
            "model_calls" to setOf("calls", "unreported", "inputTokens", "cachedTokens", "outputTokens"),
            "task_ended" to setOf("outcome", "kind"),
        )
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
        private val ID_RE = Regex("""\d{8}-\d{6}-[a-z0-9]{6}""")

        internal fun screenPath(id: Int) = "screens/%04d.jpg".format(Locale.US, id)
    }
}

/**
 * Record of a run; [close] at the end of the task. Both disk writing and image compression are done on a
 * dedicated thread. [event]/[screen] only join the queue and never block the Agent loop; the summary
 * ([RunMetrics]) is also written and calculated on this thread. After writing the last event, it is
 * handed over to [finished] (only runs that have run up); when the picture accumulation exceeds
 * [MAX_PENDING_FRAMES], the oldest one is discarded. [close] will wait for the queue to finish writing
 * (up to [CLOSE_WAIT_MS]).
 */
class RunHandle internal constructor(
    val runId: String,
    private val dir: File,
    private var summary: RunSummary,
    private val encode: (ByteArray) -> ByteArray?,
    private val finished: (RunMetrics) -> Unit = {},
) : RunSink, Closeable {
    private val lock = Object()
    private val events = ArrayDeque<RunEvent>()
    private val frames = ArrayDeque<Pair<Int, ByteArray>>()
    private var closing = false
    private var sinceSummary = 0
    private val metrics = RunMetrics.Builder(runId)
    private val writer = Thread(::drain, "mina-run-$runId").apply { isDaemon = true }

    init {
        writeSummary()
        writer.start()
    }

    override fun event(e: RunEvent) {
        synchronized(lock) {
            if (closing) return
            events.addLast(e)
            lock.notifyAll()
        }
    }

    override fun media(mediaId: Int, png: ByteArray) {
        synchronized(lock) {
            if (closing) return
            if (frames.size >= MAX_PENDING_FRAMES) frames.removeFirst()
            frames.addLast(mediaId to png)
            lock.notifyAll()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closing) return
            closing = true
            lock.notifyAll()
        }
        writer.join(CLOSE_WAIT_MS)
    }

    private fun drain() {
        while (true) {
            var batch: List<RunEvent> = emptyList()
            var frame: Pair<Int, ByteArray>? = null
            var done = false
            synchronized(lock) {
                while (events.isEmpty() && frames.isEmpty() && !closing) lock.wait()
                if (events.isEmpty() && frames.isEmpty()) {
                    writeSummary()
                    done = true
                } else {
                    batch = events.toList().also { events.clear() }
                    // Events take priority; one picture is taken at a time, checking back in the middle
                    // to see if there are any new events.
                    frame = if (batch.isEmpty()) frames.removeFirstOrNull() else null
                }
            }
            if (done) {
                // Summary: writing outside the lock: writing to another file, not blocking other threads
                metrics.build()?.let { m -> safely("metrics") { finished(m) } }
                return
            }
            batch.forEach(::write)
            frame?.let { (id, png) -> writeFrame(id, png) }
        }
    }

    private fun write(e: RunEvent) {
        // It is included in the summary first and then added. Even if the addition fails, the ending
        // will be written into run.json
        summary = summary.with(e)
        metrics.add(e)
        safely("event") { File(dir, RunStore.EVENTS).appendText(RunEvent.encode(e) + "\n") }
        sinceSummary += 1
        // Regular refresh summary: When the process is killed, run.json also has the approximate number
        // of steps and usage.
        if (e is RunEvent.TaskEnded || sinceSummary >= SUMMARY_EVERY) writeSummary()
    }

    private fun writeFrame(id: Int, png: ByteArray) {
        val jpg = safely("screen") { encode(png) } ?: return
        safely("screen") {
            val f = File(dir, RunStore.screenPath(id))
            f.parentFile?.mkdirs()
            f.writeBytes(jpg)
        }
    }

    private fun writeSummary() {
        sinceSummary = 0
        safely("summary") {
            val tmp = File(dir, "${RunStore.RUN_JSON}.tmp")
            tmp.writeText(RunEvent.json.encodeToString(RunSummary.serializer(), summary))
            if (!tmp.renameTo(File(dir, RunStore.RUN_JSON))) error("rename failed")
        }
    }

    private inline fun <T> safely(what: String, block: () -> T): T? = try {
        block()
    } catch (e: Throwable) {
        runCatching { Log.w(TAG, "could not write run $what", e) }
        null
    }

    internal companion object {
        const val MAX_PENDING_FRAMES = 4
        const val SUMMARY_EVERY = 10
        const val CLOSE_WAIT_MS = 10_000L
    }
}

/** First read only the size, downsample and decode by the power of 2, and then shrink to width ≤ 720, JPEG quality 70. */
private fun toJpeg(png: ByteArray): ByteArray? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(png, 0, png.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= MAX_WIDTH) sample *= 2
    val src = BitmapFactory.decodeByteArray(png, 0, png.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
    val scaled = if (src.width <= MAX_WIDTH) src
    else Bitmap.createScaledBitmap(src, MAX_WIDTH, (src.height.toLong() * MAX_WIDTH / src.width).toInt().coerceAtLeast(1), true)
    return try {
        ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
    } finally {
        if (scaled !== src) scaled.recycle()
        src.recycle()
    }
}
