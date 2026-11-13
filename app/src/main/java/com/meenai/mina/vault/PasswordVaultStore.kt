package com.meenai.mina.vault

import android.content.Context
import com.meenai.mina.core.record.Redactor
import com.meenai.mina.settings.KeyCipher
import com.meenai.mina.settings.KeystoreKeyCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.util.Base64
import java.util.UUID

/**
 * Metadata for a saved credential in the Password Vault.
 * Passwords are never kept in this plain metadata structure; they are encrypted separately.
 */
data class VaultEntry(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val username: String,
    val url: String? = null,
    val notes: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * On-device Password Vault store.
 *
 * Security architecture:
 * 1. Passwords are saved in a separate file (vault-secrets.json) from metadata (vault.json).
 * 2. Secrets are encrypted using Android Keystore AES/GCM cipher with dedicated alias "harness-password-vault".
 * 3. Never synced to cloud or servers; stored strictly in local filesDir.
 * 4. Passwords registered with [Redactor] to prevent accidental leakage into logs, traces, or model prompts.
 */
class PasswordVaultStore(
    private val dir: File,
    private val cipher: KeyCipher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val metadataFile = File(dir, METADATA_FILE)
    private val secretsFile = File(dir, SECRETS_FILE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _entries = MutableStateFlow<List<VaultEntry>>(emptyList())
    val entries: StateFlow<List<VaultEntry>> = _entries.asStateFlow()

    /** id -> base64 encrypted ciphertext */
    private var secrets: Map<String, String> = emptyMap()

    init {
        dir.mkdirs()
        load()
    }

    fun list(): List<VaultEntry> = _entries.value

    fun getEntry(id: String): VaultEntry? = _entries.value.firstOrNull { it.id == id }

    /**
     * Decrypts and returns the password for the given vault entry id.
     * Callers MUST ensure biometric / device unlock gate is passed before invoking or displaying.
     */
    @Synchronized
    fun getPassword(id: String): String? {
        val enc = secrets[id] ?: return null
        return runCatching {
            val decryptedBytes = cipher.decrypt(decode(enc))
            val plain = String(decryptedBytes, Charsets.UTF_8)
            Redactor.registerSecret(plain)
            plain
        }.getOrNull()
    }

    /**
     * Saves or updates a vault entry with its associated password.
     */
    @Synchronized
    fun saveEntry(entry: VaultEntry, password: String): VaultEntry {
        val id = entry.id.ifBlank { UUID.randomUUID().toString() }
        val updated = entry.copy(
            id = id,
            title = entry.title.trim(),
            username = entry.username.trim(),
            url = entry.url?.trim()?.ifBlank { null },
            notes = entry.notes?.trim()?.ifBlank { null },
            updatedAt = clock(),
        )

        // Encrypt password
        val enc = encode(cipher.encrypt(password.toByteArray(Charsets.UTF_8)))
        secrets = secrets + (id to enc)
        Redactor.registerSecret(password)

        val current = _entries.value
        val idx = current.indexOfFirst { it.id == id }
        _entries.value = if (idx >= 0) {
            current.toMutableList().also { it[idx] = updated }
        } else {
            current + updated
        }

        persist()
        return updated
    }

    /**
     * Deletes a vault entry and cleans up its encrypted secret.
     */
    @Synchronized
    fun deleteEntry(id: String): Boolean {
        val current = _entries.value
        if (current.none { it.id == id } && id !in secrets) return false

        _entries.value = current.filterNot { it.id == id }
        secrets = secrets - id
        persist()
        return true
    }

    /**
     * Filter entries matching query across title, username, or website/url.
     */
    fun search(query: String): List<VaultEntry> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return _entries.value
        return _entries.value.filter {
            it.title.lowercase().contains(q) ||
                it.username.lowercase().contains(q) ||
                it.url?.lowercase()?.contains(q) == true
        }
    }

    private fun load() {
        if (metadataFile.isFile) {
            runCatching {
                val element = json.parseToJsonElement(metadataFile.readText())
                val list = element.jsonArray.mapNotNull {
                    val o = it.jsonObject
                    val id = o["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val title = o["title"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val username = o["username"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    val url = o["url"]?.jsonPrimitive?.content
                    val notes = o["notes"]?.jsonPrimitive?.content
                    val updatedAt = o["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L
                    VaultEntry(id = id, title = title, username = username, url = url, notes = notes, updatedAt = updatedAt)
                }
                _entries.value = list
            }
        }

        if (secretsFile.isFile) {
            runCatching {
                val o = json.parseToJsonElement(secretsFile.readText()).jsonObject
                secrets = o.mapValues { it.value.jsonPrimitive.content }
            }
        }
    }

    private fun persist() {
        // Write metadata
        val metadataJson = buildJsonObject {
            putJsonArray("items") {
                _entries.value.forEach { e ->
                    addJsonObject {
                        put("id", e.id)
                        put("title", e.title)
                        put("username", e.username)
                        e.url?.let { put("url", it) }
                        e.notes?.let { put("notes", it) }
                        put("updatedAt", e.updatedAt)
                    }
                }
            }
        }["items"].toString()

        writeAtomically(metadataFile, metadataJson)

        // Write secrets
        val secretsJson = buildJsonObject {
            secrets.forEach { (id, enc) -> put(id, enc) }
        }.toString()

        writeAtomically(secretsFile, secretsJson)
    }

    private fun writeAtomically(file: File, content: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "cannot write ${file.name}" }
        }
    }

    private fun encode(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    private fun decode(s: String): ByteArray = Base64.getDecoder().decode(s)

    interface Owner {
        val vault: PasswordVaultStore
    }

    companion object {
        const val KEYSTORE_ALIAS = "harness-password-vault"
        private const val METADATA_FILE = "vault.json"
        private const val SECRETS_FILE = "vault-secrets.json"

        fun create(context: Context): PasswordVaultStore =
            PasswordVaultStore(
                dir = File(context.applicationContext.filesDir, "vault"),
                cipher = KeystoreKeyCipher(KEYSTORE_ALIAS),
            )
    }
}
