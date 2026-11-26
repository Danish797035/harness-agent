package com.meenai.mina.memory

import android.security.keystore.UserNotAuthenticatedException
import com.meenai.mina.core.memory.MemoryItem
import com.meenai.mina.core.memory.MemoryPersistence
import com.meenai.mina.settings.KeyCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStoreException
import java.security.ProviderException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The entire memory is encrypted into `memory/memory.bin` (AES-GCM, the key is given by the caller,
 * officially `KeystoreKeyCipher("mina-memory")`). After writing the temporary file, the name is changed
 * atomically, and the file is either completely ciphertext or unchanged. Unable to open (the key is
 * lost) or the content is damaged: do not overwrite, change the name and save it, start from scratch,
 * use [brokenNotice] for interface prompts.
 */
class EncryptedMemoryFile(
    dir: File,
    private val cipher: KeyCipher,
    private val clock: () -> Long,
) : MemoryPersistence {
    private val memDir = File(dir, "memory")
    private val file = File(memDir, FILE)
    private val tmp = File(memDir, "$FILE.tmp")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(MemoryItem.serializer())

    private val _brokenNotice = MutableStateFlow<String?>(null)
    /** Bad file name for save; null = No problem. */
    val brokenNotice: StateFlow<String?> = _brokenNotice.asStateFlow()

    @Synchronized
    override fun load(): List<MemoryItem> {
        if (!file.exists()) return emptyList()
        val bytes = file.readBytes() // Unable to read is an IO failure. It will be thrown as usual and will not be treated as a bad file.
        return try {
            json.decodeFromString(serializer, cipher.decrypt(bytes).toString(Charsets.UTF_8))
        } catch (e: Exception) {
            // The temporary unavailability of the keystore (not unlocked, busy) does not mean that the
            // file is broken: throw it, read it again next time, and never save it as another file.
            if (e is UserNotAuthenticatedException || e is KeyStoreException || e is ProviderException) throw e
            // Key invalidation, authentication failure, truncation, and JSON corruption: save them all
            // and don’t let them be overwritten by the next write.
            moveAside()
            emptyList()
        }
    }

    @Synchronized
    override fun save(items: List<MemoryItem>) {
        memDir.mkdirs()
        val bytes = cipher.encrypt(json.encodeToString(serializer, items).toByteArray(Charsets.UTF_8))
        // First drop it to the disk and then rename it: no empty files with changed names will be left
        // when the power is turned off.
        FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        _brokenNotice.value = null
    }

    /** "Clear All": Delete memory files (including saved bad files). Key reconstruction is done by the caller after deleting the alias. */
    @Synchronized
    fun wipe() {
        memDir.listFiles()?.forEach { it.delete() }
        _brokenNotice.value = null
    }

    private fun moveAside() {
        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(clock())
        var target = File(memDir, "$FILE.broken-$stamp")
        var n = 1
        while (target.exists()) target = File(memDir, "$FILE.broken-$stamp-${n++}")
        try {
            Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        _brokenNotice.value = target.name
    }

    private companion object {
        const val FILE = "memory.bin"
    }
}
