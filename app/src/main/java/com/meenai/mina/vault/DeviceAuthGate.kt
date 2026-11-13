package com.meenai.mina.vault

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
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

object DeviceAuthGate {

    fun isDeviceSecure(context: Context): Boolean {
        val km = context.getSystemService(KeyguardManager::class.java) ?: return false
        return km.isDeviceSecure
    }

    /**
     * Authenticates using BiometricPrompt (API 30+) with biometric or device credential (PIN/pattern/password).
     */
    fun authenticate(
        activity: Activity,
        title: String = "Unlock Password Vault",
        subtitle: String = "Confirm your identity to view password",
        onSuccess: () -> Unit,
        onFailed: (String) -> Unit = {},
    ) {
        val km = activity.getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isDeviceSecure) {
            // Device does not have screen lock configured; allow access
            onSuccess()
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
                            if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED &&
                                errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                            ) {
                                onFailed(errString?.toString() ?: "Authentication error")
                            }
                        }

                        override fun onAuthenticationFailed() {
                            onFailed("Authentication failed")
                        }
                    },
                )
                return
            } catch (_: Exception) {
                // Fall back to Keyguard credential intent below
            }
        }

        val intent = km.createConfirmDeviceCredentialIntent(title, subtitle)
        if (intent != null) {
            activity.startActivity(intent)
        } else {
            onSuccess()
        }
    }
}

class ComposeAuthGate(
    private val activity: Activity?,
    private val launchIntent: (Intent) -> Unit,
    private val setPendingAction: (() -> Unit) -> Unit,
) {
    fun authenticate(
        title: String = "Unlock Password Vault",
        subtitle: String = "Confirm your identity to view password",
        onSuccess: () -> Unit,
    ) {
        val act = activity ?: run { onSuccess(); return }
        val km = act.getSystemService(KeyguardManager::class.java)
        if (km == null || !km.isDeviceSecure) {
            onSuccess()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val prompt = BiometricPrompt.Builder(act)
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
                    act.mainExecutor,
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                            onSuccess()
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                            // User cancelled or dismissed
                        }

                        override fun onAuthenticationFailed() {}
                    },
                )
                return
            } catch (_: Exception) {
                // Fall back to activity launcher
            }
        }

        val intent = km.createConfirmDeviceCredentialIntent(title, subtitle)
        if (intent != null) {
            setPendingAction(onSuccess)
            launchIntent(intent)
        } else {
            onSuccess()
        }
    }
}

@Composable
fun rememberAuthGate(): ComposeAuthGate {
    val context = LocalContext.current
    val activity = context as? Activity
    var pendingAuthAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingAuthAction?.invoke()
        }
        pendingAuthAction = null
    }

    return remember(activity) {
        ComposeAuthGate(
            activity = activity,
            launchIntent = { launcher.launch(it) },
            setPendingAction = { pendingAuthAction = it },
        )
    }
}
