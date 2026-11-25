package com.meenai.mina.capsule

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether Mina's own interface (dialogue page, web page takeover page, etc. any activity) is visible at
 * this moment: Count the activities that have onStart and have not yet onStop. When rebuilding such as
 * screen rotation, the old one is stopped first and the new one is started again, and the middle time
 * does not count as leaving the front desk. It is registered by the App when it starts and is only
 * called back on the main thread.
 */
class AppForeground : Application.ActivityLifecycleCallbacks {
    private var started = 0
    /** The number of instances that were stopped due to rebuilding and new instances have not been started yet. */
    private var recreating = 0
    private val _visible = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = _visible.asStateFlow()

    override fun onActivityStarted(activity: Activity) {
        if (recreating > 0) recreating-- else started++
        _visible.value = started > 0
    }

    override fun onActivityStopped(activity: Activity) {
        if (activity.isChangingConfigurations) recreating++ else started = (started - 1).coerceAtLeast(0)
        _visible.value = started > 0
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
