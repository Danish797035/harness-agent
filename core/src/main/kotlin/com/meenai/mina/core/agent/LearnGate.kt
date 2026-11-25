package com.meenai.mina.core.agent

import com.meenai.mina.core.userscreen.UserScreenMessage

/**
 * Whether one run can be regarded as "completed" for learning: drafting of personal skills and App
 * knowledge, recording and success counting of experience routes, and converting trial skills and App
 * knowledge to formal use, this is all the question.
 *
 * Look at the outcome ([OutcomeJudge]) after handing in the answer, but don’t look at the final
 * verification: the answer ([TaskState.Succeeded]) is handed in, the outcome is [RunOutcome.Done], and
 * there is no value that has not been matched after returning once; the verification of the passed
 * ([TaskState.Succeeded.reviewed]) must also be verified - the passed but failed will still be blocked,
 * and the run will not be blocked.
 */
object LearnGate {
    fun done(end: TaskState, outcome: RunOutcome): Boolean =
        end is TaskState.Succeeded && outcome == RunOutcome.Done && end.unconfirmed.isEmpty() && !end.doubtful

    /**
     * Whether this run is recorded as an experience route: the route is open, completed ([done]), and
     * has a run number (the route is taken from the placed event). The goal is not to remember the
     * user's screen: the meaning is on the screen, and the route of the word memorization will not match
     * next time.
     */
    fun shouldSaveRoute(routesOn: Boolean, end: TaskState, outcome: RunOutcome, runId: String?, goal: String): Boolean =
        routesOn && done(end, outcome) && runId != null && !UserScreenMessage.has(goal)
}
