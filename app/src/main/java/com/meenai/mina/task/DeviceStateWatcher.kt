package com.meenai.mina.task

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.view.Display
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Device status: whether the home screen is inoperable (screen off or locked), and whether the Agent's
 * App task has been moved to the home screen by the user. The system will discard the injected touches
 * after the screen is turned off, so the screen is also treated as "lock screen" when the screen is
 * turned off.
 */
class DeviceStateWatcher(
    context: Context,
    private val topTaskId: (displayId: Int) -> Int,
) {
    private val appContext = context.applicationContext
    private val power = appContext.getSystemService(PowerManager::class.java)
    private val keyguard = appContext.getSystemService(KeyguardManager::class.java)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = LockState({ LockState.Reading(power.isInteractive, keyguard.isKeyguardLocked) }, scope)
    val locked: StateFlow<Boolean> = lock.locked

    // Only broadcast is used as a signal "it's time to take another look". The status itself is read
    // from the system every time and does not rely on broadcast order inference.
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            lock.refresh()
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        lock.refresh() // Changes between before and after registration
    }

    /**
     * Poll the top task of the two screens: [taskId] is not at the top of the virtual screen, but is at
     * the top of the main screen, that is, it was moved by the user. Each "move" is only sent once, and
     * it will be sent again after it is moved back. This round is skipped if the query fails (the shell
     * is disconnected).
     */
    fun taskMovedAway(displayId: Int, taskId: Int): Flow<Unit> = flow {
        var away = false
        while (true) {
            val now = runCatching {
                isMovedAway(topTaskId(displayId), topTaskId(Display.DEFAULT_DISPLAY), taskId)
            }.getOrNull()
            if (now != null) {
                if (now && !away) emit(Unit)
                away = now
            }
            delay(POLL_MS)
        }
    }

    fun close() {
        runCatching { appContext.unregisterReceiver(receiver) }
        scope.cancel()
    }

    companion object {
        const val POLL_MS = 500L

        internal fun isMovedAway(agentTop: Int, mainTop: Int, taskId: Int) =
            taskId >= 0 && agentTop != taskId && mainTop == taskId
    }
}

/**
 * Whether the home screen is inoperable (screen off or locked). When the broadcast comes, it is read
 * again; but when unlocking, the system first sends USER_PRESENT,
 * [android.app.KeyguardManager.isKeyguardLocked]. It will not change until the lock screen interface
 * really retreats. What is read at this moment is still "locked", and there will be no more broadcasts
 * later. So when it reads "lit but locked", as long as someone is looking at this status, it will be
 * read again every [DeviceStateWatcher.POLL_MS] until it reads unlocked or the screen is turned off; it
 * will not be read when the screen is turned off and waits for SCREEN_ON.
 */
internal class LockState(private val read: () -> Reading, scope: CoroutineScope) {
    data class Reading(val interactive: Boolean, val keyguardLocked: Boolean) {
        val locked get() = !interactive || keyguardLocked
    }

    @Volatile private var last = read()
    private val _locked = MutableStateFlow(last.locked)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    fun refresh() {
        last = read()
        _locked.value = last.locked
    }

    init {
        scope.launch {
            _locked.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { watched ->
                while (watched) {
                    delay(DeviceStateWatcher.POLL_MS)
                    if (last.interactive && last.keyguardLocked) refresh()
                }
            }
        }
    }
}
