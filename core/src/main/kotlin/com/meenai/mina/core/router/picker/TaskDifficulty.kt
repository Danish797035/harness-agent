package com.meenai.mina.core.router.picker

/**
 * Difficulty level of an incoming task.
 *
 * - [Light]: Fast, simple factual questions, short replies, translations, single setting query.
 * - [Heavy]: Screen control, app interaction, coding, long plans (>3 steps), multi-app workflows, or image input.
 */
enum class TaskDifficulty {
    Light,
    Heavy,
}
