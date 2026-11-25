package com.meenai.mina.core.uistate


/**
 * A set of verb tools with the same name for a screen (mobile phone, webpage...): the name is
 * `<id>_<verb>`, the parameter structure is the same, and the instructions and allowed values are
 * given separately. Each one will be given a copy if it has the ability to have a screen; when the port
 * is not connected, the tool will still be registered (you can find it by name), but it will not be
 * provided.
 */
class ScreenFamily(
    /** The id of the ability is also the prefix of the name of this set of tools. */
    val id: String,
    /** Read the prefix of the screen number of this screen; if you cannot identify which screen the number belongs to, press it. */
    val snapshotPrefix: String,
    /** The "Where" field is reported in the screen reading results. */
    val placeKey: String = APP_PLACE,
    /** press_key The key that can be pressed; null No limit. */
    val keys: Set<Int>? = null,
    /** Description given to the model when [keys] is not allowed. */
    val keysHint: String? = null,
    /** The experience path records its actions and replays them. */
    val routable: Boolean = false,
    /** The result in this set is a text tool, which can be adjusted by `mina phone` in the sandbox. */
    val sandboxCallable: Boolean = false,
    /** The description of these verbs uses its own copy of this screen (prompts/tools/<id>_<verb>.md), and the rest use a shared template. */
    val ownPrompts: Set<String> = emptySet(),
    /** Instructions for the model (feedback button) when nothing is opened on this screen. */
    val emptyHint: String = "empty_screen",
) {
    /** The name of the tool in this set that performs the [base] action. */
    fun tool(base: String) = "${id}_$base"

    companion object {
        const val APP_PLACE = "currentApp"
        const val SITE_PLACE = "currentSite"
    }
}

/** A screen connected to a port is provided by the ability to have a screen. */
class Surface(
    val family: ScreenFamily,
    /** Screen reading and actions of this screen go through it. */
    val screen: ScreenPort,
    /** How to write the declared app field on this screen: if not, throw ArgError; if yes, return the standard writing method. */
    val declaredTarget: (String) -> String,
    /** Confirm what is written in the declared app field on this screen (used in the tool parameter description). */
    val targetNote: String,
    /** The current version of the screen written in the running status (such as the mobile version or computer version of the web page); if not, it is null. */
    val mode: (suspend () -> String?)? = null,
) {
    val id: String get() = family.id
}

/**
 * Which screen is being operated: when a call is in progress, it is the screen it works on ([enter] to
 * [leave]); when there is no call in progress, it is the screen that was last successfully operated, and
 * when the task starts, it is the first screen. It is only used to take the default value where the
 * screen is not named (confirmation, takeover, verification of evidence, etc. interface stability); no
 * calls are forwarded. Calls can be nested (actions in page scripts).
 */
class SurfaceFocus(val surfaces: List<Surface>) {
    init {
        require(surfaces.isNotEmpty()) { "need at least one surface" }
    }

    /** The screen that was last successfully operated. */
    @Volatile private var settled: Surface = surfaces.first()
    private val acting = ArrayDeque<Surface>()

    val current: Surface get() = synchronized(acting) { acting.lastOrNull() } ?: settled

    operator fun get(id: String): Surface? = surfaces.firstOrNull { it.id == id }

    /** At the beginning of a call, the effect is on the [id] screen; if it is not a screen or is not provided, the current screen is used. */
    fun enter(id: String?) {
        val s = id?.let(::get) ?: current
        synchronized(acting) { acting.addLast(s) }
    }

    /** That call ends; when [succeeded], its screen becomes the most recently operated screen, and the failure is not counted. */
    fun leave(succeeded: Boolean) {
        val s = synchronized(acting) { acting.removeLastOrNull() } ?: return
        if (succeeded) settled = s
    }
}
