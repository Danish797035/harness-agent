package com.meenai.mina.cap.phone.setup

import android.util.Log
import com.meenai.mina.cap.phone.shell.HelperHub
import com.meenai.mina.capsule.CapsuleMode
import com.meenai.mina.core.shell.A11yObservation
import com.meenai.mina.core.shell.RecoveryCapability
import com.meenai.mina.core.shell.RecoveryConsent
import com.meenai.mina.core.shell.RestoreWish
import com.meenai.mina.core.shell.RestoreWishSender
import com.meenai.mina.shell.IHarnessShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch

/**
 * Submit "Do you want to pull Mina up after being killed?" ([RestoreWish]) to the built-in assistant:
 * capsule mode, whether there are tasks running, "turn on accessibility for me", whether there is Mina's
 * accessibility service in the system list, if any changes, or if the assistant is changed, submit it
 * again through [IHarnessShell.setRestoreWish]. The assistant cannot read Mina's settings. After Mina
 * dies, he will only act on the last one handed to him.
 */
class RestoreWishSync(
    private val hub: HelperHub,
    private val capsuleMode: Flow<CapsuleMode>,
    private val taskRunning: Flow<Boolean>,
    private val consent: Flow<RecoveryConsent>,
    private val accessibility: Flow<A11yObservation>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sender = RestoreWishSender<IHarnessShell> { shell, wish ->
        runCatching { shell.setRestoreWish(wish.flags) }
            .onFailure { Log.w(TAG, "cannot hand the restore wish to the helper", it) }
            .isSuccess
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        var lastOn: Boolean? = null
        // It will take a while for Mina's service that is not in the list to be handed over: when the
        // process is forcibly stopped, the system will remove it first, and cannot change the wish at
        // this time; it has not been (the user has turned it off in the system settings) so it will not
        // be written back for him.
        val accessibilityOn = accessibility.map { it.enabled }.distinctUntilChanged().mapLatest { on ->
            if (!on && lastOn == true) delay(OFF_SETTLE_MS)
            lastOn = on
            on
        }
        val wishes = combine(capsuleMode, taskRunning, consent, accessibilityOn) { mode, busy, c, on ->
            RestoreWish.of(mode == CapsuleMode.Always, busy, c.wants(RecoveryCapability.Accessibility), on)
        }.distinctUntilChanged()
        scope.launch { combine(hub.shell, wishes, ::Pair).collect { (shell, wish) -> sender.update(shell, wish) } }
    }

    private companion object {
        const val TAG = "MinaRestoreWish"
        const val OFF_SETTLE_MS = 3_000L
    }
}
