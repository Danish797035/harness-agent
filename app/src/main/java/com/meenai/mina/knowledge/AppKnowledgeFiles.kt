package com.meenai.mina.knowledge

import com.meenai.mina.core.knowledge.AppKnowledgeDraft
import com.meenai.mina.core.knowledge.AppKnowledgeDraftStore
import com.meenai.mina.core.knowledge.AppKnowledgeStage
import com.meenai.mina.core.knowledge.AppKnowledgeTrial
import com.meenai.mina.core.knowledge.LearnedNote
import com.meenai.mina.core.knowledge.FrontMatter
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * The ledger of each version and stage of App knowledge supplement; those to be confirmed (drafts) will
 * not be read by the knowledge base. Each App has at most one copy to be confirmed, and only one copy is
 * injected ([AppKnowledgeTrial.inUse]): the accepted revision replaces the old one during the trial
 * period, and the old one is archived when it is converted to official. When it is archived, it is
 * replaced with the old one (the files before changes are kept in the archive).
 */
class AppKnowledgeFiles(dir: File) : AppKnowledgeDraftStore {
    private val root = File(dir, "knowledge/app-drafts")
    private val archive = File(root, "archive")
    private val journal = File(root, "changes.jsonl")
    private val idPattern = Regex("[A-Za-z0-9_-]+")

    @Synchronized override fun all(): List<AppKnowledgeDraft> =
        root.listFiles { f -> f.isDirectory && f.name != "archive" && idPattern.matches(f.name) }.orEmpty()
            .mapNotNull { d ->
                runCatching {
                    val row = Json.decodeFromString(AppKnowledgeDraft.serializer(), File(d, "meta.json").readText())
                    row.copy(markdown = File(d, "APP.md").readText())
                }.getOrNull()
            }

    @Synchronized override fun putPending(draft: AppKnowledgeDraft): Boolean {
        val rows = all()
        if (!idPattern.matches(draft.id) || !idPattern.matches(draft.appName) ||
            draft.stage != AppKnowledgeStage.Draft ||
            rows.any { it.appName == draft.appName && it.stage == AppKnowledgeStage.Draft } || stale(draft, rows)) return false
        save(draft)
        return true
    }

    @Synchronized override fun approve(id: String): Boolean {
        val rows = all()
        val old = rows.firstOrNull { it.id == id && it.stage == AppKnowledgeStage.Draft } ?: return false
        if (stale(old, rows)) {
            save(old.copy(stage = AppKnowledgeStage.Archived))
            return false
        }
        save(old.copy(stage = AppKnowledgeStage.Trial))
        archiveStale(old.appName)
        return true
    }

    @Synchronized override fun reject(id: String): Boolean {
        val old = all().firstOrNull { it.id == id && it.stage != AppKnowledgeStage.Archived } ?: return false
        save(old.copy(stage = AppKnowledgeStage.Archived))
        archiveStale(old.appName)
        return true
    }

    /** The trial version is the one that was injected; the old version it replaced will be archived when it is converted to official, and the old version will be naturally replaced when archiving. */
    @Synchronized override fun recordTrial(appName: String, good: Boolean): AppKnowledgeDraft? {
        val rows = all()
        val old = AppKnowledgeTrial.inUse(rows, appName)?.takeIf { it.stage == AppKnowledgeStage.Trial } ?: return null
        val next = AppKnowledgeTrial.next(old, good)
        if (next.stage == AppKnowledgeStage.Verified) rows.filter {
            it.appName == appName && it.id != old.id && (it.stage == AppKnowledgeStage.Trial || it.stage == AppKnowledgeStage.Verified)
        }.forEach { save(it.copy(stage = AppKnowledgeStage.Archived)) }
        save(next)
        archiveStale(appName)
        return next
    }

    /** The copy injected by each App; the old version drafted the entire copy APP.md with the header, and only took the text. */
    @Synchronized override fun notes(): Map<String, LearnedNote> {
        val rows = all()
        return rows.map { it.appName }.distinct().mapNotNull { app -> AppKnowledgeTrial.inUse(rows, app) }
            .associate { row -> row.appName to LearnedNote(FrontMatter.parse(row.markdown).second.trim(), trial = row.stage == AppKnowledgeStage.Trial) }
    }

    /** The copy against which the revision was drafted is no longer the one that is now injected (another revision was accepted first): accepting it will throw away that copy. */
    private fun stale(draft: AppKnowledgeDraft, rows: List<AppKnowledgeDraft>) =
        draft.revises != AppKnowledgeTrial.inUse(rows, draft.appName)?.id

    /**
     * After the copy you were using changed (accepted, archived, and changed the trial stage), the draft
     * revisions to be confirmed that were drafted in the other copy could no longer be accepted and
     * archived, so that it would not always occupy "at most one copy of each App to be confirmed" and
     * block future drafts of this App.
     */
    private fun archiveStale(appName: String) {
        val rows = all()
        rows.filter { it.appName == appName && it.stage == AppKnowledgeStage.Draft && stale(it, rows) }
            .forEach { save(it.copy(stage = AppKnowledgeStage.Archived)) }
    }

    private fun save(row: AppKnowledgeDraft) {
        val d = File(root, row.id)
        if (d.exists()) {
            val revision = UUID.randomUUID().toString()
            d.copyRecursively(File(archive, revision), overwrite = true)
            journal.parentFile.mkdirs()
            journal.appendText(revision + " " + row.id + " " + row.stage + " " + System.currentTimeMillis() + "\n")
        }
        d.mkdirs()
        atomic(File(d, "APP.md"), row.markdown)
        atomic(File(d, "meta.json"), Json.encodeToString(AppKnowledgeDraft.serializer(), row.copy(markdown = "")))
    }

    private fun atomic(file: File, content: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(content)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
}
