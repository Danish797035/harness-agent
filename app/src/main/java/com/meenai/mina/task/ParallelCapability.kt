package com.meenai.mina.task

import com.meenai.mina.R
import com.meenai.mina.i18n.tr

/** At most, several tasks can be run at the same time; [reason] explains why there is only one (displayed on the settings page), and it is empty when there is no limit. */
data class ParallelLimit(val max: Int, val reason: String?)

/**
 * The number of tasks to be parallelized is determined based on the device capabilities: each virtual
 * screen must have independent focus (Android 14+); low-memory devices are not parallelized; if the
 * second screen fails to run this time, it will not be tried again; the rest are limited to 1-3
 * according to the upper limit set by the user.
 */
object ParallelCapability {
    const val MIN_SDK = 34
    const val DEFAULT_MAX = 2
    private const val MAX = 3
    val SECOND_DISPLAY_FAILED: String get() = tr(R.string.parallel_second_display_failed)

    fun detect(sdk: Int, lowRam: Boolean, userMax: Int, secondDisplayFailed: Boolean): ParallelLimit = when {
        sdk < MIN_SDK -> ParallelLimit(1, tr(R.string.parallel_needs_14))
        lowRam -> ParallelLimit(1, tr(R.string.parallel_low_ram))
        secondDisplayFailed -> ParallelLimit(1, SECOND_DISPLAY_FAILED)
        else -> ParallelLimit(userMax.coerceIn(1, MAX), null)
    }
}
