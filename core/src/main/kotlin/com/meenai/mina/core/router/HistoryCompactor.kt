package com.meenai.mina.core.router

import com.meenai.mina.core.agent.TokenEstimate
import com.meenai.mina.core.model.Author
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role

/**
 * Compacts conversation history when switching to a model with a smaller context window.
 * Keeps system prompt, initial task goal, and recent turns intact, while summarizing
 * older intermediary steps and dropping historical images.
 */
object HistoryCompactor {

    fun compact(
        messages: List<Message>,
        targetContextTokens: Int,
        keepRecentTurns: Int = 4,
    ): List<Message> {
        val currentTokens = TokenEstimate.of(messages)
        if (currentTokens <= targetContextTokens || messages.size <= keepRecentTurns + 2) {
            return messages
        }

        val systemMessages = messages.filter { it.role == Role.System }
        val nonSystem = messages.filter { it.role != Role.System }

        if (nonSystem.size <= keepRecentTurns + 1) {
            return messages
        }

        val initialUserMsg = nonSystem.first()
        val recentMessages = nonSystem.takeLast(keepRecentTurns)
        val middleMessages = nonSystem.drop(1).dropLast(keepRecentTurns)

        // Summarize middle steps
        val toolsExecuted = mutableListOf<String>()
        var stepCount = 0

        for (msg in middleMessages) {
            for (p in msg.parts) {
                if (p is Part.ToolCall) {
                    toolsExecuted.add(p.name)
                    stepCount++
                }
            }
        }

        val summaryText = if (toolsExecuted.isNotEmpty()) {
            "Summary of previous $stepCount steps: executed tools [${toolsExecuted.joinToString(", ")}]. Current state maintained."
        } else {
            "Summary of previous steps: conversation continued."
        }

        val summaryMessage = Message(
            role = Role.User,
            parts = listOf(Part.Text(summaryText)),
            author = Author.Mina,
        )

        // Drop historical images from recent messages if still exceeding target
        val sanitizedRecent = recentMessages.map { msg ->
            if (msg.parts.any { it is Part.Image }) {
                msg.copy(parts = msg.parts.map { part ->
                    if (part is Part.Image) {
                        Part.Text("[historical image omitted for context limit]")
                    } else part
                })
            } else msg
        }

        val compacted = systemMessages + listOf(initialUserMsg, summaryMessage) + sanitizedRecent
        return compacted
    }
}
