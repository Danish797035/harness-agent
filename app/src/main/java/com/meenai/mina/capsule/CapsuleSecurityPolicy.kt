package com.meenai.mina.capsule

import com.meenai.mina.core.access.AppAccessState
import com.meenai.mina.core.access.AppCategory

/**
 * Security policy for screen interactions: live share, screen capture, circle-to-ask,
 * and on-screen automation (yahin karo).
 *
 * Ensures that blocked apps, Aadhaar, bank, and UPI apps automatically disallow
 * screen reading and automated actions. Also enforces that when the screen is locked,
 * screen operations are immediately stopped.
 */
object CapsuleSecurityPolicy {

    private val SENSITIVE_PACKAGE_PREFIXES = listOf(
        "in.gov.uidai",
        "in.org.npci",
        "com.phonepe",
        "net.one97.paytm",
        "com.google.android.apps.nbu.paisa.user",
        "com.sbi.",
        "com.hdfc",
        "com.icici",
        "com.axis",
        "com.kotak",
        "com.pnb",
        "com.bank",
    )

    private val SENSITIVE_KEYWORDS = listOf(
        "aadhaar", "maadhaar", "uidai",
        "bank", "banking",
        "upi", "bhim", "paytm", "phonepe", "gpay",
    )

    /**
     * Returns true if the package is sensitive (Aadhaar, banking, UPI, Finance category, or user-blocked).
     */
    fun isSensitiveApp(packageName: String?, state: AppAccessState): Boolean {
        if (packageName.isNullOrBlank()) return false
        if (!state.available) return true
        if (packageName in state.blocked) return true

        val classification = state.classifications[packageName]
        if (classification?.category == AppCategory.Finance) return true

        val lowerPkg = packageName.lowercase()
        if (SENSITIVE_PACKAGE_PREFIXES.any { lowerPkg.startsWith(it) }) return true
        if (SENSITIVE_KEYWORDS.any { lowerPkg.contains(it) }) return true

        return false
    }

    /**
     * Checks if screen operations (sharing, capturing, circle-to-ask, automated taps) can proceed.
     * Returns false if device is locked or if any package is sensitive / blocked.
     */
    fun canPerformScreenAction(
        packages: Collection<String>?,
        locked: Boolean,
        state: AppAccessState,
    ): Boolean {
        if (locked) return false
        if (packages.isNullOrEmpty()) return false
        for (pkg in packages) {
            if (isSensitiveApp(pkg, state)) return false
            if (!state.canShare(pkg)) return false
        }
        return true
    }

    /**
     * Reads the real lock status of the device via KeyguardManager.
     */
    fun isDeviceLocked(context: android.content.Context): Boolean {
        val km = context.getSystemService(android.content.Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        return km?.isDeviceLocked == true
    }
}
