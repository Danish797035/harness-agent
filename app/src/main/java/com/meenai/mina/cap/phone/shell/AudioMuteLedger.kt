package com.meenai.mina.cap.phone.shell

import java.io.File

/**
 * Apps muted by Mina → PLAY_AUDIO mode before muting, also fell into the file: the built-in assistant
 * process was killed during muting, and after the phone restarts, this record will be used to return the
 * sound the next time it is started. When muted, it will be recorded and deleted after successful
 * recovery; when file reading and writing fails, it will be returned and only recorded in the memory.
 * Only the packages in the record will be restored, and the values set by others will not be touched.
 * The recorded key is [AppAudioKey]: the separate item in another user's copy.
 */
class AudioMuteLedger(private val file: File?) {
    private val modes = LinkedHashMap<String, String>()

    init {
        val text = runCatching { file?.takeIf { it.isFile }?.readText() }.getOrNull().orEmpty()
        modes.putAll(parse(text))
    }

    fun contains(packageName: String): Boolean = packageName in modes
    operator fun get(packageName: String): String? = modes[packageName]
    fun packages(): List<String> = modes.keys.toList()

    fun put(packageName: String, mode: String) {
        modes[packageName] = mode
        save()
    }

    fun remove(packageName: String) {
        if (modes.remove(packageName) != null) save()
    }

    /** Restore each package in the record to [restoreMode]; [write] and delete the record only after returning true. If it fails, keep it and try again next time. Return whether all are restored. */
    fun restoreAll(write: (packageName: String, mode: String) -> Boolean): Boolean {
        packages().forEach { pkg -> if (write(pkg, restoreMode(modes.getValue(pkg)))) remove(pkg) }
        return modes.isEmpty()
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            if (modes.isEmpty()) {
                f.delete()
            } else {
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(format(modes))
                if (!tmp.renameTo(f)) error("rename failed")
            }
        }
    }

    companion object {
        private val LINE = Regex("""([A-Za-z0-9_.]+(?:@[0-9]+)?) ([a-z_]+)""")

        /**
         * The mode of writing back during recovery. It turns out that it is clear that
         * allow/ignore/deny, etc. are written back as they are; if they have not been set (parse out
         * default), write allow: The audio system in Android only recognizes PLAY_AUDIO as allow before
         * playing sound. Explicit default will make the App silent all the time, and if it is not set,
         * the default value taken by the outdated system is originally allow. After writing allow,
         * `appops get` also returns to "not set". There are only two sources for default appearing in
         * the record: it has not been set before muting, or the old version was written down after being
         * written down. Changing it to allow will not cause any accidental damage.
         */
        fun restoreMode(prior: String?): String = if (prior.isNullOrEmpty() || prior == "default") "allow" else prior

        /** The output of `appops get <pkg> PLAY_AUDIO`: "PLAY_AUDIO: ignore; time=..." → "ignore"; "No operations." → null (not set). */
        fun parseSetMode(output: String): String? = Regex("""PLAY_AUDIO:\s*([a-z_]+)""").find(output)?.groupValues?.get(1)

        internal fun format(modes: Map<String, String>): String = modes.entries.joinToString("") { "${it.key} ${it.value}\n" }

        internal fun parse(text: String): Map<String, String> =
            text.lineSequence().mapNotNull { LINE.matchEntire(it.trim())?.destructured }
                .associate { (pkg, mode) -> pkg to mode }
    }
}

/**
 * A mute key. PLAY_AUDIO is opened separately according to "package + user": the original copy (in the
 * user where Mina is located) is the package name, and the copy installed in another user (available
 * avatar, work information) is "package name@user number". The original values of each copy are
 * recorded and restored separately.
 */
object AppAudioKey {
    private val KEY = Regex("""([A-Za-z0-9_.]+)(?:@([0-9]+))?""")

    /** [user] is the original share if it is empty. */
    fun of(packageName: String, user: Int?): String = if (user == null) packageName else "$packageName@$user"

    /** Retrieve (package name, user); the original user is empty. Unformatted is null. */
    fun parse(key: String): Pair<String, Int?>? =
        KEY.matchEntire(key)?.destructured?.let { (pkg, user) -> pkg to user.takeIf { it.isNotEmpty() }?.toIntOrNull() }
}
