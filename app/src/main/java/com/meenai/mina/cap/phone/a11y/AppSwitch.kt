package com.meenai.mina.cap.phone.a11y

/** If the front desk replaced it with another bag, does it count as being "cut away"? */
internal object AppSwitch {
    /** [byTarget]: The new frontend is opened by the target App in its own task (such as sharing to another App), and the wording does not constitute an interruption. */
    data class Switch(val packageName: String, val byTarget: Boolean)

    /**
     * Not counted as being cut away: the system selector, file/photo selector, permission box
     * ([partOfTask]), and the system settings page ([settingsPage], such as the city selection page
     * provided by the system settings) opened by the target App in its own task. Other apps in the
     * target task (including pre-installed browsers and app stores) are still reported, but marked
     * byTarget. [sameTask] is only checked when the package names are different and not selectors. When
     * the package names are the same, only look at [otherCopy]: The same App is installed in two copies
     * (clone, work data), and the top one is replaced by the one that is not the target, it is also
     * considered to be cut away.
     */
    fun switchedTo(
        target: String?,
        current: String?,
        sameTask: () -> Boolean,
        partOfTask: Set<String>,
        settingsPage: (String) -> Boolean,
        otherCopy: () -> Boolean = { false },
    ): Switch? {
        if (target != null && current == target) return if (otherCopy()) Switch(current, byTarget = false) else null
        if (target == null || current == null || current in partOfTask) return null
        if (!sameTask()) return Switch(current, byTarget = false)
        return if (settingsPage(current)) null else Switch(current, byTarget = true)
    }
}
