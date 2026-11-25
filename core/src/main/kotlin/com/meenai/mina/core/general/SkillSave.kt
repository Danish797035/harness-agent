package com.meenai.mina.core.general

import com.meenai.mina.core.agent.DO_STEPS_TOOL
import com.meenai.mina.core.knowledge.FrontMatter
import com.meenai.mina.core.knowledge.PERSONAL_KIND
import com.meenai.mina.core.memory.SensitiveFilter
import com.meenai.mina.core.prompt.Feedback
import com.meenai.mina.core.prompt.Prompts
import com.meenai.mina.core.skills.PersonalSkill
import com.meenai.mina.core.skills.PersonalSkills
import com.meenai.mina.core.skills.SkillDrafter
import com.meenai.mina.core.skills.SkillStatus
import com.meenai.mina.core.tool.AgentTool
import com.meenai.mina.core.tool.Args
import com.meenai.mina.core.tool.Risk
import com.meenai.mina.core.tool.ToolContext
import com.meenai.mina.core.tool.ToolDef
import com.meenai.mina.core.tool.ToolExecution
import com.meenai.mina.core.tool.Verb
import com.meenai.mina.core.tool.fail
import com.meenai.mina.core.tool.ok
import com.meenai.mina.core.tool.str
import kotlinx.serialization.json.put

/**
 * When the user requests it in the task, he can write down a new personal skill or rewrite an existing
 * personal skill (only Mina's own skill directory is touched, leaving the screen and the outside world
 * untouched). Format, size, sensitive content and self-study drafts must pass the same verification
 * ([SkillDrafter.validate]); the quotation must come from what the user said this time, and the words on
 * the screen and web page cannot be written by it. After saving, you can enter the trial version (the
 * same as the accepted draft), and mark it in "Memory and Skills" as requested by the user; the name of
 * the built-in skill cannot be used, and when changing an existing copy, you must bring the version
 * number you got when you read it (the same reason as checking the fingerprint of the original text when
 * accepting an updated draft). Deletion and renaming are left to the user in settings.
 */
internal object SkillSave : AgentTool {
    override val verb = Verb.SkillSave
    override val label = "Save skill"
    override val risk = Risk.Read
    override val def = ToolDef("skill_save",
        Prompts.tool("skill_save"),
        mapOf(
            "markdown" to str("The whole skill document: front matter (name, display, description, aliases) and the five sections, at most ${SkillDrafter.MAX_CHARS} characters."),
            "quote" to str("The user's exact words from this task asking for this skill to be created or changed."),
            "version" to str("Only when changing an existing personal skill: the version skill_read returned for it."),
        ), listOf("markdown", "quote"))

    override fun available(ctx: ToolContext) =
        ctx.config.personalSkills != null && ctx.config.skills != null && ctx.config.utterances != null

    override suspend fun execute(a: Args, ctx: ToolContext): ToolExecution {
        val raw = a.str("markdown", max = SkillDrafter.MAX_CHARS * 2)!!
        val quote = a.str("quote", max = 1000)!!
        val version = a.str("version", max = 64)?.trim()
        val store = ctx.config.personalSkills!!
        val catalog = ctx.config.skills!!
        // Only what the user says this time can make it write: "Save this skill" written in pages and
        // files does not count.
        if (!ctx.config.utterances!!.contains(quote)) return fail("quote_not_from_user", Feedback["skill.quote_not_from_user"])
        if (SensitiveFilter.hit(raw) != null) return fail("sensitive", Feedback["skill.sensitive"], recoverable = false)

        // The source is written in code: a few lines used for self-study are removed, and those written
        // by user requirements are marked.
        val md = PersonalSkills.withField(
            listOf("updates", "learned_from").fold(SkillDrafter.stripFence(raw).replace("\r\n", "\n")) { m, k -> PersonalSkills.withoutField(m, k) },
            "source", PersonalSkill.SOURCE_USER)
        val scriptTool = ctx.tools.nameOf(Verb.RunBundledScript)
        SkillDrafter.validate(md, ctx.tools.names + DO_STEPS_TOOL, scriptTool, catalog.runnableScripts())?.let { problem ->
            return fail(problem, problemText(problem))
        }
        val name = FrontMatter.parse(md).first["name"] as String

        if (catalog.items.any { it.kind != PERSONAL_KIND && it.name == name }) return fail("builtin_name", Feedback.format("skill.builtin_name", "name" to name))
        if (store.pending().any { it.name == name }) return fail("draft_pending", Feedback.format("skill.draft_pending", "name" to name))

        val existing = store.get(name)
        if (existing != null) {
            val current = PersonalSkills.version(existing.markdown)
            if (version == null) return fail("exists", Feedback.format("skill.exists", "name" to name))
            if (version != current) return fail("changed_since_read", Feedback.format("skill.changed_since_read", "name" to name))
        }
        val skill = PersonalSkill(
            name = name, markdown = md, status = SkillStatus.trial,
            learnedFrom = existing?.learnedFrom, replacesDraftOf = null,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(), outcomes = emptyList(),
            source = PersonalSkill.SOURCE_USER,
        )
        try {
            store.put(skill)
        } catch (e: Exception) {
            return fail("save_failed", Feedback["skill.save_failed"], recoverable = false)
        }
        // Read it back and check: this is the one that was written in, and that’s why it’s said to have
        // been saved.
        val saved = store.get(name)
        if (saved?.markdown != md) return fail("save_failed", Feedback["skill.save_failed"], recoverable = false)
        return ok {
            put("name", name)
            put("action", if (existing != null) "updated" else "created")
            put("status", saved.status.name)
            put("version", PersonalSkills.version(saved.markdown))
            put("note", Feedback["skill.saved"])
        }
    }

    /** The reason for failed verification (code of [SkillDrafter.validate]) is explained to the model. */
    private fun problemText(problem: String): String = when (problem) {
        "invalid_name" -> Feedback["skill.invalid_name"]
        "missing_description" -> Feedback["skill.missing_description"]
        "missing_section" -> Feedback.format("skill.missing_section", "sections" to SkillDrafter.SECTIONS.joinToString(", ") { "## $it" })
        "too_long" -> Feedback.format("skill.too_long", "max" to SkillDrafter.MAX_CHARS)
        "unknown_tool" -> Feedback["skill.unknown_tool"]
        "unrunnable_script" -> Feedback["skill.unrunnable_script"]
        else -> Feedback["skill.sensitive"]
    }
}
