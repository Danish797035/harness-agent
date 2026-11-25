package com.meenai.mina.core.skills

import com.meenai.mina.core.agent.FailKind
import com.meenai.mina.core.agent.RunOutcome
import com.meenai.mina.core.agent.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SkillOutcomesTest {
    @Test fun trialNeedsVerifiedUseBeforeBecomingActive() {
        val draft = personalSkill("trial-skill", status = SkillStatus.trial)
        assertEquals(SkillStatus.trial, SkillOutcomes.record(draft, SkillResult.unverified, followed = true).status)
        assertEquals(SkillStatus.active, SkillOutcomes.record(draft, SkillResult.verified, followed = true).status)
        assertEquals(SkillStatus.suspended, SkillOutcomes.record(draft, SkillResult.verifier_rejected, followed = true).status)
    }
    private val s = personalSkill("wechat-msg")

    @Test fun keepsLast5() {
        var cur = s
        repeat(7) { cur = SkillOutcomes.record(cur, SkillResult.verified, followed = true) }
        assertEquals(SkillOutcomes.KEEP, cur.outcomes.size)
        assertEquals(SkillStatus.active, cur.status)
    }

    @Test fun twoOfLastThreeSuspends() {
        var cur = SkillOutcomes.record(s, SkillResult.unverified, followed = false)
        assertEquals(SkillStatus.active, cur.status)
        cur = SkillOutcomes.record(cur, SkillResult.verified, followed = false)
        assertEquals(SkillStatus.active, cur.status)
        cur = SkillOutcomes.record(cur, SkillResult.failed, followed = false)
        assertEquals(SkillStatus.suspended, cur.status)
        assertEquals(listOf("unverified", "verified", "failed"), cur.outcomes)
    }

    @Test fun followedRejectionSuspendsImmediately() {
        assertEquals(SkillStatus.suspended, SkillOutcomes.record(s, SkillResult.verifier_rejected, followed = true).status)
        // If you don’t follow it (it’s just recommended), return it once and don’t stop using it.
        assertEquals(SkillStatus.active, SkillOutcomes.record(s, SkillResult.verifier_rejected, followed = false).status)
    }

    @Test fun cancelledNotRecorded() {
        assertNull(SkillOutcomes.resultOf(TaskState.Cancelled, RunOutcome.Unknown))
        assertEquals(SkillResult.verified, SkillOutcomes.resultOf(TaskState.Succeeded("ok", verified = true), RunOutcome.Done))
        assertEquals(SkillResult.unverified, SkillOutcomes.resultOf(TaskState.Succeeded("ok", verified = false, note = "x"), RunOutcome.Done))
        // The judgment is completed and no verification is carried out: it is considered as good as the
        // verification (the trial will be converted to official accordingly)
        assertEquals(SkillResult.verified, SkillOutcomes.resultOf(TaskState.Succeeded("ok", verified = false, reviewed = false), RunOutcome.Done))
        assertEquals(SkillResult.unreviewed, SkillOutcomes.resultOf(TaskState.Succeeded("ok", verified = false, reviewed = false), RunOutcome.Unknown))
        assertEquals(SkillResult.verifier_rejected, SkillOutcomes.resultOf(TaskState.Failed("核查未通过:没发出去", FailKind.GaveUp), RunOutcome.Unknown))
        // The answer truthfully is that it didn’t work, or only part of it worked: it’s considered a
        // failure; it’s not considered successful: it’s neither effective nor ineffective.
        assertEquals(SkillResult.failed, SkillOutcomes.resultOf(TaskState.Succeeded("找不到这个菜单", verified = true), RunOutcome.NotPossible))
        assertEquals(SkillResult.failed, SkillOutcomes.resultOf(TaskState.Succeeded("只看了两条", verified = true), RunOutcome.Partial))
        assertEquals(SkillResult.unreviewed, SkillOutcomes.resultOf(TaskState.Succeeded("ok", verified = true), RunOutcome.Unknown))
        assertEquals(SkillResult.failed, SkillOutcomes.resultOf(TaskState.Failed("找不到", FailKind.GaveUp), RunOutcome.Unknown))
        assertEquals(SkillResult.failed, SkillOutcomes.resultOf(TaskState.Failed("步数用完", FailKind.StepLimit), RunOutcome.Unknown))
        // Errors in the model, problems in the operating environment, and operation protection stop: it
        // cannot explain whether the skills or knowledge are effective.
        for (k in listOf(FailKind.Model, FailKind.Environment, FailKind.Guard))
            assertEquals(k.name, SkillResult.unreviewed, SkillOutcomes.resultOf(TaskState.Failed("x", k), RunOutcome.Unknown))
    }
}
