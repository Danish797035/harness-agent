package com.meenai.mina.memory

import android.security.keystore.UserNotAuthenticatedException
import com.meenai.mina.core.memory.ProfileBackend
import com.meenai.mina.core.memory.ProfileEntry
import com.meenai.mina.settings.KeyCipher
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStoreException
import java.security.ProviderException

/** User portrait file separate from ordinary memory; full atomic replacement, retaining the replaced entries. */
class FileProfileBackend(private val file: File, private val cipher: KeyCipher) : ProfileBackend {
    private val json = Json { ignoreUnknownKeys = true }
    private val codec = ListSerializer(ProfileEntry.serializer())

    @Synchronized
    override fun read(): List<ProfileEntry> {
        if (!file.exists()) return emptyList()
        val bytes = file.readBytes()
        return try {
            json.decodeFromString(codec, cipher.decrypt(bytes).toString(Charsets.UTF_8))
        } catch (e: Exception) {
            if (e is UserNotAuthenticatedException || e is KeyStoreException || e is ProviderException) throw e
            quarantineCorruptFile()
            emptyList()
        }
    }

    @Synchronized
    override fun write(entries: List<ProfileEntry>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        val bytes = cipher.encrypt(json.encodeToString(codec, entries).toByteArray(Charsets.UTF_8))
        FileOutputStream(tmp).use {
            it.write(bytes)
            it.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun quarantineCorruptFile() {
        val broken = File(file.parentFile, "${file.name}.broken-${System.currentTimeMillis()}")
        runCatching {
            Files.move(file.toPath(), broken.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.onFailure {
            runCatching { file.delete() }
        }
    }
}
