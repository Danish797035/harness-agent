package com.meenai.mina.core.skills

import com.meenai.mina.core.model.Author
import com.meenai.mina.core.knowledge.FrontMatter
import com.meenai.mina.core.knowledge.PERSONAL_KIND
import com.meenai.mina.core.knowledge.GuideCatalog
import com.meenai.mina.core.memory.SensitiveFilter
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.Role
import com.meenai.mina.core.prompt.Prompts
import com.meenai.mina.core.observation.ObsKind
import com.meenai.mina.core.observation.PlaceKind
import com.meenai.mina.core.record.RunEvent
import com.meenai.mina.core.verify.AnswerSource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import com.meenai.mina.core.tool.ToolIndex
import com.meenai.mina.core.tool.Verb
import com.meenai.mina.core.net.UrlHost

/**
 * All inputs for drafting skills: [events] is the event of this run, [toolNames] is Mina’s current tool
 * name, [tools] checks the action of the tool by name.
 */
/** [answer] is the final answer given to the user, and [sources] is the answer source found by the code (see [AnswerSource]). */
data class DraftInput(
    val runId: String, val goal: String, val answer: String, val sources: List<AnswerSource>, val events: List<RunEvent>,
    val toolNames: Set<String>, val tools: ToolIndex,
)

/** The actions of doing things (mobile phone, web page, sandbox); reading the screen, checking skills, finishing, etc. are not counted as steps. */
private val ACTION_VERBS = setOf(Verb.Tap, Verb.Scroll, Verb.Swipe, Verb.TypeText, Verb.ClearText, Verb.Back, Verb.PressKey, Verb.LaunchApp, Verb.ShareText,
    Verb.Navigate, Verb.Upload, Verb.Tabs, Verb.RunCommand, Verb.RunBundledScript, Verb.WriteFile, Verb.ShareFile)

/** When it's worth drafting; switching versus evaluation mode is left to the caller's discretion. */
object Eligibility {
    const val MIN_ACTIONS = 5

    /** null = can be drafted; otherwise is the reason not to draft. [done]: This operation is completed ([com.meenai.mina.core.agent.LearnGate]). */
    fun check(events: List<RunEvent>, followedPersonalSkill: String?, tools: ToolIndex, done: Boolean): String? {
        val ended = events.filterIsInstance<RunEvent.TaskEnded>().lastOrNull()
        return when {
            ended?.outcome != "succeeded" -> "not_succeeded"
            !done -> "not_done"
            // This time the user has asked Mina to write down the skills according to his wishes without
            // starting another draft.
            events.any { it is RunEvent.ToolResult && tools.verb(it.tool) == Verb.SkillSave && it.resultJson.contains("\"ok\":true") } -> "skill_saved"
            // Wrong actions are not counted as steps (the results may be truncated, please refer to the
            // original text)
            events.count { it is RunEvent.ToolResult && tools.verb(it.tool) in ACTION_VERBS && !it.resultJson.contains("\"ok\":false") } < MIN_ACTIONS -> "too_few_actions"
            else -> interrupted(events) ?: "followed_personal_skill".takeIf { followedPersonalSkill != null }
        }
    }

    /** There is a part of this process that Mina did not do by herself (skipping the takeover, completing it by the user, not agreeing to confirm), and nothing can be learned from it: return the reason, otherwise null. */
    fun interrupted(events: List<RunEvent>): String? = when {
        events.any { it is RunEvent.Takeover && it.outcome == "skipped" } -> "takeover_skipped"
        // Completed by the user himself: Mina has not walked that route
        events.any { it is RunEvent.Takeover && it.outcome == "user_finished" } -> "takeover_user_finished"
        events.any { it is RunEvent.Confirmation && it.decision in DECLINED } -> "confirmation_declined"
        else -> null
    }

    private val DECLINED = setOf("declined", "declined_before")
}

/**
 * One line of the action list: only the tool, the name of the target element, the App or website where
 * it is located ([where], the website is written as "website host name"), whether it is valid, and the
 * error code. [typed]: This step is to enter text.
 */
data class ActionLine(
    val step: Int, val tool: String, val target: String?, val where: String?, val changed: Boolean?, val error: String?,
    val typed: Boolean = false,
)

/** Take a condensed action list from the running event. The full text of the screen is not fetched; the text of `type_text` is not fetched (rendered into `<输入内容>`). */
object ActionDigest {
    private val ELEMENT = Regex("\"ref\":\"(@e\\d+)\",\"role\":\"[^\"]*\",\"name\":(\"(?:[^\"\\\\]|\\\\.)*\")")
    private val CURRENT_APP = Regex("\"currentApp\":\"([^\"]+)\"")
    private val CURRENT_SITE = Regex("\"currentSite\":\"([^\"]+)\"")
    private val READS = setOf(Verb.Observe, Verb.ListElements)

    fun of(events: List<RunEvent>, tools: ToolIndex, maxLines: Int = 60): List<ActionLine> {
        val args = HashMap<Int, JsonObject>()
        var names = emptyMap<String, String>()
        // Which App is on the mobile phone, which site is on the web page; onWeb is which side is it at
        // this moment?
        var app: String? = null
        var site: String? = null
        var onWeb = false
        val out = mutableListOf<ActionLine>()
        for (e in events) {
            when (e) {
                is RunEvent.ModelReply -> e.argsJson?.let(::parse)?.let { args[e.step] = it }
                is RunEvent.Observed -> if (e.kind == ObsKind.Read.wire) when (e.place?.kind) {
                    PlaceKind.Site -> { site = e.place.name; onWeb = true }
                    PlaceKind.Page -> UrlHost.of(e.place.name)?.let { site = it; onWeb = true }
                    PlaceKind.App -> { app = e.place.name; onWeb = false }
                    else -> Unit
                }
                is RunEvent.ToolResult -> {
                    // The screen reading result may be truncated into incomplete JSON, and ref→name is
                    // taken from the text of each element, which is only used to name the target.
                    val verb = tools.verb(e.tool)
                    if (verb in READS) {
                        names = ELEMENT.findAll(e.resultJson).associate { m -> m.groupValues[1] to unquote(m.groupValues[2]) }
                        CURRENT_APP.find(e.resultJson)?.let { app = it.groupValues[1]; onWeb = false }
                        CURRENT_SITE.find(e.resultJson)?.let { site = it.groupValues[1]; onWeb = true }
                    } else {
                        // Where is the screen included in the action result also reported?
                        CURRENT_SITE.find(e.resultJson)?.let { site = it.groupValues[1]; onWeb = true }
                            ?: CURRENT_APP.find(e.resultJson)?.let { app = it.groupValues[1]; onWeb = false }
                    }
                    if (verb !in ACTION_VERBS || out.size >= maxLines) continue
                    val a = args[e.step]
                    fun arg(k: String) = (a?.get(k) as? JsonPrimitive)?.contentOrNull
                    if (verb == Verb.LaunchApp || verb == Verb.ShareText) arg("package")?.let { app = it; onWeb = false }
                    if (verb == Verb.Navigate) arg("url")?.let(UrlHost::of)?.let { site = it; onWeb = true }
                    val target = when (verb) {
                        // Only remember which app it was shared with, not the text: personal information
                        // may be included
                        Verb.LaunchApp, Verb.ShareText -> arg("package")
                        Verb.PressKey -> arg("key") ?: arg("keys")
                        // Only remember the site, not the URL: there may be personal information in the
                        // query string
                        Verb.Navigate -> arg("url")?.let(UrlHost::of)
                        Verb.Tabs -> arg("action")
                        // Commands, paths, and uploaded files may contain personal information: do not
                        // remember
                        Verb.RunCommand, Verb.RunBundledScript, Verb.WriteFile, Verb.ShareFile, Verb.Upload -> null
                        else -> arg("ref")?.let { names[it] }?.takeIf { it.isNotBlank() }
                    }
                    val result = parse(e.resultJson)
                    val error = (result?.get("error") as? JsonPrimitive)?.contentOrNull
                    val where = if (onWeb) site?.let { "Site $it" } else app
                    out += ActionLine(e.step, e.tool, target, where, e.changed, error, typed = verb == Verb.TypeText)
                }
                else -> Unit
            }
        }
        return out
    }

    /** The goal is not taken from the action of reading the screen. */

    fun render(lines: List<ActionLine>): String = lines.joinToString("\n") { l ->
        buildString {
            append(l.step).append(". ").append(l.tool)
            l.target?.let { append(" 「").append(it).append("」") }
            if (l.typed) append(" 输入 <输入内容>")
            l.where?.let { append(" 在 ").append(it) }
            when {
                l.error != null -> append(" 出错:").append(l.error)
                l.changed == true -> append(" 生效")
                l.changed == false -> append(" 画面没变")
            }
        }
    }

    private fun parse(s: String): JsonObject? = try {
        Json.parseToJsonElement(s) as? JsonObject
    } catch (e: Exception) {
        null
    }

    private fun unquote(s: String) = try {
        (Json.parseToJsonElement(s) as? JsonPrimitive)?.contentOrNull ?: ""
    } catch (e: Exception) {
        ""
    }
}

sealed interface DraftResult {
    /**
     * [isUpdateOf]: The model is judged as the same type of thing that already has personal skills, as
     * its updated draft; [replacesPending]: The same type of thing is judged as a draft to be confirmed,
     * which is the number of that draft - save it back to the original place and replace it, without
     * starting a new one.
     */
    data class Draft(val skill: PersonalSkill, val isUpdateOf: String?, val replacesPending: String? = null) : DraftResult
    data class Dropped(val reason: String) : DraftResult
}

/**
 * A single model call (without tools) writes the completed task into a skill draft, which is then
 * verified by the code; however, it is discarded without disturbing the user. What is sent to the model
 * is the goal, the final answer, the source of the answer (which step was read where) and the action
 * list; the value in the goal and the input text are replaced with placeholders, excluding the original
 * text read. Also list the existing personal skills (together with drafts to be confirmed): This time it
 * is the same type of thing as which one. The model writes `updates: <名字>` in the header to indicate
 * that the code only checks that the name is true.
 */
class SkillDrafter(private val model: ChatModel) {
    suspend fun draft(input: DraftInput, existing: PersonalSkills, catalog: GuideCatalog): DraftResult {
        // The value this time (the text and numbers entered this time in quotation marks in the target)
        // is replaced by a placeholder, and the skill can be used next time when the target is changed;
        // the answers and words on the screen are given as they are, and received as they are: the pages
        // and button names inside are exactly the method itself.
        val literals = SkillRedactor.literals(input.goal, emptyList()) + SkillRedactor.textLiterals(typedTexts(input.events, input.tools))
        val keepDigits = catalogDigits(catalog)
        // The names of existing skills are given as they are (the numbers won’t match if they are
        // changed); they are the user’s own skills, not this time’s values.
        val request = SkillRedactor.scrubNumbers(SkillRedactor.redact(request(input), literals), keepDigits) + "\n" + existingSkills(existing)
        val reply = try {
            model.complete(PROMPT, listOf(Message(Role.User, listOf(Part.Text(request)), Author.Mina)), emptyList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return DraftResult.Dropped("model_error")
        }
        val redacted = SkillRedactor.redact(stripFence(reply.parts.filterIsInstance<Part.Text>().joinToString("") { it.text }), literals)
        // First check it as it is: there are things like passwords and card numbers written in the
        // draft, which means that the drafting has gone astray, and the numbers will not be accepted
        // even if they are replaced.
        if (SensitiveFilter.hit(redacted) != null) return DraftResult.Dropped("sensitive")
        // Which copy do you want to update? Take it before changing the numbers: the number in the skill
        // name is part of the name.
        val updates = (FrontMatter.parse(redacted).first["updates"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val md = PersonalSkills.withoutField(SkillRedactor.scrubNumbers(redacted, keepDigits), "updates")

        validate(md, input.toolNames, input.tools.nameOf(Verb.RunBundledScript), catalog.runnableScripts())?.let { return DraftResult.Dropped(it) }
        val meta = FrontMatter.parse(md).first
        val name = meta["name"] as String

        // The model refers to a draft to be confirmed: replace it (if it is an update of an existing
        // skill, the new draft will then update that copy); refers to the one that has been accepted (in
        // use, trial or deactivated): as its update. If the name does not match (there is no copy, it is
        // a built-in skill), it will not be counted and will be treated as a new copy.
        val pending = updates?.let { u -> existing.drafts().entries.firstOrNull { it.value.name == u } }
        val updateOf = if (pending != null) pending.value.replacesDraftOf else updates?.takeIf { existing.get(it) != null }
        // New drafts cannot overwrite built-in skills, existing personal skills or drafts; any updated
        // draft will use the name of the one being updated (overwrite it when accepting it, and the
        // header name must match)
        if (pending == null && updateOf == null &&
            (existing.get(name) != null || existing.pending().any { it.name == name } || catalog.items.any { it.name == name }))
            return DraftResult.Dropped("name_taken")
        val finalName = pending?.value?.name ?: updateOf ?: name
        // Which run comes from is written in code: the numbers in the request are replaced by
        // placeholders, and the model cannot copy the real run number.
        val finalMd = PersonalSkills.withField(PersonalSkills.withName(md, finalName), "learned_from", input.runId)

        val skill = PersonalSkill(
            name = finalName, markdown = finalMd, status = SkillStatus.pending, learnedFrom = input.runId, replacesDraftOf = updateOf,
            createdAt = input.events.lastOrNull()?.atMs ?: 0L, outcomes = emptyList(),
            baseHash = updateOf?.let { name -> existing.get(name)?.markdown?.let { PersonalSkills.fingerprint(it) } },
        )
        return DraftResult.Draft(skill, updateOf, pending?.key)
    }

    /** Existing personal skills (accepted ones are at the front, drafts to be confirmed are at the back, each is arranged by name), one line for each copy: name, display name, description. */
    private fun existingSkills(existing: PersonalSkills): String {
        val lines = (existing.list().sortedBy { it.name } + existing.pending().sortedBy { it.name }).map { s ->
            val meta = FrontMatter.parse(s.markdown).first
            "- ${s.name}(${meta["display"] as? String ?: s.name}):${meta["description"] as? String ?: ""}"
        }
        return "已有的个人技能(含待用户确认的草稿):" + if (lines.isEmpty()) "无" else "\n" + lines.joinToString("\n")
    }


    /** This time type_text is the text entered (the password box has been replaced by *** in the record). */
    private fun typedTexts(events: List<RunEvent>, tools: ToolIndex): List<String> = events.filterIsInstance<RunEvent.ModelReply>()
        .filter { tools.verb(it.tool) == Verb.TypeText }
        .mapNotNull { e ->
            val o = try { Json.parseToJsonElement(e.argsJson ?: return@mapNotNull null) as? JsonObject } catch (x: Exception) { null }
            (o?.get("text") as? JsonPrimitive)?.contentOrNull?.takeIf { it != "***" }
        }

    /** The built-in skill name, display name, and numbers in the alias (such as the name of the Railway App) are retained in the draft. */
    private fun catalogDigits(catalog: GuideCatalog): Set<String> =
        catalog.items.filter { it.kind != PERSONAL_KIND }.flatMap { listOf(it.name, it.display) + it.aliases }
            .flatMap { Regex("\\d{2,}").findAll(SkillRedactor.norm(it)).map { m -> m.value }.toList() }.toSet()

    private fun request(input: DraftInput): String = buildString {
        appendLine("运行编号:${input.runId}")
        appendLine("目标:${input.goal}")
        appendLine("最终回答:${input.answer}")
        if (input.sources.isNotEmpty()) {
            appendLine("回答的来源:")
            input.sources.forEach { s -> appendLine("- 第 ${s.step} 步 ${s.kind} ${s.place.orEmpty()}" + s.title?.let { "「$it」" }.orEmpty()) }
        }
        appendLine("动作清单:")
        appendLine(ActionDigest.render(ActionDigest.of(input.events, input.tools)))
        append("可用工具:").append(input.toolNames.sorted().joinToString(", "))
    }

    companion object {
        /** The code block fence surrounding the model reply is removed. */
        fun stripFence(text: String): String {
            val t = text.trim()
            if (!t.startsWith("```")) return t
            return t.substringAfter('\n').removeSuffix("```").trim()
        }

        const val MAX_CHARS = 4000

        /**
         * The format and security check of the skill document (the draft and the full text that the user
         * has modified and accepted all pass this level); return null, otherwise the reason for return:
         * the name is legal, has a description, five sections are complete, no more than [MAX_CHARS]
         * words, the tool name in the backtick is in [toolNames], and the sensitive filtering is not
         * hit; when mentioning the tool for running the skill script ([scriptTool]), there must be at
         * least one script path in the article, and all must be in [runnableScripts](清单里标为可运行的).
         */
        fun validate(md: String, toolNames: Set<String>, scriptTool: String?, runnableScripts: Set<String> = emptySet()): String? {
            val (meta, body) = FrontMatter.parse(md)
            val name = meta["name"] as? String
            if (name == null || !PersonalSkills.NAME.matches(name)) return "invalid_name"
            if ((meta["description"] as? String).isNullOrBlank()) return "missing_description"
            val headings = body.lines().filter { it.startsWith("## ") }
            if (SECTIONS.any { s -> headings.none { it.removePrefix("## ").trim().startsWith(s) } }) return "missing_section"
            if (md.length > MAX_CHARS) return "too_long"
            if (TOOL_MENTION.findAll(md).any { it.groupValues[1] !in toolNames }) return "unknown_tool"
            if (scriptTool != null && TOOL_MENTION.findAll(md).any { it.groupValues[1] == scriptTool }) {
                val scripts = SCRIPT_PATH.findAll(md).map { it.value }.toList()
                fun runnable(path: String) = runnableScripts.any { it == path || it.endsWith("/$path") }
                if (scripts.isEmpty() || !scripts.all(::runnable)) return "unrunnable_script"
            }
            if (SensitiveFilter.hit(md) != null) return "sensitive"
            return null
        }
        val SECTIONS = listOf("入口", "步骤", "容易出错的地方", "怎样算办成", "什么时候停下交给用户")
        /** An identifier in the form of a tool name in backticks. */
        private val TOOL_MENTION = Regex("`([a-z][a-z0-9_]*)`")
        /** The skill script path in the article, such as `scripts/extract_pdf.py` or `skills/documents/scripts/extract_pdf.py`. */
        private val SCRIPT_PATH = Regex("[A-Za-z0-9_./-]*scripts/[A-Za-z0-9_./-]+\\.(?:py|mjs|js|sh)(?![A-Za-z0-9_])")

        val PROMPT: String by lazy { Prompts.role("skill_draft") }
    }
}

