package com.meenai.mina.settings

import android.content.Context
import android.content.SharedPreferences
import com.meenai.mina.core.shell.A11yLoss
import com.meenai.mina.core.shell.A11yLossRecord
import com.meenai.mina.core.shell.RecoveryCapability
import com.meenai.mina.core.shell.RecoveryConsent
import com.meenai.mina.core.shell.RecoveryConsentStore
import com.meenai.mina.core.shell.RestoreOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The records of several capabilities of "Mobile Phone Operation", as well as the results of automatic
 * recovery from the last time the phone was turned on. The boot broadcast and the settings page are
 * viewed in the same process.
 *
 * Each item has two Boolean values: the original key (such as `accessibility`) is marked as "effective",
 * and the meaning is the same as before (previously, it only wrote true after the command was
 * successful); `<key>_declined` is marked as "turned off by the user". The old version only has the
 * previous key: if it says false, it cannot be distinguished whether it has been untouched or turned
 * off. It will be treated as untouched - if the new key does not exist, it means it has not been turned
 * off, so there is no need to perform additional migrations when upgrading. These items will be opened
 * by default when the connection is available.
 */
class RecoverySettings(private val prefs: SharedPreferences) : RecoveryConsentStore {
    private val _consent = MutableStateFlow(read())
    override val consent: StateFlow<RecoveryConsent> = _consent.asStateFlow()

    private val _lastRestore = MutableStateFlow(readOutcome())
    val lastRestore: StateFlow<RestoreOutcome?> = _lastRestore.asStateFlow()

    private val _lastA11yLoss = MutableStateFlow(readLoss())
    /** The last time Mina was started, it was found that the accessibility service was removed or not bound (for diagnostic information). */
    val lastA11yLoss: StateFlow<A11yLossRecord?> = _lastA11yLoss.asStateFlow()

    /** Mina's accessibility service has been connected: it is only a problem if it is not connected when it is started later, and it is not considered if it has never been set up. */
    var accessibilitySeen: Boolean
        get() = prefs.getBoolean(A11Y_SEEN, false)
        set(v) { if (v != accessibilitySeen) prefs.edit().putBoolean(A11Y_SEEN, v).apply() }

    fun recordA11yLoss(record: A11yLossRecord) {
        prefs.edit().putString(A11Y_LOSS, record.loss.name).putLong(A11Y_LOSS_AT, record.atMs).apply()
        _lastA11yLoss.value = record
    }

    private fun readLoss(): A11yLossRecord? {
        val loss = prefs.getString(A11Y_LOSS, null)?.let { n -> A11yLoss.entries.firstOrNull { it.name == n } } ?: return null
        return A11yLossRecord(loss, prefs.getLong(A11Y_LOSS_AT, 0))
    }

    @Synchronized override fun save(consent: RecoveryConsent) {
        val restore = RecoveryCapability.RestoreAfterReboot
        val restoreChanged = consent.isApplied(restore) != _consent.value.isApplied(restore)
        prefs.edit().apply {
            for ((c, key) in KEYS) {
                putBoolean(key, consent.isApplied(c))
                putBoolean(key + DECLINED, !consent.wants(c))
            }
            if (restoreChanged) remove(LAST_RESTORE)
        }.commit()
        _consent.value = consent
        // After restarting or shutting down, the old startup results no longer indicate the problem.
        if (restoreChanged) _lastRestore.value = null
    }

    fun recordRestore(outcome: RestoreOutcome) {
        prefs.edit().putString(LAST_RESTORE, outcome.name).commit()
        _lastRestore.value = outcome
    }

    private fun read() = RecoveryConsent(
        applied = KEYS.filter { (_, key) -> prefs.getBoolean(key, false) }.keys,
        declined = KEYS.filter { (_, key) -> prefs.getBoolean(key + DECLINED, false) }.keys,
    )

    private fun readOutcome(): RestoreOutcome? =
        prefs.getString(LAST_RESTORE, null)?.let { n -> RestoreOutcome.entries.firstOrNull { it.name == n } }

    companion object {
        private const val PREFS = "builtin_shell_recovery"
        private val KEYS = mapOf(
            RecoveryCapability.Accessibility to "accessibility",
            RecoveryCapability.BatteryWhitelist to "battery_whitelist",
            RecoveryCapability.RestoreAfterReboot to "restore_after_reboot",
            RecoveryCapability.AutoWirelessDebugging to "auto_wireless_debugging",
        )
        private const val DECLINED = "_declined"
        private const val LAST_RESTORE = "last_restore"
        private const val A11Y_SEEN = "a11y_seen"
        private const val A11Y_LOSS = "a11y_loss"
        private const val A11Y_LOSS_AT = "a11y_loss_at"

        /** The read and write preference file in the product (built by [com.meenai.mina.cap.phone.PhoneServices]). */
        fun prefs(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }
}
