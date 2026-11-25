package com.meenai.mina.cap.phone.shell

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.meenai.mina.cap.phone.PhoneServices
import com.meenai.mina.core.shell.HandoffNonces
import com.meenai.mina.core.shell.HelperHandoff
import com.meenai.mina.core.shell.HelperRestoreReport
import com.meenai.mina.core.shell.NonceSlots
import com.meenai.mina.core.shell.NonceStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.os.ParcelFileDescriptor
import java.io.IOException
import com.meenai.mina.shell.IHarnessShell

/**
 * The location of the built-in assistant Binder in the Mina process. The assistant submits it to the
 * Binder via [HandoffProvider], checks it here and accepts it; a copy is created by
 * [com.meenai.mina.cap.phone.PhoneServices] (the assistant only recognizes one drop point in a process),
 * [BuiltinShellConnection] and the watchdog both look at its [shell].
 */
class HelperHub(context: Context) {
    private val app = context.applicationContext

    private val _shell = MutableStateFlow<IHarnessShell?>(null)
    /** The assistant interface in hand now; it will return to null when the assistant process dies. */
    val shell: StateFlow<IHarnessShell?> = _shell.asStateFlow()

    private val _restores = MutableStateFlow<HelperRestoreReport?>(null)
    /** The record of "Mina was pulled up after being killed" (used for diagnostic information) when the assistant handed over the report; if not pulled, it is null. */
    val restores: StateFlow<HelperRestoreReport?> = _restores.asStateFlow()

    /** The WebView debugging socket of this process is connected through the assistant; IOException is thrown when the assistant is not available, and null when the assistant refuses or cannot connect. */
    fun openDevtoolsSocket(endpoint: String): ParcelFileDescriptor? {
        val sh = shell.value ?: throw IOException("phone helper is not running")
        return sh.openDevtoolsSocket(endpoint)
    }

    /** The process token given to the assistant: As soon as the Mina process dies, the assistant will know from its death notification and remove the changes left by Mina. */
    private val token = Binder()
    private val nonces: HandoffNonces by lazy { HandoffNonces(PrefsNonceStore(app), SystemClock::elapsedRealtime, System::currentTimeMillis) }

    /** New helper prefix via adbd: This one-time password is given to the helper via the standard input of the exec stream. */
    fun issueLaunchNonce(): String = nonces.issueLaunch()

    /** The one-time password written into the "Start with Computer" script (see [HandoffNonces.usbNonce]); the same [offer] is used for handover. */
    fun usbNonce(): String = nonces.usbNonce()

    /** The number of times the password has been invalidated as a whole (see [HandoffNonces.resets]): the "Start with Computer" script is rewritten accordingly. */
    val nonceResets: StateFlow<Int> get() = nonces.resets

    /** Unpairing: All passwords are invalidated (the assistant who is still alive will be rejected and exit next time), and the interface in hand is also released. */
    fun forget() {
        nonces.clear()
        _shell.value = null
    }

    /**
     * The assistant will hand over. Only the shell (uid 2000) is accepted, the self-reported uid/pid is
     * consistent with the caller, the current APK is running, and the password is correct; call Binder
     * again to confirm that it is indeed in the process to be handed over, and finally use the password
     * and change it to the next one.
     */
    @Synchronized
    fun offer(extras: Bundle?, callingUid: Int, callingPid: Int): Bundle {
        val binder = extras?.getBinder(HelperHandoff.KEY_BINDER)
        HelperHandoff.check(
            callingUid, callingPid,
            reportedUid = extras?.getInt(HelperHandoff.KEY_UID, -1) ?: -1,
            reportedPid = extras?.getInt(HelperHandoff.KEY_PID, -1) ?: -1,
            helperApk = extras?.getString(HelperHandoff.KEY_APK),
            appApk = app.applicationInfo.sourceDir,
            hasBinder = binder != null,
        )?.let { return reject(it) }
        val gate = nonces
        val nonce = extras?.getString(HelperHandoff.KEY_NONCE)
        // If the password is incorrect, don’t touch the Binder given by the other party.
        if (!gate.matches(nonce)) return reject(HelperHandoff.Reject.BadNonce)
        val shell = IHarnessShell.Stub.asInterface(binder!!)
        val ids = runCatching { shell.identity() }.getOrNull()
        if (ids == null || ids.size < 2 || ids[0] != callingUid || ids[1] != callingPid) {
            return reject(HelperHandoff.Reject.BinderElsewhere)
        }
        val next = gate.accept(nonce) ?: return reject(HelperHandoff.Reject.BadNonce)
        try {
            binder.linkToDeath({ _shell.compareAndSet(shell, null) }, 0)
        } catch (e: Exception) {
            return reject(HelperHandoff.Reject.NoBinder)
        }
        // A new assistant is replaced: the old one will be ended by the new one, and its death
        // notification will be ignored because it is not the current one.
        _shell.value = shell
        if (extras?.containsKey(HelperHandoff.KEY_RESTORED_AT) == true) {
            _restores.value = HelperRestoreReport(
                extras.getLong(HelperHandoff.KEY_RESTORED_AT), extras.getInt(HelperHandoff.KEY_RESTORES), extras.getLong(HelperHandoff.KEY_GAVE_UP_AT),
            )
        }
        Log.i(TAG, "helper pid $callingPid handed off")
        return Bundle().apply {
            putBoolean(HelperHandoff.KEY_ACCEPTED, true)
            putString(HelperHandoff.KEY_NEXT, next)
            putBinder(HelperHandoff.KEY_TOKEN, token)
        }
    }

    private fun reject(why: HelperHandoff.Reject): Bundle {
        Log.w(TAG, "helper handoff rejected: $why")
        return Bundle().apply {
            putBoolean(HelperHandoff.KEY_ACCEPTED, false)
            putString(HelperHandoff.KEY_REASON, why.name)
        }
    }

    private companion object {
        const val TAG = "MinaHelperHub"
        const val PREFS = "builtin_helper"
    }

    private class PrefsNonceStore(context: Context) : NonceStore {
        private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        override fun load() = NonceSlots(
            prefs.getString("resume", null), prefs.getString("launch", null), prefs.getLong("launchAt", 0),
            prefs.getString("usb", null), prefs.getLong("usbAt", 0),
        )
        // Synchronous writing: Mina may be killed immediately after the assistant gets the new password,
        // and the new process must recognize it
        override fun save(slots: NonceSlots) {
            prefs.edit().putString("resume", slots.resume).putString("launch", slots.launch).putLong("launchAt", slots.launchAt)
                .putString("usb", slots.usb).putLong("usbAt", slots.usbAt).commit()
        }
    }
}

/**
 * The built-in assistant handovers the entry to Binder. It needs to be open to the shell, so it is
 * exported. The list also requires INTERACT_ACROSS_USERS_FULL (the shell has it, but the ordinary app
 * does not). The ordinary app cannot even get the provider; the real check is [HelperHub.offer]. The
 * landing point is the App part ([PhoneServices.of]): the App object is constructed before the provider,
 * but the handover call may arrive earlier than or at the same time as Application.onCreate; safety
 * depends on the fact that the passwords in HarnessApp.phone and [HelperHub] are synchronized and lazy
 * (whoever arrives first builds it, only builds it once), [HelperHub.offer] itself is also locked, and
 * does not rely on onCreate to run first.
 */
open class HandoffProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != HelperHandoff.METHOD_ATTACH) return null
        val ctx = context ?: return null
        return PhoneServices.of(ctx).hub.offer(extras, Binder.getCallingUid(), Binder.getCallingPid())
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
