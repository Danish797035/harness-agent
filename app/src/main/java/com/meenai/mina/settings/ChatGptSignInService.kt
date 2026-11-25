package com.meenai.mina.settings

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.meenai.mina.HarnessApp
import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.task.TaskService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service during login using ChatGPT: Mina retreats to the background, and the user holds on
 * to the process when logging in in the browser. The system does not freeze it, and only someone can
 * answer the local callback, and the token change is not delayed. Just hang a quiet notification, which
 * can "cancel" this login. Don’t do things by yourself: The login runs in [ChatGptSignIns]. Here you can
 * watch its running. It will stop as soon as the login is completed (success, failure, cancellation,
 * timeout).
 */
class ChatGptSignInService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val signIns get() = (application as HarnessApp).chatGptSignIn
    private var watch: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TaskService.ensureChannels(this)
        // After startForegroundService, you must enter the foreground as soon as possible, even if you
        // have to stop immediately; if you cannot enter the foreground, you will stop and log in as
        // usual (just without this layer of protection)
        val foreground = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification())
            }
        }.onFailure { Log.w(TAG, "sign-in service could not enter the foreground", it) }.isSuccess
        if (intent?.action == ACTION_CANCEL) signIns.cancel()
        if (!foreground) {
            stopSelf()
            return START_NOT_STICKY
        }
        watch?.cancel()
        watch = scope.launch {
            signIns.running.first { !it }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        // If the process is gone and the login is gone, there is no need to rebuild it.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val cancel = PendingIntent.getService(this, 1, Intent(this, ChatGptSignInService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, TaskService.CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(signIns.noticeTitle ?: tr(R.string.chatgpt_signin_notif_title))
            .setContentText(signIns.noticeBody ?: tr(R.string.chatgpt_signin_notif_body))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(TaskService.openApp(this))
            .addAction(Notification.Action.Builder(null, tr(R.string.cancel), cancel).build())
            .build()
    }

    companion object {
        private const val TAG = "MinaChatGpt"
        private const val ACTION_CANCEL = "com.meenai.mina.chatgpt.CANCEL_SIGN_IN"
        /** Notifications related to tasks and computer connections are staggered. */
        private const val NOTIFICATION_ID = 30_002

        /** Called when login starts (the user has just clicked on login, Mina is in the foreground, and can open the front-end service); if it cannot be opened, it will be thrown, and the caller will record it. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, ChatGptSignInService::class.java))
        }
    }
}
