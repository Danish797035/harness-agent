package com.meenai.mina.core.prompt

import com.meenai.mina.core.model.ToolSpec
import java.security.MessageDigest

/** The version of the prompt word for this task: the system prompt word plus a summary of the tool definition are written into the running record; this can be used to distinguish the prompt word version during comparison and evaluation. */
object PromptVersion {
    fun of(system: String, tools: List<ToolSpec>): String {
        val text = system + tools.joinToString("") { "\n\n" + it.name + "\n" + it.description + "\n" + it.parametersJsonSchema }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).take(6).joinToString("") { "%02x".format(it) }
    }
}
