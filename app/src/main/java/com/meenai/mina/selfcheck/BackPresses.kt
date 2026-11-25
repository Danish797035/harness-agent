package com.meenai.mina.selfcheck

/**
 * During the self-check, press Return and wait for the page to leave: when you press the input method
 * before, Android will first take away the input method (or clear the words that are not on the screen),
 * and it does not count as one time; pressing [tries] when the input method is not playing will only
 * count as a failure if the page is still there. The maximum number of presses in total is [maxPresses].
 * The input method cannot be closed and will not keep pressing.
 */
internal object BackPresses {
    suspend fun until(
        tries: Int,
        maxPresses: Int,
        keyboardUp: suspend () -> Boolean,
        press: suspend () -> Unit,
        left: suspend () -> Boolean,
    ): Boolean {
        var counted = 0
        var presses = 0
        while (counted < tries && presses < maxPresses) {
            val keyboard = keyboardUp()
            press()
            presses++
            if (left()) return true
            if (!keyboard) counted++
        }
        return false
    }
}
