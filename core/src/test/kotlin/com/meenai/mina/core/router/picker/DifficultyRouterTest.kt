package com.meenai.mina.core.router.picker

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DifficultyRouterTest {

    private val sampleCandidates = listOf(
        ModelCandidate(
            provider = "openrouter",
            id = "meta-llama/llama-3.3-70b-instruct:free",
            contextTokens = 131072,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            tierRank = 3,
            parameterSizeB = 70.0,
        ),
        ModelCandidate(
            provider = "openrouter",
            id = "meta-llama/llama-3.1-8b-instruct:free",
            contextTokens = 131072,
            supportsTools = true,
            supportsImages = false,
            isFree = true,
            tierRank = 17,
            parameterSizeB = 8.0,
        ),
        ModelCandidate(
            provider = "openrouter",
            id = "google/gemini-2.0-flash-exp:free",
            contextTokens = 1048576,
            supportsTools = true,
            supportsImages = true,
            isFree = true,
            tierRank = 11,
            parameterSizeB = 14.0,
        ),
    )

    private val fakePicker = AutoModelPicker(apiClient = { "" })

    @Test
    fun tenExampleTasksClassifiedCorrectly() {
        val classifier = TaskDifficultyClassifier

        // 5 Heavy Tasks:
        // 1. Phone / screen control & message sending
        val heavy1 = "WhatsApp kholo aur Danish ko message bhejo"
        assertEquals(TaskDifficulty.Heavy, classifier.classify(heavy1))

        // 2. Settings modification & device toggle
        val heavy2 = "Settings mein jaakar Bluetooth band karo"
        assertEquals(TaskDifficulty.Heavy, classifier.classify(heavy2))

        // 3. Coding & scripting
        val heavy3 = "Ek Python script likho jo prime numbers generate kare"
        assertEquals(TaskDifficulty.Heavy, classifier.classify(heavy3))

        // 4. Multi-app workflow / web control
        val heavy4 = "Chrome kholo, flipkart par jao aur shoes search karo"
        assertEquals(TaskDifficulty.Heavy, classifier.classify(heavy4))

        // 5. App interaction & social action
        val heavy5 = "Instagram open karo aur latest post like karo"
        assertEquals(TaskDifficulty.Heavy, classifier.classify(heavy5))

        // 5 Light Tasks:
        // 1. Simple factual QA
        val light1 = "France ki capital kya hai?"
        assertEquals(TaskDifficulty.Light, classifier.classify(light1))

        // 2. Translation
        val light2 = "Translate 'Good morning, how are you?' to Hindi"
        assertEquals(TaskDifficulty.Light, classifier.classify(light2))

        // 3. Simple explanation / concept query
        val light3 = "Quantum computing kya hai simple shabdon mein batao"
        assertEquals(TaskDifficulty.Light, classifier.classify(light3))

        // 4. Who-is factual question
        val light4 = "Who is the Prime Minister of India?"
        assertEquals(TaskDifficulty.Light, classifier.classify(light4))

        // 5. Anuvad / translation
        val light5 = "Anuvad karo: 'I am reading a book'"
        assertEquals(TaskDifficulty.Light, classifier.classify(light5))

        // Extra checks: hasImage and plannedSteps overrides
        assertEquals(TaskDifficulty.Heavy, classifier.classify("Kuch bhi sawal", hasImage = true))
        assertEquals(TaskDifficulty.Heavy, classifier.classify("Kuch bhi sawal", plannedSteps = 5))
        assertEquals(TaskDifficulty.Light, classifier.classify("France ki capital kya hai?", plannedSteps = 2))

        // Image generation requests must be classified as Heavy and ModelRole.Main
        val imagePrompt1 = "he harness generate image flying in sky many bird arround delhi park and many people walking in park use best image generation model"
        assertEquals(TaskDifficulty.Heavy, classifier.classify(imagePrompt1))
        assertEquals(ModelRole.Main, classifier.classifyToRole(imagePrompt1))

        val imagePrompt2 = "ek billi ki photo banao"
        assertEquals(TaskDifficulty.Heavy, classifier.classify(imagePrompt2))
        assertEquals(ModelRole.Main, classifier.classifyToRole(imagePrompt2))
    }

    @Test
    fun lightTaskEscalatesToHeavyAfterTwoFailures() = runTest {
        val router = DifficultyRouter(picker = fakePicker)

        // Start with a Light task
        val initialDiff = router.startTask("France ki capital kya hai?")
        assertEquals(TaskDifficulty.Light, initialDiff)
        assertEquals(ModelRole.Light, router.getRole())
        assertFalse(router.isEscalated)

        // Initial model resolution gives Light model (8B)
        val initialModel = router.resolveModel(sampleCandidates)
        assertNotNull(initialModel)
        assertEquals("meta-llama/llama-3.1-8b-instruct:free", initialModel!!.id)

        // 1st failure: still Light
        router.recordFailure()
        assertEquals(1, router.failureCount)
        assertEquals(TaskDifficulty.Light, router.currentDifficulty)
        assertEquals(ModelRole.Light, router.getRole())

        val afterOneFailModel = router.resolveModel(sampleCandidates)
        assertEquals("meta-llama/llama-3.1-8b-instruct:free", afterOneFailModel!!.id)

        // 2nd failure: escalates to Heavy on next step boundary!
        router.recordFailure()
        assertEquals(2, router.failureCount)
        assertEquals(TaskDifficulty.Heavy, router.currentDifficulty)
        assertEquals(ModelRole.Main, router.getRole())
        assertTrue(router.isEscalated)

        // Model resolution now picks Heavy Main model (70B)
        val afterEscalationModel = router.resolveModel(sampleCandidates)
        assertNotNull(afterEscalationModel)
        assertEquals("meta-llama/llama-3.3-70b-instruct:free", afterEscalationModel!!.id)

        // Success does NOT downgrade back to Light within the same task
        router.recordSuccess()
        assertEquals(TaskDifficulty.Heavy, router.currentDifficulty)
        assertEquals(ModelRole.Main, router.getRole())
        val afterSuccessModel = router.resolveModel(sampleCandidates)
        assertEquals("meta-llama/llama-3.3-70b-instruct:free", afterSuccessModel!!.id)
    }

    @Test
    fun lightTaskEscalatesToHeavyOnRepeatedStep() = runTest {
        val router = DifficultyRouter(picker = fakePicker)

        router.startTask("Translate 'Hello' to Hindi")
        assertEquals(TaskDifficulty.Light, router.currentDifficulty)

        // First step execution
        router.recordStep("fetch_translation_dictionary")
        assertEquals(TaskDifficulty.Light, router.currentDifficulty)
        assertFalse(router.isEscalated)

        // Repeating the same step triggers escalation to Heavy
        router.recordStep("fetch_translation_dictionary")
        assertEquals(TaskDifficulty.Heavy, router.currentDifficulty)
        assertTrue(router.isEscalated)
        assertEquals(ModelRole.Main, router.getRole())

        val model = router.resolveModel(sampleCandidates)
        assertEquals("meta-llama/llama-3.3-70b-instruct:free", model!!.id)
    }

    @Test
    fun pinnedModelBypassesRoutingCompletely() = runTest {
        val pinnedId = "meta-llama/llama-3.1-8b-instruct:free"
        val router = DifficultyRouter(picker = fakePicker, pinnedModelId = pinnedId)

        // For a Heavy task, pinned model is still returned (routing disabled)
        val heavyModel = router.route("WhatsApp kholo aur Danish ko message bhejo", candidates = sampleCandidates)
        assertNotNull(heavyModel)
        assertEquals(pinnedId, heavyModel!!.id)

        // For a Light task, pinned model is still returned
        val lightModel = router.route("France ki capital kya hai?", candidates = sampleCandidates)
        assertNotNull(lightModel)
        assertEquals(pinnedId, lightModel!!.id)

        // Even after multiple failures, pinned model remains unchanged
        repeat(5) { router.recordFailure() }
        val afterFailsModel = router.resolveModel(sampleCandidates)
        assertNotNull(afterFailsModel)
        assertEquals(pinnedId, afterFailsModel!!.id)
    }
}
