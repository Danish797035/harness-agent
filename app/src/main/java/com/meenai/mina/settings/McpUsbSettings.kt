package com.meenai.mina.settings

import com.meenai.mina.core.mcp.McpUsb
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64

/**
 * Switch and token via USB computer connection (the kind that the computer opens with adb). Like the
 * local area network ([McpSettings]), each has its own business, and each has its own token.
 *
 * - - A new token is generated when it is opened; if it is already opened and opened again, the original
 * one will be used (the computer reruns the command, and the configured one will not be invalidated).
 * - - After monitoring, [publish] writes the connection information to [infoFile] (external file
 * directory, only Mina starting from Android 11
 * and shell readable).
 * - - Forget the token when shutting down (replace it with a new one next time you open it) and delete
 * the connection information file.
 * - - No cross-reboot: The switch remembers which boot time ([bootId]) it was turned on, and it will be
 * turned off after another boot, and the files left last time can be cleared easily.
 *
 * The token is also written in clear text in the connection information file (which must be read by the
 * computer), so the clear text is also stored in the App's own private directory without additional
 * encryption.
 */
class McpUsbSettings(
    private val dir: File,
    private val bootId: () -> String,
    private val infoFile: () -> File?,
    private val random: SecureRandom = SecureRandom(),
) {
    private val file = File(dir, "mcp-usb.json")
    private val _enabled = MutableStateFlow(false)
    @Volatile private var token: String? = null

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    init {
        val o = runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull()
        val boot = (o?.get("boot") as? JsonPrimitive)?.content
        val t = (o?.get("token") as? JsonPrimitive)?.content
        if (boot != null && boot == bootId() && t != null && t.length >= MIN_TOKEN) {
            token = t
            _enabled.value = true
        } else {
            file.delete()
            runCatching { infoFile()?.delete() }
        }
    }

    /** The token when on; null when off. */
    fun token(): String? = token

    /** Open; if it is already open, the original token will be used. Returns the token at this moment. */
    @Synchronized fun enable(): String {
        token?.let { return it }
        val t = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        dir.mkdirs()
        val tmp = File(dir, file.name + ".tmp")
        tmp.writeText(buildJsonObject { put("boot", bootId()); put("token", t) }.toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        token = t
        _enabled.value = true
        return t
    }

    /** Turn off: The token is invalidated on the spot and the connection information file is deleted. */
    @Synchronized fun disable() {
        token = null
        _enabled.value = false
        file.delete()
        unpublish()
    }

    /** The monitoring sounds: write the connection information to the computer (write the temporary file first and then change the name, so that the computer will not read half of it while it is reading). Does nothing when closed. */
    @Synchronized fun publish(port: Int) {
        val t = token ?: return
        val f = infoFile() ?: return
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(McpUsb.info(port, t, bootId()))
        if (!tmp.renameTo(f)) {
            f.delete()
            check(tmp.renameTo(f)) { "rename failed" }
        }
    }

    /** Not monitoring (not turned on, turned off): Delete the connection information file, and the script on the computer will know that it is not turned on. */
    @Synchronized fun unpublish() {
        runCatching { infoFile()?.delete() }
    }

    private companion object {
        const val MIN_TOKEN = 32
    }
}
