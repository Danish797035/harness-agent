package com.meenai.mina.cap.phone.shell

/**
 * What to do with the App on Mina's screen when the task ends (rules on the shell side, do not touch the
 * system interface, unit test directly tests).
 *
 * Leave it by default: Move it to the home screen and place it at the end. Users can see it stops on the
 * page Mina finished in recent tasks. Next time Mina opens it, it will also continue on this page. The
 * current sound (playing audio and video) is turned off by default to prevent it from suddenly sounding
 * when the task is completed and the mute is restored. Models marked according to the user's words
 * ([keep] / [close]) take precedence over the default. Mina's own pages and those that are not ordinary
 * App pages (desktop, recent tasks, etc.) will be closed with the screen.
 */
object AppsAfterTask {
    /** Why is a task determined in this way? [wire] writes the results and running records. */
    enum class Why(val wire: String) {
        /** Model marked. */
        Marked("marked"),
        /** Making a sound now. */
        Playing("playing"),
        /** It cannot be read whether it is making a sound or not, but it is being muted by Mina: it may suddenly beep when reverting to mute, so pressing the sound count will count. */
        PlaybackUnknown("playback_unknown"),
        /** Default: Leave. */
        Default("default"),
        /** Mina's own page. */
        Own("mina"),
        /** It is not a page of ordinary App. */
        NotApp("not_app"),
        /** This system cannot move tasks and is still turned off with the screen. */
        MoveUnsupported("move_not_supported"),
        /** I failed to move it halfway, so I closed it so that it would not be in front of the user's eyes. */
        MoveFailed("move_failed"),
    }

    /**
     * A task on the screen: [keys] is the key replaced by the package to which its page belongs (see
     * [keyOf]), [playing] is null when it cannot be read, [muted] is that it is currently being held by
     * Mina (it will be restored at the end).
     */
    data class Task(val id: Int, val keys: List<String>, val standard: Boolean, val own: Boolean, val playing: Boolean?, val muted: Boolean = false)

    data class Fate(val task: Task, val keep: Boolean, val why: Why) {
        val key: String get() = task.keys.firstOrNull().orEmpty()
    }

    /** Key: The original copy (the user where Mina is located) is the package name, and the other copy is package name@user, which is written in the same way as the mute record ([AppAudioKey]). */
    fun keyOf(packageName: String, userId: Int, myUser: Int): String =
        AppAudioKey.of(packageName, userId.takeIf { it != myUser && it >= 0 })

    /** The model target key is written in the same way: the other copy written as Mina's user is equal to the original copy; the inappropriate format is discarded. */
    fun normalize(keys: Array<String>?, myUser: Int): Set<String> = keys.orEmpty().mapNotNull { k ->
        AppAudioKey.parse(k)?.let { (pkg, user) -> AppAudioKey.of(pkg, user?.takeIf { it != myUser }) }
    }.toSet()

    fun decide(tasks: List<Task>, keep: Set<String>, close: Set<String>): List<Fate> = tasks.map { t ->
        when {
            t.own -> Fate(t, false, Why.Own)
            !t.standard -> Fate(t, false, Why.NotApp)
            t.keys.any { it in close } -> Fate(t, false, Why.Marked)
            t.keys.any { it in keep } -> Fate(t, true, Why.Marked)
            t.playing == true -> Fate(t, false, Why.Playing)
            // It cannot be read whether there is a sound or not: it is not left silently (it has already
            // made a sound if it wants to make a sound, and the ending does not change anything); it is
            // calculated by pressing the sound silently to avoid a sudden sound when restoring.
            t.playing == null && t.muted -> Fate(t, false, Why.PlaybackUnknown)
            else -> Fate(t, true, Why.Default)
        }
    }

    /**
     * After moving over, take another look: it is not marked keep, and if it starts making sound now,
     * change it to off (it will sound as soon as the mute is restored). Photos marked keep will be
     * retained.
     */
    fun recheck(fate: Fate, playingNow: Boolean?): Fate =
        if (fate.keep && fate.why != Why.Marked && playingNow == true) fate.copy(keep = false, why = Why.Playing) else fate

    /**
     * Those uids currently playing in `dumpsys audio`: one row per player `AudioPlaybackConfiguration
     * piid:.. u/pid:<uid>/<pid> state:started ..`. If it is not recognized as the output of the audio
     * service (the segment that is not played and recorded), it will be null, and the caller will treat
     * it as "cannot read".
     */
    fun playingUids(dumpsys: String): Set<Int>? {
        if (!dumpsys.contains(PLAYBACK_SECTION)) return null
        return dumpsys.lineSequence().filter { "AudioPlaybackConfiguration" in it }
            .mapNotNull { PLAYER.find(it) }
            .filter { it.groupValues[2] == "started" }
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .filter { it >= 0 }
            .toSet()
    }

    /** The item turned off in the result: key: reason. */
    fun closedEntry(f: Fate): String = "${f.key}:${f.why.wire}"

    /**
     * One line in the diagnostic information: only counts the number, not the package name (the
     * diagnostic information does not include what is installed or opened). Such as "kept 1, closed 1
     * (playing 1)" "closed 2 (move not supported)" "none".
     */
    fun diagnosticsLine(kept: Int, closed: List<String>, note: String?): String {
        if (kept == 0 && closed.isEmpty()) return note?.let { "none ($it)" } ?: "none"
        val reasons = closed.map { it.substringAfterLast(':') }.groupingBy { it }.eachCount()
            .entries.sortedBy { it.key }.joinToString(", ") { "${it.key.replace('_', ' ')} ${it.value}" }
        val parts = listOfNotNull(
            "kept $kept".takeIf { kept > 0 },
            if (closed.isEmpty()) null else "closed ${closed.size}" + (if (reasons.isEmpty()) "" else " ($reasons)"),
        )
        return parts.joinToString(", ") + (note?.let { "; $it" } ?: "")
    }

    // finishDisplay Bundle
    const val KEY_KEPT = "kept"
    const val KEY_CLOSED = "closed"
    const val KEY_NOTE = "note"

    /** If you can't move it, write it down in a note. */
    const val NOTE_MOVE_UNSUPPORTED = "move not supported"
    const val NOTE_PLAYBACK_UNKNOWN = "playback unknown"

    // AudioPlaybackConfiguration.PLAYER_STATE_STARTED
    const val PLAYER_STATE_STARTED = 2
    private const val PLAYBACK_SECTION = "PlaybackActivityMonitor"
    private val PLAYER = Regex("""u/pid:(-?\d+)/-?\d+\s+state:(\w+)""")
}
