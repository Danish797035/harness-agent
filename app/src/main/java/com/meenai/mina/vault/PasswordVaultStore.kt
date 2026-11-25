package com.meenai.mina.vault

import android.content.Context
import android.util.Log
import com.meenai.mina.core.record.Redactor
import com.meenai.mina.settings.KeyCipher
import com.meenai.mina.settings.KeystoreKeyCipher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

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
    private val lock = ReentrantLock()
    private val metadataFile = File(dir, METADATA_FILE)
    private val secretsFile = File(dir, SECRETS_FILE)
    private val json = Json { ignoreUnknownKeys = true }

    private val _entries = MutableStateFlow<List<VaultEntry>>(emptyList())
    val entries: StateFlow<List<VaultEntry>> = _entries.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    /** Set if an existing vault file failed to parse or decrypt on [load]; blocks destructive overwrites in [persist]. */
    @Volatile
    var loadFailed: Boolean = false
        private set

    /** id -> base64 encrypted ciphertext */
    private var secrets: Map<String, String> = emptyMap()

    init {
        dir.mkdirs()
        lock.withLock {
            load()
        }
    }

    fun list(): List<VaultEntry> = lock.withLock { _entries.value }

    fun getEntry(id: String): VaultEntry? = lock.withLock {
        _entries.value.firstOrNull { it.id == id }
    }

    /**
     * Decrypts and returns the password for the given vault entry id.
     * Callers MUST ensure biometric / device unlock gate is passed before invoking or displaying.
     */
    fun reveal(id: String): String? = getPassword(id)

    /**
     * Decrypts and returns the password for the given vault entry id.
     * Callers MUST ensure biometric / device unlock gate is passed before invoking or displaying.
     */
    fun getPassword(id: String): String? = lock.withLock {
        val enc = secrets[id] ?: return@withLock null
        return@withLock runCatching {
            val decryptedBytes = cipher.decrypt(decode(enc))
            val plain = String(decryptedBytes, Charsets.UTF_8)
            decryptedBytes.fill(0)
            Redactor.registerSecret(plain)
            plain
        }.getOrNull()
    }

    /**
     * Saves or updates a vault entry with its associated password.
     */
    fun saveEntry(entry: VaultEntry, password: String): VaultEntry = lock.withLock {
        check(!loadFailed) {
            _loadError.value ?: "Vault failed to load existing data; refusing to overwrite stored secrets"
        }

        val id = entry.id.ifBlank { UUID.randomUUID().toString() }
        val updated = entry.copy(
            id = id,
            title = entry.title.trim(),
            username = entry.username.trim(),
            url = entry.url?.trim()?.ifBlank { null },
            notes = entry.notes?.trim()?.ifBlank { null },
            updatedAt = clock(),
        )

        val prevEntries = _entries.value
        val prevSecrets = secrets

        // Encrypt password
        val enc = encode(cipher.encrypt(password.toByteArray(Charsets.UTF_8)))
        secrets = prevSecrets + (id to enc)
        Redactor.registerSecret(password)

        val idx = prevEntries.indexOfFirst { it.id == id }
        _entries.value = if (idx >= 0) {
            prevEntries.toMutableList().also { it[idx] = updated }
        } else {
            prevEntries + updated
        }

        try {
            persist()
        } catch (t: Throwable) {
            secrets = prevSecrets
            _entries.value = prevEntries
            throw t
        }
        updated
    }

    /**
     * Deletes a vault entry and cleans up its encrypted secret.
     */
    fun deleteEntry(id: String): Boolean = lock.withLock {
        check(!loadFailed) {
            _loadError.value ?: "Vault failed to load existing data; refusing to overwrite stored secrets"
        }

        val prevEntries = _entries.value
        val prevSecrets = secrets
        if (prevEntries.none { it.id == id } && id !in prevSecrets) return@withLock false

        _entries.value = prevEntries.filterNot { it.id == id }
        secrets = prevSecrets - id
        try {
            persist()
        } catch (t: Throwable) {
            secrets = prevSecrets
            _entries.value = prevEntries
            throw t
        }
        true
    }

    /**
     * Filter entries matching query across title, username, or website/url.
     */
    fun search(query: String): List<VaultEntry> = lock.withLock {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return@withLock _entries.value
        _entries.value.filter {
            it.title.lowercase().contains(q) ||
                it.username.lowercase().contains(q) ||
                it.url?.lowercase()?.contains(q) == true
        }
    }

    private fun load() = lock.withLock {
        var failed = false
        val errors = mutableListOf<String>()

        if (metadataFile.isFile) {
            runCatching {
                val element = json.parseToJsonElement(metadataFile.readText())
                val list = element.jsonArray.map {
                    val o = it.jsonObject
                    val id = o["id"]?.jsonPrimitive?.content
                        ?: throw IllegalArgumentException("Missing entry id in vault metadata")
                    val title = o["title"]?.jsonPrimitive?.content
                        ?: throw IllegalArgumentException("Missing entry title in vault metadata")
                    val username = o["username"]?.jsonPrimitive?.content
                        ?: throw IllegalArgumentException("Missing entry username in vault metadata")
                    val url = o["url"]?.jsonPrimitive?.content
                    val notes = o["notes"]?.jsonPrimitive?.content
                    val updatedAt = o["updatedAt"]?.jsonPrimitive?.longOrNull ?: 0L
                    VaultEntry(id = id, title = title, username = username, url = url, notes = notes, updatedAt = updatedAt)
                }
                _entries.value = list
            }.onFailure { err ->
                failed = true
                errors += "Failed to load vault metadata: ${err.message ?: err::class.simpleName}"
                Log.e(TAG, "Failed to load vault metadata", err)
            }
        }

        if (secretsFile.isFile) {
            runCatching {
                val o = json.parseToJsonElement(secretsFile.readText()).jsonObject
                val parsed = o.mapValues { it.value.jsonPrimitive.content }
                secrets = parsed
                // Verify each ciphertext can be decoded and decrypted so corrupted/wrong-key files
                // set loadFailed and block destructive overwrites in persist().
                for ((entryId, enc) in parsed) {
                    runCatching {
                        val plain = cipher.decrypt(decode(enc))
                        plain.fill(0)
                    }.onFailure { err ->
                        failed = true
                        errors += "Failed to decrypt secret for $entryId: ${err.message ?: err::class.simpleName}"
                        Log.e(TAG, "Failed to decrypt vault secret $entryId", err)
                    }
                }
            }.onFailure { err ->
                failed = true
                errors += "Failed to load vault secrets: ${err.message ?: err::class.simpleName}"
                Log.e(TAG, "Failed to load vault secrets", err)
            }
        }

        loadFailed = failed
        _loadError.value = if (failed) errors.joinToString("; ") else null
    }

    private fun persist() = lock.withLock {
        check(!loadFailed) {
            _loadError.value ?: "Vault failed to load existing data; refusing to overwrite stored secrets"
        }

        // Write secrets before metadata so metadata never references a missing secret after a crash.
        val secretsJson = buildJsonObject {
            secrets.forEach { (id, enc) -> put(id, enc) }
        }.toString()
        writeAtomically(secretsFile, secretsJson)

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
    }

    private fun writeAtomically(file: File, content: String) {
        val parent = file.parentFile ?: dir
        parent.mkdirs()
        val tmp = File.createTempFile("${file.name}.", ".tmp", parent)
        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.fd.sync()
            }
            try {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (t: Throwable) {
            runCatching { tmp.delete() }
            throw t
        }
    }

    private fun encode(b: ByteArray): String = Base64.getEncoder().encodeToString(b)
    private fun decode(s: String): ByteArray = Base64.getDecoder().decode(s)

    interface Owner {
        val vault: PasswordVaultStore
    }

    companion object {
        private const val TAG = "PasswordVaultStore"
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
