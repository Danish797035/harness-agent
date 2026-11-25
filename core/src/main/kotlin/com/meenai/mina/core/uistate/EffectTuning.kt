package com.meenai.mina.core.uistate

/**
 * All thresholds for judging action effects are gathered in one place. They are all predetermined
 * initial values, which need to be calibrated according to the operations recorded on the real machine
 * (the interface and results before and after the action). When making changes, look at the samples of
 * ActionEffectTest and FrameDiffTest together. Coordinates are normalized to 0–999.
 */
object EffectTuning {
    // ---- Interface structure ----
    /** Obviously changed: at least this many changes... */
    const val MAJOR_MIN = 3
    /** ...and account for at least this proportion of the elements. */
    const val MAJOR_SHARE = 0.10
    /** The same input box has changed words: the distance between the front and back centers is no more than this far. */
    const val NEAR = 50
    /** When reading back, identify an input box: the centers are no more than this far apart. */
    const val SAME_FIELD = 120
    /** When there are less than this many information-rich elements (self-drawn interface), large-area screen changes can be considered obvious changes. */
    const val FEW_ELEMENTS = 3

    // ---- Picture (circumstantial evidence; see FrameTuning for grid and threshold) ----
    /** The coordinate action depends on the size of the area (half width) around the landing point. */
    const val POINT_REGION = 60

    // ---- wait ----
    /** When the port does not wait for the interface in the action, the upper limit of the stability of the interface before the effect is determined. */
    const val SETTLE_MS = 1_500L
    /** If it seems the same, wait this long and watch it again (the interface may be updated asynchronously). */
    const val RECHECK_MS = 700L
    /** It takes the most time to take a look before taking action; if it is too late, just judge based on the facts of the port. */
    const val PROBE_MS = 1_000L
    /** The maximum amount of time it will take to judge the effect after the action (waiting for the additional reading interface); when it arrives, draw conclusions based on the existing facts. */
    const val BUDGET_MS = 2_500L
}
