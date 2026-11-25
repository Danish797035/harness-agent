package com.meenai.mina.cap.phone.shell

import java.lang.reflect.Method

/**
 * Keep Mina's virtual screen on. Screens built with OWN_DISPLAY_GROUP (trusted screens starting from
 * Android 13) form a display group of their own and have their own screen pause timing; the injected
 * input does not count as user operations and is left alone. When the screen pause time comes, the group
 * of screens will go to sleep: the screen is no longer synthesized, screenshots remain unchanged, and
 * there is no response when clicking. When the screen is on, it reports a user operation every
 * [PERIOD_MS] (much shorter than the shortest screen-off time allowed by the system), and stops when the
 * screen is released. If [poke] cannot be reported (the system does not have an interface for reporting
 * on the screen), do not try again. Use the screen photo.
 */
internal class DisplayAwake(
    /** Report a user operation for this screen; return false if it cannot be reported. */
    private val poke: (Int) -> Boolean,
    /** Run once immediately and every [PERIOD_MS] thereafter; return to the method of stopping it. */
    private val repeat: (Runnable) -> () -> Unit,
) {
    private val running = HashMap<Int, () -> Unit>()
    @Volatile private var unavailable = false

    fun start(displayId: Int) {
        if (unavailable) return
        val cancel = repeat(Runnable { if (!poke(displayId)) giveUp() })
        synchronized(running) { running.put(displayId, cancel) }?.invoke()
        // When you fail to register for the first time, giveUp may finish running earlier than
        // registration: make up for one stop.
        if (unavailable) stopAll()
    }

    fun stop(displayId: Int) {
        synchronized(running) { running.remove(displayId) }?.invoke()
    }

    fun stopAll() {
        synchronized(running) { running.values.toList().also { running.clear() } }.forEach { it() }
    }

    private fun giveUp() {
        unavailable = true
        stopAll()
    }

    companion object {
        const val PERIOD_MS = 4_000L
        // PowerManager.USER_ACTIVITY_EVENT_TOUCH
        const val EVENT_TOUCH = 2
        // Display.DEFAULT_DISPLAY_GROUP (hidden constant)
        private const val DEFAULT_DISPLAY_GROUP = 0

        /**
         * Do you want to report this screen: only report it if it really forms a group of its own.
         * Checking into the default group (the group where the user's home screen is located) will keep
         * the user's home screen alive. If [groupId] cannot be read, it will be considered as null. In
         * this case, [ownGroupFlag] will be included or not when creating the screen.
         */
        fun needed(flags: Int, ownGroupFlag: Int, groupId: Int?): Boolean =
            flags and ownGroupFlag != 0 && groupId != DEFAULT_DISPLAY_GROUP

        /**
         * The overload in IPowerManager that reports user operations by screen `userActivity(int
         * displayId, long time, int event, int flags)` (starting from Android 12); only the old overload
         * without screen number is null: it is reported to the default group, which will keep the user's
         * home screen alive and is not used.
         */
        fun userActivityMethod(cls: Class<*>): Method? = cls.methods.firstOrNull {
            it.name == "userActivity" && it.parameterTypes.contentEquals(PER_DISPLAY)
        }

        private val PER_DISPLAY = arrayOf<Class<*>>(Int::class.javaPrimitiveType!!, Long::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
    }
}
