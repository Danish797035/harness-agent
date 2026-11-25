package com.meenai.mina.browser

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

/**
 * Should the browser page use the mobile version or the computer version:
 * - - The user browses the mobile version by default; the website that changes to the computer version
 * in "More" is recorded as [UserSiteModes], and the computer version will be used next time;
 * - - When "Log in to Mina" (open a website that you haven't logged in to from a frequently used
 * website), the entire tab will appear as Mina does when working ([agentMobile]),
 * The cookie left after logging in is the same version of the website that Mina saw when she was
 * working, and Mina is also logged in. The Agent's own tags do not look here, the ones remembered by the
 * site remain the same (default computer version).
 */
object BrowserIdentity {
    /** Return true to use the mobile version. */
    fun mobile(loginForMina: Boolean, agentMobile: Boolean, userDesktop: Boolean): Boolean =
        if (loginForMina) agentMobile else !userDesktop

    /** Open from frequently used websites: If you haven't logged in yet, go to "Log in for Mina". */
    fun loginFlow(site: FavoriteSite): Boolean = !site.loggedIn
}

/**
 * The user switches to the computer version of the website in the browser page (default mobile version).
 * According to the host name, remove the www. at the beginning; the subdomain name follows the superior
 * one (I remember example.com, m.example.com also uses the computer version). It is stored separately
 * from the Agent's share and does not affect each other. It is stored in a JSON file in the App's
 * private directory. If it is broken, it will start from an empty table.
 */
class UserSiteModes(dir: File) {
    private val file = File(dir, FILE)
    private val desktop = LinkedHashSet<String>()

    init {
        runCatching {
            if (file.isFile) {
                (Json.parseToJsonElement(file.readText()) as? JsonArray)?.forEach { e ->
                    (e as? JsonPrimitive)?.contentOrNull?.let(::key)?.let { desktop += it }
                }
            }
        }
    }

    @Synchronized
    fun isDesktop(host: String?): Boolean {
        var h = host?.let(::key) ?: return false
        while (true) {
            if (h in desktop) return true
            val dot = h.indexOf('.')
            if (dot < 0) return false
            h = h.substring(dot + 1)
        }
    }

    /** Make a note of which version of this website you use; when switching back to the mobile version, delete the record of the upper-level domain name as well, otherwise you will still follow the higher-level domain name. */
    @Synchronized
    fun set(host: String, desktop: Boolean) {
        val k = key(host) ?: return
        if (desktop) this.desktop += k
        else this.desktop.removeAll { k == it || k.endsWith(".$it") }
        save()
    }

    private fun save() {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(JsonArray(desktop.map(::JsonPrimitive)).toString())
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText())
                tmp.delete()
            }
        }
    }

    private companion object {
        const val FILE = "browser_site_modes.json"

        fun key(host: String): String? =
            host.trim().trimEnd('.').lowercase().removePrefix("www.").takeIf { it.isNotEmpty() && ' ' !in it && '/' !in it }
    }
}
