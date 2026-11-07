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
    fun twoExchangesKeysRemainSameWhenThirdAddedAndNoDuplicates() {
        val ex1 = Exchange(
            goal = "First question",
            steps = listOf(StepRow(1, "Search", "", StepState.Ok)),
            status = ExchangeStatus.Done("Answer 1", null),
            live = false,
        )
        val ex2 = Exchange(
            goal = "Second task",
            steps = emptyList(),
            status = ExchangeStatus.Working,
            live = true,
            draft = "Draft streaming...",
        )

        // Do exchange ke saath keys nikaalo
        val initialTwoKeys = chatListKeys(listOf(ex1, ex2)).map { it.first }
        assertEquals(
            listOf("u:${ex1.id}", "s:${ex1.id}", "r:${ex1.id}", "u:${ex2.id}", "draft:${ex2.id}"),
            initialTwoKeys,
        )

        // Teesra add karo
        val ex3 = Exchange(
            goal = "Third question",
            steps = listOf(StepRow(1, "Query", "", StepState.Run)),
            status = ExchangeStatus.Working,
            live = true,
            draft = null,
        )
        val withThirdKeys = chatListKeys(listOf(ex1, ex2, ex3)).map { it.first }

        // Purani keys same rahein
        assertEquals(initialTwoKeys, withThirdKeys.take(initialTwoKeys.size))
        // Koi duplicate na ho
        assertEquals(withThirdKeys.size, withThirdKeys.toSet().size)
    }

    @Test
    fun draftKeyAndFinalAnswerKey() {
        // Draft key: "draft:${ex.id}", contentType "draft"
        val exStreaming = Exchange(
            goal = "Tell me a joke",
            steps = emptyList(),
            status = ExchangeStatus.Working,
            live = true,
            draft = "Why did the chicken...",
        )
        val streamingKeys = exchangeItemKeys(exStreaming)
        val draftItem = streamingKeys.firstOrNull { it.first == "draft:${exStreaming.id}" }
        assertNotNull("Draft item must be present with key draft:\${ex.id}", draftItem)
        assertEquals("draft", draftItem?.second)

        // Final answer key: "r:${ex.id}", contentType "assistant"
        val exDone = exStreaming.copy(
            status = ExchangeStatus.Done("To get to the other side!", null),
            draft = null,
        )
        val doneKeys = exchangeItemKeys(exDone)
        assertTrue(doneKeys.none { it.first.startsWith("draft:") })
        val answerItem = doneKeys.firstOrNull { it.first == "r:${exDone.id}" }
        assertNotNull("Final message must have key r:\${ex.id}", answerItem)
        assertEquals("assistant", answerItem?.second)
    }

    @Test
    fun allExpectedKeysAndContentTypes() {
        val steer1 = SteerNote("s1", "Steer 1", SteerState.Joined, finished = "Done part", interrupted = "Cut part")
        val steer2 = SteerNote("s2", "Steer 2", SteerState.Received)

        val ex = Exchange(
            goal = "Task goal",
            steps = listOf(StepRow(1, "Step 1", "", StepState.Ok)),
            status = ExchangeStatus.Done("Task done", null),
            live = false,
            runId = "run-test-id",
            files = listOf(SharedFile("test.txt", 100L, "test.txt", "uri")),
            memoryNotes = listOf(MemoryNote(NoteKind.added, "m1", "u1", null)),
            keptPage = "https://example.com",
            steers = listOf(steer1, steer2),
        )

        val keysAndTypes = chatListKeys(listOf(ex), hasBlocked = true, phoneSetupNeeded = true)
        val typeMap = keysAndTypes.toMap()

        assertEquals("user", typeMap["u:${ex.id}"])
        assertEquals("step", typeMap["s:${ex.id}"])
        assertEquals("assistant", typeMap["a:${ex.id}-s1"])
        assertEquals("assistant", typeMap["i:${ex.id}-s1"])
        assertEquals("user", typeMap["m:${ex.id}-s1"])
        assertEquals("user", typeMap["m:${ex.id}-s2"])
        assertEquals("assistant", typeMap["r:${ex.id}"])
        assertEquals("kept", typeMap["k:${ex.id}"])
        assertEquals("files", typeMap["f:${ex.id}"])
        assertEquals("learning", typeMap["ln:${ex.id}"])
        assertEquals("setup", typeMap["phone-setup"])
        assertEquals("blocked", typeMap["blocked"])
    }

    @Test
    fun exchangeIdCreationPrefersRunIdOrFallsBackToUuid() {
        val withRunId = Exchange(
            goal = "Goal with runId",
            steps = emptyList(),
            status = ExchangeStatus.Done("Answer", null),
            live = false,
            runId = "fixed-run-123",
        )
        assertEquals("fixed-run-123", withRunId.id)

        val withoutRunId = Exchange(
            goal = "Goal without runId",
            steps = emptyList(),
            status = ExchangeStatus.Working,
            live = true,
        )
        assertNotNull(withoutRunId.id)
        assertTrue(withoutRunId.id.isNotBlank())
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

        // Matches new "m:" format
        val foundNew = withdrawableSteer(list, "m:${ex.id}-s1")
        assertEquals(steer.id, foundNew?.id)

        // Matches legacy "m$i-" format
        val foundOld = withdrawableSteer(list, "m0-s1")
        assertEquals(steer.id, foundOld?.id)
    }
}
