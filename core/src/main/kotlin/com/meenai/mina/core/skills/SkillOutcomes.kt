package com.meenai.mina.core.skills

import com.meenai.mina.core.agent.FailKind
import com.meenai.mina.core.agent.LearnGate
import com.meenai.mina.core.agent.RunOutcome
import com.meenai.mina.core.agent.TaskState

@Suppress("EnumEntryName")
/**
 * : Done([verified]), the trial will be converted to official accordingly (the saved name will be used,
 * and the old record will still be read);[LearnGate]: The judgment was completed, but the verification
 * failed;[unverified]: It is not judged as the ending, it does not indicate whether the skill is
 * effective or not, and it does not count when it is deactivated.[unreviewed]:没判成结局,不说明技能灵不灵,停用时不算。
 */
enum class SkillResult { verified, unverified, failed, verifier_rejected, unreviewed }

/**
 * Skill tips under the result card: "Mina learned a skill:... · Take a look" ([draftId]), "This skill
 * has not been working recently, so it has been deactivated · Restore · Delete" ([suspended] is the name
 * of the skill).
 */
data class SkillNote(val text: String, val draftId: String? = null, val suspended: String? = null)

/** Recent usage results and automatic deactivation of personal skills (deactivation if not working properly). */
object SkillOutcomes {
    const val KEEP = 5
    private const val WINDOW = 3
    private const val BAD_IN_WINDOW = 2
    /** Result card and verification returned: Following the skill but it was returned by verification, it was judged as verifier_rejected. */
    const val VERIFIER_REJECTED_MARK = "核查未通过"

    /** Append the results once and keep only the latest [KEEP] times; it may be disabled according to the rules. */
    fun record(s: PersonalSkill, r: SkillResult, followed: Boolean): PersonalSkill {
        val outcomes = (s.outcomes + r.name).takeLast(KEEP)
        if (s.status == SkillStatus.trial) return s.copy(outcomes = outcomes, status = when (r) {
            SkillResult.verified -> SkillStatus.active
            SkillResult.failed, SkillResult.verifier_rejected -> SkillStatus.suspended
            else -> SkillStatus.trial
        })
        val suspend = s.status == SkillStatus.active &&
            shouldSuspend(outcomes, followed && r == SkillResult.verifier_rejected)
        return s.copy(outcomes = outcomes, status = if (suspend) SkillStatus.suspended else s.status)
    }

    /** Two of the last three times (those that have not been verified are not counted) were not verified, or this time they were returned after verification. */
    fun shouldSuspend(all: List<String>, lastFollowedRejected: Boolean): Boolean {
        val outcomes = all.filter { it != SkillResult.unreviewed.name }
        return lastFollowedRejected || outcomes.takeLast(WINDOW).count { it != SkillResult.verified.name } >= BAD_IN_WINDOW
    }

    /**
     * The result corresponding to the task ending; if canceled, it will not be recorded (null).
     * [outcome] is the outcome judged after handing in the answer ([RunOutcome]): Those who answer
     * truthfully and say that they did not complete it or only completed part of it will be counted as
     * failed; those who did not succeed do not mean that the skills are not working and will be counted
     * as unreviewed; those who succeeded will be counted as verified by pressing [LearnGate] (it will
     * also be counted if they did not run the verification), and those who failed the verification will
     * be counted as unverified; those who failed will only be counted as giving up and running out of
     * steps. failed, if there is a model error, an environment problem, or the operation protection
     * stops, it is considered unreviewed.
     */
    fun resultOf(end: TaskState, outcome: RunOutcome): SkillResult? = when (end) {
        is TaskState.Succeeded -> when {
            outcome == RunOutcome.Partial || outcome == RunOutcome.NotPossible -> SkillResult.failed
            // After being returned once, there are still things that are not correct: even if you follow
            // the skills and fail to do it correctly, it is the same as when it was returned by
            // verification before (it is judged that it is not judged)
            end.unconfirmed.isNotEmpty() -> SkillResult.verifier_rejected
            outcome == RunOutcome.Unknown -> SkillResult.unreviewed
            LearnGate.done(end, outcome) -> SkillResult.verified
            else -> SkillResult.unverified
        }
        is TaskState.Failed -> when {
            VERIFIER_REJECTED_MARK in end.reason -> SkillResult.verifier_rejected
            end.kind == FailKind.GaveUp || end.kind == FailKind.StepLimit -> SkillResult.failed
            // Errors in the model, problems in the operating environment, and operation protection stop:
            // it has nothing to do with whether following the instructions will work.
            else -> SkillResult.unreviewed
        }
        else -> null
    }
}

