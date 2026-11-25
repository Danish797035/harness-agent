package com.meenai.mina.setup

import com.meenai.mina.cap.phone.setup.BootRestoreHandler
import com.meenai.mina.task.TriggerRuntime
import android.content.Context
import android.content.Intent
import android.app.AlarmManager

/** Entrance to boot broadcast: The full name is registered in the list; it is implemented in the mobile phone capabilities. */
class BootRestoreReceiver : BootRestoreHandler() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_TIME_CHANGED ||
            intent.action == AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ||
            intent.action == Intent.ACTION_TIMEZONE_CHANGED) {
            TriggerRuntime.rearm(context)
            com.meenai.mina.ui.router.MagicubeReminder.scheduleDailyAlarm(context)
        }
        // When starting up, the LAN type computer connection is open and the monitoring is restarted
        // (the USB type does not restart: after changing the boot number, it has been turned off here,
        // and the last connection information is also cleared)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) (context.applicationContext as com.meenai.mina.HarnessApp).mcp.ensureRunning()
    }
}
