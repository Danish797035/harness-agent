package com.meenai.mina.core.tool

import com.meenai.mina.core.model.ToolSpec

/**
 * Tools are sent to the model in two layers: those that are always there (core common tools and
 * abilities that are not collected), and groups divided by abilities (when collected, there are only
 * entrance tools with short descriptions; when used for the first time, they are expanded into a full
 * group and replaced with full text, which is always kept in this task). The general tools (takeover,
 * reporting obstacles, reviewing the screen, and multiple steps at a time) that are only provided when
 * the groups with screens are expanded are counted as a group of [SCREENS], and any screen will be
 * expanded when it is expanded.
 *
 * The order is fixed: always → [SCREENS] → groups of each ability (in the order of registration).
 * Expanding a group only changes the part starting from that group, and the previous bytes remain
 * unchanged; the request prefix will be invalidated at most once due to expansion.
 */
internal object ToolGroups {
    /** The group with the screen shared by several groups. */
    const val SCREENS = "screens"

    /** A frozen tool: full text definition, entry definition when closed (not the entry is null), and the group it belongs to (it is always null). */
    class Slot(val full: ToolSpec, val entry: ToolSpec?, val group: String?)

    /**
     * The tools sent to the model at this time: [expanded] is an expanded group (including [SCREENS]);
     * [extra] is a definition given by the loop itself and belongs to a certain group (multiple steps at
     * a time belong to [SCREENS]), ranked at the end of that group, and only given when that group is
     * expanded.
     */
    fun layout(slots: List<Slot>, expanded: Set<String>, extra: Map<String, List<ToolSpec>> = emptyMap()): List<ToolSpec> =
        order(slots).flatMap { g ->
            val members = slots.filter { it.group == g }
            if (g == null || g in expanded) members.map { it.full } + extra[g].orEmpty()
            else members.mapNotNull { it.entry }
        }

    /** The order of each group: always there, [SCREENS], and then in the order of the ability appearing for the first time. */
    fun order(slots: List<Slot>): List<String?> = listOf(null, SCREENS) + slots.mapNotNull { it.group }.filter { it != SCREENS }.distinct()
}
