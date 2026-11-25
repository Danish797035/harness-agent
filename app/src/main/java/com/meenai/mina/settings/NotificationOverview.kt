package com.meenai.mina.settings

import android.app.NotificationManager
import com.meenai.mina.task.TaskService

/** What a notification channel looks like now (only depending on the importance in the system). */
enum class ChannelLevel {
    /** The channel is turned off (or the main switch is turned off). */
    Off,
    /** will pop up on the screen. */
    HeadsUp,
    /** Just enter the notification bar quietly. */
    Quiet,
}

/** A line in a channel in the notification page: [warn] If true, it means that it is inconsistent with expectations (what should pop up does not pop up), and it must be marked. */
data class ChannelRow(val channelId: String, val level: ChannelLevel, val warn: Boolean)

/** The overall status of notifications is displayed on the right side of the "Notifications" row in settings. */
sealed interface NotificationSummary {
    data object Normal : NotificationSummary
    /** [count] channels should be popped up but are not. */
    data class Lowered(val count: Int) : NotificationSummary
    /** The app’s main notification switch is turned off. */
    data object AllOff : NotificationSummary
}

data class NotificationOverview(
    val appAllowed: Boolean,
    val attention: ChannelRow,
    val result: ChannelRow,
    val progress: ChannelRow,
    val summary: NotificationSummary,
) {
    companion object {
        /**
         * The status of each row and the overall status are derived from the master switch and the
         * importance of the three channels. If the importance level is empty, it means that the channel
         * has not been established yet, and it will be calculated based on the level when it was
         * established. "Requires your processing" and "Task Result" should pop up. If not "will pop up",
         * just mark a warning (it counts as closed); "Task Progress" being quiet or closed will not
         * affect the task, so it will not be marked. When the main switch is turned off, the three lines
         * are "closed" and there is no warning (the problem is with the main switch).
         */
        fun of(appAllowed: Boolean, attention: Int?, result: Int?, progress: Int?): NotificationOverview {
            fun row(id: String, importance: Int?, shouldPopUp: Boolean): ChannelRow {
                val level = if (!appAllowed) ChannelLevel.Off else levelOf(importance ?: initialImportance(id))
                return ChannelRow(id, level, warn = appAllowed && shouldPopUp && level != ChannelLevel.HeadsUp)
            }
            val a = row(TaskService.CHANNEL_ATTENTION, attention, shouldPopUp = true)
            val r = row(TaskService.CHANNEL_RESULT, result, shouldPopUp = true)
            val p = row(TaskService.CHANNEL_PROGRESS, progress, shouldPopUp = false)
            val lowered = listOf(a, r, p).count { it.warn }
            val summary = when {
                !appAllowed -> NotificationSummary.AllOff
                lowered > 0 -> NotificationSummary.Lowered(lowered)
                else -> NotificationSummary.Normal
            }
            return NotificationOverview(appAllowed, a, r, p, summary)
        }

        fun levelOf(importance: Int): ChannelLevel = when {
            importance == NotificationManager.IMPORTANCE_NONE -> ChannelLevel.Off
            importance >= NotificationManager.IMPORTANCE_HIGH -> ChannelLevel.HeadsUp
            else -> ChannelLevel.Quiet
        }

        private fun initialImportance(id: String): Int =
            TaskService.CHANNEL_IMPORTANCE[id] ?: NotificationManager.IMPORTANCE_DEFAULT
    }
}
