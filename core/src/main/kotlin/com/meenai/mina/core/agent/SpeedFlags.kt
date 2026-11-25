package com.meenai.mina.core.agent

/** Explicit full off value for the speed-up switch; the app's first-time default value is determined by FeatureFlags. */
data class SpeedFlags(
    val inlineScreen: Boolean = false,
    val batchSteps: Boolean = false,
    val replay: Boolean = false,
    val deepLinks: Boolean = false,
    val lightSteps: Boolean = false,
    val parallelTasks: Boolean = false,
    /** When compressing, write the summary into four structured paragraphs and calibrate the estimate with real usage (it will change what the model sees, and make a decision after evaluation and comparison). */
    val structuredCompaction: Boolean = false,
    /** Elements take priority, and the main model screen will only be given if it is unclear or actively requested by the model. */
    val adaptiveVision: Boolean = false,
) {
    private fun on(): Map<String, Boolean> = mapOf(
        "inlineScreen" to inlineScreen, "batchSteps" to batchSteps,
        "replay" to replay, "deepLinks" to deepLinks, "lightSteps" to lightSteps, "parallelTasks" to parallelTasks,
        "structuredCompaction" to structuredCompaction,
        "adaptiveVision" to adaptiveVision,
    )

    /** Open switch names are concatenated with commas in alphabetical order; all off are "none". [parse] can be read back. */
    fun describe(): String = on().filterValues { it }.keys.sorted().joinToString(",").ifEmpty { NONE }

    companion object {
        private const val NONE = "none"
        val OFF = SpeedFlags()
        private val KEYS = OFF.on().keys
        /** Switches that have been turned positive or removed: There may still be some in the saved settings and evaluation parameters, so just skip them if you read them. */
        private val RETIRED = setOf("cacheFriendlyPrune")

        /** "none" (or empty) = all off; otherwise comma separated `key=on|off` or just the key name (= on). Unknown key or value throws [IllegalArgumentException]. */
        fun parse(spec: String): SpeedFlags {
            val s = spec.trim()
            if (s.isEmpty() || s == NONE) return OFF
            val set = mutableMapOf<String, Boolean>()
            for (item in s.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                val key = item.substringBefore('=').trim()
                val value = if ('=' in item) item.substringAfter('=').trim() else "on"
                if (key in RETIRED) continue
                require(key in KEYS) { "unknown speed flag: $key" }
                set[key] = when (value) {
                    "on" -> true
                    "off" -> false
                    else -> throw IllegalArgumentException("speed flag $key must be on or off, got $value")
                }
            }
            fun f(k: String) = set[k] ?: false
            return SpeedFlags(f("inlineScreen"), f("batchSteps"), f("replay"), f("deepLinks"), f("lightSteps"), f("parallelTasks"), f("structuredCompaction"), f("adaptiveVision"))
        }
    }
}
