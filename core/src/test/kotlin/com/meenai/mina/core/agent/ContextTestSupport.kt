package com.meenai.mina.core.agent

import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.Part

/** Remove the runtime status at the end of each step in a request so that you can view tool results and prompts at the old location. */
internal fun List<Message>.withoutRuntime(): List<Message> = map(ContextBuilder::withoutRuntimeState).filter { it.parts.isNotEmpty() }

/** The text of the message (excluding runtime status): the context removes the shell and leaves only the text, leaving the rest as it is. */
internal fun Message.plainText(): String = ContextBuilder.withoutRuntimeState(this).let { m ->
    ContextBuilder.bodyOf(m) ?: m.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }
}

/** The text of a certain context, in order of appearance. */
internal fun List<Message>.contextBodies(vararg kinds: ContextKind): List<String> =
    withoutRuntime().filter { ContextBuilder.kindOf(it) in kinds }.mapNotNull { ContextBuilder.bodyOf(it) }

/** The runtime status text in a request, in order of appearance. */
internal fun List<Message>.runtimeStates(): List<String> = flatMap { it.parts }.filterIsInstance<Part.Text>()
    .filter { ContextBuilder.kindOf(it.text) == ContextKind.RuntimeState }
    .map { ContextBuilder.bodyOf(com.meenai.mina.core.model.Message(com.meenai.mina.core.model.Role.User, listOf(it), com.meenai.mina.core.model.Author.Mina))!! }
