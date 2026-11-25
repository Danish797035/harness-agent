package com.meenai.mina.device

import com.meenai.mina.core.device.CredentialKind

/**
 * General rules: Only input boxes with password signs in SystemUI are recognized; the edit box
 * distinguishes PIN (numeric) and password (non-numeric) according to the input type. The PIN box of the
 * original Android lock screen was drawn by myself (`pinEntry` / `PasswordTextView`). It is not declared
 * editable and is only released according to the original set of names; other non-editable controls and
 * SIM PINs will not be recognized and will not be entered if they are not correct.
 */
internal object KeyguardFields {
    fun matches(kind: CredentialKind, id: String, className: String, password: Boolean, editable: Boolean, numeric: Boolean): Boolean {
        if (!password || !id.startsWith("com.android.systemui:id/") || "sim" in id.lowercase()) return false
        val pinView = id == "com.android.systemui:id/pinEntry" && className == "com.android.keyguard.PasswordTextView"
        return when (kind) {
            CredentialKind.Pin -> pinView || editable && numeric
            CredentialKind.Password -> editable && !numeric
        }
    }
}
