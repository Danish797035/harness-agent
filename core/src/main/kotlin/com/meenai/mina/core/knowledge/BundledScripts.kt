package com.meenai.mina.core.knowledge

/** Whether a script in the knowledge or skill directory can be run at this moment; when it cannot run, [reason] writes the reason. */
data class ScriptState(val file: String, val runnable: Boolean, val reason: String?)

/** The ability to run built-in scripts is left to knowledge_read, skill_read and reference notes. */
interface BundledScripts {
    /** This task provides tools for running scripts (the running environment may still be prepared). */
    fun offered(): Boolean

    /** When the running environment is ready at this time, first check the skills directory into the packaged copy and return true; if it is not ready, it will be false. */
    suspend fun ready(): Boolean

    /** [skillDir] Check whether the scripts in the list can be run at this time. */
    fun scripts(skillDir: String): List<ScriptState>
}
