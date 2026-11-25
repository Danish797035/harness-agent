package com.meenai.mina.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Query / Autonomous (get it when a new task starts), save it as a small JSON file; if you don’t
 * understand, press Query. Write the temporary file first and then rename it. If the power is turned off
 * during writing, no bad files will be left behind.
 */
class ApprovalStore(private val file: File) {
    private val _autonomous: MutableStateFlow<Boolean>

    init {
        val o = runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull()
        _autonomous = MutableStateFlow((o?.get("autonomous") as? JsonPrimitive)?.booleanOrNull ?: false)
    }

    /** Autonomous mode: You are not even asked for payment when opening it (only payment is asked first when asking). Please go to [ApprovalControl.setAutonomous] to change it, and the running task will be changed accordingly. */
    val autonomous: StateFlow<Boolean> = _autonomous.asStateFlow()

    @Synchronized fun setAutonomous(on: Boolean) {
        _autonomous.value = on
        save()
    }

    @Synchronized private fun save() {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(buildJsonObject { put("autonomous", _autonomous.value) }.toString())
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
