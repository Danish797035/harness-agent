package com.meenai.mina.vault

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAuthGateTest {

    private class TestActivity : Activity()

    private class TestContextWrapper(
        private val customBase: Context?,
    ) : ContextWrapper(customBase) {
        override fun getBaseContext(): Context? = customBase
    }

    private class CyclicContextWrapper : ContextWrapper(null) {
        override fun getBaseContext(): Context? = this
    }

    @Test
    fun findActivityReturnsActivityWhenContextIsActivity() {
        val activity = TestActivity()
        assertSame(activity, activity.findActivity())
    }

    @Test
    fun findActivityUnwrapsNestedContextWrappers() {
        val activity = TestActivity()
        val wrappedOnce = TestContextWrapper(activity)
        val wrappedTwice = TestContextWrapper(wrappedOnce)
        val wrappedThrice = TestContextWrapper(wrappedTwice)

        assertSame(activity, wrappedThrice.findActivity())
    }

    @Test
    fun findActivityReturnsNullWhenNoActivityInChain() {
        val leaf = TestContextWrapper(null)
        val wrapper = TestContextWrapper(leaf)

        assertNull(wrapper.findActivity())
    }

    @Test
    fun findActivityTerminatesSafelyOnCyclicContextWrapper() {
        val cyclic = CyclicContextWrapper()
        assertNull(cyclic.findActivity())
    }

    @Test
    fun authenticateWithNullActivityFailsClosed() {
        var succeeded = false
        var errorMessage: String? = null

        DeviceAuthGate.authenticate(
            activity = null,
            onSuccess = { succeeded = true },
            onFailed = { errorMessage = it },
        )

        assertFalse(succeeded)
        assertNotNull(errorMessage)
        assertTrue(errorMessage!!.isNotBlank())
    }

    @Test
    fun authenticateWithUnavailableKeyguardManagerFailsClosed() {
        var succeeded = false
        var errorMessage: String? = null
        val activity = TestActivity()

        DeviceAuthGate.authenticate(
            activity = activity,
            onSuccess = { succeeded = true },
            onFailed = { errorMessage = it },
        )

        assertFalse(succeeded)
        assertEquals("Screen lock is required to access saved passwords", errorMessage)
    }

    @Test
    fun isDeviceSecureReturnsFalseWhenKeyguardUnavailable() {
        assertFalse(DeviceAuthGate.isDeviceSecure(TestActivity()))
    }

    @Test
    fun composeAuthGateWithNullActivityFailsClosedWithoutLaunchingIntent() {
        var succeeded = false
        var launched = false
        var failedMessage: String? = null
        val gate = ComposeAuthGate(
            activity = null,
            launchIntent = { launched = true },
            setPendingCallbacks = { _, _ -> },
        )

        gate.authenticate(
            onFailed = { failedMessage = it },
        ) {
            succeeded = true
        }

        assertFalse(succeeded)
        assertFalse(launched)
        assertNotNull(failedMessage)
    }
}
