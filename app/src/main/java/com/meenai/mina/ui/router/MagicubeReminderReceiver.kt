package com.meenai.mina.ui.router

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.meenai.mina.core.router.MagicubeLedger

/**
 * BroadcastReceiver for ModelScope Magicube:
 * - MAGICUBE_REMINDER_ALARM: fires at 9:00 IST daily, posts notification if not yet logged in today, and schedules next alarm.
 * - MAGICUBE_MARK_LOGIN: action from reminder notification to mark today's login and dismiss notification.
 */
class MagicubeReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            MagicubeReminder.ACTION_ALARM -> {
                MagicubeReminder.maybeNotify(context)
                MagicubeReminder.scheduleDailyAlarm(context)
            }
            MagicubeReminder.ACTION_MARK_LOGIN -> {
                MagicubeLedger.recordDailyLogin(isAlibabaLinked = MagicubeLedger.isAlibabaLinked)
                val nm = context.getSystemService(NotificationManager::class.java)
                nm?.cancel(MagicubeReminder.NOTIFICATION_ID)
            }
        }
    }
}
