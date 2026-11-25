package com.meenai.mina.cap.phone.shell

import com.meenai.mina.core.tool.LinkPolicy

/** The two judgments made by the shell service before opening the link do not rely on the Android class, and the unit test can be directly called. */
internal object ShellLinks {
    /** [ShellService.START_INVALID_LINK] is returned when the link is not allowed to be opened, and null is allowed. The distributor has been checked, check again here. */
    fun refuse(action: String?, uri: String?, pkg: String?): Int? =
        if (LinkPolicy.check(action, uri, pkg) != null) ShellService.START_INVALID_LINK else null

    /** Among the apps that can open links, if one of them has a task on the home screen, it is considered to be in use; if none of the apps can be parsed and cannot be seen clearly, it is still considered to be in use. */
    fun inUse(candidates: List<String>, onMainScreen: Set<String>): Boolean =
        candidates.isEmpty() || candidates.any { it in onMainScreen }
}
