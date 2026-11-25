package com.meenai.mina.memory

import android.content.Context
import com.meenai.mina.chat.ConversationStore
import com.meenai.mina.core.memory.MemoryItem
import com.meenai.mina.core.memory.MemoryNote
import com.meenai.mina.core.memory.MemoryStore
import com.meenai.mina.core.memory.ProfileStore
import com.meenai.mina.core.skills.SkillNote
import com.meenai.mina.settings.KeystoreKeyCipher
import com.meenai.mina.settings.MemorySettings
import com.meenai.mina.skills.PersonalSkillFiles
import com.meenai.mina.knowledge.AppKnowledgeFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.security.KeyStore
import java.util.UUID

/** The prompt under the result card after a run is completed; press the run number to link to that question and answer. */
data class RunNotes(
    val conversationId: String,
    val runId: String,
    val memoryNotes: List<MemoryNote> = emptyList(),
    val usedMemory: List<String> = emptyList(),
    val skillNotes: List<SkillNote> = emptyList(),
)

/** Open the request for "Memory and Skills" (click "Look" on the result card): [draftId] is the skill draft you want to see. */
data class MemoryOpen(val draftId: String? = null)

/**
 * Memory storage, personal skills, switch and outcome card prompts. Created by App and given by [Owner]:
 * Several parallel tasks share the same [MemoryStore], and writes are serialized by its own lock. The
 * first time it is accessed, the disk will be read and decrypted, so do not adjust it on the main
 * thread.
 */
class MemoryRuntime(context: Context) {
    private val app = context.applicationContext

    private var file: EncryptedMemoryFile? = null
    private var store: MemoryStore? = null
    private var skills: PersonalSkillFiles? = null
    private var settings: MemorySettings? = null
    private var profile: ProfileStore? = null
    private var appKnowledge: AppKnowledgeFiles? = null

    /** The learning after the task ends is run here: it is not canceled when the service exits. */
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _notes = MutableStateFlow<Map<String, RunNotes>>(emptyMap())
    /** Run number → Prompt; the interface is displayed under the results card accordingly. */
    val notes: StateFlow<Map<String, RunNotes>> = _notes.asStateFlow()

    private val _open = MutableStateFlow<MemoryOpen?>(null)
    val openRequest: StateFlow<MemoryOpen?> = _open.asStateFlow()

    fun requestOpen(o: MemoryOpen?) { _open.value = o }

    fun publish(n: RunNotes) = _notes.update { it + (n.runId to n) }

    /**
     * Memorize the items and give the prompt text to the result card. When accessing for the first time,
     * the disk is read and decrypted in the background. It is empty before reading; after that, the
     * storage changes.
     */
    fun items(): StateFlow<List<MemoryItem>> {
        val out = MutableStateFlow<List<MemoryItem>>(emptyList())
        scope.launch(Dispatchers.IO) { runCatching { store() }.getOrNull()?.items?.collect { out.value = it } }
        return out.asStateFlow()
    }

    @Synchronized fun file(): EncryptedMemoryFile =
        file ?: EncryptedMemoryFile(app.filesDir, KeystoreKeyCipher(KEY_ALIAS), System::currentTimeMillis).also { file = it }

    @Synchronized fun store(): MemoryStore =
        store ?: MemoryStore(file(), System::currentTimeMillis, { UUID.randomUUID().toString() }).also { store = it }

    @Synchronized fun skills(): PersonalSkillFiles =
        skills ?: PersonalSkillFiles(app.filesDir).also { skills = it }

    @Synchronized fun profile(): ProfileStore =
        profile ?: ProfileStore(FileProfileBackend(File(app.filesDir, "memory/profile.bin"), KeystoreKeyCipher(KEY_ALIAS)), ids = { UUID.randomUUID().toString() }).also { profile = it }

    @Synchronized fun appKnowledge(): AppKnowledgeFiles =
        appKnowledge ?: AppKnowledgeFiles(app.filesDir).also { appKnowledge = it }

    @Synchronized fun settings(): MemorySettings =
        settings ?: MemorySettings(File(app.filesDir, "memory_settings.json")).also { settings = it }

    /** "Clear All": Memories, bad saved files, personal skills and drafts are all deleted, and the memory key is rebuilt; memory prompts in conversations are also removed. */
    @Synchronized fun wipeAll() {
        store().clearAll()
        file().wipe()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_ALIAS) }
        File(app.filesDir, "skills").deleteRecursively()
        File(app.filesDir, "knowledge/app-drafts").deleteRecursively()
        stripMemory(null)
        _notes.update { m -> m.mapValues { (_, n) -> n.copy(skillNotes = emptyList()) } }
    }

    /** These memories are deleted (null = all for [ids]): they are no longer mentioned in the result cards and conversation files. */
    fun stripMemory(ids: Set<String>?) {
        fun keep(id: String) = ids != null && id !in ids
        _notes.update { m -> m.mapValues { (_, n) -> n.copy(memoryNotes = n.memoryNotes.filter { keep(it.memoryId) }, usedMemory = n.usedMemory.filter(::keep)) } }
        runCatching { ConversationStore.shared(app).stripMemory(ids) }
    }

    /** Give the application in the process. */
    interface Owner {
        val memory: MemoryRuntime
    }

    companion object {
        const val KEY_ALIAS = "mina-memory"

        fun of(context: Context): MemoryRuntime = (context.applicationContext as? Owner)?.memory
            ?: error("the Application (${context.applicationContext.javaClass.name}) must implement MemoryRuntime.Owner to give the memory runtime")
    }
}
