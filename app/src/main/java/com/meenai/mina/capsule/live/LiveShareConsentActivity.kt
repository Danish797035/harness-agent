package com.meenai.mina.capsule.live

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle

/**
 * Transparent activity that requests MediaProjection consent for a live share session.
 * Guarantees that each session requests new consent directly from the user and finishes immediately.
 */
class LiveShareConsentActivity : Activity() {

    private var sessionId: String? = null
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
        if (sessionId == null) {
            finish()
            return
        }

        val mpm = getSystemService(MediaProjectionManager::class.java)
        if (mpm == null) {
            LiveShareManager.onConsentDenied(this, sessionId!!)
            finish()
            return
        }

        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CAPTURE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CAPTURE) {
            val id = sessionId
            if (id != null) {
                handled = true
                if (resultCode == RESULT_OK && data != null) {
                    LiveShareManager.onConsentGranted(this, id, resultCode, data)
                } else {
                    LiveShareManager.onConsentDenied(this, id)
                }
            }
            finish()
        }
    }

    override fun onDestroy() {
        if (!handled && sessionId != null) {
            LiveShareManager.onConsentDenied(this, sessionId!!)
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SESSION_ID = "live_share_session_id"
        private const val REQUEST_CAPTURE = 1001
    }
}
