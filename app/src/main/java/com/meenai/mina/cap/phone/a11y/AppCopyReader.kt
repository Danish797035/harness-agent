package com.meenai.mina.cap.phone.a11y

import com.meenai.mina.cap.phone.shell.ShellService
import com.meenai.mina.core.uistate.AppCopies
import com.meenai.mina.core.uistate.AppCopy
import com.meenai.mina.core.uistate.AppProfiles
import com.meenai.mina.core.uistate.UserProfile
import com.meenai.mina.shell.IHarnessShell

/** Ask the built-in assistant: another App that can be opened (installed in another user such as a clone or work profile). */
internal object AppCopyReader {
    /** The user where Mina is located: the original App is here; the other users have another copy. */
    val myUser: Int get() = android.os.Process.myUid() / 100_000 // UserHandle.PER_USER_RANGE

    /**
     * The built-in assistant determines which copies can be used by users (see [AppCopies] for rules),
     * then checks what is installed in those user copies, and matches it with [apps] that the current
     * user can open; the name is given by the system in `pm list users` (depending on the system
     * language). If not asked, it is null, and if there is no other copy, it is empty.
     */
    fun read(shell: IHarnessShell, apps: List<Pair<String, String>>): List<AppCopy>? {
        val ids = shell.appCopyUsers()?.getIntArray(ShellService.KEY_COPY_USERS) ?: return null
        if (ids.isEmpty()) return emptyList()
        val names = AppProfiles.parseUsers(shell.listUsers().orEmpty()).associate { it.id to it.name }
        val packages = ids.associateWith { AppProfiles.parsePackages(shell.listPackages(it) ?: return null) }
        return AppCopies.copies(apps, ids.map { UserProfile(it, names[it].orEmpty(), running = true) }, packages)
    }
}
