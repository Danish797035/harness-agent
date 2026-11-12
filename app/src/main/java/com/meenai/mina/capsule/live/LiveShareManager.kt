package com.meenai.mina.capsule.live

import android.content.Context
import android.content.Intent
import com.meenai.mina.HarnessApp
import com.meenai.mina.R
import com.meenai.mina.cap.phone.a11y.MainScreenCapture
import com.meenai.mina.cap.phone.a11y.ScreenAccessibility
import com.meenai.mina.capsule.CapsuleSecurityPolicy
import com.meenai.mina.i18n.tr
import com.meenai.mina.task.TaskService.Companion.isActive
import kotlinx.coroutines.flow.StateFlow

/**
 * Singleton manager coordinating live screen share across the UI, service, and security policy.
 */
object LiveShareManager {

    private var appCtx: Context? = null

    val engine = LiveShareEngine { sessionId ->
        val ctx = appCtx ?: return@LiveShareEngine
        val intent = Intent(ctx, LiveShareConsentActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(LiveShareConsentActivity.EXTRA_SESSION_ID, sessionId)
        }
        ctx.startActivity(intent)
    }.apply {
        deviceLockChecker = { appCtx?.let { CapsuleSecurityPolicy.isDeviceLocked(it) } ?: false }
    }

    val state: StateFlow<LiveShareState> get() = engine.state

    fun init(context: Context) {
        appCtx = context.applicationContext
    }

    /**
     * Attempts to start a live screen share session.
     * Checks security policy first. Returns false if blocked or unable to start.
     */
    fun start(context: Context): Boolean {
        init(context)
        val app = context.applicationContext as? HarnessApp
        val accessState = app?.appAccess?.current()
        val service = ScreenAccessibility.instance.value

        // Check if currently on a blocked / sensitive app or device locked
        val packages = service?.let { MainScreenCapture.sourcePackages(it) }
        val locked = CapsuleSecurityPolicy.isDeviceLocked(context)
        if (locked) return false
        if (accessState != null && packages != null) {
            if (!CapsuleSecurityPolicy.canPerformScreenAction(packages, locked, accessState)) {
                return false
            }
        }

        engine.startSession()
        return true
    }

    fun stop(context: Context, reason: StopReason = StopReason.UserStop) {
        init(context)
        engine.stopSession(reason)
        val stopIntent = Intent(context, LiveShareService::class.java).apply {
            action = LiveShareService.ACTION_STOP
        }
        context.startService(stopIntent)
    }

    fun onConsentGranted(context: Context, sessionId: String, resultCode: Int, data: Intent) {
        init(context)
        val serviceIntent = Intent(context, LiveShareService::class.java).apply {
            action = LiveShareService.ACTION_START
            putExtra(LiveShareService.EXTRA_SESSION_ID, sessionId)
            putExtra(LiveShareService.EXTRA_RESULT_CODE, resultCode)
            putExtra(LiveShareService.EXTRA_DATA, data)
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }

    fun onConsentDenied(context: Context, sessionId: String) {
        init(context)
        engine.onConsentDenied(sessionId)
    }

    fun onLockEvent(locked: Boolean) {
        engine.onDeviceLockChanged(locked)
    }

    fun onPackagesChanged(packages: Collection<String>?, accessState: com.meenai.mina.core.access.AppAccessState) {
        engine.onForegroundPackagesChanged(packages, accessState)
    }

    fun visionModel(context: Context): com.meenai.mina.core.model.ChatModel? {
        val app = context.applicationContext as? HarnessApp ?: return null
        return app.providers.modelFor(com.meenai.mina.core.model.ModelRole.Vision)
    }

    fun currentTask(context: Context): String? {
        val app = context.applicationContext as? HarnessApp ?: return null
        return app.taskState.runs.value.firstOrNull { it.state.value.isActive() }?.goal
    }
}
