package com.meenai.mina.core.router

import com.meenai.mina.core.model.Author
import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.PromptCaching
import com.meenai.mina.core.model.Role
import com.meenai.mina.core.model.StopReason
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZonedDateTime

class MagicubeLedgerTest {

    private fun mockModel(
        name: String,
        responder: suspend (system: String, messages: List<Message>, tools: List<com.meenai.mina.core.model.ToolSpec>, onText: (String) -> Unit) -> ModelReply,
    ) = MockChatModel(name, Capabilities(toolCalls = true, vision = true, contextTokens = 32_000, reasoning = false, promptCaching = PromptCaching.Implicit), responder)

    private val userMsg = listOf(Message(Role.User, listOf(Part.Text("test")), Author.User))
    private val simpleReply = ModelReply(listOf(Part.Text("done")), StopReason.EndTurn, null)

    @Before
    fun setUp() {
        MagicubeLedger.detachStorage()
        MagicubeLedger.clear()
        RouterUsageTracker.clear()
    }

    @Test
    fun testIstMidnightShortTermReset() {
        // 2026-05-10 23:59:50 IST
        val tBeforeMidnight = ZonedDateTime.of(
            2026, 5, 10, 23, 59, 50, 0,
            MagicubeLedger.IST_ZONE,
        ).toInstant().toEpochMilli()

        // 2026-05-11 00:00:10 IST (crossed midnight in Asia/Kolkata)
        val tAfterMidnight = ZonedDateTime.of(
            2026, 5, 11, 0, 0, 10, 0,
            MagicubeLedger.IST_ZONE,
        ).toInstant().toEpochMilli()

        // Login on day 1 gives 200 short-term
        MagicubeLedger.recordDailyLogin(isAlibabaLinked = false, nowMs = tBeforeMidnight)
        assertEquals(200.0, MagicubeLedger.getShortTermBalance(tBeforeMidnight), 0.001)
        assertEquals(200.0, MagicubeLedger.totalBalance(tBeforeMidnight), 0.001)
        assertTrue(MagicubeLedger.isLoginMarkedToday(tBeforeMidnight))

        // Across IST midnight: short-term balance resets to 0 (no rollover)
        assertEquals(0.0, MagicubeLedger.getShortTermBalance(tAfterMidnight), 0.001)
        assertEquals(0.0, MagicubeLedger.totalBalance(tAfterMidnight), 0.001)
        assertFalse(MagicubeLedger.isLoginMarkedToday(tAfterMidnight))

        // Login on day 2 with Alibaba linked gives 250
        MagicubeLedger.recordDailyLogin(isAlibabaLinked = true, nowMs = tAfterMidnight)
        assertEquals(250.0, MagicubeLedger.getShortTermBalance(tAfterMidnight), 0.001)
        assertEquals(250.0, MagicubeLedger.totalBalance(tAfterMidnight), 0.001)
        assertTrue(MagicubeLedger.isLoginMarkedToday(tAfterMidnight))
    }

    @Test
    fun testShortTermConsumedFirst() {
        val tNow = ZonedDateTime.of(
            2026, 5, 10, 12, 0, 0, 0,
            MagicubeLedger.IST_ZONE,
        ).toInstant().toEpochMilli()

        // 200 short-term + 100 long-term
        MagicubeLedger.recordDailyLogin(isAlibabaLinked = false, nowMs = tNow)
        MagicubeLedger.setLongTermBalance(100.0, expiryEpochMs = tNow + 30 * 86400 * 1000L)
        assertEquals(300.0, MagicubeLedger.totalBalance(tNow), 0.001)

        // Deduct 50: consumes short-term first (200 -> 150), long-term remains 100
        val deducted1 = MagicubeLedger.deduct(50.0, nowMs = tNow)
        assertTrue(deducted1)
        assertEquals(150.0, MagicubeLedger.getShortTermBalance(tNow), 0.001)
        assertEquals(100.0, MagicubeLedger.getLongTermBalance(tNow), 0.001)
        assertEquals(250.0, MagicubeLedger.totalBalance(tNow), 0.001)

        // Deduct 170: exhausts short-term (150 -> 0), remaining 20 deducted from long-term (100 -> 80)
        val deducted2 = MagicubeLedger.deduct(170.0, nowMs = tNow)
        assertTrue(deducted2)
        assertEquals(0.0, MagicubeLedger.getShortTermBalance(tNow), 0.001)
        assertEquals(80.0, MagicubeLedger.getLongTermBalance(tNow), 0.001)
        assertEquals(80.0, MagicubeLedger.totalBalance(tNow), 0.001)
    }

    @Test
    fun testFailedCallNoDeduction() = runTest {
        val tNow = ZonedDateTime.of(
            2026, 5, 10, 12, 0, 0, 0,
            MagicubeLedger.IST_ZONE,
        ).toInstant().toEpochMilli()

        MagicubeLedger.recordDailyLogin(isAlibabaLinked = false, nowMs = tNow)
        assertEquals(200.0, MagicubeLedger.totalBalance(tNow), 0.001)

        var shouldFail = true
        val msModel = mockModel("Qwen/Qwen2.5-72B-Instruct") { _, _, _, _ ->
            if (shouldFail) {
                throw RuntimeException("HTTP 400 Bad Request / Schema Error")
            }
            simpleReply
        }

        val candidate = RouterCandidate(
            id = "Qwen/Qwen2.5-72B-Instruct",
            provider = "modelscope",
            model = msModel,
            curatedRank = 50,
        )

        val router = FreeKeyRouter(
            candidates = listOf(candidate),
            now = { tNow },
            delayMs = {},
        )

        // Failed call (e.g. HTTP 400): should NOT deduct balance
        try {
            router.completeStreaming("sys", userMsg, emptyList()) {}
        } catch (_: Throwable) {}

        assertEquals("Failed call must not deduct balance", 200.0, MagicubeLedger.totalBalance(tNow), 0.001)

        // Successful call: should deduct cost (~2.0 for 72B)
        shouldFail = false
        val tSecondCall = tNow + 70_000L // after 60s cooldown expires
        val router2 = FreeKeyRouter(
            candidates = listOf(candidate),
            now = { tSecondCall },
            delayMs = {},
        )
        router2.completeStreaming("sys", userMsg, emptyList()) {}

        val cost = MagicubeLedger.costForModel("Qwen/Qwen2.5-72B-Instruct")
        assertEquals(2.0, cost, 0.001)
        assertEquals("Successful call must deduct cost", 200.0 - cost, MagicubeLedger.totalBalance(tSecondCall), 0.001)
    }

    @Test
    fun testBalanceBelowFiveSkipsModelScope() {
        val tNow = ZonedDateTime.of(
            2026, 5, 10, 12, 0, 0, 0,
            MagicubeLedger.IST_ZONE,
        ).toInstant().toEpochMilli()

        val msModel = mockModel("Qwen/Qwen2.5-7B-Instruct") { _, _, _, _ -> simpleReply }
        val candidate = RouterCandidate(
            id = "Qwen/Qwen2.5-7B-Instruct",
            provider = "modelscope",
            model = msModel,
            curatedRank = 50,
        )
        val router = FreeKeyRouter(
            candidates = listOf(candidate),
            now = { tNow },
            delayMs = {},
        )

        // When balance is below 5 (e.g. 4.0): ModelScope is avoided/skipped
        MagicubeLedger.syncBalance(4.0, nowMs = tNow)
        assertEquals(4.0, MagicubeLedger.totalBalance(tNow), 0.001)
        assertFalse(MagicubeLedger.canAffordModelScope(tNow))
        assertFalse(router.cooldownStore.isAvailable("Qwen/Qwen2.5-7B-Instruct", "modelscope", tNow))
        assertFalse(router.checkCachedQuota(candidate))
        assertNull("ModelScope should be skipped when balance < 5", router.pickBestCandidate())

        // When balance is 5 or more (e.g. 5.0): ModelScope is available
        MagicubeLedger.syncBalance(5.0, nowMs = tNow)
        assertEquals(5.0, MagicubeLedger.totalBalance(tNow), 0.001)
        assertTrue(MagicubeLedger.canAffordModelScope(tNow))
        assertTrue(router.cooldownStore.isAvailable("Qwen/Qwen2.5-7B-Instruct", "modelscope", tNow))
        assertTrue(router.checkCachedQuota(candidate))
        assertEquals("Qwen/Qwen2.5-7B-Instruct", router.pickBestCandidate()?.id)
    }

    @Test
    fun testLoginMarkedTodaySkipsReminder() {
        // 2026-05-10 08:30:00 IST (before 9:00 IST)
        val tBefore9 = ZonedDateTime.of(
            2026, 5, 10, 8, 30, 0, 0,
            MagicubeLedger.IST_ZONE,
        ).toInstant().toEpochMilli()

        // 2026-05-10 09:15:00 IST (after 9:00 IST)
        val tAfter9 = ZonedDateTime.of(
            2026, 5, 10, 9, 15, 0, 0,
            MagicubeLedger.IST_ZONE,
        ).toInstant().toEpochMilli()

        // 2026-05-11 09:05:00 IST (next day after 9:00 IST)
        val tNextDay9 = ZonedDateTime.of(
            2026, 5, 11, 9, 5, 0, 0,
            MagicubeLedger.IST_ZONE,
        ).toInstant().toEpochMilli()

        // Before 9:00 IST: do not send reminder
        assertFalse(MagicubeLedger.shouldSendLoginReminder(tBefore9))

        // At 9:15 IST when not logged in: should send reminder
        assertTrue(MagicubeLedger.shouldSendLoginReminder(tAfter9))

        // After logging in today: reminder skipped
        MagicubeLedger.recordDailyLogin(isAlibabaLinked = false, nowMs = tAfter9)
        assertTrue(MagicubeLedger.isLoginMarkedToday(tAfter9))
        assertFalse("Already logged in today must skip reminder", MagicubeLedger.shouldSendLoginReminder(tAfter9))

        // Next day at 9:05 IST: should send reminder again until logged in on day 2
        assertTrue("Next day should remind again", MagicubeLedger.shouldSendLoginReminder(tNextDay9))
    }

    @Test
    fun testSaveAndReloadPreservesBalanceAndDays() {
        val tNow = ZonedDateTime.of(
            2026, 5, 10, 15, 30, 0, 0,
            MagicubeLedger.IST_ZONE,
        ).toInstant().toEpochMilli()

        val mockStorage = object : MagicubeStorage {
            var savedState: MagicubeState? = null
            override fun load(): MagicubeState? = savedState
            override fun save(state: MagicubeState) {
                savedState = state
            }
        }

        MagicubeLedger.attachStorage(mockStorage, nowMs = tNow)
        MagicubeLedger.recordDailyLogin(isAlibabaLinked = true, nowMs = tNow)
        MagicubeLedger.setLongTermBalance(100.0, expiryEpochMs = tNow + 86400_000L)
        MagicubeLedger.deduct(30.0, nowMs = tNow) // 250 - 30 = 220 short-term

        val originalDayKey = MagicubeLedger.todayKey(tNow)
        assertEquals(220.0, MagicubeLedger.getShortTermBalance(tNow), 0.001)
        assertEquals(100.0, MagicubeLedger.getLongTermBalance(tNow), 0.001)
        assertEquals(320.0, MagicubeLedger.totalBalance(tNow), 0.001)

        // Clear memory to simulate app restart
        MagicubeLedger.detachStorage()
        MagicubeLedger.clear()
        assertEquals(0.0, MagicubeLedger.totalBalance(tNow), 0.001)

        // Reload on same day: balance and day keys are restored
        MagicubeLedger.attachStorage(mockStorage, nowMs = tNow)
        assertEquals(220.0, MagicubeLedger.getShortTermBalance(tNow), 0.001)
        assertEquals(100.0, MagicubeLedger.getLongTermBalance(tNow), 0.001)
        assertEquals(320.0, MagicubeLedger.totalBalance(tNow), 0.001)
        assertTrue(MagicubeLedger.isAlibabaLinked)
        assertTrue(MagicubeLedger.isLoginMarkedToday(tNow))
        assertEquals(originalDayKey, MagicubeLedger.toState().shortTermDayKey)
    }

    @Test
    fun testNextDayLoadResetsShortTermToZero() {
        // Day 1
        val tDay1 = ZonedDateTime.of(
            2026, 5, 10, 20, 0, 0, 0,
            MagicubeLedger.IST_ZONE,
        ).toInstant().toEpochMilli()

        // Day 2 (after IST midnight)
        val tDay2 = ZonedDateTime.of(
            2026, 5, 11, 8, 0, 0, 0,
            MagicubeLedger.IST_ZONE,
        ).toInstant().toEpochMilli()

        val mockStorage = object : MagicubeStorage {
            var savedState: MagicubeState? = null
            override fun load(): MagicubeState? = savedState
            override fun save(state: MagicubeState) {
                savedState = state
            }
        }

        MagicubeLedger.attachStorage(mockStorage, nowMs = tDay1)
        MagicubeLedger.recordDailyLogin(isAlibabaLinked = false, nowMs = tDay1)
        assertEquals(200.0, MagicubeLedger.getShortTermBalance(tDay1), 0.001)

        // Simulate app restart on Day 2
        MagicubeLedger.detachStorage()
        MagicubeLedger.clear()

        // Load on Day 2: short-term balance must be 0 (no rollover across IST midnight)
        MagicubeLedger.attachStorage(mockStorage, nowMs = tDay2)
        assertEquals("Short-term must reset to 0 across IST midnight", 0.0, MagicubeLedger.getShortTermBalance(tDay2), 0.001)
        assertEquals("Total balance should be 0", 0.0, MagicubeLedger.totalBalance(tDay2), 0.001)
        assertFalse("Day 2 login not yet marked", MagicubeLedger.isLoginMarkedToday(tDay2))
    }

    @Test
    fun testRecordDailyLoginGrantsBalanceAndSkipsReminder() {
        val tDay1 = ZonedDateTime.of(
            2026, 5, 10, 9, 30, 0, 0,
            MagicubeLedger.IST_ZONE,
        ).toInstant().toEpochMilli()

        // Before login: balance is 0 and reminder is due
        assertEquals(0.0, MagicubeLedger.totalBalance(tDay1), 0.001)
        assertTrue(MagicubeLedger.shouldSendLoginReminder(tDay1))

        // "Aaj login kiya" without Alibaba linked: balance is 200, reminder skipped
        MagicubeLedger.recordDailyLogin(isAlibabaLinked = false, nowMs = tDay1)
        assertEquals(200.0, MagicubeLedger.getShortTermBalance(tDay1), 0.001)
        assertEquals(200.0, MagicubeLedger.totalBalance(tDay1), 0.001)
        assertTrue(MagicubeLedger.isLoginMarkedToday(tDay1))
        assertFalse("Reminder must be skipped once logged in today", MagicubeLedger.shouldSendLoginReminder(tDay1))

        // Day 2: "Aaj login kiya" with Alibaba linked: balance is 250 (200 + 50)
        val tDay2 = ZonedDateTime.of(
            2026, 5, 11, 10, 0, 0, 0,
            MagicubeLedger.IST_ZONE,
        ).toInstant().toEpochMilli()

        MagicubeLedger.clear()
        assertTrue(MagicubeLedger.shouldSendLoginReminder(tDay2))

        MagicubeLedger.recordDailyLogin(isAlibabaLinked = true, nowMs = tDay2)
        assertEquals(250.0, MagicubeLedger.getShortTermBalance(tDay2), 0.001)
        assertEquals(250.0, MagicubeLedger.totalBalance(tDay2), 0.001)
        assertTrue(MagicubeLedger.isLoginMarkedToday(tDay2))
        assertFalse(MagicubeLedger.shouldSendLoginReminder(tDay2))
    }

    @Test
    fun testNextNineAmIstCalculation() {
        // 2026-05-10 08:30:00 IST -> target is 2026-05-10 09:00:00 IST
        val tBefore9 = ZonedDateTime.of(
            2026, 5, 10, 8, 30, 0, 0,
            MagicubeLedger.IST_ZONE,
        ).toInstant().toEpochMilli()

        val expectedToday9 = ZonedDateTime.of(
            2026, 5, 10, 9, 0, 0, 0,
            MagicubeLedger.IST_ZONE,
        ).toInstant().toEpochMilli()

        assertEquals(expectedToday9, MagicubeLedger.nextNineAmIst(tBefore9))

        // 2026-05-10 09:30:00 IST -> target is 2026-05-11 09:00:00 IST
        val tAfter9 = ZonedDateTime.of(
            2026, 5, 10, 9, 30, 0, 0,
            MagicubeLedger.IST_ZONE,
        ).toInstant().toEpochMilli()

        val expectedTomorrow9 = ZonedDateTime.of(
            2026, 5, 11, 9, 0, 0, 0,
            MagicubeLedger.IST_ZONE,
        ).toInstant().toEpochMilli()

        assertEquals(expectedTomorrow9, MagicubeLedger.nextNineAmIst(tAfter9))
    }

    @Test
    fun testAtomicDeductOrNothingWhenInsufficientBalance() {
        val tNow = ZonedDateTime.of(
            2026, 5, 10, 12, 0, 0, 0,
            MagicubeLedger.IST_ZONE,
        ).toInstant().toEpochMilli()

        MagicubeLedger.setLongTermBalance(4.0, expiryEpochMs = tNow + 86400 * 1000L)
        assertEquals(4.0, MagicubeLedger.totalBalance(tNow), 0.001)

        val success = MagicubeLedger.deduct(5.0, nowMs = tNow)
        assertFalse("deduct must return false when balance < cost", success)
        assertEquals("balance must stay 4.0 when payment fails", 4.0, MagicubeLedger.totalBalance(tNow), 0.001)
        assertEquals(4.0, MagicubeLedger.getLongTermBalance(tNow), 0.001)
    }
}
