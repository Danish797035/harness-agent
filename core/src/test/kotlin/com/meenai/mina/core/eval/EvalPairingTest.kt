package com.meenai.mina.core.eval

import org.junit.Assert.assertEquals
import org.junit.Test

class EvalPairingTest {
    private fun t(id: String, vararg apps: String) =
        EvalTask(id = id, goal = "g", apps = apps.toList(), category = EvalCategory.Read, expect = Expect.Succeeded, checks = emptyList())

    // Only two disjoint questions from the App used are matched; greedy in order of questions
    @Test fun pairsDisjointAppsGreedily() {
        val tasks = listOf(t("a", "settings"), t("b", "settings"), t("c", "clock"), t("d", "clock", "settings"), t("e", "calc"))
        assertEquals(listOf(listOf("a", "c"), listOf("b", "e"), listOf("d")), EvalPairing.groups(tasks, 2).map { g -> g.map { it.id } })
    }

    // I don’t know which apps are used in the questions. I don’t know if they will be bumped. I’ll run
    // them separately; n=1 means serial.
    @Test fun unknownAppsRunAlone() {
        val tasks = listOf(t("a"), t("b", "clock"), t("c", "calc"))
        assertEquals(listOf(listOf("a"), listOf("b", "c")), EvalPairing.groups(tasks, 2).map { g -> g.map { it.id } })
        assertEquals(listOf(listOf("a"), listOf("b"), listOf("c")), EvalPairing.groups(tasks, 1).map { g -> g.map { it.id } })
    }
}
