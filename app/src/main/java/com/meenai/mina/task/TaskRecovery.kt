package com.meenai.mina.task

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.meenai.mina.settings.KeyCipher
import com.meenai.mina.settings.KeystoreKeyCipher
import com.meenai.mina.shell.IHarnessShell
import com.meenai.mina.cap.phone.shell.AppAudioKey
import com.meenai.mina.cap.phone.shell.SharedShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** A persistent set of "original values"; a value of null indicates that this item did not originally exist. [load] returns null when there is no record. */
interface MarkerStore {
    fun load(): Map<String, String?>?
    fun save(values: Map<String, String?>)
    fun clear()
}

/** Exists in SharedPreferences, synchronous submission: must be placed first before changing the system status. */
class PrefsMarker(private val prefs: SharedPreferences, private val name: String) : MarkerStore {
    override fun load(): Map<String, String?>? {
        val keys = prefs.getStringSet("$name.keys", null) ?: return null
        return keys.associateWith { prefs.getString("$name.v.$it", null) }
    }

    override fun save(values: Map<String, String?>) {
        val e = prefs.edit()
        clearInto(e)
        e.putStringSet("$name.keys", values.keys.toSet())
        values.forEach { (k, v) -> e.putString("$name.v.$k", v) }
        e.commit()
    }

    override fun clear() {
        prefs.edit().also { clearInto(it) }.commit()
    }

    private fun clearInto(e: SharedPreferences.Editor) {
        prefs.all.keys.filter { it.startsWith("$name.") }.forEach { e.remove(it) }
    }
}

/** Shell capabilities required for muting. The parameter is the mute key (see [AppAudioKey]): the original copy is the package name, and the copy in the other user contains the user number. */
interface AudioPort {
    /** Current PLAY_AUDIO mode; returns null if not read. */
    fun mode(packageName: String): String?
    fun mute(packageName: String): Boolean
    fun setMode(packageName: String, mode: String): Boolean
}

class ShellAudio(private val shell: IHarnessShell) : AudioPort {
    override fun mode(packageName: String): String? = runCatching {
        val (pkg, user) = AppAudioKey.parse(packageName) ?: return null
        if (user == null) shell.getAudioMode(pkg) else shell.getAudioModeInUser(pkg, user)
    }.getOrNull()

    override fun mute(packageName: String): Boolean = runCatching {
        val (pkg, user) = AppAudioKey.parse(packageName) ?: return false
        if (user == null) shell.setAudioMuted(pkg, true) else shell.setAudioMutedInUser(pkg, user, true)
    }.getOrDefault(false)

    override fun setMode(packageName: String, mode: String): Boolean = runCatching {
        // Unformatted keys are treated as recovered: they cannot be recovered even if they are left.
        val (pkg, user) = AppAudioKey.parse(packageName) ?: return true
        if (user == null) shell.setAudioMode(pkg, mode) else shell.setAudioModeInUser(pkg, user, mode)
    }.getOrDefault(false)
}

/**
 * Turn off the target app's sound during the task. Write the original mode into the persistent record
 * before muting: the shell service ends with the Mina process, and the original value in its memory will
 * be lost. Only this record can be restored at the next startup.
 */
class AudioMute(private val port: AudioPort, private val store: MarkerStore) {
    @Synchronized
    fun mute(packageName: String): Boolean {
        val saved = store.load().orEmpty()
        if (packageName !in saved) {
            val prior = port.mode(packageName) ?: return false
            store.save(saved + (packageName to prior))
        }
        return port.mute(packageName)
    }

    /** Only restore one App (make it sound when the user takes over); if it cannot be restored, leave it in the record. Those that have not been muted are considered restored. */
    @Synchronized
    fun restore(packageName: String): Boolean {
        val saved = store.load().orEmpty()
        if (packageName !in saved) return true
        if (!port.setMode(packageName, saved[packageName] ?: "default")) return false
        val left = saved - packageName
        if (left.isEmpty()) store.clear() else store.save(left)
        return true
    }

    /** Restore all apps in the record; keep those that cannot be restored in the record and try again next time. */
    @Synchronized
    fun restoreAll(): Boolean {
        val saved = store.load() ?: return true
        val left = saved.filterNot { (pkg, mode) -> port.setMode(pkg, mode ?: "default") }
        if (left.isEmpty()) store.clear() else store.save(left)
        return left.isEmpty()
    }
}

/**
 * When Mina uses the clipboard to paste, the user's original clipboard text (and the text Mina put in)
 * is encrypted and left behind: when the shell process is killed along with Mina, the original clipboard
 * in its memory is gone, and the next startup can only rely on this to return the user's content. Use
 * [cipher] to encrypt and save it as a piece of text, then delete it after restoration.
 */
class ClipBackup(private val read: () -> String?, private val write: (String?) -> Unit, private val cipher: KeyCipher) {
    fun save(original: String, ours: String) {
        val plain = buildJsonObject { put("original", original); put("ours", ours) }.toString().toByteArray()
        write(Base64.getEncoder().encodeToString(cipher.encrypt(plain)))
    }

    /** (Original text, text put in by Mina); null when there is no bottom left, or it cannot be solved (the key has been changed). */
    fun load(): Pair<String, String>? = runCatching {
        val data = read() ?: return null
        val o = Json.parseToJsonElement(String(cipher.decrypt(Base64.getDecoder().decode(data)))).jsonObject
        o.getValue("original").jsonPrimitive.content to o.getValue("ours").jsonPrimitive.content
    }.getOrNull()

    fun clear() = write(null)
}

/**
 * System changes left after Mina exits abnormally (App mute, clipboard temporarily replaced during
 * pasting) will be restored the next time it is started. Record one copy of each task silently (press
 * [TaskRun.key]). When several tasks are run at the same time, each package will be recorded. Changes
 * during the task are completed by the task itself, and restoration only processes those that no one has
 * completed: the records of the task that is still running ([live]) are not moved, and the clipboard is
 * not restored when there is a task running (it may be pasting through the clipboard at that time).
 * Create a copy from the App and give it via [Owner] ([live] should be shared between tasks); use the
 * [sharedShell] shared in the process when waiting for the assistant.
 */
class TaskRecovery(context: Context, private val sharedShell: SharedShell) {
    private val app = context.applicationContext
    private val clipCipher by lazy { KeystoreKeyCipher(CLIP_KEY_ALIAS) }

    /** The restoration is shared with the registration when the task is started to ensure that the two do not overlap. */
    val lock = Mutex()

    /** Tasks that are still running; after the process is killed, it will be empty, and all records will be restored. */
    private val live = ConcurrentHashMap.newKeySet<String>()

    fun audioMute(shell: IHarnessShell, runKey: String) = AudioMute(ShellAudio(shell), marker(runKey))

    private fun marker(runKey: String) = PrefsMarker(prefs(), "${MUTED}_$runKey")

    /** Names of all existing silent records (including the old version). */
    private fun markerNames(): List<String> =
        prefs().all.keys.filter { it.startsWith(MUTED) && it.endsWith(".keys") }.map { it.removeSuffix(".keys") }

    private fun liveNames() = live.mapTo(HashSet()) { "${MUTED}_$it" }

    fun pending(): Boolean =
        markerNames().any { it !in liveNames() } || (live.isEmpty() && prefs().getBoolean(CLIP_HELD, false))

    /** Task start: first restore the legacy that no one has finished, and then register it as running. */
    suspend fun beginRun(shell: IHarnessShell, runKey: String) = lock.withLock {
        withContext(Dispatchers.IO) { recoverWith(shell) }
        live += runKey
    }

    /** Called after the task ends (your own mute has been restored); those that have not been restored are left in the record and will be restored next time. */
    fun endRun(runKey: String) {
        live -= runKey
    }

    /** During the pasting process, the clipboard belongs to Mina; the process ends during this period, and the next time it is started, the shell will return the user's original clipboard. */
    fun markClipHeld(held: Boolean) {
        prefs().edit().putBoolean(CLIP_HELD, held).apply { if (!held) remove(CLIP_BACKUP) }.commit()
    }

    private fun clipBackup() = ClipBackup(
        { prefs().getString(CLIP_BACKUP, null) },
        { v -> prefs().edit().apply { if (v == null) remove(CLIP_BACKUP) else putString(CLIP_BACKUP, v) }.commit() },
        clipCipher,
    )

    /** After successfully changing the clipboard, the bottom will be retained; if the encryption fails, it will not be retained (just without the restoration after the process is killed). */
    fun saveClip(original: String, ours: String) {
        runCatching { clipBackup().save(original, ours) }.onFailure { Log.w(TAG, "clipboard backup not saved", it) }
    }

    /** Called when [lock] is already held. */
    fun recoverWith(shell: IHarnessShell) {
        val records = markerNames().associateWith { PrefsMarker(prefs(), it) }
        if (!restoreRuns(records, ShellAudio(shell), liveNames())) Log.w(TAG, "muted apps not restored yet")
        if (live.isNotEmpty()) return
        // While the shell process is still there, restore the original clipboard it remembers (it will
        // not move when the clipboard has been replaced by the user); if the shell has been killed, just
        // restore it by encrypting the original clipboard. In both steps, the clipboard will only be
        // replaced when it is still Mina's.
        val inShell = runCatching { shell.restoreClip() }.isSuccess
        val fromBackup = restoreClip(clipBackup()) { o, u -> shell.restoreClipText(o, u) }
        if (inShell && fromBackup) markClipHeld(false)
    }

    /**
     * Called at startup: If there are any remaining changes, wait until the built-in assistant is
     * connected to restore them; if not, leave them until next time. Do not occupy the lock while
     * waiting for the assistant to avoid new tasks waiting; after obtaining the lock, confirm again
     * whether there is anything left.
     */
    suspend fun recover() {
        if (!pending()) return
        val connection = sharedShell.acquire()
        try {
            val shell = withTimeoutOrNull(SHELL_WAIT_MS) { connection.shell.filterNotNull().first() } ?: return
            lock.withLock { if (pending()) recoverWith(shell) }
        } finally {
            sharedShell.release()
        }
    }

    private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Give the application in the process. */
    interface Owner {
        val taskRecovery: TaskRecovery
    }

    companion object {
        private const val TAG = "MinaTask"
        private const val PREFS = "task_recovery"
        private const val SHELL_WAIT_MS = 60_000L
        private const val CLIP_HELD = "clip_held"
        private const val CLIP_BACKUP = "clip_backup"
        private const val CLIP_KEY_ALIAS = "mina-clip-backup"
        /** The mute record name of each task is muted_<task key>; the old version only has one copy, named muted, and can be restored as usual. */
        private const val MUTED = "muted"

        fun of(context: Context): TaskRecovery = (context.applicationContext as? Owner)?.taskRecovery
            ?: error("the Application (${context.applicationContext.javaClass.name}) must implement TaskRecovery.Owner to give the task recovery")

        /** Restore all mutes in [records] (record name → record) that do not belong to [live]; return true when restored. */
        fun restoreRuns(records: Map<String, MarkerStore>, port: AudioPort, live: Set<String>): Boolean =
            records.filterKeys { it !in live }.values.map { AudioMute(port, it).restoreAll() }.all { it }

        /** Press Leave to return the user's original clipboard ([restore] only changes when the clipboard is still Mina's); returns true if restored or there is no retain, and deletes the retain. */
        fun restoreClip(backup: ClipBackup, restore: (original: String, ours: String) -> Unit): Boolean {
            val (original, ours) = backup.load() ?: return true.also { backup.clear() }
            return runCatching { restore(original, ours) }.onSuccess { backup.clear() }.isSuccess
        }
    }
}
