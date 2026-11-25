package com.meenai.mina.device

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.UserManager
import android.text.InputType
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import com.meenai.mina.cap.phone.a11y.ScreenAccessibility
import com.meenai.mina.core.device.CredentialKind
import com.meenai.mina.core.device.DeviceAccess
import com.meenai.mina.core.device.DeviceAccessPort
import com.meenai.mina.core.device.DeviceCredential
import com.meenai.mina.core.device.DeviceState
import com.meenai.mina.settings.DeviceAccessSettings
import com.meenai.mina.settings.KeystoreKeyCipher
import com.meenai.mina.shell.IHarnessShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** App-level only instance: Settings pages are shared with certified MCPs without going through models, screen tools, or run logging. */
class AndroidDeviceAccess(context: Context, helper: () -> IHarnessShell?) {
    val settings = DeviceAccessSettings(File(context.filesDir, "settings"), KeystoreKeyCipher("mina-device-access"))
    val access = DeviceAccess(AndroidDeviceAccessPort(context, helper), settings)
}

internal class AndroidDeviceAccessPort(context: Context, private val helper: () -> IHarnessShell?) : DeviceAccessPort {
    private val power = context.getSystemService(PowerManager::class.java)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val user = context.getSystemService(UserManager::class.java)

    override suspend fun state() = withContext(Dispatchers.IO) {
        DeviceState(power.isInteractive, keyguard.isKeyguardLocked, keyguard.isDeviceLocked,
            keyguard.isDeviceSecure, helper() != null, user.isUserUnlocked)
    }

    override suspend fun wake(): Boolean = withContext(Dispatchers.IO) { runCatching { helper()?.wakeDevice() == true }.getOrDefault(false) }
    override suspend fun dismiss(): Boolean = withContext(Dispatchers.IO) { runCatching { helper()?.dismissDeviceKeyguard() == true }.getOrDefault(false) }

    /**
     * Only recognize the unique password box with matching type in the main screen SystemUI. SIM
     * PIN/unrecognized manufacturer page is not attempted. Credential buttons must only be cast to the
     * SystemUI window, relying on directional injection starting from Android 13; earlier systems
     * reported that they are not supported before occupying the space, leaving no pause.
     */
    override suspend fun credentialReady(kind: CredentialKind): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return@withContext false
        if (!power.isInteractive || !keyguard.isKeyguardLocked || !keyguard.isDeviceLocked) return@withContext false
        val service = ScreenAccessibility.instance.value ?: return@withContext false
        val fields = mutableListOf<AccessibilityNodeInfo>()
        fun visit(n: AccessibilityNodeInfo, depth: Int, sim: Boolean = false) {
            if (depth > 30 || fields.size > 1) return
            val id = n.viewIdResourceName.orEmpty()
            val simChallenge = sim || "sim" in id.lowercase()
            if (n.isVisibleToUser && !simChallenge) {
                val numeric = n.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER
                if (KeyguardFields.matches(kind, id, n.className?.toString().orEmpty(), n.isPassword, n.isEditable, numeric)) fields += n
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { visit(it, depth + 1, simChallenge) }
        }
        service.windowsOn(Display.DEFAULT_DISPLAY).forEach { window ->
            window.root?.takeIf { it.packageName?.toString() == "com.android.systemui" }?.let { visit(it, 0) }
        }
        val field = fields.singleOrNull() ?: return@withContext false
        field.isFocused || field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
    }

    override suspend fun enter(credential: DeviceCredential): Boolean {
        if (!credentialReady(credential.kind)) return false
        return withContext(Dispatchers.IO) {
            val bytes = ByteArray(credential.value.size) { credential.value[it].code.toByte() }
            try { runCatching { helper()?.enterDeviceCredential(credential.kind.ordinal, bytes) == true }.getOrDefault(false) }
            finally { bytes.fill(0) }
        }
    }
}
