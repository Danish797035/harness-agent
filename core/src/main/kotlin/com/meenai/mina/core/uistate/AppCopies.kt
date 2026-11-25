package com.meenai.mina.core.uistate

/**
 * The App installed in another user (information such as clones and work information): [packageName]
 * package name, [userId] the number of the user (use it to specify when opening), [userName] the name
 * given to the user by the system (according to the system language, do not hard-code it; it will be
 * empty if it cannot be read).
 */
data class AppCopy(val packageName: String, val userId: Int, val userName: String) {
    /** The name listed for the model: "App name (another copy: the user's name)". */
    fun label(appLabel: String): String = "$appLabel (another copy: ${userName.ifBlank { "user $userId" }})"
}

/**
 * Which apps among different users can be opened by Mina, as well as the listed items. Pure logic,
 * built-in assistant (determining which copies are available to users) and Mina (listing, checking) are
 * shared.
 *
 * Determine whether a user can be used (according to Android's multi-user concept, read the UserInfo and
 * UserProperties given by UserManager and the name given to the tag by the system itself, without
 * looking at the user name): 1. Exclude first, no matter what else: user type with private; system tag
 * name with HIDDEN or PRIVATE; system mark hidden from other apps (profileApiVisibility of
 * UserProperties); guest, demo, temporary, disabled (tag name GUEST / DEMO / EPHEMERAL / DISABLED, press
 * these original tag bits when the tag name cannot be read). Private spaces and other categories are not
 * listed or opened, even if they are running and connected to the current user. 2. Running and unlocked;
 * not in quiet mode (quiet mode when work data is paused). 3. Connected to the current user: the profile
 * group (profileGroupId) is the same as the current user, or the parent user (parentId) reported by the
 * system is the current user. 4. And it can be seen that it is a clone or work data: the user type has
 * clone, or the system tag name has CLONE, or the UserProperties says it shares media with the superior
 * user, or the original work data type is android.os.usertype.profile.MANAGED. If you cannot read any
 * item, it will be considered unsatisfactory: it is better to leave out one item than to touch
 * information that the user does not intend to show to others.
 */
object AppCopies {
    // UserInfo.FLAG_* (original value); only used when the system tag name cannot be read
    const val FLAG_GUEST = 0x4
    const val FLAG_DISABLED = 0x40
    const val FLAG_QUIET_MODE = 0x80
    const val FLAG_EPHEMERAL = 0x100
    const val FLAG_DEMO = 0x200
    private const val EXCLUDED_FLAGS = FLAG_GUEST or FLAG_DISABLED or FLAG_EPHEMERAL or FLAG_DEMO
    private val EXCLUDED_NAMES = setOf("GUEST", "DEMO", "EPHEMERAL", "DISABLED")

    /** UserInfo.NO_PROFILE_GROUP_ID (ie UserHandle.USER_NULL): Not in any data group. */
    const val NO_PROFILE_GROUP = -10000
    const val TYPE_MANAGED = "android.os.usertype.profile.MANAGED"

    /**
     * A user read by the built-in assistant: [flags] UserInfo tags, [flagNames] The names given by the
     * system to these tags (UserInfo.flagsToString, including system-defined ones; if not read, it is
     * null), [type] user type (if not read, it is null), [profileGroupId] data group, [parentId] The
     * superior user reported by the system (if not, it is null), [running] Running, [unlocked] is
     * unlocked, [quiet] is in quiet mode, [mediaShared] UserProperties.isMediaSharedWithParent, [hidden]
     * UserProperties indicates that it is hidden from other apps; these two items are null if they
     * cannot be read.
     */
    data class User(
        val id: Int,
        val flags: Int,
        val type: String?,
        val profileGroupId: Int,
        val running: Boolean,
        val unlocked: Boolean = running,
        val quiet: Boolean = false,
        val mediaShared: Boolean? = null,
        val hidden: Boolean? = null,
        val flagNames: List<String>? = null,
        val parentId: Int? = null,
    )

    /** Why one user can or cannot use it; only [Copy] can use it. */
    enum class Verdict { Copy, Self, Private, Flags, Stopped, Locked, Quiet, NotLinked, NotCopy }

    fun verdict(u: User, current: Int, group: Int): Verdict {
        val type = u.type.orEmpty()
        val names = u.flagNames.orEmpty().map { it.uppercase() }
        return when {
            u.id == current -> Verdict.Self
            type.contains("private", ignoreCase = true) || u.hidden == true ||
                names.any { "HIDDEN" in it || "PRIVATE" in it } -> Verdict.Private
            (if (u.flagNames != null) names.any { it in EXCLUDED_NAMES } else u.flags and EXCLUDED_FLAGS != 0) -> Verdict.Flags
            !u.running -> Verdict.Stopped
            !u.unlocked -> Verdict.Locked
            u.quiet || u.flags and FLAG_QUIET_MODE != 0 || "QUIET_MODE" in names -> Verdict.Quiet
            !(u.profileGroupId == group && group != NO_PROFILE_GROUP) && u.parentId != current -> Verdict.NotLinked
            type.contains("clone", ignoreCase = true) || names.any { "CLONE" in it } || u.mediaShared == true || type == TYPE_MANAGED -> Verdict.Copy
            else -> Verdict.NotCopy
        }
    }

    /** The data group where the current user [current] is located; when the current user cannot read or is not in the group, it is itself. */
    fun groupOf(current: Int, users: List<User>): Int =
        users.firstOrNull { it.id == current }?.profileGroupId?.takeIf { it != NO_PROFILE_GROUP } ?: current

    /** The numbers of the users who can open the app inside. */
    fun usable(current: Int, users: List<User>): List<Int> {
        val group = groupOf(current, users)
        return users.filter { verdict(it, current, group) == Verdict.Copy }.map { it.id }
    }

    /** A line for diagnostic information: each user's number, tag (and tag name given by the system), type, data group, superior, and judgment; excluding name and installed content. */
    fun summary(current: Int, users: List<User>): String {
        val group = groupOf(current, users)
        val others = users.filter { it.id != current }
        val usable = others.count { verdict(it, current, group) == Verdict.Copy }
        if (others.isEmpty()) return "no other users"
        return "${others.size} other, $usable usable: " + others.joinToString("; ") { u ->
            "${u.id} flags=0x${Integer.toHexString(u.flags)}" + (u.flagNames?.let { " (${it.joinToString("|")})" } ?: "") +
                " type=${u.type ?: "?"} group=${u.profileGroupId}" + (u.parentId?.let { " parent=$it" } ?: "") +
                (if (!u.running) " stopped" else if (u.unlocked) " unlocked" else " locked") +
                " -> ${verdict(u, current, group).name.lowercase()}"
        }
    }

    /** Several items of a user in `dumpsys user`: the name given to the mark by the system (in brackets), the parent user (parentId). */
    data class Dumped(val flagNames: List<String>?, val parentId: Int?)

    private val DUMP_USER = Regex("""UserInfo\{(\d+):""")
    private val DUMP_PARENT = Regex("""parentId=(-?\d+)""")
    private val DUMP_FLAGS = Regex("""^\s*Flags:\s*-?\d+\s*\(([^)]*)\)""")

    /** Read `dumpsys user`: the tag name and superior user of each user (number); if the tag name cannot be read, it is null. Used when reflection cannot read the tag name. */
    fun parseDumpsys(output: String): Map<Int, Dumped> {
        val out = linkedMapOf<Int, Dumped>()
        var id: Int? = null
        for (line in output.lineSequence()) {
            val user = DUMP_USER.find(line)
            if (user != null) {
                val u = user.groupValues[1].toInt()
                id = u
                out[u] = Dumped(null, DUMP_PARENT.find(line)?.groupValues?.get(1)?.toInt())
                continue
            }
            val u = id ?: continue
            val flags = DUMP_FLAGS.find(line) ?: continue
            out[u] = out.getValue(u).copy(flagNames = flags.groupValues[1].split('|').map(String::trim).filter(String::isNotEmpty))
        }
        return out
    }

    /**
     * Another one listed: [apps] Apps that can be opened by the current user (package name, display
     * name), [users] those users that can use them (with names), [packages] the packages installed in
     * each user. Only the apps installed by the current user (that match the apps in the list) are
     * listed, arranged in order of [apps].
     */
    fun copies(apps: List<Pair<String, String>>, users: List<UserProfile>, packages: Map<Int, Set<String>>): List<AppCopy> =
        apps.flatMap { (pkg, _) ->
            users.filter { pkg in packages[it.id].orEmpty() }.map { AppCopy(pkg, it.id, it.name) }
        }
}
