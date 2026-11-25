package com.meenai.mina.cap.phone.setup

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The task requires the use of a mobile phone and the built-in assistant but does not raise a hand when
 * running: on the dialogue page, put a line "Go to Settings" under the task card. Web page and file
 * tasks will not encounter this. Created by [com.meenai.mina.cap.phone.PhoneServices], it is shared
 * between tasks and conversation pages.
 */
class PhoneSetupPrompt(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _needed = MutableStateFlow(false)
    val needed: StateFlow<Boolean> = _needed.asStateFlow()

    fun request() {
        _needed.value = true
    }

    fun clear() {
        _needed.value = false
    }

    /** It will automatically enter the boot when you open it for the first time; it will no longer automatically enter after viewing it (completed or clicking "Later"). */
    fun guideSeen(): Boolean = prefs.getBoolean(SEEN, false)

    fun markGuideSeen() {
        prefs.edit().putBoolean(SEEN, true).apply()
    }

    private companion object {
        const val PREFS = "phone_setup"
        const val SEEN = "guide_seen"
    }
}
