package com.meenai.mina.core.tool

/**
 * After completing a certain step in a batch of actions, you have to wait before doing the next step;
 * several items must be established at the same time. The code only performs literal comparison and does
 * not understand the meaning: [appears] The name of an element on the screen contains this character;
 * [gone] There is no element on the screen containing this character; [place] The front-end App package
 * name (mobile phone) or page address or site (webpage) contains this character; [stable] The interface
 * stops and does not change. The maximum waiting time is [timeoutMs].
 */
data class Until(
    val appears: String? = null,
    val gone: String? = null,
    val place: String? = null,
    val stable: Boolean = false,
    val timeoutMs: Long = DEFAULT_MS,
) {
    companion object {
        const val DEFAULT_MS = 8_000L
        const val MAX_MS = 20_000L
    }
}

/**
 * The outcome of waiting: [met] The condition is established; when it is not established, [stopped]
 * explains why it is no longer waiting - [TIMEOUT] The time limit is reached, or an accident is seen in
 * advance: [POPUP] The same App has an extra window (pop-up box) or the page pop-up box, [SYSTEM_DIALOG]
 * The windows of other packages are covered (permissions, system dialog boxes), [APP_SWITCHED] The front
 * desk is replaced by something else App,[NEW_MESSAGE] user chimed in. [waitedMs] is how long you have
 * been waiting.
 */
data class UntilOutcome(val met: Boolean, val stopped: String?, val waitedMs: Long) {
    companion object {
        const val TIMEOUT = "timeout"
        const val POPUP = "popup"
        const val SYSTEM_DIALOG = "system_dialog"
        const val APP_SWITCHED = "app_switched"
        const val NEW_MESSAGE = "new_message"
    }
}
