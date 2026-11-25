package com.meenai.mina.core.cap.phone

import com.meenai.mina.core.cap.web.PagePacing
import com.meenai.mina.core.uistate.PlacePace
import kotlin.random.Random

/**
 * "Operation rhythm" in settings: Is the operation in the app fast or human-like? The gesture itself
 * (finger-drop pause, holding time, landing point deviation, arc) are all human-like, see
 * [GesturePacing].
 *
 * - - [FAST]: There is no pause between actions, the full text is written once you type, the page does
 * not stop when changing pages, and there is no upper limit on the number of screens (previous
 * practice);
 * - - [STANDARD] (default): Type one paragraph at a time, pausing for a short time between actions
 * (about
 * 0.5–1.2 seconds for the same screen, about 1–2 seconds for a new screen), Pause for 1.5-2.5 seconds
 * before page change; App knowledge says that apps that limit browsing speed should use the slower one
 * (pause longer, read by the number of words, have an upper limit on the number of screens, and type
 * more slowly);
 * - - [HUMAN]: All apps and web pages use the slowest setting to pause and type; the upper limit on the
 * number of screens is still only for apps that limit browsing speed.
 */
enum class OperatingPace {
    FAST, STANDARD, HUMAN;

    /** This task is performed step by step in the app. */
    fun appPacing(random: Random = Random.Default): AppPacing = when (this) {
        FAST -> AppPacing.Off
        STANDARD -> AppPacing.human(random)
        HUMAN -> AppPacing.human(random, limits = AppPacing.Limits.CAREFUL)
    }

    /**
     * The rhythm of this task is to read page by page on the web page (make up the pause before changing
     * the page). In the past, regardless of the setting, the web page would stop at a human-like 4-7
     * seconds, and you would have to wait half a minute for a single search point after several pages.
     * Now, following the settings: [FAST] will not stop, [STANDARD] will only stop for 1.5-2.5 seconds,
     * and [HUMAN] will still look like a human.
     */
    fun webPacing(random: Random = Random.Default): PagePacing = when (this) {
        FAST -> PagePacing.Off
        STANDARD -> PagePacing.human(
            random,
            limits = PagePacing.Limits(firstDwellMs = 1_500L..2_500L, perPageMs = 300L to 500L, maxDwellMs = 4_000L),
            careful = PagePacing.Limits(firstDwellMs = 4_000L..7_000L, perPageMs = 600L to 1_000L, maxDwellMs = 12_000L),
        )
        HUMAN -> PagePacing.human(random)
    }

    /** The rhythm of typing in the App Knowledge Says [pace]. */
    fun typing(pace: PlacePace, random: Random = Random.Default): TypingPacing = when {
        this == FAST -> TypingPacing.Instant
        this == HUMAN || pace == PlacePace.CAREFUL -> TypingPacing.human(random, TypingPacing.Limits.CAREFUL)
        else -> TypingPacing.human(random)
    }

    companion object {
        val DEFAULT = STANDARD

        /** The saved name (regardless of case); if not recognized, it is null. */
        fun parse(value: String?): OperatingPace? = entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}
