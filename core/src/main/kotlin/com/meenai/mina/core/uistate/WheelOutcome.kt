package com.meenai.mina.core.uistate

/**
 * The result of turning the wheel to a value. [reached] arrived; [value] displayed the value at the
 * moment (cannot be read as null), [from] displayed at the beginning; [attempts] made several actions
 * (input, scroll, and drag each count as one); [method] The last method used: text (direct input),
 * actions (unobstructed scrolling), drag (drag); [error] Reasons for not arriving (ref_expired,
 * not_a_wheel, unknown_value, at_end, not_moving, not_reached...), supplementary description of [hint]
 * port.
 */
data class WheelOutcome(
    val reached: Boolean,
    val value: String?,
    val from: String?,
    val attempts: Int,
    val method: String,
    val error: String? = null,
    val hint: String? = null,
)
