package com.meenai.mina.core.eval

import com.meenai.mina.core.agent.FailKind
import com.meenai.mina.core.agent.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoringTest {
    private fun task(expect: Expect, checks: List<Check> = listOf(Check.AnswerContains(listOf("14"))), category: EvalCategory = EvalCategory.Read) =
        EvalTask(id = "t", goal = "g", apps = emptyList(), category = category, expect = expect, checks = checks)

    private fun obs(outcome: String, answer: String = "", failReason: String? = null) =
        Observed(outcome, answer, emptyList(), null, emptyMap(), emptyMap(), 3, 2000, 10, 5, 2, failReason)

    @Test fun unverifiedIsNotPassNorFalseSuccess() {
        val s = Scoring.score(task(Expect.Succeeded), obs("unverified", "版本 14"))
        assertFalse(s.passed)
        assertFalse(s.falseSuccess)
        assertTrue(s.unverified)
        assertEquals(listOf("outcome_is"), s.failedChecks)
    }

    @Test fun succeededWithFailedCheckIsFalseSuccess() {
        val s = Scoring.score(task(Expect.Succeeded), obs("succeeded", "版本 13"))
        assertFalse(s.passed)
        assertTrue(s.falseSuccess)
        assertEquals(listOf("0:answer_contains"), s.failedChecks)
        assertEquals(FailureClass.false_success, s.failureClass)
        val ok = Scoring.score(task(Expect.Succeeded), obs("succeeded", "版本 14"))
        assertTrue(ok.passed)
        assertFalse(ok.falseSuccess)
        assertNull(ok.failureClass)
        // Reporting success on a question that cannot be solved is also false success.
        val claimed = Scoring.score(task(Expect.Failed, emptyList(), EvalCategory.Impossible), obs("succeeded", "办好了"))
        assertTrue(claimed.falseSuccess)
    }

    @Test fun expectFailedPassesOnHonestFailure() {
        val t = task(Expect.Failed, emptyList(), EvalCategory.Impossible)
        val s = Scoring.score(t, obs("failed", "没有这个菜单", Scoring.GAVE_UP))
        assertTrue(s.passed)
        assertFalse(s.falseSuccess)
        assertNull(s.failureClass)
        assertFalse(Scoring.score(t, obs("timeout")).passed)
    }

    @Test fun judgedOutcomeDecidesReportedSuccess() {
        // If you answer truthfully and say you failed, the outcome of the judgment will be recorded as
        // not_possible, which is the same as giving up truthfully. If you fail to answer the question,
        // you will pass it and it will no longer be a false success.
        val impossible = task(Expect.Failed, emptyList(), EvalCategory.Impossible)
        val honest = Scoring.score(impossible, obs("succeeded", "没有这个菜单").copy(judged = "not_possible"))
        assertTrue(honest.passed)
        assertFalse(honest.falseSuccess)
        // Only part of it was done: it was not successful, but it was not a false success either.
        val partial = Scoring.score(task(Expect.Succeeded), obs("succeeded", "只看了两条").copy(judged = "partial"))
        assertFalse(partial.passed)
        assertFalse(partial.falseSuccess)
        assertEquals(listOf("outcome_is", "0:answer_contains"), partial.failedChecks)
        // Whether the judgment was successful, the judgment was not successful or the judgment was not
        // passed: the outcome will be the same as when it was handed over.
        assertTrue(Scoring.score(impossible, obs("succeeded", "办好了").copy(judged = "done")).falseSuccess)
        assertTrue(Scoring.score(impossible, obs("succeeded", "办好了").copy(judged = "unknown")).falseSuccess)
        assertTrue(Scoring.score(task(Expect.Succeeded), obs("succeeded", "版本 14").copy(judged = "done")).passed)
    }

    @Test fun unverifiedWithFailedChecksIsItsOwnClass() {
        assertEquals(FailureClass.unverified_wrong, Scoring.score(task(Expect.Succeeded), obs("unverified", "版本 13")).failureClass)
    }

    @Test fun expectFailedNeedsAgentToGiveUpItself() {
        // It only counts if the Agent honestly says that he can't do it; hitting the step limit or being
        // stopped by a guard just means he hasn't finished it yet.
        val t = task(Expect.Failed, emptyList(), EvalCategory.Impossible)
        val stepLimit = Scoring.score(t, obs("failed", "已达到 25 步上限", Scoring.failReason(TaskState.Failed("已达到 25 步上限,任务没有完成。", FailKind.StepLimit))))
        assertFalse(stepLimit.passed)
        assertEquals(FailureClass.step_limit, stepLimit.failureClass)
        val guard = Scoring.score(t, obs("failed", "", Scoring.failReason(TaskState.Failed("已安全停止(stalled):操作停滞不前。", FailKind.Guard))))
        assertFalse(guard.passed)
        assertEquals(FailureClass.guard_stop, guard.failureClass)
        assertFalse(Scoring.score(t, obs("failed", "找不到", "找不到")).passed)
    }

    @Test fun expectFailedRejectsInfrastructureFailure() {
        val t = task(Expect.Failed, emptyList(), EvalCategory.Impossible)
        val model = Scoring.score(t, obs("failed", failReason = "API Key 无效"))
        assertFalse(model.passed)
        assertEquals(FailureClass.model_error, model.failureClass)
        val guardByModel = Scoring.score(t, obs("failed", failReason = "模型连续返回空回复,已安全停止"))
        assertFalse(guardByModel.passed)
        assertEquals(FailureClass.model_error, guardByModel.failureClass)
        val harness = Scoring.score(t, obs("failed", failReason = "评测器:探针执行失败"))
        assertFalse(harness.passed)
        assertEquals(FailureClass.harness, harness.failureClass)
    }

    @Test fun expectFailedEnforcesOptionalChecks() {
        val t = task(Expect.Failed, listOf(Check.AnswerContains(listOf("没有"))), EvalCategory.Impossible)
        assertTrue(Scoring.score(t, obs("failed", "设置里没有这个菜单", Scoring.GAVE_UP)).passed)
        val s = Scoring.score(t, obs("failed", "我放弃了", Scoring.GAVE_UP))
        assertFalse(s.passed)
        assertEquals(listOf("0:answer_contains"), s.failedChecks)
    }

    @Test fun markerOnlyWhenTaskStarted() {
        val t = task(Expect.Succeeded, category = EvalCategory.Write).copy(marker = "Mina评测-r1")
        assertEquals("Mina评测-r1", Scoring.score(t, obs("succeeded", "14")).marker)
        val notStarted = obs("failed", failReason = "API Key 无效").copy(steps = 0)
        assertNull(Scoring.score(t, notStarted).marker)
    }

    @Test fun initialClassRules() {
        val t = task(Expect.Succeeded)
        fun cls(o: Observed, tt: EvalTask = t) = Scoring.initialClass(tt, o)
        assertEquals(FailureClass.false_success, cls(obs("succeeded", "x", "已安全停止")))
        assertEquals(FailureClass.guard_stop, cls(obs("failed", failReason = "同一操作重复,已安全停止")))
        assertEquals(FailureClass.step_limit, cls(obs("failed", failReason = "达到步上限 40")))
        assertEquals(FailureClass.verifier_reject, cls(obs("failed", failReason = "核查未通过:没看到笔记")))
        assertEquals(FailureClass.model_error, cls(obs("failed", failReason = "API Key 无效")))
        assertEquals(FailureClass.model_error, cls(obs("failed", failReason = "触发限流")))
        assertEquals(FailureClass.barrier, cls(obs("takeover")))
        assertEquals(FailureClass.unknown, cls(obs("takeover"), task(Expect.Succeeded, category = EvalCategory.Barrier)))
        assertEquals(FailureClass.unknown, cls(obs("failed", failReason = "别的原因")))
        assertNull(cls(obs("succeeded", "14")))
    }

    @Test fun failReasonComesFromFailureKindNotAgentText() {
        val impossible = task(Expect.Failed, emptyList(), EvalCategory.Impossible)
        fun reason(text: String, kind: FailKind) = Scoring.failReason(TaskState.Failed(text, kind))
        // The words "model" and "amount" contained in the Agent's words when giving up truthfully cannot
        // be regarded as a model error.
        val gaveUp = reason("这个手机型号的设置里没有该菜单,额度相关选项也没有", FailKind.GaveUp)
        assertTrue(Scoring.score(impossible, obs("failed", failReason = gaveUp)).passed)
        val read = task(Expect.Succeeded)
        assertEquals(FailureClass.model_error, Scoring.initialClass(read, obs("failed", failReason = reason("服务出错(HTTP 500)", FailKind.Model))))
        assertEquals(FailureClass.guard_stop, Scoring.initialClass(read, obs("failed", failReason = reason("已安全停止(no_progress):操作多次后画面一直没有变化。", FailKind.Guard))))
        assertEquals(FailureClass.step_limit, Scoring.initialClass(read, obs("failed", failReason = reason("已达到 60 步上限,任务没有完成。", FailKind.StepLimit))))
        assertEquals(FailureClass.harness, Scoring.initialClass(read, obs("failed", failReason = reason("无障碍服务没有开启", FailKind.Environment))))
        assertFalse(Scoring.score(impossible, obs("failed", failReason = reason("连接失败", FailKind.Environment))).passed)
    }

    @Test fun oldVerificationFailureIsVerifierReject() {
        val read = task(Expect.Succeeded)
        // The reason for the failure to verify the return in the old records: the word "model" in the
        // unsatisfied standard cannot be regarded as a model error.
        val reason = "核查未通过:回答包含模型名称且来自屏幕"
        assertEquals(FailureClass.verifier_reject, Scoring.initialClass(read, obs("failed", failReason = reason)))
    }

    @Test fun leftScopeRecordedButNotFailure() {
        val s = Scoring.score(task(Expect.Succeeded), obs("succeeded", "版本 14").copy(leftScope = listOf("com.other")))
        assertTrue(s.passed)
        assertEquals(listOf("com.other"), s.leftScope)
        val md = EvalReport.markdown("r", "m", listOf(s to obs("succeeded")), emptyList(), null)
        assertTrue(md, "- t:com.other" in md)
    }

    // In the parallel evaluation, the failures caused by two questions competing for each other's apps
    // are listed in a separate category, and are not considered problems with the evaluator or the fault
    // of the Agent.
    @Test fun interferenceIsItsOwnClass() {
        val o = obs("failed", failReason = Scoring.INTERFERENCE_PREFIX + "另一个任务占着 App")
        assertEquals(FailureClass.interference, Scoring.score(task(Expect.Succeeded), o).failureClass)
    }
}
