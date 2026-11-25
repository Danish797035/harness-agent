package com.meenai.mina.cap.phone.a11y

import com.meenai.mina.settings.AppAccessStore

/**
 * When the user actively shares the screen of a disabled application, what do the three options on the
 * floating layer correspond to: 1 = Allow only screen sharing (active sharing will be possible in the
 * future, automatic reading and operation are still prohibited), 2 = Allow this application (restore
 * normal calls), 0/Other = Cancel sharing. Return whether to continue this sharing; the permissions will
 * not be changed when canceling. Selecting "Allow sharing only" will not undisable it, and selecting
 * "Allow this app" will clear the independent sharing permission.
 */
internal fun applyShareChoice(choice: Int, pkg: String, store: AppAccessStore): Boolean = when (choice) {
    1 -> { store.setShareAllowed(pkg, true); true }
    2 -> { store.setBlocked(pkg, false); true }
    else -> false
}
