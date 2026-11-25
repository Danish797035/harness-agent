package com.meenai.mina.core.agent

import com.meenai.mina.core.model.Author
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import com.meenai.mina.core.prompt.Prompts
import kotlinx.coroutines.CancellationException

/** Only when preparing to call the first tool of this round, independently determine whether the user is discussing, or wants to read or operate. */
internal class IntentGate(private val judge: ChatModel, private val fallback: ChatModel? = null) {
    enum class Intent { Discuss, Read, Act }

    suspend fun assess(task: String, priorTurns: List<Turn>): Intent? {
        if (com.meenai.mina.core.image.ImageIntentRouter.isVisualPrompt(task)) return Intent.Act
        // A clear command ("open YouTube", "search Motu Patlu") is an action by
        // its words; asking the judge model here adds a whole extra model round
        // trip to every task. Questions still go to the judge.
        quick(task)?.let { return it }
        val prior = Conversation.context(priorTurns, maxTurns = 3, maxTokens = 800)
            ?.parts?.filterIsInstance<Part.Text>()?.joinToString("\n") { it.text }.orEmpty()
        val input = buildString {
            if (prior.isNotEmpty()) appendLine(prior).appendLine()
            append("The user's current message:\n").append(task)
        }
        val messages = listOf(Message(Role.User, listOf(Part.Text(input)), Author.User))
        for (candidate in listOfNotNull(judge, fallback)) {
            val result = try {
                val reply = candidate.complete(Prompts.role("intent_gate"), messages, emptyList())
                parse(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text })
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (result != null) return result
        }
        return null
    }

    companion object {
        /** It is clearly a command statement: follow the action directly without adjusting the model more than once. Only English/Hindi is spoken, other languages are left to the judge. */
        private val POLITE = listOf(
            "please ", "pls ", "kindly ", "can you ", "could you ", "would you ", "will you ",
            "kya aap ", "kripya ",
        )
        private val QUESTION = listOf(
            "what ", "why ", "how ", "who ", "when ", "where ", "which ", "is ", "are ", "does ",
            "do ", "did ", "tell me ", "explain ", "describe ", "kya ", "kaise ", "kyun ", "kaun ",
        )
        private val ACTION = listOf(
            "open ", "launch ", "start ", "search ", "find ", "play ", "send ", "message ", "call ",
            "book ", "order ", "buy ", "download ", "install ", "set ", "turn ", "type ", "tap ",
            "click ", "scroll ", "go to ", "visit ", "check ", "read ", "show ", "get ", "make ",
            "create ", "add ", "remove ", "delete ", "share ", "upload ", "translate ", "summarize ",
            "fill ", "submit ", "post ", "pay ", "navigate ", "refresh ", "reload ", "close ",
            "stop ", "pause ", "resume ",
        )
        private val HINGLISH_ACTION = listOf(
            "khol", "kholo", "bhej", "bhejo", "chala", "chalao", "band", "dikha", "dikhao",
            "likh", "likho", "dhoondh", "dhundo", "chalu", "bajao", "sunao", "banao",
            "karo", "kardo",
        ).map { word -> Regex("\\b" + Regex.escape(word) + "\\b") }

        fun quick(task: String): Intent? {
            var t = task.trim().lowercase()
            if (t.isEmpty()) return null
            if (t.endsWith("?") || t.endsWith("？")) return null
            for (p in POLITE) if (t.startsWith(p)) { t = t.removePrefix(p).trim(); break }
            if (t.isEmpty()) return null
            if (QUESTION.any { t.startsWith(it) }) return null
            if (ACTION.any { t.startsWith(it) }) return Intent.Act
            if (HINGLISH_ACTION.any { it.containsMatchIn(t) }) return Intent.Act
            return null
        }

        fun parse(raw: String): Intent? = when (raw.trim().removePrefix("`").removeSuffix("`").trim().uppercase()) {
            "DISCUSS" -> Intent.Discuss
            "READ" -> Intent.Read
            "ACT" -> Intent.Act
            else -> null
        }
    }
}
