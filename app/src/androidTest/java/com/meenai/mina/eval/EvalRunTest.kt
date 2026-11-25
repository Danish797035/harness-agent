package com.meenai.mina.eval

import com.meenai.mina.DeviceTaskState
import android.content.Context
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.meenai.mina.cap.phone.a11y.DeviceSupport
import com.meenai.mina.confirm.ConfirmationCopy
import com.meenai.mina.core.risk.IrrevAction
import com.meenai.mina.core.agent.SpeedFlags
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.core.eval.AppCapabilities
import com.meenai.mina.core.eval.Check
import com.meenai.mina.core.eval.EvalCategory
import com.meenai.mina.core.eval.EvalPairing
import com.meenai.mina.core.eval.Checks
import com.meenai.mina.core.eval.EvalReport
import com.meenai.mina.core.record.RunMetrics
import com.meenai.mina.core.record.RunMetricsStore
import com.meenai.mina.core.eval.EvalTask
import com.meenai.mina.core.eval.EvalTasks
import com.meenai.mina.core.eval.Observed
import com.meenai.mina.core.eval.Prices
import com.meenai.mina.core.eval.RunFacts
import com.meenai.mina.core.eval.Scoring
import com.meenai.mina.core.eval.TaskScore
import com.meenai.mina.core.model.ModelRole
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.memory.MemoryRuntime
import com.meenai.mina.record.RunStore
import com.meenai.mina.settings.FeatureFlags
import com.meenai.mina.settings.ProviderStore
import com.meenai.mina.task.TaskRun
import com.meenai.mina.task.TaskService
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import com.meenai.mina.core.assembly.recordedTools
import com.meenai.mina.task.testTasks
import com.meenai.mina.core.cap.phone.OperatingPace

/**
 * Real machine evaluation: Run the task set one by one through the real path of [TaskService], score and
 * write a summary. Only runs when parameters are passed explicitly.
 *
 * `-e evalRun <name> [-e evalSet <assets directory or id prefix>] [-e evalPriceIn <unit price per million input tokens> -e evalPriceOut
 * <output unit price> [-e evalPriceCached <cached unit price>]] [-e evalOnTakeover cancel|wait] [-e evalAllowIrreversible true]
 * [-e evalFlags none|<key=on,...>] [-e evalChannel web|app] [-e evalSplit tune|holdout]`
 *
 * The questions come from the category directory of the test package assets (warehouse
 * eval/tasks/<category>/), the web page question directory web and the smoking question directory eval-smoke;
 * if evalSet is not passed, all except the smoke questions will be run. evalSplit gives you the option
 * to run only this pool of questions (according to the partition of the question; do not pass and run
 * all of them). The results of the holdout pool are only used for release decisions and are not used to
 * adjust prompt words and knowledge. If evalChannel is given, the channel of each question will be
 * forced to be a web page or an app (add "use web page"/"use app" before the target), and compare the
 * two routes. The main model is configured in the settings (use LiveRunTest#configureProvider first).
 * The results are written to the App external file directory eval/<evalRun>/:summary.md, scores.jsonl,
 * <question id>/run/ (complete running record of this question).
 *
 * evalFlags (default none) takes effect via [FeatureFlags.setSpeed] and will be restored to its original
 * value after running; the switch combination is written into the summary. evalPace (fast / standard /
 * human, default fast) is the pace of operation in the App for this evaluation. Do not change the
 * settings; the evaluation will still run fast by default.
 *
 * `-e evalVariants on` (playback evaluation, serial): Only run questions with variations; run the
 * original question first for each question (note the route after completing it), and then run its
 * variants in sequence. The aggregated indicators only count variants, and the original question is
 * listed in a separate section; the running records of variants are recorded under <original title id>_v<n>/.
 *
 * `-e evalParallel <n>` (n >= 2, also enable `parallelTasks`): greedily groups tasks by disjoint
 * apps ([EvalPairing]) and runs tasks in the same group concurrently, adding "Total wall clock
 * time" to the summary. Tasks in the same group share an auto-reply set (matching each task's
 * `answers` by keyword) and step limit (max across the group). When a task fails because another
 * task in the same group holds an app, the failure reason is recorded as [Scoring.INTERFERENCE_PREFIX]
 * (listed as a separate category, not counted as an evaluator bug).
 *
 * Use a new dialogue for each question in the evaluation. Confirm that the level is valid in the
 * question mode (autonomous mode is turned off in the developer options). When you want to confirm,
 * [testTasks.evalHooks.autoDecideConfirmation] will automatically answer (if you are not connected, wait
 * until the timeout and press Disagree). Writing and irreversible questions will only be run when
 * automatically rejected and all requirements are met; irreversible questions must also be explicitly
 * passed `-e evalAllowIrreversible true`, and will not be run by default.
 */
@RunWith(AndroidJUnit4::class)
class EvalRunTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args: Bundle = InstrumentationRegistry.getArguments()

    private var savedFlags: SpeedFlags? = null

    @After
    fun tearDown() {
        testTasks.evalHooks.reset()
        savedFlags?.let { FeatureFlags.get(context).setSpeed(it) }
    }

    @Test
    fun runEval() {
        val run = args.getString("evalRun")
        assumeTrue(run != null)
        require(RUN_NAME.matches(run!!)) { "evalRun must match $RUN_NAME" }
        val onTakeover = args.getString("evalOnTakeover") ?: "cancel"
        require(onTakeover == "cancel" || onTakeover == "wait") { "evalOnTakeover must be cancel or wait" }
        testTasks.evalHooks.onTakeover = onTakeover
        // Reviews will never be sent, deleted, or placed: they will be rejected at all levels.
        testTasks.evalHooks.autoDecideConfirmation = { false }
        // The evaluation does not learn skills or automatically memorize by default: the scores obtained
        // are not affected by what was learned in the previous round; turn it on explicitly when taking
        // the test
        testTasks.evalHooks.learnSkills = args.getString("learnSkills") == "on"
        testTasks.evalHooks.rememberMemory = args.getString("memory") == "on"
        testTasks.evalHooks.autoApproveSkillDrafts = args.getString("autoApproveSkills") == "true"
        testTasks.evalHooks.operatingPace = OperatingPace.parse(args.getString("evalPace")) ?: OperatingPace.FAST
        val allowIrreversible = args.getString("evalAllowIrreversible") == "true"
        val prices = prices()
        val flags = SpeedFlags.parse(args.getString("evalFlags") ?: "none")
        val featureFlags = FeatureFlags.get(context)
        savedFlags = featureFlags.speedFlags()
        featureFlags.setSpeed(flags)

        // The status of learned skills and memory: cleared by default, passed in round 3 (running with
        // the skills learned in ②) and keptState=true
        if (args.getString("keepState") != "true") MemoryRuntime.of(context).wipeAll()
        val split = args.getString("split")
        require(split == null || split == "train" || split == "test") { "split must be train or test" }
        val evalSplit = args.getString("evalSplit")?.takeIf { it.isNotBlank() }
        require(evalSplit == null || evalSplit in EvalTasks.PARTITIONS) { "evalSplit must be tune or holdout" }
        // evalChannel web|app: The same batch of questions is forced to go to the web or App (comparison
        // of two routes)
        val channel = args.getString("evalChannel")?.takeIf { it.isNotBlank() }
        // Questions in the same session are run in the same conversation in order of id, so they are
        // arranged according to (session, id)
        val tasks = loadTasks(args.getString("evalSet")).filter { split == null || it.split == split }
            .filter { evalSplit == null || it.partition == evalSplit }
            .map { t -> if (channel != null) t.copy(channel = channel) else t }
            .sortedWith(compareBy({ it.session.orEmpty() }, { it.id }))
        check(tasks.isNotEmpty()) { "no eval tasks matched evalSet=${args.getString("evalSet")}" }
        val errors = EvalTasks.validateAll(tasks)
        check(errors.isEmpty()) { "invalid eval tasks:\n" + errors.joinToString("\n") }

        val ext = context.getExternalFilesDir(null) ?: error("no external files dir")
        val out = File(ext, "eval/$run").apply { deleteRecursively(); mkdirs() }
        DeviceSupport.shell()
        DeviceSupport.service()
        val model = ProviderStore.get(context).configFor(ModelRole.Main)?.model ?: error("no main model configured")
        val probes = EvalProbes(DeviceSupport::sh, File(ext, "eval/logged-in.txt"))
        val runs = RunStore(context)

        val parallel = args.getString("evalParallel")?.toInt() ?: 1
        require(parallel == 1 || flags.parallelTasks) { "evalParallel needs evalFlags parallelTasks=on" }
        val variants = args.getString("evalVariants") == "on"
        // For the variation, you need to use the route you just memorized in the original question, and
        // you can only go one question after another.
        require(!variants || parallel == 1) { "evalVariants runs serially; drop evalParallel" }
        val scores = mutableListOf<Pair<TaskScore, Observed>>()
        val skipped = mutableListOf<Pair<String, String>>()
        val scoresFile = File(out, "scores.jsonl")
        val runnable = mutableListOf<EvalTask>()
        for (t in tasks) {
            // Will change the equipment or encounter problems with irreversible operations: if the level
            // is not connected, it will be automatically rejected and will not run; irreversible types
            // must be explicitly allowed
            if (t.category in GATED && testTasks.evalHooks.autoDecideConfirmation == null) {
                skipped += t.id to "评测器不跑 ${t.category.wire()} 类题(确认关卡自动拒绝没有接上)"
                continue
            }
            if (t.category == EvalCategory.Irreversible && !allowIrreversible) {
                skipped += t.id to "不可逆类题默认不跑(要跑须传 evalAllowIrreversible=true)"
                continue
            }
            val unmet = t.requires.map(probes::requirementMet).filter { !it.first }.map { it.second }
            if (unmet.isNotEmpty()) {
                skipped += t.id to unmet.joinToString(";")
                continue
            }
            if (variants && t.variants.isEmpty()) {
                skipped += t.id to "回放评测只跑带变体的题"
                continue
            }
            runnable += t
        }
        if (variants) {
            val originals = mutableListOf<Pair<TaskScore, Observed>>()
            for (t in runnable) {
                val (task, observed) = runOne(t, run, probes, runs, File(out, t.id))
                originals += Scoring.score(task, observed) to observed
                scoresFile.appendText(scoreLine(originals.last().first, observed) + "\n")
                for (v in EvalTasks.variantsOf(t)) {
                    val (vt, vo) = runOne(v, run, probes, runs, File(out, v.id.replace('~', '_')))
                    val score = Scoring.score(vt, vo)
                    scores += score to vo
                    scoresFile.appendText(scoreLine(score, vo) + "\n")
                }
            }
            File(out, "summary.md").writeText(EvalReport.markdown(run, model, scores, skipped, prices, flags.describe(), originals = originals))
            File(out, "metrics.json").writeText(EvalReport.metricsJson(scores))
            return
        }
        val t0 = System.currentTimeMillis()
        for (group in EvalPairing.groups(runnable, parallel)) {
            val results = if (parallel == 1) listOf(runOne(group.single(), run, probes, runs, File(out, group.single().id), sessionConversation(group.single())))
                else runGroup(group, run, probes, runs, out)
            for ((task, observed) in results) {
                val score = Scoring.score(task, observed)
                scores += score to observed
                scoresFile.appendText(scoreLine(score, observed) + "\n")
            }
        }
        val total = if (parallel > 1) System.currentTimeMillis() - t0 else null
        File(out, "summary.md").writeText(EvalReport.markdown(run, model, scores, skipped, prices, flags.describe(), total, parallel))
        File(out, "metrics.json").writeText(EvalReport.metricsJson(scores))
        // How many times each learned skill has been used (for comparison report)
        val skillUse = scores.flatMap { it.second.skillsUsed }.groupingBy { it }.eachCount()
        File(out, "skill-use.json").writeText(JsonObject(skillUse.mapValues { JsonPrimitive(it.value) }).toString())
    }

    /** Run a set of questions at the same time (parallel evaluation); open a dialogue for each question, and follow the respective task objects until the end. */
    private fun runGroup(group: List<EvalTask>, run: String, probes: EvalProbes, runs: RunStore, out: File): List<Pair<EvalTask, Observed>> {
        DeviceSupport.sh("input keyevent KEYCODE_HOME")
        val prepared = group.map { t ->
            val before = mutableMapOf<String, String>()
            var bad: String? = null
            for ((name, probe) in t.probesBefore) {
                val v = runCatching { probes.run(probe) }.getOrNull()
                if (v.isNullOrBlank() || v == "null") { bad = "探针 $probe 读不到"; break }
                before[name] = v
            }
            if (bad != null) t to bad else EvalTasks.substitute(t, run, before) to null
        }
        val tasks = prepared.filter { it.second == null }.map { it.first }
        testTasks.evalHooks.autoAnswer = { prompt, _ -> tasks.firstNotNullOfOrNull { t -> t.answers.entries.firstOrNull { it.key in prompt }?.value } }
        testTasks.evalHooks.maxSteps = tasks.mapNotNull { it.maxSteps }.maxOrNull()
        if (!waitFor(BUSY_WAIT_MS) { !DeviceTaskState.busy.value }) return group.map { it to harness("上一组任务一直没有收尾") }

        class Live(val task: EvalTask, val conversation: String, var run: TaskRun? = null, var t0: Long = 0, var tookOver: Boolean = false,
                   var timedOut: Boolean = false, var end: TaskState? = null, var wallMs: Long = 0, var failed: String? = null)
        val live = tasks.map { Live(it, UUID.randomUUID().toString()) }
        for (l in live) {
            l.t0 = System.currentTimeMillis()
            TaskService.start(context, l.task.goal, l.conversation)
            if (!waitFor(START_WAIT_MS) { DeviceTaskState.run(l.conversation) != null }) l.failed = "任务没有开始" else l.run = DeviceTaskState.run(l.conversation)
        }
        while (live.any { it.run != null && it.end == null && it.failed == null }) {
            for (l in live) {
                val r = l.run ?: continue
                if (l.end != null || l.failed != null) continue
                val s = r.state.value
                if (s is TaskState.WaitingTakeover && !l.tookOver) {
                    l.tookOver = true
                    if (testTasks.evalHooks.onTakeover == "cancel") r.cancel()
                }
                val timeoutMs = (l.task.timeoutSec ?: DEFAULT_TIMEOUT_SEC) * 1000L
                val elapsed = System.currentTimeMillis() - l.t0
                if (!l.timedOut && elapsed > timeoutMs) { l.timedOut = true; r.cancel() }
                if (l.timedOut && elapsed > timeoutMs + BUSY_WAIT_MS) l.failed = "超时取消后任务一直没有收尾"
                // Only after the finishing touches are completed (the running record is closed) will it
                // be removed from the registration office.
                if (DeviceTaskState.run(l.conversation) == null) {
                    l.end = r.state.value
                    l.wallMs = System.currentTimeMillis() - l.t0
                }
            }
            Thread.sleep(POLL_MS)
        }
        val results = prepared.filter { it.second != null }.map { (t, why) -> t to harness(why!!) }.toMutableList()
        for (l in live) {
            if (l.failed != null) { results += l.task to harness(l.failed!!); continue }
            val record = l.run?.runId?.value?.let { id -> runs.list().firstOrNull { it.id == id } }
            val events = record?.let { runs.events(it.id) }.orEmpty()
            record?.let { runs.copyTo(it.id, File(File(out, l.task.id), "run")) }
            val task = resolveApps(l.task, events, probes)
            val observed = observe(task, l.end!!, events, record != null, l.timedOut, l.tookOver, l.wallMs, probes, record?.id.orEmpty())
            // Didn't complete it, and bumped into another question occupying the App on the way:
            // separate columns interfered with each other
            val interfered = observed.outcome != "succeeded" && events.any { it is RunEvent.ToolResult && "app_in_use_by_other_task" in it.resultJson }
            results += task to if (interfered) observed.copy(failReason = Scoring.INTERFERENCE_PREFIX + "同组另一题占着要用的 App") else observed
        }
        return results
    }

    /** Questions in the same session share a dialogue (memory questions: the second question is continued in the dialogue of the first question). */
    private val sessions = mutableMapOf<String, String>()
    private fun sessionConversation(t: EvalTask): String? = t.session?.let { sessions.getOrPut(it) { UUID.randomUUID().toString() } }

    /**
     * Comparison and threshold of the two rounds of evaluation: `-e evalCompareBase <baseline run name> -e
     * evalCompareTreated <skill run name>`, read scores.jsonl from two runs and skill-use.json from the time
     * with skills, write compare.md to the directory from the time with skills.
     */
    @Test
    fun compareRuns() {
        val base = args.getString("evalCompareBase")
        val treated = args.getString("evalCompareTreated")
        assumeTrue(base != null && treated != null)
        val ext = context.getExternalFilesDir(null) ?: error("no external files dir")
        fun read(run: String): List<Pair<TaskScore, Observed>> = File(ext, "eval/$run/scores.jsonl").readLines().filter { it.isNotBlank() }.map { line ->
            val o = Json.parseToJsonElement(line) as JsonObject
            fun b(k: String) = (o[k] as? JsonPrimitive)?.content == "true"
            fun l(k: String) = (o[k] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            TaskScore((o["id"] as JsonPrimitive).content, b("passed"), b("falseSuccess"), b("unverified"), emptyList(), null) to
                Observed((o["outcome"] as? JsonPrimitive)?.content.orEmpty(), "", emptyList(), null, emptyMap(), emptyMap(), l("steps").toInt(), l("wallMs"), 0, 0, 0, null)
        }
        val b = read(base!!)
        val t = read(treated!!)
        val use = runCatching { (Json.parseToJsonElement(File(ext, "eval/$treated/skill-use.json").readText()) as JsonObject).mapValues { (it.value as JsonPrimitive).content.toInt() } }
            .getOrDefault(emptyMap())
        File(ext, "eval/$treated/compare.md").writeText(
            "# 对比 $base → $treated\n\n" + EvalReport.compare(b.map { it.first }, t.map { it.first }, use, b.map { it.second }, t.map { it.second }),
        )
    }

    /** Run a question and return the question with the placeholders replaced and the observed facts. If [conversation] is not empty, continue running in this dialogue. */
    private fun runOne(t: EvalTask, run: String, probes: EvalProbes, runs: RunStore, dir: File, conversation: String? = null): Pair<EvalTask, Observed> {
        DeviceSupport.sh("input keyevent KEYCODE_HOME")
        val before = mutableMapOf<String, String>()
        for ((name, probe) in t.probesBefore) {
            val v = runCatching { probes.run(probe) }.getOrNull()
            if (v.isNullOrBlank() || v == "null") return t to harness("探针 $probe 读不到")
            before[name] = v
        }
        val task = EvalTasks.substitute(t, run, before)
        testTasks.evalHooks.autoAnswer = { prompt, _ -> task.answers.entries.firstOrNull { it.key in prompt }?.value }
        testTasks.evalHooks.maxSteps = task.maxSteps

        if (!waitFor(BUSY_WAIT_MS) { !DeviceTaskState.busy.value }) return task to harness("上一个任务一直没有收尾")
        val prevConversation = DeviceTaskState.conversationId.value
        val t0 = System.currentTimeMillis()
        TaskService.start(context, task.goal, conversation)
        // When a new conversation is opened, the number changes, indicating that the request has been
        // accepted; when the same conversation is run next, the service becomes busy.
        val started = if (conversation == null) waitFor(START_WAIT_MS) { DeviceTaskState.conversationId.value != prevConversation }
            else waitFor(START_WAIT_MS) { DeviceTaskState.busy.value && DeviceTaskState.conversationId.value == conversation }
        if (!started) return task to harness("任务没有开始")
        val timeoutMs = (task.timeoutSec ?: DEFAULT_TIMEOUT_SEC) * 1000L
        var tookOver = false
        var timedOut = false
        // busy only changes back to false after the status changes to the end and the running record is
        // turned off.
        while (DeviceTaskState.busy.value) {
            val s = DeviceTaskState.state.value
            if (s is TaskState.WaitingTakeover && !tookOver) {
                tookOver = true
                if (testTasks.evalHooks.onTakeover == "cancel") TaskService.cancel(context)
            }
            if (!timedOut && System.currentTimeMillis() - t0 > timeoutMs) {
                timedOut = true
                TaskService.cancel(context)
            }
            if (timedOut && System.currentTimeMillis() - t0 > timeoutMs + BUSY_WAIT_MS) {
                return task to harness("超时取消后任务一直没有收尾")
            }
            Thread.sleep(POLL_MS)
        }
        val wallMs = System.currentTimeMillis() - t0
        val end = DeviceTaskState.state.value

        // After handing in the answer, the outcome will be determined again (backend): wait until it is
        // recorded in the running record before taking it. If it fails, there will be an unknown; if you
        // cannot wait, it will not be judged.
        if (end is TaskState.Succeeded) waitFor(JUDGE_WAIT_MS) {
            runs.list().firstOrNull { it.startedAtMs >= t0 }?.judged != null
        }
        val record = runs.list().firstOrNull { it.startedAtMs >= t0 }
        val events = record?.let { runs.events(it.id) }.orEmpty()
        record?.let { runs.copyTo(it.id, File(dir, "run")) }
        val resolved = resolveApps(task, events, probes)
        return resolved to observe(resolved, end, events, record != null, timedOut, tookOver, wallMs, probes, record?.id.orEmpty())
    }

    /** After running, replace `{app:<capability>}` with the package name on this phone: candidates opened by Agent are given priority (see [AppCapabilities.choose]). */
    private fun resolveApps(task: EvalTask, events: List<RunEvent>, probes: EvalProbes): EvalTask {
        val launched = RunFacts.appsLaunched(events, recordedTools())
        val written = RunFacts.appsWrittenIn(events, recordedTools())
        val chosen = EvalTasks.appCapabilities(task).mapNotNull { cap ->
            AppCapabilities.choose(probes.appCandidates(cap), launched, context.packageName, written)?.let { cap to it }
        }.toMap()
        return EvalTasks.resolveApps(task, chosen)
    }

    /** After a question is finished: sort out the observed facts according to the ending, running records and probes after the end. */
    private fun observe(
        task: EvalTask, end: TaskState, events: List<RunEvent>, hasRecord: Boolean, timedOut: Boolean, tookOver: Boolean,
        wallMs: Long, probes: EvalProbes, runId: String,
    ): Observed {
        val confirmations = events.filterIsInstance<RunEvent.Confirmation>()
        // Stopped at the level: The last declaration was rejected (automatically in the review), and
        // then the Agent report ended
        val stoppedAtGate = RunFacts.stoppedAtGate(events)
        val outcome = when {
            timedOut -> "timeout"
            stoppedAtGate -> "confirmation"
            // The reply without a call is the answer: if it has not been verified, it will still be
            // counted as succeeded (see the answer and account book for verification items); if it has
            // been verified but cannot be verified, it will be counted as unverified
            end is TaskState.Succeeded -> if (end.doubtful) "unverified" else "succeeded"
            end is TaskState.Failed -> "failed"
            end == TaskState.Cancelled -> if (tookOver) "takeover" else "cancelled"
            else -> "timeout"
        }
        val screenHas = mutableMapOf<String, Boolean>()
        val webHas = mutableMapOf<String, Boolean>()
        val after = mutableMapOf<String, String>()
        for (c in task.checks) when (c) {
            is Check.ScreenHas -> screenHas[Checks.screenKey(c)] =
                runCatching { runBlocking { probes.screenHas(c.packageName, c.text, c.deepLink) } }.getOrDefault(false)
            is Check.WebHas -> webHas[Checks.webKey(c)] =
                runCatching { runBlocking { probes.webHas(c.url, c.text) } }.getOrDefault(false)
            is Check.ProbeEquals -> after[c.probe] = runCatching { probes.run(c.probe) }.getOrDefault("")
            else -> Unit
        }
        val answer = when (end) {
            is TaskState.Succeeded -> end.answer
            is TaskState.Failed -> end.reason
            else -> ""
        }
        val sources = RunFacts.evidence(events, recordedTools(), task.goal, answer)
        return Observed(
            outcome = outcome,
            answer = answer,
            // The same evidence ledger and the same challenge as the runtime verifier
            evidence = sources,
            today = java.time.LocalDate.now(),
            // What is being checked is the word displayed on the card: Action uses the Chinese
            // expression in the title of the card
            confirmation = confirmations.lastOrNull { it.decision != "mismatch" && it.decision != "required" }?.let { c ->
                val action = IrrevAction.entries.firstOrNull { it.name == c.action }?.let(ConfirmationCopy::actionLabel) ?: c.action
                listOfNotNull("action" to action, c.target?.let { "target" to it }, c.content?.let { "content" to it }, c.amount?.let { "amount" to it }).toMap()
            },
            screenHas = screenHas,
            webHas = webHas,
            probes = after,
            steps = events.count { it is RunEvent.ModelReply && it.acted },
            wallMs = wallMs,
            inputTokens = events.filterIsInstance<RunEvent.ModelReply>().sumOf { (it.inputTokens ?: 0).toLong() },
            outputTokens = events.filterIsInstance<RunEvent.ModelReply>().sumOf { (it.outputTokens ?: 0).toLong() },
            cachedTokens = events.filterIsInstance<RunEvent.ModelReply>().sumOf { (it.cachedTokens ?: 0).toLong() },
            // The failure reason is generated based on the failure source, without using the Agent's
            // original words.
            leftScope = if (task.apps.isEmpty()) emptyList() else RunFacts.appsSeen(events, recordedTools()) - task.apps.toSet(),
            goal = task.goal,
            verdict = events.filterIsInstance<RunEvent.Verifier>().lastOrNull()?.verdict,
            modelCalls = events.count { it is RunEvent.ModelReply },
            modelMs = events.filterIsInstance<RunEvent.ModelReply>().sumOf { it.latencyMs },
            toolMs = events.filterIsInstance<RunEvent.ToolResult>().sumOf { it.durationMs },
            waitMs = events.filterIsInstance<RunEvent.ToolResult>().sumOf { it.waitMs },
            // If the last step of a route matches, it will be counted as completed; if any step does not
            // match, it will be counted as returned.
            replayHits = events.filterIsInstance<RunEvent.Replay>().count { it.matched && it.step == it.total },
            replayHandBacks = events.filterIsInstance<RunEvent.Replay>().count { !it.matched },
            memoryUsed = events.filterIsInstance<RunEvent.MemoryUsed>().sumOf { it.ids.size },
            skillsUsed = events.filterIsInstance<RunEvent.SkillUsed>().map { it.name }.distinct(),
            askedUser = events.count { it is RunEvent.ModelReply && it.acted && it.tool == "ask_user" },
            takeoverBarrier = RunFacts.takeoverBarrier(events, recordedTools()),
            judged = events.filterIsInstance<RunEvent.OutcomeJudged>().lastOrNull()?.outcome,
            metrics = RunMetrics.of(events, runId),
            failReason = when {
                timedOut -> null
                end is TaskState.Failed -> Scoring.failReason(end)
                !hasRecord -> Scoring.HARNESS_PREFIX + "找不到这次运行的记录"
                else -> null
            },
        )
    }

    private fun harness(why: String) = Observed(
        "failed", "", emptyList(), null, emptyMap(), emptyMap(), 0, 0, 0, 0, 0, Scoring.HARNESS_PREFIX + why,
    )

    private fun prices(): Prices? {
        val inP = args.getString("evalPriceIn")?.toDouble() ?: return null
        val outP = args.getString("evalPriceOut")?.toDouble() ?: error("evalPriceOut missing")
        return Prices(inP, outP, args.getString("evalPriceCached")?.toDouble() ?: inP)
    }

    /** All.json questions under the test package assets; [set] is the directory (such as eval-smoke, read) or id prefix. */
    private fun loadTasks(set: String?): List<EvalTask> {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        fun walk(path: String): List<String> {
            val children = assets.list(path).orEmpty()
            if (children.isEmpty()) return if (path.endsWith(".json")) listOf(path) else emptyList()
            return children.sorted().flatMap { walk("$path/$it") }
        }
        // Questions are only placed in the category directory (eval/tasks/<category>/), special question
        // collection, web page question directory and smoking question directory; there are other json
        // brought in by dependent libraries under the assets root.
        val dirs = EvalCategory.entries.map { it.wire() } + SUITE_DIRS + WEB_DIR + SMOKE_DIR
        return dirs.flatMap(::walk)
            .filter { path -> set != null || (SUITE_DIRS + SMOKE_DIR).none { path.startsWith("$it/") } }
            .map { path ->
                val task = runCatching { EvalTasks.parse(assets.open(path).bufferedReader().use { it.readText() }) }
                    .getOrElse { throw IllegalStateException("cannot parse eval task $path", it) }
                path to task
            }
            .filter { (path, task) -> set == null || path.startsWith("$set/") || task.id.startsWith(set) }
            .map { it.second }
    }

    /** Category names used in task files and directories. */
    private fun EvalCategory.wire(): String = when (this) {
        EvalCategory.Read -> "read"
        EvalCategory.Write -> "write"
        EvalCategory.Irreversible -> "irreversible"
        EvalCategory.Impossible -> "impossible"
        EvalCategory.Barrier -> "barrier"
    }

    private fun scoreLine(s: TaskScore, o: Observed): String = Json.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("id", s.id)
        put("passed", s.passed)
        put("falseSuccess", s.falseSuccess)
        put("unverified", s.unverified)
        put("failedChecks", JsonArray(s.failedChecks.map(::JsonPrimitive)))
        put("failureClass", s.failureClass?.name)
        put("outcome", o.outcome)
        put("judged", o.judged)
        put("answer", o.answer.take(2000))
        put("failReason", o.failReason)
        put("verdict", o.verdict)
        put("leftScope", JsonArray(s.leftScope.map(::JsonPrimitive)))
        put("steps", o.steps)
        put("wallMs", o.wallMs)
        put("inputTokens", o.inputTokens)
        put("outputTokens", o.outputTokens)
        put("cachedTokens", o.cachedTokens)
        put("modelCalls", o.modelCalls)
        put("modelMs", o.modelMs)
        put("toolMs", o.toolMs)
        put("waitMs", o.waitMs)
        o.metrics?.let { put("metrics", RunMetricsStore.JSON.encodeToJsonElement(RunMetrics.serializer(), it)) }
    })

    private fun waitFor(timeoutMs: Long, test: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (!test()) {
            if (System.currentTimeMillis() > end) return false
            Thread.sleep(POLL_MS)
        }
        return true
    }

    companion object {
        private val RUN_NAME = Regex("^[A-Za-z0-9_-]+$")
        private const val SMOKE_DIR = "eval-smoke"
        /** Special question set: matching questions (three rounds of running), memory questions. If evalSet is not passed, it will not run. You must select it explicitly. */
        private val SUITE_DIRS = listOf("skills", "memory")
        /** Web page title directory (eval/tasks/web/), with different categories. */
        private const val WEB_DIR = "web"
        private const val DEFAULT_TIMEOUT_SEC = 600
        private const val POLL_MS = 250L
        private const val START_WAIT_MS = 15_000L
        private const val BUSY_WAIT_MS = 60_000L
        /** There is an upper limit of 30 seconds for the model that determines the outcome to call itself, once for each of the two candidate models. */
        private const val JUDGE_WAIT_MS = 65_000L
        private val GATED = setOf(EvalCategory.Write, EvalCategory.Irreversible)
    }
}
