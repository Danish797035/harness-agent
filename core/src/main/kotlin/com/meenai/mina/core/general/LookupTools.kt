package com.meenai.mina.core.general

import com.meenai.mina.core.knowledge.GuideView
import com.meenai.mina.core.knowledge.KNOWLEDGE_KINDS
import com.meenai.mina.core.knowledge.KnowledgeIndex
import com.meenai.mina.core.knowledge.PERSONAL_KIND
import com.meenai.mina.core.knowledge.SKILL_KINDS
import com.meenai.mina.core.knowledge.toolsNote
import com.meenai.mina.core.prompt.Prompts
import com.meenai.mina.core.skills.PersonalSkills
import com.meenai.mina.core.tool.AgentTool
import com.meenai.mina.core.tool.ArgError
import com.meenai.mina.core.tool.Args
import com.meenai.mina.core.tool.Concurrency
import com.meenai.mina.core.tool.Risk
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.tool.ToolDef
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.Verb
import com.meenai.mina.core.tool.fail
import com.meenai.mina.core.tool.ok
import com.meenai.mina.core.tool.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Check App and website knowledge; read-only directory, only provided if there is a directory. */
internal object KnowledgeSearch : AgentTool {
    override val verb = Verb.KnowledgeSearch
    override val label = "Look up app/site knowledge"
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    private val KINDS = KnowledgeIndex.KINDS.filter { it in KNOWLEDGE_KINDS }
    override val def = ToolDef("knowledge_search",
        Prompts.tool("knowledge_search"),
        mapOf(
            "query" to query("App or website name, alias or topic words, 1-200 characters."),
            "kind" to str("Only this kind: app (a phone app) or site (a website).", KINDS),
        ), listOf("query"))

    override fun available(ctx: ToolContext) = ctx.config.skills != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val q = a.str("query", max = 200)!!
        val kind = a.str("kind")
        if (kind != null && kind !in KINDS) throw ArgError("kind", "must be ${KINDS.joinToString(", ")}")
        return matches(ctx, q, kind, KNOWLEDGE_KINDS)
    }
}

/** Check skills (built-in skills and personal skills used by users); read-only directory, only provided if there is a directory. */
internal object SkillSearch : AgentTool {
    override val verb = Verb.SkillSearch
    override val label = "Look up skills"
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef("skill_search", Prompts.tool("skill_search"), mapOf("query" to query("Words for the kind of task, 1-200 characters.")), listOf("query"))

    override fun available(ctx: ToolContext) = ctx.config.skills != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution =
        matches(ctx, a.str("query", max = 200)!!, null, SKILL_KINDS)
}

/** Read the text or attachments of app or website knowledge. */
internal object KnowledgeRead : AgentTool {
    override val verb = Verb.KnowledgeRead
    override val label = "Read app/site knowledge"
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef("knowledge_read",
        Prompts.tool("knowledge_read"),
        mapOf(
            "name" to str("Name from knowledge_search or from the knowledge given to you, optionally as app:<name> or site:<domain>."),
            "file_path" to FILE_PATH,
        ), listOf("name"))

    override fun available(ctx: ToolContext) = ctx.config.skills != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = read(a, ctx, KNOWLEDGE_KINDS)
}

/** Read the text or attachments of skills, including the user's personal skills. */
internal object SkillRead : AgentTool {
    override val verb = Verb.SkillRead
    override val label = "Read skill"
    override val risk = Risk.Read
    override val concurrency = Concurrency.ParallelSafe
    override val def = ToolDef("skill_read",
        Prompts.tool("skill_read"),
        mapOf(
            "name" to str("Skill name from skill_search or from a skill list given to you, optionally as skill:<name> or personal:<name>."),
            "file_path" to FILE_PATH,
        ), listOf("name"))

    override fun available(ctx: ToolContext) = ctx.config.skills != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution = read(a, ctx, SKILL_KINDS)
}

private fun query(description: String): JsonObject = buildJsonObject {
    put("type", "string"); put("description", description); put("minLength", 1); put("maxLength", 200)
}
private val FILE_PATH = str("Optional relative path of an attachment listed in files, e.g. references/stations.md.")

private fun matches(ctx: ToolContext, query: String, kind: String?, among: Set<String>): ToolExecution {
    val found = ctx.config.skills!!.find(query, kind, among = among)
    return ok {
        put("matches", JsonArray(found.map {
            buildJsonObject { put("name", it.name); put("display", it.display); put("kind", it.kind); put("description", it.description) }
        }))
    }
}

private suspend fun read(a: Args, ctx: ToolContext, among: Set<String>): ToolExecution {
    val name = a.str("name", max = 200)!!
    val file = a.str("file_path", max = 500)
    val catalog = ctx.config.skills!!
    val runner = ctx.scripts
    val ready = runner?.ready() == true
    return when (val v = catalog.view(name, file, toolsNote(runner?.offered() == true), among)) {
        is GuideView.Failed -> fail(v.error, v.message)
        is GuideView.Found -> {
            // When scripts can be run, the scripts in this strip are listed and whether they can be run
            // at this moment; scriptSource is the value to be filled in when running scripts. Personal
            // skills only have text, without scripts.
            val dir = if (v.info.kind == PERSONAL_KIND) "" else catalog.scriptSource(v.info)
            val scripts = if (ready && dir.isNotEmpty()) runner!!.scripts(dir) else emptyList()
            ok {
                put("name", v.info.name); put("kind", v.info.kind); put("file", v.file)
                put("content", v.content); put("truncated", v.truncated)
                put("files", JsonArray(v.info.files.map(::JsonPrimitive)))
                // The version number of personal skills: Bring it with you when rewriting it
                // (skill_save). If it is modified elsewhere, it will not be correct.
                if (v.info.kind == PERSONAL_KIND && v.file == v.info.files.firstOrNull())
                    ctx.config.personalSkills?.get(v.info.name)?.let { put("version", PersonalSkills.version(it.markdown)) }
                if (scripts.isNotEmpty()) {
                    put("scriptSource", dir)
                    put("scripts", JsonArray(scripts.map { e ->
                        buildJsonObject { put("file", e.file); put("runnable", e.runnable); e.reason?.let { put("reason", it) } }
                    }))
                }
            }
        }
    }
}
