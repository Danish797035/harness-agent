package com.meenai.mina.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64

/**
 * Computer connection (MCP) settings: switches and ports are saved in a small JSON file (press close if
 * you can’t read it); the access token is encrypted by [KeyCipher] like the service provider’s Key and
 * is saved in a separate file, which is generated when it is used for the first time. Off by default.
 */
class McpSettings(private val dir: File, private val cipher: KeyCipher, private val random: SecureRandom = SecureRandom()) {
    private val file = File(dir, "mcp.json")
    private val tokenFile = File(dir, "mcp-token.bin")
    private val _enabled: MutableStateFlow<Boolean>
    @Volatile private var cachedToken: String? = null

    /** The listening port; if it is not written in the file, use [DEFAULT_PORT]. */
    val port: Int

    init {
        val o = runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull()
        _enabled = MutableStateFlow((o?.get("enabled") as? JsonPrimitive)?.booleanOrNull ?: false)
        port = (o?.get("port") as? JsonPrimitive)?.intOrNull?.takeIf { it in 1024..65535 } ?: DEFAULT_PORT
    }

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Synchronized fun setEnabled(on: Boolean) {
        _enabled.value = on
        save()
    }

    /** The current access token; generate one and save it if it is not yet available (or cannot be read). */
    @Synchronized fun token(): String {
        cachedToken?.let { return it }
        val stored = runCatching { if (tokenFile.isFile) String(cipher.decrypt(tokenFile.readBytes()), Charsets.UTF_8) else null }.getOrNull()
        return stored?.takeIf { it.length >= MIN_TOKEN }?.also { cachedToken = it } ?: regenerate()
    }

    /** Change to a new token: the old one becomes invalid on the spot, and the connected computer must be replaced with a new one before it can be used again. */
    @Synchronized fun regenerate(): String {
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        dir.mkdirs()
        val tmp = File(dir, tokenFile.name + ".tmp")
        tmp.writeBytes(cipher.encrypt(token.toByteArray(Charsets.UTF_8)))
        Files.move(tmp.toPath(), tokenFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        cachedToken = token
        return token
    }

    @Synchronized private fun save() {
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(buildJsonObject { put("enabled", _enabled.value); put("port", port) }.toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val DEFAULT_PORT = 8765
        private const val MIN_TOKEN = 32
    }
}
