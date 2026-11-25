package com.meenai.mina.cap.phone.setup

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meenai.mina.cap.phone.PhoneServices
import kotlinx.coroutines.launch

/**
 * Start broadcasting. Only when the user has turned on "Automatic recovery after restart" and Mina still
 * has the authorization to modify the system settings will the action be taken. In other cases, nothing
 * will be done (judgment is [BuiltinShellRecovery.restoreAfterBoot]).
 */
open class BootRestoreHandler : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        val phone = PhoneServices.of(context)
        phone.pairing.scope.launch {
            try {
                runCatching { phone.recovery.restoreAfterBoot() }
            } finally {
                pending.finish()
            }
        }
    }
}
