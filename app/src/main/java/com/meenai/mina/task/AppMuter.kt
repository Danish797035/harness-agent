package com.meenai.mina.task

import com.meenai.mina.cap.phone.shell.AppAudioKey

/**
 * Silence apps on Mina's screen during missions. PLAY_AUDIO Press "Package + User" settings
 * ([AppAudioKey]), and the original copy will be separated from the other copy (clone, work data).
 *
 * - - [beforeOpen]: When Mina wants to launch an App, it must wait for a while before launching the app.
 * There will be no sound for the first few seconds. If this step fails, the app will not be reported
 * until it is actually launched and then try again.
 * - - [opened]: An App pops up on Mina's screen (the target of activation, or clicked from another App,
 * and another copy on top): If it is not silent, it will be silent. If it is not silent, it will be
 * recorded as "may make noise".
 * - - [notOpened]: The startup is refused (not installed, the user is using it...). The one that was
 * silenced in advance and not started is restored immediately to prevent it from being silent during the
 * task.
 * - - [release]: Return the sound of this App when the user takes over; after that, [opened] will
 * silence it again.
 *
 * Not to touch: Mina itself; system packages without desktop access (system interface, selection box,
 * permission box, etc., if muted, the system sound will be associated); the user can see on the home
 * screen at the moment (mute the package function, if muted, there will be no sound on the user's hand).
 * When mute is turned off in settings, no one is silent. At the end, [AudioMute.restoreAll] restored
 * them together, including those that were silently uploaded in the middle of the mission.
 */
class AppMuter(
    private val audio: AudioMute,
    /** Settings › "Mute open apps" while working; every time you read it now, closing it in the middle of the task also counts. */
    private val enabled: () -> Boolean,
    /** Whether this package can be static (see the first two items of "Untouchable" in the class description). */
    private val mutable: (packageName: String) -> Boolean,
    /** This package (the [user] copy, null is the original copy) is now visible on the home screen. */
    private val onMainScreen: (packageName: String, user: Int?) -> Boolean,
    /** It should be quiet but it is not: the task progress prompts "This app may make noise". */
    private val failed: (packageName: String) -> Unit,
) {
    /** The keys that have been silenced (not yet restored) in this mission. */
    private val muted = HashSet<String>()

    /** Quiet before starting, and you haven't seen the key that comes out yet: restore accordingly when the startup is refused. */
    private val early = HashSet<String>()

    @Synchronized
    fun beforeOpen(packageName: String, user: Int?) {
        val key = AppAudioKey.of(packageName, user)
        if (key in muted || !wanted(packageName) || onMainScreen(packageName, user)) return
        if (audio.mute(key)) {
            muted += key
            early += key
        }
    }

    @Synchronized
    fun opened(packageName: String, user: Int?) {
        val key = AppAudioKey.of(packageName, user)
        early -= key
        if (key in muted || !wanted(packageName)) return
        // The user is using it on the home screen: it is not silent, to be honest it may make a sound
        if (onMainScreen(packageName, user) || !audio.mute(key)) {
            failed(packageName)
            return
        }
        muted += key
    }

    @Synchronized
    fun notOpened(packageName: String, user: Int?) {
        val key = AppAudioKey.of(packageName, user)
        if (!early.remove(key)) return
        if (audio.restore(key)) muted -= key
    }

    /** Restore the sound of an App (user takes over); if it cannot be restored, return false, leave it in the record and try again at the end. */
    @Synchronized
    fun release(packageName: String, user: Int?): Boolean {
        val key = AppAudioKey.of(packageName, user)
        early -= key
        if (!audio.restore(key)) return false
        muted -= key
        return true
    }

    private fun wanted(packageName: String) = enabled() && mutable(packageName)
}
