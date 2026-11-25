package com.meenai.mina.core.skills

/** pending draft; trial has been accepted, but nothing has been done with it yet; active has been done with it (see LearnGate); suspended has not worked recently. */
@Suppress("EnumEntryName")
enum class SkillStatus { active, pending, suspended, trial }

/**
 * The user's own skills: precipitated from completed tasks and accepted by the user (or drafts to be
 * accepted). [markdown] is the entire SKILL.md (including the head); [replacesDraftOf] is the existing
 * skill name pointed to by the "Update" draft; [outcomes] is the result of the last few uses (used to
 * deactivate rules).
 */
data class PersonalSkill(
    val name: String,
    val markdown: String,
    val status: SkillStatus,
    val learnedFrom: String?,
    val replacesDraftOf: String?,
    val createdAt: Long,
    val outcomes: List<String>,
    /** Update the fingerprint of the original text that was read when drafting the draft; you must re-read the original text to check before accepting it. */
    val baseHash: String? = null,
    /** Where does it come from: [SOURCE_USER] is what the user asked to write down in the task (`skill_save`); null is precipitated from the completed task. */
    val source: String? = null,
) {
    val createdByUser: Boolean get() = source == SOURCE_USER

    /** Which level to return to when restoring after deactivation: those that have not been completed (precipitated, user requirements written down) will return to trial, and those that have been completed will return to official. */
    fun restoredStatus(): SkillStatus =
        if ((learnedFrom != null || createdByUser) && SkillResult.verified.name !in outcomes) SkillStatus.trial else SkillStatus.active

    companion object {
        const val SOURCE_USER = "user"
    }
}

data class SkillRevision(val id: String, val operation: String, val atMs: Long)

/** Access to personal skills and drafts to be accepted; placement is implemented by the app layer. */
interface PersonalSkills {
    /** Received (active and deactivated), excluding drafts. */
    fun list(): List<PersonalSkill>
    fun get(name: String): PersonalSkill?
    fun put(s: PersonalSkill)
    fun remove(name: String)
    /** Reading and modifying a skill are mutually exclusive with other writes (the results will not be lost when parallel tasks record the results at the same time); [transform] returns null to indicate no changes. Return the modified one. */
    fun update(name: String, transform: (PersonalSkill) -> PersonalSkill?): PersonalSkill?

    fun pending(): List<PersonalSkill>
    /** For drafts to be accepted, press the draft number (used when accepting or not using the interface). */
    fun drafts(): Map<String, PersonalSkill>
    fun putPending(id: String, s: PersonalSkill)
    /** Accept the draft: it becomes a personal skill in use (the updated draft covers [PersonalSkill.replacesDraftOf]); [editedMarkdown] is the full text modified by the user. Draft does not exist → null. */
    fun approve(id: String, editedMarkdown: String? = null): PersonalSkill?
    fun reject(id: String)
    fun revisions(name: String): List<SkillRevision> = emptyList()
    fun restore(revision: String): Boolean = false

    companion object {
        /** The version number shown to the model: the first 12 digits of the fingerprint of the original text (read it and then change it, bring it with you when you change it, if it doesn’t match, it means it was changed halfway). */
        fun version(markdown: String): String = fingerprint(markdown).take(12)
        fun fingerprint(markdown: String): String = java.security.MessageDigest.getInstance("SHA-256")
            .digest(markdown.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        /** Skill name: lowercase letters, numbers, hyphens, and also the directory name. */
        val NAME = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")

        /** Change the `name:` line in the header to [name](when accepting the updated draft and revising the full text, the header name must be consistent with the stored name); return it as is without the header. */
        fun withName(markdown: String, name: String): String = withField(markdown, "name", name)

        /** The [key] line in the header is replaced with [value](if not, add it); the line without the header is returned as is. */
        fun withField(markdown: String, key: String, value: String): String {
            val lines = markdown.replace("\r\n", "\n").split("\n").toMutableList()
            if (lines.firstOrNull()?.trimEnd() != "---") return markdown
            val end = (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" } ?: return markdown
            val i = (1 until end).firstOrNull { lines[it].startsWith("$key:") }
            if (i != null) lines[i] = "$key: $value" else lines.add(end, "$key: $value")
            return lines.joinToString("\n")
        }

        /** The [key] line with the header removed; no header or no line is returned unchanged. */
        fun withoutField(markdown: String, key: String): String {
            val lines = markdown.replace("\r\n", "\n").split("\n").toMutableList()
            if (lines.firstOrNull()?.trimEnd() != "---") return markdown
            val end = (1 until lines.size).firstOrNull { lines[it].trimEnd() == "---" } ?: return markdown
            val i = (1 until end).firstOrNull { lines[it].startsWith("$key:") } ?: return markdown
            lines.removeAt(i)
            return lines.joinToString("\n")
        }
    }
}
