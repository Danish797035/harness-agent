package com.meenai.mina.ui

import com.meenai.mina.chat.SharedFile
import com.meenai.mina.core.memory.MemoryNote
import com.meenai.mina.core.memory.NoteKind
import com.meenai.mina.core.skills.SkillNote
import com.meenai.mina.task.SteerNote
import com.meenai.mina.task.SteerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatKeysTest {

    @Test
    fun oldItemKeysRemainUnchangedWhenNewItemAdded() {
        val ex1 = Exchange(
            goal = "First question",
            steps = emptyList(),
            status = ExchangeStatus.Done("Answer 1", null),
            live = false,
        )
        val initialKeys = chatListKeys(listOf(ex1)).map { it.first }
        assertEquals(listOf("u:${ex1.id}", "r:${ex1.id}"), initialKeys)

        // Append a second running exchange
        val ex2 = Exchange(
            goal = "Second task",
            steps = emptyList(),
            status = ExchangeStatus.Working,
            live = true,
            draft = "Streaming response...",
        )
        val appendedList = listOf(ex1, ex2)
        val appendedKeys = chatListKeys(appendedList).map { it.first }

        // Existing items' keys must remain completely identical
        assertEquals(initialKeys, appendedKeys.take(initialKeys.size))
        // And no duplicates in the entire list
        assertEquals(appendedKeys.size, appendedKeys.toSet().size)

        // Prepend an earlier historical exchange
        val ex0 = Exchange(
            goal = "Earlier turn",
            steps = emptyList(),
            status = ExchangeStatus.Done("Earlier answer", null),
            live = false,
        )
        val prependedList = listOf(ex0, ex1, ex2)
        val prependedKeys = chatListKeys(prependedList).map { it.first }

        // ex1 and ex2 keys must remain unchanged even though list indices shifted
        assertTrue(prependedKeys.contains("u:${ex1.id}"))
        assertTrue(prependedKeys.contains("r:${ex1.id}"))
        assertTrue(prependedKeys.contains("u:${ex2.id}"))
        assertTrue(prependedKeys.contains("s:${ex2.id}"))
        assertTrue(prependedKeys.contains("draft"))
        assertEquals(prependedKeys.size, prependedKeys.toSet().size)
    }

    @Test
    fun noDuplicateKeysAcrossComplexExchanges() {
        val steer1 = SteerNote("s1", "Steer 1", SteerState.Joined, finished = "Done part", interrupted = "Cut part")
        val steer2 = SteerNote("s2", "Steer 2", SteerState.Received)

        val ex1 = Exchange(
            goal = "Search flight",
            steps = listOf(StepRow(1, "Open app", "", StepState.Ok)),
            status = ExchangeStatus.Done("Flight found", null),
            live = false,
            runId = "run-1",
            files = listOf(SharedFile("ticket.pdf", 1024L, "ticket.pdf", "file://ticket.pdf")),
            memoryNotes = listOf(MemoryNote(NoteKind.added, "m1", "u1", null)),
            skillNotes = listOf(SkillNote("k1", "Learned flight booking")),
            keptPage = "https://airline.example",
            steers = listOf(steer1, steer2),
        )

        val steer3 = SteerNote("s3", "Steer 3", SteerState.Pending)
        val ex2 = Exchange(
            goal = "Book hotel",
            steps = listOf(StepRow(1, "Check rooms", "", StepState.Run)),
            status = ExchangeStatus.Working,
            live = true,
            draft = "Booking room...",
            steers = listOf(steer3),
        )

        val allItems = chatListKeys(listOf(ex1, ex2), phoneSetupNeeded = true)
        val allKeys = allItems.map { it.first }

        // Assert all generated keys are unique (no duplicates whatsoever)
        assertEquals("Duplicate keys found: $allKeys", allKeys.size, allKeys.toSet().size)

        // Verify prefixes
        assertTrue(allKeys.contains("u:${ex1.id}"))
        assertTrue(allKeys.contains("s:${ex1.id}"))
        assertTrue(allKeys.contains("a:${ex1.id}-s1"))
        assertTrue(allKeys.contains("i:${ex1.id}-s1"))
        assertTrue(allKeys.contains("m:${ex1.id}-s1"))
        assertTrue(allKeys.contains("m:${ex1.id}-s2"))
        assertTrue(allKeys.contains("r:${ex1.id}"))
        assertTrue(allKeys.contains("k:${ex1.id}"))
        assertTrue(allKeys.contains("f:${ex1.id}"))
        assertTrue(allKeys.contains("ln:${ex1.id}"))
        assertTrue(allKeys.contains("u:${ex2.id}"))
        assertTrue(allKeys.contains("s:${ex2.id}"))
        assertTrue(allKeys.contains("draft"))
        assertTrue(allKeys.contains("m:${ex2.id}-s3"))
        assertTrue(allKeys.contains("phone-setup"))
    }

    @Test
    fun streamingDraftHasFixedKeyAndBecomesFinalMessageId() {
        // While streaming: draft is present, status is Working
        val exStreaming1 = Exchange(
            goal = "Tell me a joke",
            steps = emptyList(),
            status = ExchangeStatus.Working,
            live = true,
            draft = "Why did the chicken...",
        )
        val streaming1Keys = exchangeItemKeys(exStreaming1)
        val draftItem1 = streaming1Keys.firstOrNull { it.first == "draft" }
        assertNotNull("Draft item must be present with key 'draft'", draftItem1)
        assertEquals("draft", draftItem1?.second)

        // Streaming text updates: key stays fixed as "draft"
        val exStreaming2 = exStreaming1.copy(draft = "Why did the chicken cross the road?")
        val streaming2Keys = exchangeItemKeys(exStreaming2)
        val draftItem2 = streaming2Keys.firstOrNull { it.first == "draft" }
        assertNotNull("Draft item must stay 'draft'", draftItem2)
        assertEquals("draft", draftItem2?.second)

        // Final completion: draft becomes null, status is Done
        val exFinal = exStreaming2.copy(
            status = ExchangeStatus.Done("Why did the chicken cross the road? To get to the other side!", null),
            draft = null,
        )
        val finalKeys = exchangeItemKeys(exFinal)

        // 'draft' must be gone
        assertTrue("Draft must not be present once final", finalKeys.none { it.first == "draft" })
        // Real message id 'r:${exFinal.id}' must be present as assistant
        val finalItem = finalKeys.firstOrNull { it.first == "r:${exFinal.id}" }
        assertNotNull("Final message must have key r:${exFinal.id}", finalItem)
        assertEquals("assistant", finalItem?.second)
    }

    @Test
    fun contentTypesAreAssignedCorrectly() {
        val steer = SteerNote("s1", "Steer note", SteerState.Joined, finished = "Done chunk")
        val ex = Exchange(
            goal = "Task goal",
            steps = listOf(StepRow(1, "Step 1", "", StepState.Ok)),
            status = ExchangeStatus.Done("Task done", null),
            live = false,
            runId = "run-test",
            files = listOf(SharedFile("test.txt", 100L, "test.txt", "uri")),
            memoryNotes = listOf(MemoryNote(NoteKind.added, "m1", "u1", null)),
            steers = listOf(steer),
        )

        val keysAndTypes = chatListKeys(listOf(ex), hasBlocked = true, phoneSetupNeeded = true)
        val typeMap = keysAndTypes.toMap()

        assertEquals("user", typeMap["u:${ex.id}"])
        assertEquals("step", typeMap["s:${ex.id}"])
        assertEquals("assistant", typeMap["a:${ex.id}-s1"])
        assertEquals("user", typeMap["m:${ex.id}-s1"])
        assertEquals("assistant", typeMap["r:${ex.id}"])
        assertEquals("files", typeMap["f:${ex.id}"])
        assertEquals("learning", typeMap["ln:${ex.id}"])
        assertEquals("setup", typeMap["phone-setup"])
        assertEquals("blocked", typeMap["blocked"])
    }

    @Test
    fun withdrawableSteerMatchesBothFormats() {
        val steer = SteerNote("s1", "Change date", SteerState.Received)
        val ex = Exchange(
            goal = "Book ticket",
            steps = emptyList(),
            status = ExchangeStatus.Working,
            live = true,
            steers = listOf(steer),
        )
        val list = listOf(ex)

        // Matches new stable format
        val foundNew = withdrawableSteer(list, "m:${ex.id}-s1")
        assertEquals(steer.id, foundNew?.id)

        // Backward compatibility: matches old index format
        val foundOld = withdrawableSteer(list, "m0-s1")
        assertEquals(steer.id, foundOld?.id)
    }
}
