package com.meenai.mina.core.uistate

/**
 * The model determines "what to do when the task ends" for the App in this task: keep it (keep) or close
 * it (close), with a reason. If it is not marked, the default is: stay in the background, and turn off
 * the sound when it is finished (executed by the phone side at the end of the task). If the same App is
 * marked again, the last time it will be marked shall prevail. [userId] is null, it is the original
 * copy; write the user number in the other copy (clone, work information).
 */
class AfterTaskMarks {
    data class Mark(val packageName: String, val userId: Int?, val keep: Boolean, val reason: String) {
        /** The key agreed with the side of the mobile phone: the original copy is the package name, and the other copy is package name@user. */
        val key: String get() = key(packageName, userId)
    }

    private val marks = LinkedHashMap<String, Mark>()

    /** Make a note of one; return the one before this App (null if not). */
    @Synchronized
    fun mark(packageName: String, userId: Int?, keep: Boolean, reason: String): Mark? {
        val m = Mark(packageName, userId, keep, reason)
        return marks.remove(m.key).also { marks[m.key] = m }
    }

    @Synchronized
    fun all(): List<Mark> = marks.values.toList()

    fun keepKeys(): List<String> = all().filter { it.keep }.map { it.key }
    fun closeKeys(): List<String> = all().filter { !it.keep }.map { it.key }

    companion object {
        fun key(packageName: String, userId: Int?): String = if (userId == null) packageName else "$packageName@$userId"
    }
}
