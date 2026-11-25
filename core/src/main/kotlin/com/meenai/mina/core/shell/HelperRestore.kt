package com.meenai.mina.core.shell

/**
 * Mina's wish to the built-in assistant: whether to pull up its own process after it is killed
 * ([revive]), and whether to write Mina's accessibility service back to the system list
 * ([accessibility]) before pulling it up. The assistant cannot read Mina's settings and only recognizes
 * the one that Mina handed in through Binder for the last time (see [RestoreWishSender]).
 */
data class RestoreWish(val revive: Boolean, val accessibility: Boolean) {
    /** Integer passed via AIDL. */
    val flags: Int get() = (if (revive) REVIVE else 0) or (if (accessibility) ACCESSIBILITY else 0)

    companion object {
        const val REVIVE = 1
        const val ACCESSIBILITY = 2

        /** When the assistant wakes up and Mina has not handed in her wish: do nothing. */
        val NONE = RestoreWish(revive = false, accessibility = false)

        fun fromFlags(flags: Int) = RestoreWish(flags and REVIVE != 0, flags and ACCESSIBILITY != 0)

        /**
         * It is worth pulling up the capsule after it is killed if it is set to always be displayed or
         * if there is a task running; otherwise, if the user forces it to stop, it will just stay there.
         * The accessibility service only writes back when the user has not turned off "Turn on
         * accessibility for me" ([accessibilityWanted]) in Mina, and there is indeed Mina
         * ([accessibilityOn]) in the system list at the moment: The user turned it off in the system
         * settings while Mina was alive, and will not turn it on for him.
         */
        fun of(capsuleAlways: Boolean, taskRunning: Boolean, accessibilityWanted: Boolean, accessibilityOn: Boolean): RestoreWish {
            val revive = capsuleAlways || taskRunning
            return RestoreWish(revive, revive && accessibilityWanted && accessibilityOn)
        }
    }
}

/**
 * Mina side: Give your wish to the assistant in your hand. Submit it only after you change your
 * assistant (new Binder) or your wish changes; if you fail to submit it, it will not be remembered and
 * you will submit it again next time. [send] returns whether it has been delivered.
 */
class RestoreWishSender<T : Any>(private val send: (T, RestoreWish) -> Boolean) {
    private var shell: T? = null
    private var sent: RestoreWish? = null

    @Synchronized
    fun update(current: T?, wish: RestoreWish) {
        if (current == null) {
            shell = null
            sent = null
            return
        }
        if (current === shell && wish == sent) return
        if (send(current, wish)) {
            shell = current
            sent = wish
        } else {
            shell = null
            sent = null
        }
    }
}

/** What the assistant told Mina during the handover: the last time it was pulled up, the number of times it was pulled up in the recent window, and the last time it was stopped because it was pulled too hard (wall clock milliseconds, 0 means none). */
data class HelperRestoreReport(val lastRestoredAtMs: Long, val recent: Int, val gaveUpAtMs: Long) {
    val empty: Boolean get() = lastRestoredAtMs == 0L && gaveUpAtMs == 0L
}

/**
 * Assistant side: When Mina's process is gone and has not returned after [GRACE_MS], decide whether to
 * pull it up. Just look at the last [RestoreWish] and [windowMs] handed in by Mina. It can be pulled at
 * most [maxAttempts] times. If it is pulled, it will die immediately, so stop and wait for the window to
 * pass.
 */
class HelperRestorePolicy(
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val windowMs: Long = WINDOW_MS,
) {
    enum class Decision {
        Restore,
        /** Mina doesn't want it (the capsule is not always displayed and there are no tasks running). */
        NotWanted,
        /** While waiting for a while, Mina herself came back (or was handing over). */
        AppBack,
        /** I've been pulling too hard recently: Mina died as soon as I started pulling and stopped pulling. */
        BackedOff,
    }

    private val attempts = ArrayDeque<Long>()
    private var lastRestoredAt = 0L
    private var gaveUpAt = 0L

    @Synchronized
    fun decide(wish: RestoreWish, appBack: Boolean, now: Long): Decision {
        if (appBack) return Decision.AppBack
        if (!wish.revive) return Decision.NotWanted
        prune(now)
        if (attempts.size >= maxAttempts) {
            gaveUpAt = now
            return Decision.BackedOff
        }
        attempts.addLast(now)
        lastRestoredAt = now
        return Decision.Restore
    }

    @Synchronized
    fun report(now: Long): HelperRestoreReport {
        prune(now)
        return HelperRestoreReport(lastRestoredAt, attempts.size, gaveUpAt)
    }

    private fun prune(now: Long) {
        // It is also cleared when the clock goes backwards: it does not count if it cannot be seen
        // clearly.
        while (attempts.isNotEmpty() && (now - attempts.first() >= windowMs || now < attempts.first())) attempts.removeFirst()
    }

    companion object {
        /** Wait this long after Mina dies: the system or the user may reopen it immediately, so there is no need for an assistant to do it. */
        const val GRACE_MS = 8_000L
        const val MAX_ATTEMPTS = 3
        const val WINDOW_MS = 10 * 60_000L
    }
}

/** Before the assistant pulls Mina up, write back the accessibility service command (the way the original Android settings are written). */
object RestoreCommands {
    const val ENABLE_ACCESSIBILITY = "settings put secure accessibility_enabled 1"

    /** If there is no Mina in the list, add it (other services will not move), if there is, just turn on the main switch; [current] is the original output read by `settings get`. */
    fun accessibility(current: String?, component: String): List<String> =
        if (AccessibilityList.contains(current, component)) listOf(ENABLE_ACCESSIBILITY)
        else PermissionCommands.writeAccessibility(AccessibilityList.with(current, component))
}

/** The accessibility service problem seen when Mina is started: it is removed from the system list, or it is in the list but has never been bound. */
enum class A11yLoss { Removed, Unbound }

/** Accessibility service issue and time (wall clock milliseconds) last seen when Mina started. */
data class A11yLossRecord(val loss: A11yLoss, val atMs: Long)

object A11yStartCheck {
    /** How long to wait for the service to connect: When the process first starts, the system usually binds within a few seconds. */
    const val CONNECT_WAIT_MS = 5_000L

    /**
     * [initial] is what Mina looks like when it first wakes up, [connectedSoon] is whether
     * [CONNECT_WAIT_MS] is connected; [expected] is false (the service has never been connected, for
     * example, it has not been set up), it is not a problem.
     */
    fun loss(initial: A11yObservation, connectedSoon: Boolean, expected: Boolean): A11yLoss? = when {
        !expected || initial.connected -> null
        !initial.enabled -> A11yLoss.Removed
        !connectedSoon -> A11yLoss.Unbound
        else -> null
    }
}
