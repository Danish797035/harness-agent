package com.meenai.mina.chat

import android.content.Context
import com.meenai.mina.cap.sandbox.SandboxDirs
import com.meenai.mina.core.userscreen.CapturedScreen
import com.meenai.mina.core.userscreen.UserScreen
import com.meenai.mina.core.userscreen.UserScreenJson
import com.meenai.mina.core.userscreen.UserScreenSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The screen sent by the user is stored along with the conversation: it is placed in the workspace of
 * that conversation (`/workspace/screens/<number>.jpg` and `.json`), in the same place as the attachment.
 * When the conversation workspace is deleted, it disappears together; commands in the sandbox can also
 * read this picture. Reading and writing all go through the file gate of the workspace (commands in the
 * sandbox are replaced by links and cannot be read or written elsewhere), and the permission of the tree
 * lock is held when writing (and the mutual exclusion is reset).
 */
class UserScreenFiles(private val dirs: SandboxDirs) {
    constructor(context: Context) : this(SandboxDirs(context))

    /** Save the conversation [conversationId] and return the numbered description. */
    suspend fun save(conversationId: String, shot: CapturedScreen): UserScreen = dirs.tree.shared {
        withContext(Dispatchers.IO) {
            val id = "s" + UUID.randomUUID().toString().replace("-", "").take(12)
            val screen = shot.screen.copy(id = id)
            val ws = dirs.workspace(conversationId)
            ws.mkdirs()
            shot.jpeg?.let { dirs.gate.replace(ws, "$DIR/$id.jpg", it) }
            dirs.gate.replace(ws, "$DIR/$id.json", UserScreenJson.encode(screen).toByteArray())
            screen
        }
    }

    suspend fun screen(conversationId: String, id: String): UserScreen? = read(conversationId, "$id.json")?.let { UserScreenJson.decode(String(it)) }

    suspend fun image(conversationId: String, id: String): ByteArray? = read(conversationId, "$id.jpg")

    /** The screen in this dialogue is assigned to the task by number. */
    fun source(conversationId: String): UserScreenSource = object : UserScreenSource {
        override suspend fun screen(id: String) = this@UserScreenFiles.screen(conversationId, id)
        override suspend fun image(id: String) = this@UserScreenFiles.image(conversationId, id)
    }

    private suspend fun read(conversationId: String, name: String): ByteArray? = withContext(Dispatchers.IO) {
        if (!SAFE.matches(name)) return@withContext null
        runCatching {
            dirs.gate.openRead(dirs.workspace(conversationId), "$DIR/$name").use { f ->
                if (f.size > MAX_BYTES) null else f.read(0, f.size.toInt())
            }
        }.getOrNull()
    }

    private companion object {
        const val DIR = "screens"
        const val MAX_BYTES = 20L shl 20
        val SAFE = Regex("^[A-Za-z0-9_-]{1,64}\\.(jpg|json)$")
    }
}
