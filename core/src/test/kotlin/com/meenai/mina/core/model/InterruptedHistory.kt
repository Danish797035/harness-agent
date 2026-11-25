package com.meenai.mina.core.model

import com.meenai.mina.core.agent.AgentLoop

/**
 * The history sent to the model after interrupting the streaming reply (see AgentLoop.keepInterrupted):
 * the user's words, the interrupted section is only a text reply (the sentence may be broken in half,
 * without thinking and calling), followed by two user messages of interrupt and runtime status. The
 * adapter of each service provider must convert it into a legal request as it is.
 */
internal object InterruptedHistory {
    const val ASK = "详细介绍一下光合作用"
    const val PARTIAL = "光合作用是绿色植物利用光能,把二氧化碳和水"
    val STEER = "${AgentLoop.STEER_MARKER}\n简短点"
    const val RUNTIME = "Current place: no screen or page read yet"

    val messages = listOf(
        Message(Role.User, listOf(Part.Text(ASK)), Author.User),
        Message(Role.Assistant, listOf(Part.Text(PARTIAL)), Author.Mina),
        Message(Role.User, listOf(Part.Text(STEER)), Author.User),
        Message(Role.User, listOf(Part.Text(RUNTIME)), Author.Mina),
    )
}
