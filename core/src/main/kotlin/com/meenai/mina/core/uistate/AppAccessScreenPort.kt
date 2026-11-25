package com.meenai.mina.core.uistate

import com.meenai.mina.core.access.AppAccessDenied
import com.meenai.mina.core.access.AppAccessPolicy
import com.meenai.mina.core.access.AppAccessState
import kotlinx.coroutines.delay

/**
 * Application access boundary of the mobile port: Each [ScreenPort] member is explicitly written here
 * (without `by` delegation), and members added later to the port will not be missed without checking;
 * check this member by member in the test.
 *
 * Check the ownership of the foreground window once before and after reading the screen and taking
 * action; when opening by target (App, share, link to a specified App), only look at the target. Window
 * probe [packages] is read-only and does not capture content or create screens for inspection. Do
 * nothing when the disabled list is empty.
 *
 * Ask less about windows: [snapshot], [peek] directly use the window ownership in the read screen to
 * check without asking any questions; when [windowToken] (the count that changes as soon as the window
 * changes) is given, the checked results will be used directly if the window does not change, the list
 * does not change, and does not exceed [CHECK_TTL_MS] (the screenshots immediately following the same
 * screen reading, and the preview frame after frame).
 */
class AppAccessScreenPort(
    private val inner: ScreenPort,
    override val appAccess: AppAccessPolicy,
    private val packages: suspend () -> List<String?>,
    private val protectedUi: () -> Boolean = { false },
    private val ownPackage: String? = null,
    private val windowToken: (() -> Long)? = null,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) : ScreenPort {
    /** The window count when the last check passed, the list version, whether Mina's own interface is being protected, and the time. */
    private class Verified(val token: Long, val revision: Long, val guarded: Boolean, val at: Long)

    @Volatile private var verified: Verified? = null

    override suspend fun checkAppAccess() {
        val state = appAccess.current()
        if (!state.available) throw AppAccessDenied("app_access_unavailable", null)
        val guarded = protectedUi()
        if (state.blocked.isEmpty() && !guarded) return
        if (stillVerified(state, guarded)) return
        val token = windowToken?.invoke()
        verify(state, packages(), guarded, token)
    }

    private fun stillVerified(state: AppAccessState, guarded: Boolean): Boolean {
        val v = verified ?: return false
        val token = windowToken?.invoke() ?: return false
        return v.token == token && v.revision == state.revision && v.guarded == guarded && clock() - v.at <= CHECK_TTL_MS
    }

    /** Check the ownership of these windows [found]; if you cannot recognize the ownership, ask the window again after a while. If passed, record it for future use by [stillVerified]. */
    private suspend fun verify(state: AppAccessState, found: List<String?>, guarded: Boolean, token: Long?) {
        verified = null
        var s = state
        var targets = found
        var t = token
        if (s.blocked.isNotEmpty() && targets.any { it.isNullOrBlank() }) {
            delay(RETRY_MS)
            t = windowToken?.invoke()
            targets = packages()
            s = appAccess.current()
        }
        if (!s.available) throw AppAccessDenied("app_access_unavailable", null)
        for (pkg in targets) {
            if (guarded && ownPackage != null && pkg == ownPackage) throw AppAccessDenied("app_access_protected", pkg)
            s.denial(pkg)?.let { throw it }
        }
        verified = t?.let { Verified(it, s.revision, guarded, clock()) }
    }

    /** Read a screen: take the window ownership of what you read, and check it after reading; if the port is not given to the window, return to the window. */
    private suspend fun observed(read: suspend () -> ScreenSnapshot): ScreenSnapshot {
        val state = appAccess.current()
        if (!state.available) throw AppAccessDenied("app_access_unavailable", null)
        val guarded = protectedUi()
        if (state.blocked.isEmpty() && !guarded) return read()
        // Count first: the window changes during the reading process and will not be used next time.
        val token = windowToken?.invoke()
        val snap = read()
        verify(appAccess.current(), snap.accessPackages() ?: packages(), guarded, token)
        return snap
    }

    private suspend fun <T> checked(body: suspend () -> T): T {
        checkAppAccess()
        val value = body()
        checkAppAccess()
        return value
    }

    private fun target(packageName: String) {
        appAccess.current().denial(packageName)?.let { throw it }
    }

    // ---------- Screen Reading ----------
    override suspend fun snapshot() = observed { inner.snapshot() }
    override suspend fun peek() = observed { inner.peek() }
    override suspend fun screenshotPng() = checked { inner.screenshotPng() }
    override suspend fun readVisibleText() = checked { inner.readVisibleText() }
    override suspend fun frameGrid() = checked { inner.frameGrid() }
    override suspend fun isProtected() = checked { inner.isProtected() }
    override suspend fun checkScreen() = checked { inner.checkScreen() }

    // ---------- Actions on the current screen ----------
    override suspend fun tapRef(ref: String, snapshotId: String) = checked { inner.tapRef(ref, snapshotId) }
    override suspend fun tapXY(x: Int, y: Int) = checked { inner.tapXY(x, y) }
    override suspend fun doubleTapRef(ref: String, snapshotId: String) = checked { inner.doubleTapRef(ref, snapshotId) }
    override suspend fun doubleTapXY(x: Int, y: Int) = checked { inner.doubleTapXY(x, y) }
    override suspend fun typeText(ref: String?, snapshotId: String?, text: String) = checked { inner.typeText(ref, snapshotId, text) }
    override suspend fun clearText(ref: String?, snapshotId: String?) = checked { inner.clearText(ref, snapshotId) }
    override suspend fun typeAt(x: Int, y: Int, text: String) = checked { inner.typeAt(x, y, text) }
    override suspend fun clearAt(x: Int, y: Int) = checked { inner.clearAt(x, y) }
    override suspend fun scroll(ref: String?, snapshotId: String?, down: Boolean) = checked { inner.scroll(ref, snapshotId, down) }
    override suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long) = checked { inner.swipe(x1, y1, x2, y2, durationMs) }
    override suspend fun idleScroll(fraction: Double) = checked { inner.idleScroll(fraction) }
    override suspend fun gesture(paths: List<List<Pair<Int, Int>>>, durationMs: Long) = checked { inner.gesture(paths, durationMs) }
    override suspend fun back() = checked { inner.back() }
    override suspend fun pressKey(keyCode: Int) = checked { inner.pressKey(keyCode) }
    override suspend fun setWheel(ref: String, snapshotId: String, value: String) = checked { inner.setWheel(ref, snapshotId, value) }

    // ---------- Waiting and events ----------
    override suspend fun waitStable(timeoutMs: Long) = checked { inner.waitStable(timeoutMs) }
    override suspend fun awaitChange(timeoutMs: Long) = checked { inner.awaitChange(timeoutMs) }
    override suspend fun waitFor(condition: String, value: String?, timeoutMs: Long) = checked { inner.waitFor(condition, value, timeoutMs) }
    override suspend fun nextEvent(kind: String, packageName: String?, timeoutMs: Long) = checked { inner.nextEvent(kind, packageName, timeoutMs) }
    override suspend fun watchEvents(kind: String, packageName: String?, durationMs: Long, maxEvents: Int) =
        checked { inner.watchEvents(kind, packageName, durationMs, maxEvents) }

    // ---------- Open by target: You can leave the restricted app and only see the target ----------
    override suspend fun openApp(packageName: String, cold: Boolean): ActionResult {
        target(packageName)
        return inner.openApp(packageName, cold)
    }

    override suspend fun openAppCopy(packageName: String, userId: Int, cold: Boolean): ActionResult {
        target(packageName)
        return inner.openAppCopy(packageName, userId, cold)
    }

    override suspend fun shareText(packageName: String, text: String): ActionResult {
        target(packageName)
        return inner.shareText(packageName, text)
    }

    /** Specified Apps can be found here; Apps that are not specified will be picked up by the port according to the list (only the port knows who opens it). */
    override suspend fun openLink(action: String?, uri: String?, packageName: String?): ActionResult {
        packageName?.let(::target)
        return inner.openLink(action, uri, packageName)
    }

    /** Just wait for occupation registration, no screen reading; just look at the target photo first when opening it, no need to wait for restricted apps, reject them on the spot. */
    override suspend fun awaitAppFree(packageName: String, userId: Int?, timeoutMs: Long): AppWait {
        target(packageName)
        return inner.awaitAppFree(packageName, userId, timeoutMs)
    }

    /** The App to be taken over is selected by the port according to the list (only the port knows who will receive it); the completed front desk will be checked by the execution portal as usual. */
    override suspend fun systemAction(action: SystemAction) = inner.systemAction(action)

    // ---------- Do not touch any App interface: hand it over as is ----------
    override suspend fun systemCommand(command: String) = inner.systemCommand(command)
    override suspend fun listApps() = inner.listApps()
    override suspend fun otherProfiles() = inner.otherProfiles()
    override suspend fun appCopies() = inner.appCopies()
    override val afterTask: AfterTaskMarks? get() = inner.afterTask

    companion object {
        /** If you cannot recognize the window you belong to, ask again after a certain interval (it will take a while to read the newly opened window). */
        const val RETRY_MS = 100L

        /** The maximum length of time the check result is used when the window has not changed; the short period of time when the window event comes late is captured. */
        const val CHECK_TTL_MS = 1_000L
    }
}

/**
 * The ownership of each App window on this screen follows the rules for window probes with the same
 * port: if there is an App window, it is their package name (if the root node cannot be read, it is
 * null); when there is no App window, it is a recognized foreground App, and if there is no App window,
 * it is an empty screen (empty list). If the port is not given to the window level (web page, test
 * port), it will be null, and the caller will ask separately.
 */
internal fun ScreenSnapshot.accessPackages(): List<String?>? {
    val apps = windows.filter { it.type == "application" }
    if (apps.isNotEmpty()) return apps.map { it.packageName }
    if (hasWindow) return null
    return listOfNotNull(unreadableApp)
}
