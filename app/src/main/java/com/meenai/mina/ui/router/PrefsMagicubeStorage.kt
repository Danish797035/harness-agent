package com.meenai.mina.ui.router

import android.content.Context
import android.content.SharedPreferences
import com.meenai.mina.core.router.MagicubeState
import com.meenai.mina.core.router.MagicubeStorage

/**
 * SharedPreferences-based persistence for MagicubeLedger.
 * Saves and restores balance, day keys, Alibaba linked status, and long-term expiry.
 */
class PrefsMagicubeStorage(
    private val prefs: SharedPreferences,
) : MagicubeStorage {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    override fun load(): MagicubeState? {
        if (!prefs.contains(KEY_HAS_STATE)) return null

        val shortTerm = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_SHORT_TERM_BAL, 0L))
        val shortDay = prefs.getString(KEY_SHORT_TERM_DAY, null)
        val loginDay = prefs.getString(KEY_LOGIN_DAY, null)
        val alibabaDay = prefs.getString(KEY_ALIBABA_DAY, null)
        val longTerm = java.lang.Double.longBitsToDouble(prefs.getLong(KEY_LONG_TERM_BAL, 0L))
        val longExpiry = if (prefs.contains(KEY_LONG_EXPIRY)) prefs.getLong(KEY_LONG_EXPIRY, -1L).takeIf { it >= 0 } else null
        val alibabaLinked = prefs.getBoolean(KEY_ALIBABA_LINKED, false)

        return MagicubeState(
            shortTermBalance = shortTerm,
            shortTermDayKey = shortDay,
            isLoginMarkedDayKey = loginDay,
            isAlibabaLinkedDayKey = alibabaDay,
            longTermBalance = longTerm,
            longTermExpiryEpochMs = longExpiry,
            isAlibabaLinked = alibabaLinked,
        )
    }

    override fun save(state: MagicubeState) {
        val editor = prefs.edit()
            .putBoolean(KEY_HAS_STATE, true)
            .putLong(KEY_SHORT_TERM_BAL, java.lang.Double.doubleToRawLongBits(state.shortTermBalance))
            .putString(KEY_SHORT_TERM_DAY, state.shortTermDayKey)
            .putString(KEY_LOGIN_DAY, state.isLoginMarkedDayKey)
            .putString(KEY_ALIBABA_DAY, state.isAlibabaLinkedDayKey)
            .putLong(KEY_LONG_TERM_BAL, java.lang.Double.doubleToRawLongBits(state.longTermBalance))
            .putBoolean(KEY_ALIBABA_LINKED, state.isAlibabaLinked)

        if (state.longTermExpiryEpochMs != null) {
            editor.putLong(KEY_LONG_EXPIRY, state.longTermExpiryEpochMs)
        } else {
            editor.remove(KEY_LONG_EXPIRY)
        }
        editor.apply()
    }

    companion object {
        const val PREFS_NAME = "magicube_ledger"
        private const val KEY_HAS_STATE = "has_state"
        private const val KEY_SHORT_TERM_BAL = "short_term_bal"
        private const val KEY_SHORT_TERM_DAY = "short_term_day"
        private const val KEY_LOGIN_DAY = "login_day"
        private const val KEY_ALIBABA_DAY = "alibaba_day"
        private const val KEY_LONG_TERM_BAL = "long_term_bal"
        private const val KEY_LONG_EXPIRY = "long_expiry"
        private const val KEY_ALIBABA_LINKED = "alibaba_linked"
    }
}
