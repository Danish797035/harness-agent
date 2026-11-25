package com.meenai.mina.chat

import com.meenai.mina.core.agent.Turn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.coroutines.EmptyCoroutineContext

@OptIn(ExperimentalCoroutinesApi::class)
class ConversationDeleterTest {
    @get:Rule val tmp = TemporaryFolder()

    private val active = MutableStateFlow<List<String>>(emptyList())
    private val calls = mutableListOf<String>()
    /** Timed task number → tied dialogue. */
    private val schedules = mutableMapOf("t1" to "a", "t2" to "a", "t3" to "b")
    private val workspaces = mutableSetOf("a", "b")

    private fun deleter(store: ConversationStore) = ConversationDeleter(
        store, active,
        cancelTasks = { calls += "cancel:$it" },
        schedulesOf = { id -> schedules.filterValues { it == id }.keys.sorted() },
        removeSchedule = { calls += "unschedule:$it"; schedules.remove(it) },
        deleteWorkspace = { calls += "workspace:$it"; workspaces.remove(it) },
        stopWaitMs = 5_000,
        io = EmptyCoroutineContext,
    )

    private fun store(): ConversationStore = ConversationStore(tmp.newFolder()).also {
        it.append("a", Turn("查天气", "晴", "succeeded"))
        it.setTitle("a", "天气")
        it.append("b", Turn("订票", "好了", "succeeded"))
    }

    @Test fun impactCountsRunningTaskAndSchedules() {
        val d = deleter(store())
        assertEquals(DeleteImpact(runningTask = false, schedules = 2), d.impact("a"))
        active.value = listOf("b")
        assertEquals(DeleteImpact(runningTask = true, schedules = 1), d.impact("b"))
        assertEquals(DeleteImpact(runningTask = false, schedules = 0), d.impact("c"))
    }

    /** There is no task running: delete its scheduled tasks, workspace and records; other conversations remain unchanged; do not stop the task. */
    @Test fun deletesRecordsWorkspaceAndSchedules() = runTest {
        val store = store()
        store.setPinned("a", true, at = 1L)
        deleter(store).delete("a")
        assertEquals(listOf("unschedule:t1", "unschedule:t2", "workspace:a"), calls)
        assertEquals(mapOf("t3" to "b"), schedules)
        assertEquals(setOf("b"), workspaces)
        assertEquals(listOf("b"), store.list().map { it.id })
        assertEquals(null, store.title("a"))
        assertEquals(null, store.pinnedAt("a"))
    }

    /** If there is a task running: first delete the scheduled task, then stop the task and wait for it to finish (the round saved at the end will also be deleted), and then delete the file. */
    @Test fun stopsTheRunningTaskAndWaitsForItToEnd() = runTest {
        val store = store()
        active.value = listOf("a", "b")
        val job = launch { deleter(store).delete("a") }
        runCurrent()
        assertEquals(listOf("unschedule:t1", "unschedule:t2", "cancel:a"), calls)
        assertTrue("a" in workspaces)
        // Mission Ending: Save the stopped round and then remove it from the ongoing round
        store.append("a", Turn("查天气", "已停止", "cancelled"))
        active.value = listOf("b")
        job.join()
        assertEquals("workspace:a", calls.last())
        assertEquals(listOf("b"), store.list().map { it.id })
        assertFalse("a" in workspaces)
    }

    /** The task has not been completed yet: it will be deleted when the upper limit is reached. */
    @Test fun givesUpWaitingAfterTheLimit() = runTest {
        val store = store()
        active.value = listOf("a")
        val job = launch { deleter(store).delete("a") }
        advanceTimeBy(4_000)
        assertTrue("a" in workspaces)
        advanceTimeBy(2_000)
        job.join()
        assertEquals(emptyList<Turn>(), store.turns("a"))
        assertFalse("a" in workspaces)
    }

    /** Even if the workspace cannot be deleted (the device does not support the operating environment, etc.), the records will still be deleted. */
    @Test fun recordsAreDeletedEvenIfTheWorkspaceFails() = runTest {
        val store = store()
        ConversationDeleter(store, active, {}, { emptyList() }, {}, { error("no sandbox") }, io = EmptyCoroutineContext).delete("a")
        assertEquals(listOf("b"), store.list().map { it.id })
    }
}
