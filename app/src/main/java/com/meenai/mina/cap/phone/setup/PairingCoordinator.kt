package com.meenai.mina.cap.phone.setup

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.meenai.mina.cap.phone.a11y.ScreenAccessibility
import com.meenai.mina.cap.phone.PhoneServices
import com.meenai.mina.core.shell.BuiltinShellControl
import com.meenai.mina.core.shell.PairingCode
import com.meenai.mina.ui.PhoneSetupCopy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch

/**
 * The whole process of pairing: find the pairing window, read the pairing code ([PairingCodeReader]) by
 * yourself when it is open without any obstacles, pop up the notification box with the input box if it
 * cannot be read, give the code to the built-in assistant [control], and display the result. Follow the
 * process, not the interface (Mina's interface is no longer in the foreground when the user looks at the
 * pairing code in the system settings): Create a copy by [com.meenai.mina.cap.phone.PhoneServices].
 */
class PairingCoordinator(context: Context, private val control: BuiltinShellControl) {
    private val app = context.applicationContext
    /** Actions such as pairing, retrying, and unpairing are also run here: they must be completed even when the interface is closed. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var watch: Job? = null
    private val deadline = SlidingDeadline(WINDOW_MS) { SystemClock.elapsedRealtime() }
    /** What is displayed on the notification at the moment; check it before reminding again. If it has been replaced by something else (for example, you just filled in the code), the old one will no longer play. */
    @Volatile private var shown: PairingNotice? = null
    private val _repairing = MutableStateFlow(false)
    /** The user clicked "Repair" and the new pairing has not yet started: the assistant has to wait for the new pairing even though it is running. */
    val repairing: StateFlow<Boolean> = _repairing
    /**
     * After filling in the code, pairing has not started (from [submit] to [pair] when the status is
     * changed to "Pairing"): During this period, the notification will always be "Pairing". [begin] in
     * [submit] returns the status of the search window to "Looking" on the spot. Otherwise, the
     * notification will be counted according to the status before filling in the code (when the pairing
     * fails, it will be "Pairing failed", and when the wrong code is refilled, the input box will beep
     * again). The status update notification does not end at this point, see [PairingNotice.follow].
     */
    @Volatile private var submitted = false

    fun startRepair() { _repairing.value = true }

    fun cancelRepair() { _repairing.value = false }

    /** Start a pairing: Once the status changes to "pairing", end the waiting for re-pairing (to avoid being judged as completed by the old running status) and the waiting after filling in the code ([submitted]). */
    suspend fun pair(code: String, port: Int?) = coroutineScope {
        val job = launch(start = CoroutineStart.UNDISPATCHED) { control.startPairing(code, port) }
        _repairing.value = false
        submitted = false
        job.join()
    }

    /** Start looking at the matching; if you are already looking, recalculate the time limit from now on to [WINDOW_MS], and do not start over again (to avoid being notified and restarting from the beginning). */
    @Synchronized
    fun begin() {
        deadline.extend()
        control.startDiscovery()
        if (watch?.isActive == true) return
        watch = scope.launch {
            val reader = launch { PairingCodeReader.run(app, control, this@PairingCoordinator) }
            val notices = launch {
                // Each of the three items is sent to combine by a coroutine, and the order of sending is
                // not necessarily the order of modification (the "pairing" that was modified first may
                // arrive later): each time, the current value is calculated. Read in the reverse order
                // of writing. When reading the value written later, the one written first will also be
                // read: submit first writes submit and then returns the status of the search window to
                // "looking for". pair writes "pairing" first and then clears repairing and submit.
                combine(control.status, control.pairingDiscovery, _repairing) { _, _, _ ->
                    val d = control.pairingDiscovery.value
                    val pending = submitted
                    val r = _repairing.value
                    PairingNotice.follow(control.status.value, d, r, pending)
                }
                    .distinctUntilChanged()
                    .transformWhile { emit(it.first); !it.second }
                    // As soon as the status changes, the pop-ups and reminders that have not reached the
                    // point will be discarded.
                    .collectLatest { notice -> notice?.let { post(it) } }
            }
            if (!deadline.await(notices)) {
                notices.cancel()
                cancelNotice()
            }
            reader.cancel()
            control.stopDiscovery()
        }
    }

    /** Send [notice]; if no one cares, play again after a while. There is an upper limit on the number of times. */
    private suspend fun post(notice: PairingNotice) {
        delay(PairingNotice.delayBefore(notice, autoRead = ScreenAccessibility.instance.value != null))
        show(notice)
        var sent = 0
        while (true) {
            delay(PairingNotice.realertAfter(notice, sent) ?: return)
            if (shown != notice) return
            show(notice)
            sent++
        }
    }

    private fun show(notice: PairingNotice) {
        shown = notice
        PairingNotifier.show(app, notice)
    }

    private fun cancelNotice() {
        shown = null
        PairingNotifier.cancel(app)
    }

    @Synchronized
    fun end() {
        watch?.cancel()
        watch = null
        _repairing.value = false
        control.stopDiscovery()
        cancelNotice()
    }

    /** The code filled in the notification or guidance page. If the number is not 6 digits, you will be prompted to refill it without trying. If it is correct, it will be immediately changed to "pairing" to prevent the input box from spinning in circles. */
    fun submit(raw: String, port: Int?): Boolean {
        val code = PairingCode.normalize(raw)
        if (code == null) {
            show(PairingNotice.EnterCode(port, PhoneSetupCopy.NOTIFY_CODE_INVALID))
            return false
        }
        submitted = true
        begin()
        show(PairingNotice.Working)
        scope.launch { pair(code, port) }
        return true
    }

    private companion object {
        /** The pairing window is open for a limited time; if it has not been matched for so long since the last [begin], it will not be searched again, and it will start again with the next [begin]. */
        const val WINDOW_MS = 10 * 60_000L
    }
}

/** Notify the placement point of the input box. */
open class PairingCodeInput : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(PairingNotifier.KEY_CODE)?.toString() ?: return
        val port = intent.getIntExtra(PairingNotifier.EXTRA_PORT, -1).takeIf { it > 0 }
        PhoneServices.of(context).pairing.submit(code, port)
    }
}
