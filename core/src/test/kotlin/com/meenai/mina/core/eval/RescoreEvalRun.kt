package com.meenai.mina.core.eval

import com.meenai.mina.core.agent.RunOutcome
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.testing.RECORDED_TOOLS
import com.meenai.mina.core.verify.RecordedVerifications
import com.meenai.mina.core.verify.Verdict
import com.meenai.mina.core.verify.Grounding
import com.meenai.mina.core.verify.Verifier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/**
 * Re-score offline and pull back the evaluation run: use the current questions, verification rules and
 * verification to recalculate without adjusting the model or connecting to the mobile phone. Skip if no
 * directory is set.
 *
 * MINA_EVAL_RUN=/path/to/<runname>./gradlew:core:test --tests '*RescoreEvalRun*' --rerun
 *
 * There must be scores.jsonl and <question id>/run/events.jsonl in the directory (as written by the evaluator).
 * Each question:
 * - - Ending (main number, according to the current ending method, see [natural]): The first final
 * answer (reply without call, with words; in the old record is finish) will end the task; Check by risk:
 * if the value in the answer does not match the account book at that time, or if it has done external
 * things and the verification did not pass at that time, it will be returned like once running time, and
 * the next final answer will be taken; if it is not correct, it will be counted as unverified (marked as
 * unconfirmed and handed over), if it is correct, it will be counted as succeeded. The first one was
 * given up before the final answer, handed over to the user, and was stopped as usual. The reminders in
 * the old records (reminders for not adjusting the tool) and the verification failure of returning to
 * the end are now replaced by the above ending.
 * - - Comparison: The outcome of the old rules (only look at the last check, use the current
 * [Verifier.backstop] recheck) and whether it is passed or not.
 * - - Check items: answers, screen reading, obstacles, questioning, and memory are recalculated
 * according to the running records; the current results to be checked on the mobile phone (screen_has,
 * web_has, probes, confirmation cards) and the expected values come from the pre-run probe. The
 * evidence is reconstructed from the evidence ledger ([RecordedVerifications.ledgerSources], the same as
 * the runtime verifier), which is the main number reported; the answer_grounded check of the answer also
 * uses the same ledger ([RunFacts.evidence]). After handing over the answer, the outcome (outcome event,
 * see [com.meenai.mina.core.agent.OutcomeJudge]) is calculated according to the record
 * ([Scoring.recorded]): the answer truthfully saying that it was not completed or only partially
 * completed is considered to have been given up truthfully; the answer that is only recognized is the
 * same answer (if the answer taken according to the current ending method is changed, it will not be
 * used). The old record does not have this event. The relative date is converted according to the time
 * of that verification (the time zone is MINA_RESCORE_ZONE, not the local one). A single column handed
 * over to the user for takeover is not counted as passed. The result is written as <directory>/rescore.md.
 */
class RescoreEvalRun {
    private val tasksDir = listOf("eval/tasks", "../eval/tasks").map(::File).first { it.isDirectory }

    @Test fun rescore() {
        val dir = (System.getenv("MINA_EVAL_RUN") ?: System.getProperty("mina.eval.run"))?.let(::File)
        assumeTrue("MINA_EVAL_RUN not set", dir != null && File(dir, "scores.jsonl").isFile)
        val zone = System.getenv("MINA_RESCORE_ZONE")?.let(ZoneId::of) ?: ZoneId.systemDefault()
        val tasks = tasksDir.walkTopDown().filter { it.isFile && it.extension == "json" }.map { EvalTasks.parse(it.readText()) }.associateBy { it.id }
        val rows = mutableListOf("| id | 结局 当时→现在 | 判的结局 | 通过 当时→现在 | 交给用户 | 没过的核对项 | 未确认 | 旧规则(核查兜底)时 |", "|---|---|---|---|---|---|---|---|")
        var before = 0
        var live = 0
        var legacyPassed = 0
        var nudged = 0
        var total = 0
        var falseBefore = 0
        var falseNow = 0
        var unverifiedWrong = 0
        var judgedNotDone = 0
        val handed = mutableMapOf<String, Int>()
        File(dir, "scores.jsonl").readLines().filter { it.isNotBlank() }.forEach { line ->
            val old = Json.parseToJsonElement(line) as JsonObject
            val id = old.str("id") ?: return@forEach
            val task = tasks[id] ?: return@forEach
            val events = File(dir, "$id/run/events.jsonl").takeIf { it.isFile }?.readLines()
                ?.mapNotNull { runCatching { RunEvent.decode(it) }.getOrNull() }.orEmpty()
            val oldOutcome = old.str("outcome").orEmpty()
            val oldFailed = (old["failedChecks"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            fun score(outcome: String, answer: String? = null, upto: Int = events.size, judged: String? = null): TaskScore {
                val observed = observed(task, events.subList(0, upto), old, outcome, oldFailed, zone, answer).copy(judged = judged)
                val s = Scoring.score(task, observed)
                // The expected value comes from the check items of the pre-run probe ({probe:...}) which
                // cannot be calculated offline, and the results at that time will be used.
                val failed = s.failedChecks.filterNot { k ->
                    val i = k.substringBefore(':').toIntOrNull() ?: return@filterNot false
                    "{probe:" in task.checks[i].toString() && k !in oldFailed
                }
                return s.copy(passed = failed.isEmpty(), failedChecks = failed)
            }
            val n = natural(task, events, oldOutcome, zone)
            // The judgment is based on the answer handed in; if you change the answer according to the
            // current ending method, it will not be used.
            val delivered = events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()?.answerOrReason
            val judged = RunOutcome.recorded(events)?.wire?.takeIf { n == null || n.answer.trim() == delivered?.trim() }
            val score = n?.let { score(it.outcome, answer = it.answer, upto = it.upto, judged = judged) } ?: score(oldOutcome, judged = judged)
            val nowOutcome = n?.outcome ?: oldOutcome
            val oldRule = recheck(task, events, oldOutcome, zone)
            val legacy = score(oldRule)
            val wasPassed = old.str("passed") == "true"
            total++
            if (wasPassed) before++
            if (score.passed) live++
            if (legacy.passed) legacyPassed++
            if (n?.nudged == true) nudged++
            if (old.str("falseSuccess") == "true") falseBefore++
            if (score.falseSuccess) falseNow++
            if (score.failureClass == FailureClass.unverified_wrong) unverifiedWrong++
            val notDone = judged == RunOutcome.Partial.wire || judged == RunOutcome.NotPossible.wire
            if (notDone) judgedNotDone++
            score.handedOver?.let { handed[it] = (handed[it] ?: 0) + 1 }
            val mark = if (wasPassed != score.passed || oldOutcome != nowOutcome || notDone || score.handedOver != null) " **" else ""
            val legacyNote = if (legacy.passed != score.passed || oldRule != nowOutcome) "$oldRule,通过 ${yes(legacy.passed)}" else ""
            rows += "| $id$mark | $oldOutcome → $nowOutcome | ${judged.orEmpty()} | ${yes(wasPassed)} → ${yes(score.passed)} | ${score.handedOver.orEmpty()} | " +
                "${score.failedChecks.joinToString(", ")} | ${n?.unconfirmed.orEmpty().joinToString("、")} | $legacyNote |"
        }
        val report = "# 重新打分 ${dir!!.name}(自然结束:不带调用的回复就是回答)\n\n" +
            "通过:当时 $before/$total → 现在 $live/$total\n\n" +
            "对比:旧规则(只看最后一次核查,现在的兜底重核)$legacyPassed/$total\n\n" +
            "退回过一次的:$nudged\n\n" +
            "假成功:当时 $falseBefore → 现在 $falseNow(判下来没办成、按如实放弃算的 $judgedNotDone);未核实且没过:$unverifiedWrong\n\n" +
            "交给用户(不算通过):" + (handed.entries.joinToString(",") { "${it.key} ${it.value}" }.ifEmpty { "无" }) + "\n\n" +
            rows.joinToString("\n") + "\n"
        File(dir, "rescore.md").writeText(report)
        println(report)
    }

    private fun yes(b: Boolean) = if (b) "是" else "否"

    /** The ending recalculated according to the current ending method: [answer] is the answer that was handed over, [upto] is the subscript of the event after it (the subsequent ones will not happen), [unconfirmed] is the several places marked as unconfirmed. */
    private class Natural(val outcome: String, val answer: String, val upto: Int, val unconfirmed: List<String>, val nudged: Boolean)

    /**
     * The first final answer ends the task (see class description). If it has ended in other ways before
     * (giving up, taking over, being stopped, timeout), or there is no final answer at all, null will be
     * returned and the ending will be the same. Each answer should be verified literally according to
     * the account book up to that time ([Grounding.check]); when doing external affairs
     * ([RunFacts.actedOutward]), the verification conclusion recorded in the same step should be
     * reviewed.
     */
    private fun natural(task: EvalTask, events: List<RunEvent>, oldOutcome: String, zone: ZoneId): Natural? {
        if (oldOutcome == "timeout" || oldOutcome == "cancelled" || oldOutcome == "skipped") return null
        val finals = events.indices.filter { RunFacts.isFinalAnswer(events[it]) }
        if (finals.isEmpty()) return null
        // The mission ended in another way before the first answer (taking over and confirming that the
        // level was stopped still counts as a later matter): As usual
        val goal = events.filterIsInstance<RunEvent.TaskStarted>().firstOrNull()?.goal ?: task.goal
        val pairs = RecordedVerifications.pairs(events).associate { (v, f) -> f to (events[v] as RunEvent.Verifier) }
        fun check(f: Int): List<String> {
            val r = events[f] as RunEvent.ModelReply
            val answer = RecordedVerifications.answer(events, f) ?: return emptyList()
            val seen = events.subList(0, f)
            val ledger = RunFacts.ledger(seen, RECORDED_TOOLS)
            val today = java.time.Instant.ofEpochMilli(r.atMs).atZone(zone).toLocalDate()
            val gaps = Grounding.check(goal, answer, ledger.forAnswer(goal, answer).map { it.text }, today).unmatched.map { it.text }
            val outward = RunFacts.actedOutward(seen)
            val verdict = pairs[f]?.verdict
            return when {
                gaps.isNotEmpty() -> gaps.distinct()
                outward && verdict != null && verdict != Verdict.Pass.wire -> listOf(RecordedVerifications.arg(pairs[f]!!.detailJson, "reason").orEmpty().ifBlank { verdict })
                else -> emptyList()
            }
        }
        val first = finals.first()
        val missing = check(first)
        if (missing.isEmpty()) return Natural("succeeded", RecordedVerifications.answer(events, first).orEmpty(), first + 1, emptyList(), nudged = false)
        // Return once: take down the final answer; if there is no more, mark the first one as
        // unconfirmed and hand it over.
        val second = finals.getOrNull(1) ?: return Natural("unverified", RecordedVerifications.answer(events, first).orEmpty(), first + 1, missing, nudged = false)
        val again = check(second)
        return Natural(if (again.isEmpty()) "succeeded" else "unverified", RecordedVerifications.answer(events, second).orEmpty(), second + 1, again, nudged = true)
    }

    /** Old rules (retained only for comparison): The last check is the final answer. Those who were revised (or let go) will be re-checked according to the current review. */
    private fun recheck(task: EvalTask, events: List<RunEvent>, oldOutcome: String, zone: ZoneId): String {
        if (oldOutcome != "succeeded" && oldOutcome != "unverified") return oldOutcome
        val (v, f) = RecordedVerifications.pairs(events).lastOrNull() ?: return oldOutcome
        val e = events[v] as RunEvent.Verifier
        val detail = runCatching { Json.parseToJsonElement(e.detailJson) as JsonObject }.getOrNull() ?: return oldOutcome
        val reason = detail.str("reason").orEmpty()
        // Only those that are passed by the verifier will be ignored: it was pass at the time, or it was
        // changed to cannot_verify (including the removed "not the same place" rule)
        val modelPassed = e.verdict == Verdict.Pass.wire || reason == Verifier.NOT_IN_READS || reason == OLD_NOT_ON_SCREEN || reason == Verifier.TRUNCATED || reason == OLD_NOT_ONE_SOURCE
        if (!modelPassed) return oldOutcome
        val asPass = Verifier.parse(JsonObject(detail + ("verdict" to JsonPrimitive("pass"))).toString()) ?: return oldOutcome
        if (asPass.verdict != Verdict.Pass) return oldOutcome
        val answer = RecordedVerifications.answer(events, f) ?: return oldOutcome
        val goal = events.filterIsInstance<RunEvent.TaskStarted>().firstOrNull()?.goal ?: task.goal
        val sources = RecordedVerifications.ledgerSources(events, v, f, goal, answer)
        val today = java.time.Instant.ofEpochMilli(e.atMs).atZone(zone).toLocalDate()
        val now = Verifier.backstop(asPass, goal, answer, sources, imageSent = false, today)
        return if (now.verdict == Verdict.Pass) "succeeded" else "unverified"
    }

    private fun observed(
        task: EvalTask, events: List<RunEvent>, old: JsonObject, outcome: String, oldFailed: List<String>, zone: ZoneId,
        given: String? = null,
    ): Observed {
        fun passedThen(i: Int, type: String) = "$i:$type" !in oldFailed
        val screenHas = mutableMapOf<String, Boolean>()
        val webHas = mutableMapOf<String, Boolean>()
        val probes = mutableMapOf<String, String>()
        task.checks.forEachIndexed { i, c ->
            when (c) {
                is Check.ScreenHas -> screenHas[Checks.screenKey(c)] = passedThen(i, "screen_has")
                is Check.WebHas -> webHas[Checks.webKey(c)] = passedThen(i, "web_has")
                is Check.ProbeEquals -> probes[c.probe] = if (passedThen(i, "probe_equals")) c.expected else ""
                else -> Unit
            }
        }
        val card = task.checks.filterIsInstance<Check.ConfirmationShows>().firstOrNull()
            ?.takeIf { c -> passedThen(task.checks.indexOf(c), "confirmation_shows") }
            ?.let { c -> listOfNotNull(c.action?.let { "action" to it }, c.target?.let { "target" to it }, c.content?.let { "content" to it }, c.amount?.let { "amount" to it }).toMap() }
        val answer = given ?: events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()?.answerOrReason?.takeIf { outcome == "succeeded" || outcome == "unverified" || outcome == "failed" }
            ?: old.str("answer").orEmpty()
        val goal = events.filterIsInstance<RunEvent.TaskStarted>().firstOrNull()?.goal ?: task.goal
        val ev = RunFacts.evidence(events, RECORDED_TOOLS, goal, answer)
        val end = events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()?.atMs ?: events.lastOrNull()?.atMs
        return Observed(
            outcome = outcome,
            answer = answer,
            evidence = ev,
            today = end?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() },
            confirmation = card,
            screenHas = screenHas,
            probes = probes,
            steps = events.count { it is RunEvent.ModelReply && it.acted },
            wallMs = old.str("wallMs")?.toLongOrNull() ?: 0,
            inputTokens = 0, outputTokens = 0, cachedTokens = 0,
            failReason = old.str("failReason"),
            goal = goal,
            webHas = webHas,
            memoryUsed = events.filterIsInstance<RunEvent.MemoryUsed>().sumOf { it.ids.size },
            askedUser = events.count { it is RunEvent.ModelReply && it.acted && it.tool == "ask_user" },
            takeoverBarrier = RunFacts.takeoverBarrier(events, RECORDED_TOOLS),
        )
    }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    /** The reason for the verification in the old record was denied by the "value is not the same as what it says" rule which has been removed. */
    /** The "foundation cannot be found" that I wrote down before changing the statement. */
    private val OLD_NOT_ON_SCREEN = "回答的依据在屏幕、网页或命令输出里都找不到"
    private val OLD_NOT_ONE_SOURCE = "回答里的值和它说的东西不是从同一个页面或 App 读来的"
}
