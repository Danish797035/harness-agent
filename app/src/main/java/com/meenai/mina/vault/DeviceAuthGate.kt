package com.meenai.mina.vault

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * Unwraps nested [ContextWrapper] instances to find the hosting [Activity], or `null` if none exists.
 */
fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    val visited = HashSet<Context>(4)
    while (ctx is ContextWrapper && visited.add(ctx)) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return ctx as? Activity
}

object DeviceAuthGate {

    fun isDeviceSecure(context: Context): Boolean {
        val km = context.getSystemService(KeyguardManager::class.java) ?: return false
        return km.isDeviceSecure
    }

    /**
     * Authenticates using BiometricPrompt (API 30+) with biometric or device credential (PIN/pattern/password),
     * falling back to an [ActivityResultContracts.StartActivityForResult] launcher when supplied.
     * Never fails open: any missing activity, insecure device, error, or cancellation invokes [onFailed].
     */
    fun authenticate(
        activity: Activity?,
        title: String = "Unlock Password Vault",
        subtitle: String = "Confirm your identity to view password",
        onSuccess: () -> Unit,
        onFailed: (String) -> Unit = {},
        launchCredential: ((Intent, () -> Unit, (String) -> Unit) -> Boolean)? = null,
    ) {
        if (activity == null) {
            onFailed("Host activity unavailable for authentication")
            return
        }

        val km = activity.getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isDeviceSecure) {
            onFailed("Screen lock is required to access saved passwords")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val prompt = BiometricPrompt.Builder(activity)
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setAllowedAuthenticators(
                        BiometricManager.Authenticators.BIOMETRIC_STRONG or
                            BiometricManager.Authenticators.DEVICE_CREDENTIAL,
                    )
                    .build()

                val cancellationSignal = CancellationSignal()
                prompt.authenticate(
                    cancellationSignal,
                    activity.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                            onSuccess()
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                            onFailed(errString?.toString()?.ifBlank { null } ?: "Authentication cancelled or failed")
                        }

                        override fun onAuthenticationFailed() {
                            // Retryable biometric mismatch (finger/face); BiometricPrompt stays open for another try.
                        }
                    },
                )
                return
            } catch (_: Exception) {
                // Fall back to ActivityResultLauncher credential prompt below if available
            }
        }

        @Suppress("DEPRECATION")
        val intent = km.createConfirmDeviceCredentialIntent(title, subtitle)
        if (intent != null && launchCredential != null && launchCredential(intent, onSuccess, onFailed)) {
            return
        }

        onFailed("Device credential authentication is unavailable")
    }
}

class ComposeAuthGate(
    private val activity: Activity?,
    private val launchIntent: (Intent) -> Unit,
    private val setPendingCallbacks: (() -> Unit, (String) -> Unit) -> Unit,
) {
    /**
     * Prompts the user for device biometric/credential verification.
     * Note: [onSuccess] stays the trailing parameter so Compose call sites keep working cleanly.
     */
    fun authenticate(
        title: String = "Unlock Password Vault",
        subtitle: String = "Confirm your identity to view password",
        onFailed: (String) -> Unit = {},
        onSuccess: () -> Unit,
    ) {
        DeviceAuthGate.authenticate(
            activity = activity,
            title = title,
            subtitle = subtitle,
            onSuccess = onSuccess,
            onFailed = onFailed,
            launchCredential = { intent, successCb, failedCb ->
                setPendingCallbacks(successCb, failedCb)
                launchIntent(intent)
                true
            },
        )
    }
}

@Composable
fun rememberAuthGate(): ComposeAuthGate {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    var pendingSuccess by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingFailure by remember { mutableStateOf<((String) -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val successCb = pendingSuccess
        val failedCb = pendingFailure
        pendingSuccess = null
        pendingFailure = null
        if (result.resultCode == Activity.RESULT_OK) {
            successCb?.invoke()
        } else {
            failedCb?.invoke("Authentication cancelled or failed")
        }
    }

    return remember(activity) {
        ComposeAuthGate(
            activity = activity,
            launchIntent = { launcher.launch(it) },
            setPendingCallbacks = { onSuccess, onFailed ->
                pendingSuccess = onSuccess
                pendingFailure = onFailed
            },
        )
    }
}
