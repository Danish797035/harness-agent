package com.meenai.mina.cap.phone

import android.content.Context
import com.meenai.mina.cap.phone.setup.BuiltinShellManager
import com.meenai.mina.cap.phone.setup.BuiltinShellRecovery
import com.meenai.mina.cap.phone.setup.PairingCoordinator
import com.meenai.mina.cap.phone.setup.PhoneSetupPrompt
import com.meenai.mina.cap.phone.shell.BuiltinShellConnection
import com.meenai.mina.cap.phone.shell.HelperHub
import com.meenai.mina.cap.phone.shell.SharedShell
import com.meenai.mina.core.shell.BuiltinShellControl
import com.meenai.mina.settings.RecoverySettings

/**
 * The services of mobile phone capabilities in the Mina process are created by the App and given by
 * [Owner]: built-in assistant (handover point, pairing and watchdog, automatic recovery and
 * authorization), the shell connection shared by tasks and startup recovery, pairing process, and "go to
 * settings" prompt. The helper process (HelperMain, ShellService) runs under the shell identity, does
 * not have an App object, and does not pass here.
 */
class PhoneServices(context: Context) {
    private val app = context.applicationContext

    /** The assistant hands over the Binder placement point: the assistant only recognizes one placement point in a process. */
    val hub = HelperHub(app)

    /** Pairing, adbd boot assistant, watchdog. */
    val shellManager = BuiltinShellManager(app, hub) { recoverySettings.consent.value }

    /** Built-in assistant itself (status, pairing, retry, unpairing). */
    val shellControl: BuiltinShellControl get() = shellManager.engine

    /** Automatic recovery and authorization switch settings. */
    val recoverySettings: RecoverySettings by lazy { RecoverySettings(RecoverySettings.prefs(app)) }

    /** Automatic recovery and those authorization switches. */
    val recovery: BuiltinShellRecovery by lazy { BuiltinShellRecovery(app, shellManager.commandRunner, recoverySettings, shellControl) }

    /** The shell connection that the task shares with startup recovery. */
    val sharedShell = SharedShell { BuiltinShellConnection(hub) { shellManager.need() } }

    /** The entire pairing process. */
    val pairing: PairingCoordinator by lazy { PairingCoordinator(app, shellControl) }

    /** The task requires the use of mobile phones and assistants but there is no prompt when running. */
    val setupPrompt = PhoneSetupPrompt(app)

    /** Give the application in the process. */
    interface Owner {
        val phone: PhoneServices
    }

    companion object {
        fun of(context: Context): PhoneServices = (context.applicationContext as? Owner)?.phone
            ?: error("the Application (${context.applicationContext.javaClass.name}) must implement PhoneServices.Owner to give the phone's shared services")
    }
}
