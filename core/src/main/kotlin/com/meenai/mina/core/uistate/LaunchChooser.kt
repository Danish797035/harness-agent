package com.meenai.mina.core.uistate

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One of the items listed when the system asks "Which one to open?" [clone]: Is it the one in another user (clone, work information)? It can’t be seen that it is null. */
data class ChoiceOption(val label: String, val clone: Boolean?)

/** After opening the app, the system pops up "Which one to open?": [packageName] is the package where the window in question is located, and [options] is the option mentioned for this app. */
data class LaunchChoice(val packageName: String, val options: List<ChoiceOption>) {
    fun toJson(): JsonObject = buildJsonObject {
        put("package", packageName)
        put("options", buildJsonArray {
            for (o in options) add(buildJsonObject { put("label", o.label); o.clone?.let { put("clone", it) } })
        })
    }
}

/**
 * Determine "which one to open": After opening the App, the top window is replaced by another system
 * package window, and the options in the window mention the name of the App. When the same App is
 * installed in two users (avatar, work profile), some systems will first ask which one to use; if not,
 * it will be regarded as not open, and the model will be reopened and asked again and again.
 */
object LaunchChooser {
    /**
     * The window element mentions clickable options for the App name [appLabel]; none of them are null
     * (not asking which one to open). Only approved ones: The description text such as the permission
     * box will also mention the App name, but its button does not have the App name. [profiles] is the
     * name of several other users of this phone: the one with it in the options is another one, and the
     * one with the exact name of the App is the original one.
     */
    fun options(appLabel: String, elements: List<UiElement>, profiles: List<String>): List<ChoiceOption>? {
        val label = key(appLabel)
        val picked = optionIndices(appLabel, elements)?.map { elements[it] } ?: return null
        val others = profiles.map(::key).filter { it.isNotEmpty() }
        val counts = picked.groupingBy { key(it.name) }.eachCount()
        return picked.map { e ->
            val name = key(e.name)
            val clone = when {
                others.any { name.contains(it) } -> true
                // The two names are exactly the same (differentiated only by corner marks): you can’t
                // tell which one is the original one just by looking at the characters.
                counts.getValue(name) > 1 -> null
                name == label -> false
                else -> null
            }
            ChoiceOption(e.name.replace('\n', ' ').trim(), clone)
        }
    }

    /** The subscripts of the options recognized by [options] in [elements] (the order remains unchanged); it is not null to ask which one to open. Used when clicking options. */
    fun optionIndices(appLabel: String, elements: List<UiElement>): List<Int>? {
        val label = key(appLabel)
        if (label.isEmpty()) return null
        return elements.indices.filter { elements[it].let { e -> e.clickable && e.name.isNotBlank() && key(e.name).contains(label) } }.ifEmpty { null }
    }

    private fun key(s: String) = s.filterNot(Char::isWhitespace).lowercase()
}

/** A user of this mobile phone (a line of `pm list users`): [id] user number, [name] name (such as avatar, work information), [running] is running. */
data class UserProfile(val id: Int, val name: String, val running: Boolean)

/** Read the output of `pm list users`, `pm list packages`. */
object AppProfiles {
    private val USER = Regex("""UserInfo\{(\d+):(.*):[0-9a-fA-F]+\}(\s+running)?""")
    private val PACKAGE = Regex("""^package:([A-Za-z0-9_.]+)\s*$""")

    fun parseUsers(output: String): List<UserProfile> = output.lineSequence().mapNotNull { line ->
        val m = USER.find(line) ?: return@mapNotNull null
        UserProfile(m.groupValues[1].toInt(), m.groupValues[2].trim(), m.groupValues[3].isNotEmpty())
    }.toList()

    fun parsePackages(output: String): Set<String> =
        output.lineSequence().mapNotNull { PACKAGE.find(it.trim())?.groupValues?.get(1) }.toSet()

    /** Each package is also installed in several different users: [others] is the user → package installed by that user. */
    fun alsoIn(others: Map<UserProfile, Set<String>>): Map<String, List<String>> {
        val out = linkedMapOf<String, MutableList<String>>()
        for ((user, packages) in others) for (p in packages) out.getOrPut(p) { mutableListOf() } += user.name.ifEmpty { "user ${user.id}" }
        return out
    }
}
