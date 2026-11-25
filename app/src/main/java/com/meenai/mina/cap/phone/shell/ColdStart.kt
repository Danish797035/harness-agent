package com.meenai.mina.cap.phone.shell

/**
 * Can the process be terminated when launch_app cold=true? Ending the process will also clear the status
 * of the App on any screen, so as long as it has tasks outside Mina's screen (even in the background),
 * it will not end and will be started normally.
 */
enum class ColdStart {
    RESTART, WARM, IN_USE;

    companion object {
        fun decide(taskDisplays: List<Int>, agentDisplay: Int, visibleOnMain: Boolean): ColdStart = when {
            visibleOnMain -> IN_USE
            taskDisplays.any { it != agentDisplay } -> WARM
            else -> RESTART
        }
    }
}
