package com.meenai.mina.core.eval

/** Grouping of parallel assessments: Questions in the same group are run at the same time. */
object EvalPairing {
    /**
     * Greedy in order of questions: Each group starts with the first question that has not yet been
     * divided, and then looks for questions that are not intertwined with the apps used in the group
     * until [n] is reached. The same App cannot be used on two screens at the same time, so the App has
     * overlapping questions in different groups; I don’t know what will be used for the questions that
     * do not say `apps`, so they are in a separate group.
     */
    fun groups(tasks: List<EvalTask>, n: Int): List<List<EvalTask>> {
        val left = tasks.toMutableList()
        val out = mutableListOf<List<EvalTask>>()
        while (left.isNotEmpty()) {
            val first = left.removeAt(0)
            val group = mutableListOf(first)
            if (first.apps.isNotEmpty()) {
                val used = first.apps.toMutableSet()
                val it = left.iterator()
                while (group.size < n && it.hasNext()) {
                    val t = it.next()
                    if (t.apps.isEmpty() || t.apps.any { a -> a in used }) continue
                    group += t
                    used += t.apps
                    it.remove()
                }
            }
            out += group
        }
        return out
    }
}
