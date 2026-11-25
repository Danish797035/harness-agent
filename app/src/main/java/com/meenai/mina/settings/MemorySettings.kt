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

/**
 * Three switches of "Memory and Skills": [useMemory] master switch (off = no retrieval, no injection, no
 * extraction, no memory tools); [autoRemember] "automatically remember what I said"; [learnSkills]
 * "learn skills from completed tasks" (on by default, the user must accept what they have learned before
 * using it).
 */
data class MemorySwitches(val useMemory: Boolean = true, val autoRemember: Boolean = true, val learnSkills: Boolean = true)

/**
 * The switch is saved as a small JSON file; if you don't understand it, press the default value. Files
 * saved in the old version do not have [LEARN_DEFAULT_APPLIED]: At that time, "Learn Skills" was off by
 * default, and it was opened once when reading; after that, it was brought in as soon as it was saved,
 * and it remained closed when the user manually turned it off.
 */
class MemorySettings(private val file: File) {
    private val _switches = MutableStateFlow(read())
    val switches: StateFlow<MemorySwitches> = _switches.asStateFlow()

    @Synchronized fun update(transform: (MemorySwitches) -> MemorySwitches) {
        val next = transform(_switches.value)
        file.parentFile?.mkdirs()
        file.writeText(buildJsonObject {
            put("useMemory", next.useMemory); put("autoRemember", next.autoRemember); put("learnSkills", next.learnSkills)
            put(LEARN_DEFAULT_APPLIED, true)
        }.toString())
        _switches.value = next
    }

    private fun read(): MemorySwitches {
        val o = runCatching { Json.parseToJsonElement(file.readText()) as? JsonObject }.getOrNull() ?: return MemorySwitches()
        fun b(k: String, d: Boolean) = (o[k] as? JsonPrimitive)?.booleanOrNull ?: d
        val def = MemorySwitches()
        val upgraded = b(LEARN_DEFAULT_APPLIED, false)
        return MemorySwitches(b("useMemory", def.useMemory), b("autoRemember", def.autoRemember),
            if (upgraded) b("learnSkills", def.learnSkills) else def.learnSkills)
    }

    private companion object {
        const val LEARN_DEFAULT_APPLIED = "learnDefaultApplied"
    }
}
