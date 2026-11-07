package com.meenai.mina.core.router.picker

/**
 * Functional roles for model selection.
 *
 * - [Main]: Heavy frontier model for complex reasoning and main execution.
 * - [Light]: Fast, lightweight model for swift low-latency tasks.
 * - [Vision]: Model optimized for multimodal image understanding.
 */
enum class ModelRole {
    Main,
    Light,
    Vision,
}
