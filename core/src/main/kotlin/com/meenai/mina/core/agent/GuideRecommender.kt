package com.meenai.mina.core.agent

import com.meenai.mina.core.knowledge.KNOWLEDGE_KINDS
import com.meenai.mina.core.knowledge.KnowledgeIndex
import com.meenai.mina.core.knowledge.PERSONAL_KIND
import com.meenai.mina.core.knowledge.RECOMMEND_MIN_SCORE
import com.meenai.mina.core.knowledge.SKILL_KINDS
import com.meenai.mina.core.knowledge.GuideCatalog
import com.meenai.mina.core.knowledge.GuideInfo
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.meenai.mina.core.tool.Verb

internal const val SKILL_INDEX_HEADER = "All skills you have, one line each (when one fits this task, read its full text with skill_read before acting on it):"
internal const val KNOWLEDGE_INDEX_HEADER = "App and website knowledge you can refer to (read the full text with knowledge_read when needed):"
private const val RECOMMEND_COUNT = 3

/**
 * Index at the beginning of the task (write it once in the start context and do not change it in the
 * middle): App/site knowledge is retrieved according to the task text, and the original method is used
 * (take the first 3 items together with the skills, leaving only the knowledge); skills are not
 * selected, all are listed - built-in at the front, personal skills at the end, each by name, each copy
 * has one line (id, display name, description). Whether to use it and which copy to use is determined by
 * the description of the model. Use skill_read to read the full text yourself.
 */
internal object GuideRecommender {
    fun id(info: GuideInfo) = "${info.kind}:${info.name}"
    fun line(info: GuideInfo) = "- ${id(info)}(${info.display}):${info.description}"

    /** Knowledge and skills are each listed under their own heading; neither is null. Personal skills are only in use and on trial (see [GuideCatalog.items]). */
    fun index(catalog: GuideCatalog, task: String): String? {
        val knowledge = catalog.find(task, limit = RECOMMEND_COUNT, minScore = RECOMMEND_MIN_SCORE).filter { it.kind in KNOWLEDGE_KINDS }
        val skills = catalog.items.filter { it.kind in SKILL_KINDS }.sortedWith(compareBy({ it.kind != KnowledgeIndex.SKILL }, { it.name }))
        return listOf(KNOWLEDGE_INDEX_HEADER to knowledge, SKILL_INDEX_HEADER to skills).filter { it.second.isNotEmpty() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { (header, items) -> header + "\n" + items.joinToString("\n", transform = ::line) }
    }

    /** skill_read gives the skill name when a personal skill is successfully read. */
    fun viewedPersonal(verb: Verb?, content: JsonObject): String? {
        if (verb != Verb.SkillRead || content["ok"] != JsonPrimitive(true)) return null
        if ((content["kind"] as? JsonPrimitive)?.contentOrNull != PERSONAL_KIND) return null
        return (content["name"] as? JsonPrimitive)?.contentOrNull
    }
}
