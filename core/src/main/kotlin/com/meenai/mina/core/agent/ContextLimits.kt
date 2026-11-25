package com.meenai.mina.core.agent

/**
 * Several lines on a model context window. All are calculated based on the **whole request** (system
 * prompt word + tool definition + message, the same caliber as the input token reported by the service
 * provider), the fixed overhead is not deducted here, it is calculated from the side of the amount
 * ([UsageMeter]), and is only counted once. The trigger point is only calculated at [of].
 *
 * - - [usable]: The window deducts the reservation for output (up to [MAX_OUTPUT_RESERVE], a quarter of
 * the window for small windows).
 * - - [margin]: The margin between the trigger point and [usable] (up to [MARGIN_TOKENS], the small
 * window is one-quarter of [usable]); it is also the hysteresis after compression,
 * After pressing, it will be at least this long before pressing again, not at every step.
 * - - [trigger]: The current request is compressed once if it exceeds it. Not lower than
 * [MIN_TRIGGER_SHARE] of the window, not higher than [usable].
 * - - [target]: One compression should compress the entire request below it ([usable] of
 * [AFTER_COMPACTION], and at least one [margin] lower than the trigger point).
 */
class ContextLimits private constructor(
    val window: Int, val usable: Int, val trigger: Int, val margin: Int, val target: Int,
    val minTrigger: Int,
) {
    companion object {
        /** The upper limit of tokens reserved for model output. */
        const val MAX_OUTPUT_RESERVE = 20_000
        /** The upper limit of the margin left between the trigger point and the available window. */
        const val MARGIN_TOKENS = 13_000
        /** A compression should be below this ratio of the available window. */
        const val AFTER_COMPACTION = 0.5
        /** The lower limit of the trigger point (proportion of the window): If you give other trigger points, it will not be so low that it will be suppressed at the beginning. */
        const val MIN_TRIGGER_SHARE = 0.5

        /**
         * [window]: Model context length. [triggerOverride]: The trigger point given separately by the
         * model (token of the entire request); limited to the lower limit and [usable].
         */
        fun of(window: Int, triggerOverride: Int? = null): ContextLimits {
            val w = window.coerceAtLeast(0)
            val usable = w - minOf(MAX_OUTPUT_RESERVE, w / 4)
            val margin = minOf(MARGIN_TOKENS, usable / 4)
            val floor = minOf((w * MIN_TRIGGER_SHARE).toInt(), usable)
            val trigger = (triggerOverride ?: (usable - margin)).coerceIn(floor, usable)
            val target = minOf((usable * AFTER_COMPACTION).toInt(), trigger - margin).coerceAtLeast(0)
            return ContextLimits(w, usable, trigger, margin, target, floor)
        }
    }
}
