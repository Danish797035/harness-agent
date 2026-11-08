package com.meenai.mina.capsule.live

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.meenai.mina.HarnessApp
import com.meenai.mina.R
import com.meenai.mina.cap.phone.a11y.MainScreenCapture
import com.meenai.mina.cap.phone.a11y.OverlayPlacement
import com.meenai.mina.cap.phone.a11y.OverlayWindow
import com.meenai.mina.cap.phone.a11y.ScreenAccessibility
import com.meenai.mina.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service hosting the active MediaProjection and user-facing indicators (border, stop pill, notification).
 */
class LiveShareService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mediaProjection: MediaProjection? = null
    private var currentSessionId: String? = null

    private var borderWindow: OverlayWindow? = null
    private var stopWindow: OverlayWindow? = null

    private val lockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                // Device locked / screen off -> permanently stop sharing
                stopShare(StopReason.DeviceLocked)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(this)
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(lockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(lockReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopShare(StopReason.UserStop)
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                @Suppress("DEPRECATION")
                val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)

                if (sessionId == null || data == null) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                currentSessionId = sessionId
                startLiveSession(sessionId, resultCode, data)
                return START_NOT_STICKY
            }
            else -> {
                return START_NOT_STICKY
            }
        }
    }

    private fun startLiveSession(sessionId: String, resultCode: Int, data: Intent) {
        val notification = buildNotification()

        val foregroundStarted = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }.onFailure { Log.w(TAG, "could not start foreground service for live share", it) }.isSuccess

        if (!foregroundStarted) {
            LiveShareManager.engine.onConsentDenied(sessionId)
            stopSelf()
            return
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val projection = runCatching { mpm?.getMediaProjection(resultCode, data) }
            .onFailure { Log.w(TAG, "could not obtain MediaProjection", it) }.getOrNull()

        if (projection == null) {
            LiveShareManager.engine.onConsentDenied(sessionId)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        mediaProjection = projection
        projection.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                stopShare(StopReason.SystemTerminated)
            }
        }, Handler(Looper.getMainLooper()))

        LiveShareManager.engine.onConsentGranted(sessionId)
        attachOverlays()
        startPackageObservation()
    }

    private fun attachOverlays() {
        val service = ScreenAccessibility.instance.value ?: return
        if (borderWindow != null) return

        val overlays = service.overlays()
        borderWindow = overlays.open(OverlayPlacement(Gravity.FILL), touchable = false) {
            com.meenai.mina.ui.theme.MinaTheme {
                val state by LiveShareManager.state.collectAsState()
                LiveShareBorderOverlay(state)
            }
        }
        borderWindow?.setFullScreen(true)

        val area = overlays.area()
        stopWindow = overlays.open(
            OverlayPlacement(Gravity.TOP or Gravity.CENTER_HORIZONTAL, y = area.insetTop),
            touchable = true
        ) {
            com.meenai.mina.ui.theme.MinaTheme {
                val state by LiveShareManager.state.collectAsState()
                LiveShareStopOverlay(state, onStop = { stopShare(StopReason.UserStop) })
            }
        }
    }

    private fun detachOverlays() {
        borderWindow?.close()
        borderWindow = null
        stopWindow?.close()
        stopWindow = null
    }

    private fun startPackageObservation() {
        scope.launch {
            val app = applicationContext as? HarnessApp ?: return@launch
            ScreenAccessibility.instance.collectLatest { service ->
                if (service == null) return@collectLatest
                service.windowChanges().collectLatest {
                    val packages = MainScreenCapture.sourcePackages(service)
                    val accessState = app.appAccess.current()
                    LiveShareManager.onPackagesChanged(packages, accessState)
                }
            }
        }
    }

    private fun stopShare(reason: StopReason) {
        detachOverlays()
        runCatching { mediaProjection?.stop() }
        mediaProjection = null

        LiveShareManager.engine.stopSession(reason)

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        detachOverlays()
        runCatching { unregisterReceiver(lockReceiver) }
        runCatching { mediaProjection?.stop() }
        mediaProjection = null
        scope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, LiveShareService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            101,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val action = Notification.Action.Builder(
            null,
            tr(R.string.live_share_stop),
            stopPending
        ).build()

        return Notification.Builder(this, CHANNEL_LIVE_SHARE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(R.string.live_share_title))
            .setContentText(tr(R.string.live_share_desc))
            .setOngoing(true)
            .addAction(action)
            .build()
    }

    companion object {
        private const val TAG = "LiveShareService"
        const val NOTIFICATION_ID = 2004
        const val CHANNEL_LIVE_SHARE = "capsule_live_share"

        const val ACTION_START = "com.meenai.mina.capsule.live.START"
        const val ACTION_STOP = "com.meenai.mina.capsule.live.STOP"

        const val EXTRA_SESSION_ID = "session_id"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"

        fun ensureNotificationChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_LIVE_SHARE,
                tr(R.string.channel_live_share),
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
    }
}
