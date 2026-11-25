package com.meenai.mina.core.cap.phone

/**
 * When a system command is used to do something that a specialized tool does (click, slide, type, press
 * keys, open an app), the results will remind you to switch back to that tool: the specialized tool is
 * done on Mina's screen, and the effect will be verified after it is done, and the input sent by the
 * shell will not be verified. The first time, only the reminder will be executed as usual; after this
 * task is reminded, the commands to do these types of things will not be executed, and only the reminder
 * ([refuses]) will be replied. Sharing text using the shell will never be executed, pointing to the tool
 * for sharing text. Commands such as checking status and reading settings are not affected.
 */
internal object CommandSteer {
    enum class Purpose(val feedbackKey: String) {
        Gesture("system_command.use_tap"), Type("system_command.use_type"), Key("system_command.use_key"), Launch("system_command.use_launch"),
        Share("system_command.use_share"),
    }

    /** The command [purpose] will not be executed this time: sharing will not be executed; the rest will not be executed after this task has been reminded ([steered]). */
    fun refuses(purpose: Purpose?, steered: Boolean): Boolean = purpose == Purpose.Share || (purpose != null && steered)

    /** Each `input …` subcommand in the command (can be preceded by input source and -d screen number). */
    private val INPUT = Regex("""(?:^|[;&|(\s])input\s+(?:(?:touchscreen|touchpad|keyboard|mouse|stylus|trackball|dpad|gamepad|joystick|rotaryencoder|touchnavigation)\s+)?(?:-d\s+\S+\s+)?([a-z]+)""")
    private val LAUNCH = Regex("""(?:^|[;&|(\s])(?:am\s+(?:start|start-activity)\b|cmd\s+activity\s+start(?:-activity)?\b|monkey\s+(?:.*\s)?-p\s)""")
    private val GESTURES = setOf("tap", "swipe", "draganddrop", "motionevent", "press", "roll")
    /** Start sharing: write all actions (android.intent.action.SEND) or just write -a SEND. */
    private val SHARE = Regex("""(?:android\.intent\.action\.SEND|-a\s+["']?SEND)(?![A-Za-z_])""")
    /** What is being shared is a file (with STREAM): the tool for sharing text cannot be used, and it will be counted as a normal startup. */
    private const val STREAM = "android.intent.extra.STREAM"

    /**
     * What type of specialized tool the command is emulating; neither is null. When typing and clicking
     * are in the same command, they are counted as typing (what should be used for typing); shared text
     * is ranked first: it will not be executed.
     */
    fun purpose(command: String): Purpose? {
        val subs = INPUT.findAll(command).map { it.groupValues[1] }.toSet()
        val launches = LAUNCH.containsMatchIn(command)
        return when {
            launches && SHARE.containsMatchIn(command) && STREAM !in command -> Purpose.Share
            "text" in subs -> Purpose.Type
            subs.any { it in GESTURES } -> Purpose.Gesture
            subs.any { it == "keyevent" || it == "keycombination" } -> Purpose.Key
            launches -> Purpose.Launch
            else -> null
        }
    }
}
