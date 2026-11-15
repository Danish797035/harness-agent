package com.meenai.mina.core.router

import com.meenai.mina.core.model.Capabilities
import com.meenai.mina.core.model.ChatModel
import com.meenai.mina.core.model.Message
import com.meenai.mina.core.model.ModelError
import com.meenai.mina.core.model.ModelReply
import com.meenai.mina.core.model.Part
import com.meenai.mina.core.model.PromptCaching
import com.meenai.mina.core.model.StopReason
import com.meenai.mina.core.model.ToolSpec
import com.meenai.mina.core.router.picker.ModelRole
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouterPreferencesTest {

    private fun createDummyModel(id: String, vision: Boolean = false, tools: Boolean = true): ChatModel =
        object : ChatModel {
            override val name: String = id
            override val capabilities: Capabilities = Capabilities(
                toolCalls = tools,
                vision = vision,
                contextTokens = 64_000,
                reasoning = false,
                promptCaching = PromptCaching.Implicit,
            )

            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply =
                ModelReply(listOf(Part.Text("OK from $id")), StopReason.EndTurn, null)
        }

    @Test
    fun manualRoleSelectionOverridesAutomaticSelection() {
        val c1 = RouterCandidate("gemini-2.5-flash", "gemini", createDummyModel("gemini-2.5-flash"), roles = setOf(ModelRole.Main))
        val c2 = RouterCandidate("groq-llama-70b", "groq", createDummyModel("groq-llama-70b"), roles = setOf(ModelRole.Main))

        val prefs = object : RouterPreferences {
            override fun getSpecialistMode(): SpecialistMode = SpecialistMode.Manual
            override fun getManualRole(role: String): String? = if (role.equals("main", ignoreCase = true)) "groq-llama-70b" else null
        }

        val router = FreeKeyRouter(listOf(c1, c2), preferences = prefs, delayMs = {})
        val picked = router.pickBestCandidate(ModelRole.Main)

        assertNotNull(picked)
        assertEquals("groq-llama-70b", picked?.id)
    }

    @Test
    fun hiddenModelsAndProvidersAreExcluded() {
        val c1 = RouterCandidate("gemini-2.5-flash", "gemini", createDummyModel("gemini-2.5-flash"))
        val c2 = RouterCandidate("groq-llama-70b", "groq", createDummyModel("groq-llama-70b"))
        val c3 = RouterCandidate("zai-glm-4.7-flash", "zai", createDummyModel("zai-glm-4.7-flash"))

        val prefs = object : RouterPreferences {
            override fun getHiddenModels(): Set<String> = setOf("gemini-2.5-flash")
            override fun getHiddenProviders(): Set<String> = setOf("groq")
        }

        val router = FreeKeyRouter(listOf(c1, c2, c3), preferences = prefs, delayMs = {})
        val picked = router.pickBestCandidate(ModelRole.Main)

        assertNotNull(picked)
        assertEquals("zai-glm-4.7-flash", picked?.id)
    }

    @Test
    fun freeOnlyStrategyFiltersNonFreeModels() {
        val cFree = RouterCandidate("gemini-2.5-flash", "gemini", createDummyModel("gemini-2.5-flash"), isFree = true)
        val cPaid = RouterCandidate("kimi-k3", "kimi", createDummyModel("kimi-k3"), isFree = false)

        val prefs = object : RouterPreferences {
            override fun isFreeOnly(): Boolean = true
        }

        val router = FreeKeyRouter(listOf(cPaid, cFree), preferences = prefs, delayMs = {})
        val picked = router.pickBestCandidate(ModelRole.Main)

        assertNotNull(picked)
        assertEquals("gemini-2.5-flash", picked?.id)
    }

    @Test
    fun fastestStrategyPrioritizesSmallerParameters() {
        val cLarge = RouterCandidate("llama-3.3-70b-versatile", "groq", createDummyModel("llama-3.3-70b-versatile"))
        val cSmall = RouterCandidate("llama-3.1-8b-instant", "groq", createDummyModel("llama-3.1-8b-instant"))

        val prefs = object : RouterPreferences {
            override fun getAutoStrategy(): AutoStrategy = AutoStrategy.Fastest
        }

        val router = FreeKeyRouter(listOf(cLarge, cSmall), preferences = prefs, delayMs = {})
        val picked = router.pickBestCandidate(ModelRole.Main)

        assertNotNull(picked)
        assertEquals("llama-3.1-8b-instant", picked?.id)
    }

    @Test
    fun preferredProvidersOrderingPrioritizesHigherRankedProvider() {
        val cGemini = RouterCandidate("gemini-2.5-flash", "gemini", createDummyModel("gemini-2.5-flash"))
        val cZai = RouterCandidate("glm-4.7-flash", "zai", createDummyModel("glm-4.7-flash"))

        // Zai preferred over Gemini
        val prefs = object : RouterPreferences {
            override fun getPreferredProviders(): List<String> = listOf("zai", "gemini")
        }

        val router = FreeKeyRouter(listOf(cGemini, cZai), preferences = prefs, delayMs = {})
        val picked = router.pickBestCandidate(ModelRole.Main)

        assertNotNull(picked)
        assertEquals("glm-4.7-flash", picked?.id)
    }

    @Test
    fun failoverDisabledPreventsEmptyReplyFailover() {
        val c1 = RouterCandidate("gemini-2.5-flash", "gemini", createDummyModel("gemini-2.5-flash"))
        val c2 = RouterCandidate("glm-4.7-flash", "zai", createDummyModel("glm-4.7-flash"))

        val prefs = object : RouterPreferences {
            override fun isFailoverEnabled(): Boolean = false
        }

        val router = FreeKeyRouter(listOf(c1, c2), preferences = prefs, delayMs = {})
        val next = router.failoverEmptyReply("gemini-2.5-flash")

        assertNull("When failover is disabled, failoverEmptyReply must return null", next)
    }

    @Test
    fun failoverDisabledThrowsImmediatelyOnErrors() = runBlocking {
        val failingModel = object : ChatModel {
            override val capabilities: Capabilities = Capabilities(toolCalls = true, vision = false, contextTokens = 64_000, reasoning = false, promptCaching = PromptCaching.Implicit)
            override suspend fun complete(system: String, messages: List<Message>, tools: List<ToolSpec>): ModelReply {
                throw ModelError.RateLimited(60_000L)
            }
        }
        val backupModel = createDummyModel("backup-model")

        val c1 = RouterCandidate("failing-model", "test", failingModel)
        val c2 = RouterCandidate("backup-model", "test", backupModel)

        val prefs = object : RouterPreferences {
            override fun isFailoverEnabled(): Boolean = false
        }

        val router = FreeKeyRouter(listOf(c1, c2), preferences = prefs, delayMs = {})

        var threwRateLimit = false
        try {
            router.complete("sys", emptyList(), emptyList())
        } catch (e: ModelError.RateLimited) {
            threwRateLimit = true
        }

        assertTrue("When failover is disabled, errors must be thrown immediately without switching candidate", threwRateLimit)
    }

    @Test
    fun maxSwitchesRespectsConfiguredPreference() {
        val c1 = RouterCandidate("m1", "p1", createDummyModel("m1"))
        val c2 = RouterCandidate("m2", "p2", createDummyModel("m2"))

        val prefs = object : RouterPreferences {
            override fun getMaxSwitches(): Int = 1
        }

        val router = FreeKeyRouter(listOf(c1, c2), preferences = prefs, maxSwitchesPerTask = 6, delayMs = {})
        assertEquals(1, router.effectiveMaxSwitches)

        // First switch allowed
        val next1 = router.failoverEmptyReply("m1")
        assertNotNull(next1)

        // Second switch rejected due to maxSwitches = 1
        val next2 = router.failoverEmptyReply(next1?.id)
        assertNull(next2)
    }
}
