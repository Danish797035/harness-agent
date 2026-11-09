package com.meenai.mina.core.router.picker

/**
 * Classifies a user task into [TaskDifficulty.Heavy] or [TaskDifficulty.Light]
 * based on task requirements, planned steps, image presence, and action keywords.
 *
 * Rules:
 * - Heavy: Phone/screen control (tap, open app, scroll, change settings), multiple apps,
 *   coding/scripting, multi-step plans (plannedSteps > 3), or image attachments.
 * - Light: Simple factual questions, short answers, translations, single setting query.
 *
 * Uses whole-word and phrase matching for English and Hinglish terms.
 */
object TaskDifficultyClassifier {

    private val KNOWN_APPS = setOf(
        "whatsapp", "chrome", "youtube", "instagram", "facebook", "twitter",
        "telegram", "gmail", "settings", "flipkart", "amazon", "uber", "ola",
        "spotify", "camera", "gallery", "browser", "maps", "drive", "contacts"
    )

    // Heavy Action & Control Keywords (Whole word / phrase patterns)
    private val HEAVY_CONTROL_PATTERNS = listOf(
        Regex("""\b(?:kholo|open|launch|chalao)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:tap|click|touch|press|dabao|select|chuno)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:scroll|swipe|drag|upar\s+karo|neeche\s+karo)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:bhejo|send|post|share|forward)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:type|enter|input)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:call|dial|milao|milaao)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:install|uninstall|download)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:settings?\s+badlo|setting\s+badlo|change\s+settings?)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:band\s+karo|chalu\s+karo|turn\s+on|turn\s+off|switch\s+on|switch\s+off|enable|disable|mute|unmute)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:like\s+karo|comment\s+karo|subscribe\s+karo)\b""", RegexOption.IGNORE_CASE),
    )

    // Heavy Coding Keywords
    private val HEAVY_CODING_PATTERNS = listOf(
        Regex("""\b(?:code\s+likho|write\s+code|coding|script\s+likho|program\s+likho|function\s+likho|debug|algorithm|refactor|implement)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:python|kotlin|java|javascript|c\+\+|sql)\s+(?:script|program|code|function|class)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:write|create|likho)\s+(?:a\s+)?(?:python|kotlin|java|javascript|code|script|program)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:script|algorithm)\b""", RegexOption.IGNORE_CASE),
    )

    // Heavy Workflow / Multi-step patterns
    private val HEAVY_WORKFLOW_PATTERNS = listOf(
        Regex("""\b(?:and\s+then|aur\s+phir|uske\s+baad|phir|after\s+that)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:step\s+\d+)\b""", RegexOption.IGNORE_CASE),
    )

    // Light Patterns (QA, translation, short answer)
    private val LIGHT_PATTERNS = listOf(
        Regex("""\b(?:kya\s+hai|what\s+is|what\s+are|define|meaning\s+of|matlab)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:kaun\s+hai|who\s+is|who\s+was|where\s+is|kahan\s+hai|when\s+is|kab\s+hai|when\s+did|when\s+was|capital\s+of)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:translate|translation|anuvad|anuvad\s+karo)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:hindi\s+me|english\s+me)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:chhota\s+jawab|short\s+answer|briefly|explain\s+briefly|summarize)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:joke|shayari|quote|trivia|fact)\b""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:weather|mausam|time|samay|date|taarikh)\b""", RegexOption.IGNORE_CASE),
    )

    /**
     * Classifies a task into [TaskDifficulty.Heavy] or [TaskDifficulty.Light].
     */
    fun classify(
        task: String,
        hasImage: Boolean = false,
        plannedSteps: Int? = null,
    ): TaskDifficulty {
        // Rule 1: Tasks with images require multimodal processing (Heavy)
        if (hasImage) {
            return TaskDifficulty.Heavy
        }

        // Rule 2: Multi-step plans (> 3 steps) are Heavy
        if (plannedSteps != null && plannedSteps > 3) {
            return TaskDifficulty.Heavy
        }

        val trimmed = task.trim()
        if (trimmed.isEmpty()) {
            return TaskDifficulty.Light
        }

        // Rule 3: Check for multiple apps mentioned in task
        val mentionedAppsCount = countMentionedApps(trimmed)
        if (mentionedAppsCount >= 2) {
            return TaskDifficulty.Heavy
        }

        // Rule 4: Coding requirements are Heavy
        if (HEAVY_CODING_PATTERNS.any { it.containsMatchIn(trimmed) }) {
            return TaskDifficulty.Heavy
        }

        // Rule 5: Screen/phone control and workflow verbs are Heavy
        val hasControlAction = HEAVY_CONTROL_PATTERNS.any { it.containsMatchIn(trimmed) }
        val hasWorkflow = HEAVY_WORKFLOW_PATTERNS.any { it.containsMatchIn(trimmed) }
        if (hasControlAction || hasWorkflow) {
            return TaskDifficulty.Heavy
        }

        // Rule 6: Check for explicit Light patterns
        val hasLightPattern = LIGHT_PATTERNS.any { it.containsMatchIn(trimmed) }
        if (hasLightPattern) {
            return TaskDifficulty.Light
        }

        // Default fallback: short questions or single queries are Light, otherwise Heavy
        val isQuestion = trimmed.endsWith("?") || trimmed.startsWith("how", ignoreCase = true) ||
            trimmed.startsWith("why", ignoreCase = true) || trimmed.startsWith("is", ignoreCase = true)
        return if (isQuestion) TaskDifficulty.Light else TaskDifficulty.Heavy
    }

    /**
     * Maps a task directly to its functional [ModelRole]:
     * - [ModelRole.Vision]: If image input is attached
     * - [ModelRole.Long]: If context length is very large (charCount >= 50,000)
     * - [ModelRole.Coding]: If coding/scripting patterns match
     * - [ModelRole.Light]: If classified as [TaskDifficulty.Light]
     * - [ModelRole.Main]: If classified as [TaskDifficulty.Heavy] (phone control, multi-step, fallback)
     */
    fun classifyToRole(
        task: String,
        hasImage: Boolean = false,
        plannedSteps: Int? = null,
        charCount: Int = 0,
    ): ModelRole {
        if (hasImage) return ModelRole.Vision
        val trimmed = task.trim()
        if (charCount >= 50_000 || trimmed.length >= 50_000) {
            return ModelRole.Long
        }
        if (HEAVY_CODING_PATTERNS.any { it.containsMatchIn(trimmed) }) {
            return ModelRole.Coding
        }
        val difficulty = classify(task, hasImage, plannedSteps)
        return when (difficulty) {
            TaskDifficulty.Heavy -> ModelRole.Main
            TaskDifficulty.Light -> ModelRole.Light
        }
    }

    private fun countMentionedApps(text: String): Int {
        val tokens = text.lowercase().split(Regex("""[\s,;.!?'"()\[\]{}]+""")).toSet()
        return KNOWN_APPS.count { it in tokens }
    }
}
