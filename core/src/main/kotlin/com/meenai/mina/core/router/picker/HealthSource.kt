package com.meenai.mina.core.router.picker

/**
 * Hook for model health status.
 *
 * Provides a health score for a given model ID (e.g. from 0.0 to 1.0 or 100.0).
 * Default implementation returns 1.0 (fully healthy).
 */
fun interface HealthSource {
    fun score(modelId: String): Double

    companion object {
        val DEFAULT = HealthSource { 1.0 }
    }
}
