package com.meenai.mina.cap.web.cdp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * Which websites should switch to mobile version (default is desktop version). According to the host
 * name, remove the www. at the beginning; the subdomain name follows the superior one (I remember
 * example.com, m.example.com also uses the mobile version). It is stored in a JSON file in the App's
 * private directory. If it is broken, it will start from an empty table.
 */
class SiteModes(dir: File) {
    private val file = File(dir, FILE)
    private val mobile = LinkedHashSet<String>()

    init {
        runCatching {
            if (file.isFile) {
                (Json.parseToJsonElement(file.readText()) as? JsonArray)?.forEach { e ->
                    (e as? JsonPrimitive)?.contentOrNull?.let(::key)?.let { mobile += it }
                }
            }
        }
    }

    @Synchronized
    fun isMobile(host: String?): Boolean {
        var h = host?.let(::key) ?: return false
        while (true) {
            if (h in mobile) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }

    /** Make a note of which version of this website you use; when changing to the desktop version, remove the record of the superior domain name as well, otherwise you will follow the superior's lead. */
    @Synchronized
    fun set(host: String, mobile: Boolean) {
        val k = key(host) ?: return
        if (mobile) this.mobile += k
        else this.mobile.removeAll { k == it || k.endsWith(".$it") }
        save()
    }

    @Synchronized
    fun mobileSites(): List<String> = mobile.toList()

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JsonArray(mobile.map(::JsonPrimitive)).toString())
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    private companion object {
        const val FILE = "site_modes.json"

        fun key(host: String): String? =
            host.trim().trimEnd('.').lowercase().removePrefix("www.").takeIf { it.isNotEmpty() && ' ' !in it && '/' !in it }
    }
}
