package com.meenai.mina.core.general

import com.meenai.mina.core.observation.ObsKind
import com.meenai.mina.core.observation.Observation
import com.meenai.mina.core.memory.MemoryIndex
import com.meenai.mina.core.memory.MemoryKind
import com.meenai.mina.core.memory.MemoryRejected
import com.meenai.mina.core.memory.MemorySource
import com.meenai.mina.core.memory.MemoryStore
import com.meenai.mina.core.prompt.Feedback
import com.meenai.mina.core.prompt.Prompts
import com.meenai.mina.core.tool.AgentTool
import com.meenai.mina.core.tool.ArgError
import com.meenai.mina.core.tool.Args
import com.meenai.mina.core.tool.Concurrency
import com.meenai.mina.core.tool.EvidenceLog
import com.meenai.mina.core.tool.Risk
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.tool.ToolDef
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.Verb
import com.meenai.mina.core.tool.argStr
import com.meenai.mina.core.tool.fail
import com.meenai.mina.core.tool.ok
import com.meenai.mina.core.tool.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

private const val MEMORY_SEARCH_LIMIT = 8

/** It is provided only after the memory and what the user has said this time are given. */
private fun memoryOn(ctx: ToolContext) = ctx.config.memory != null && ctx.config.utterances != null

internal object MemorySearch : AgentTool {
    override val verb = Verb.MemorySearch
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef("memory_search",
        Prompts.tool("memory_search"),
        mapOf("query" to str("Words to look for, 1-100 characters.")), listOf("query"))

    override fun available(ctx: ToolContext) = memoryOn(ctx)

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val q = a.str("query", max = 100)!!
        val found = MemoryIndex(ctx.config.memory!!.items.value).search(q, MEMORY_SEARCH_LIMIT)
        return ok {
            put("memories", JsonArray(found.map { (m, _) ->
                buildJsonObject { put("id", m.id); put("kind", m.kind.name); put("content", m.content) }
            }))
        }
    }

    /** What is in the memory is what the user said before: the facts given by the user are submitted for verification. */
    override fun observations(o: JsonObject, r: ToolExecution, log: EvidenceLog): List<Observation> {
        if (r.content["ok"] != JsonPrimitive(true)) return emptyList()
        return listOfNotNull((r.content["memories"] as? JsonArray)?.takeIf { it.isNotEmpty() }?.let { list ->
            Observation(ObsKind.UserSaid, list.joinToString("\n", "记忆检索「${o.argStr("query")}」:\n") { m -> ((m as? JsonObject)?.get("content") as? JsonPrimitive)?.contentOrNull.orEmpty() },
                source = "memory")
        })
    }
}

/** Only Mina's own memory is modified, leaving the screen and the outside world untouched. */
internal object MemorySave : AgentTool {
    override val verb = Verb.MemorySave
    override val risk = Risk.Read
    override val def = ToolDef("memory_save",
        Prompts.tool("memory_save"),
        mapOf(
            "kind" to str("fact = about the user, preference = what the user likes, procedure = how the user wants a kind of task done.", listOf("fact", "preference", "procedure")),
            "content" to str("One sentence in the third person, at most 200 characters, e.g. 用户点外卖不要香菜."),
            "quote" to str("The user's exact words this is based on."),
            "keywords" to buildJsonObject {
                put("type", "array"); put("description", "Optional, up to 8 search words incl. synonyms.")
                put("items", buildJsonObject { put("type", "string") })
            },
        ), listOf("kind", "content", "quote"))

    override fun available(ctx: ToolContext) = memoryOn(ctx)

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val kind = a.str("kind")!!.let { k -> MemoryKind.entries.find { it.name == k } ?: throw ArgError("kind", "must be fact, preference or procedure") }
        val content = a.str("content", max = MemoryStore.MAX_CONTENT)!!
        val quote = a.str("quote", max = 1000)!!
        val keywords = a.strList("keywords", 0, MemoryStore.MAX_KEYWORDS, itemMax = 30) ?: emptyList()
        val utterances = ctx.config.utterances!!
        // The quotation must come from what the user said this time; the words on the screen and web
        // page cannot be regarded as what the user said.
        if (!utterances.contains(quote)) return fail("quote_not_from_user",
            Feedback["memory.quote_not_from_user"], recoverable = false)
        // The citations are correct, and the specific values (address, number, name) in the content
        // must also be what the user has said and cannot be filled in from the screen.
        if ((listOf(content) + keywords).any { utterances.unsupportedSpecifics(it, quote).isNotEmpty() }) return fail("content_not_from_user",
            Feedback["memory.content_not_from_user"], recoverable = false)
        val store = ctx.config.memory!!
        val added = store.add(kind, content, keywords, MemorySource("user_said", quote.trim(), ctx.config.conversationId, store.clock(),
            ctx.config.runSpec?.provenance?.origin?.name))
        added.getOrNull()?.let(ctx::noteSaved)
        return added.fold(
            onSuccess = { c -> ok { put("id", c.item.id); put("kind", c.item.kind.name); put("status", c.item.status.name) } },
            onFailure = { e ->
                when ((e as? MemoryRejected)?.code) {
                    "sensitive" -> fail("sensitive", Feedback["memory.sensitive"], recoverable = false)
                    "memory_full" -> fail("memory_full", Feedback["memory.full"], recoverable = false)
                    "unavailable" -> fail("memory_unavailable", Feedback["memory.unavailable"], recoverable = false)
                    "too_long" -> throw ArgError("content", "must be 1-${MemoryStore.MAX_CONTENT} characters")
                    else -> throw e
                }
            },
        )
    }
}

