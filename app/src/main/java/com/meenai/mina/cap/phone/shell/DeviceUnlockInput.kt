package com.meenai.mina.cap.phone.shell

import android.app.KeyguardManager
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import com.meenai.mina.core.device.CredentialKind
import com.meenai.mina.core.device.DeviceCredential

/**
 * Only called by authenticated Mina Binders. Home screen only; credentials never enter the command line,
 * clipboard, screenshots, or logs. Each keystroke of the certificate is [injectTo] and is only cast to
 * the focus window owned by SystemUI: when there are other windows above the lock screen (Apps displayed
 * when the screen is locked, incoming calls, full-screen notifications) or when the app has been
 * unlocked and returned to the app, the system refuses to inject and the keys will not fall elsewhere.
 * [inject] is only used for bright screen keys without secrets.
 */
internal class DeviceUnlockInput(context: Context, private val inject: (InputEvent) -> Boolean,
    private val injectTo: (InputEvent, Int) -> Boolean, private val command: (List<String>) -> Boolean) {
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    /** The lock screen interface belongs to SystemUI; no credentials are entered when its uid cannot be found. */
    private val systemUiUid: Int? by lazy { runCatching { context.packageManager.getPackageUid(SYSTEM_UI, 0) }.getOrNull() }

    fun wake(): Boolean = key(KeyEvent.KEYCODE_WAKEUP, send = inject) || command(listOf("input", "keyevent", "224"))

    fun dismiss(): Boolean = command(listOf("wm", "dismiss-keyguard"))

    fun enter(kind: Int, bytes: ByteArray): Boolean {
        var chars = CharArray(0)
        var sent = false
        val target = systemUiUid
        val deadline = SystemClock.uptimeMillis() + 8_000
        fun toKeyguard(event: InputEvent): Boolean { sent = true; return injectTo(event, target!!) }
        try {
            if (target == null || !power.isInteractive || !keyguard.isKeyguardLocked || !keyguard.isDeviceLocked) return false
            val type = CredentialKind.entries.getOrNull(kind) ?: return false
            if (bytes.any { it.toInt() !in 32..126 }) return false
            chars = CharArray(bytes.size) { bytes[it].toInt().toChar() }
            if (!DeviceCredential.valid(type, chars)) return false
            val map = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
            val events = if (type == CredentialKind.Password) map.getEvents(chars) ?: return false else null
            // SystemUI's credential input box has been confirmed by the caller. Clear out any remaining
            // half-entries and only try the complete credentials once.
            if (type == CredentialKind.Pin) {
                repeat(16) { if (!key(KeyEvent.KEYCODE_DEL, send = ::toKeyguard)) return false }
            } else if (!key(KeyEvent.KEYCODE_A, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON, ::toKeyguard) ||
                !key(KeyEvent.KEYCODE_DEL, send = ::toKeyguard)) return false
            if (type == CredentialKind.Pin) {
                for (c in chars) {
                    if (SystemClock.uptimeMillis() > deadline || !keyguard.isKeyguardLocked || !power.isInteractive) return false
                    if (!key(KeyEvent.KEYCODE_0 + (c - '0'), send = ::toKeyguard)) return false
                }
            } else {
                for (event in events!!) {
                    event.source = InputDevice.SOURCE_KEYBOARD
                    if (SystemClock.uptimeMillis() > deadline || !keyguard.isKeyguardLocked || !power.isInteractive || !toKeyguard(event)) return false
                }
            }
            // Some systems automatically commit on the last digit. Enter is also only thrown to
            // SystemUI: during this period, it has been unlocked and returned to the App. It will be
            // rejected by the system and will not be sent to the App.
            SystemClock.sleep(200)
            return !keyguard.isKeyguardLocked || !keyguard.isDeviceLocked || key(KeyEvent.KEYCODE_ENTER, send = ::toKeyguard)
        } finally {
            bytes.fill(0); chars.fill('\u0000')
            // The symbols in the password may use modifier keys; they are released only after the key is
            // pressed. They are also only sent to SystemUI and do not send any events to the foreground
            // App.
            if (sent && target != null) for (code in MODIFIERS) {
                val now = SystemClock.uptimeMillis()
                injectTo(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                    InputDevice.SOURCE_KEYBOARD), target)
            }
        }
    }

    private fun key(code: Int, meta: Int = 0, send: (InputEvent) -> Boolean): Boolean {
        val now = SystemClock.uptimeMillis()
        val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
            InputDevice.SOURCE_KEYBOARD)
        return send(down) && send(KeyEvent.changeAction(down, KeyEvent.ACTION_UP))
    }

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"
        val MODIFIERS = intArrayOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT)
    }
}
