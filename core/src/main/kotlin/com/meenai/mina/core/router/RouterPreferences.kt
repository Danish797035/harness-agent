package com.meenai.mina.core.router

enum class SpecialistMode {
    Auto,
    Manual,
}

enum class AutoStrategy {
    FreeOnly,
    BestQuality,
    Fastest,
}

interface RouterPreferences {
    fun isSpecialistAutoEnabled(): Boolean = true
    fun getSpecialistMode(): SpecialistMode = SpecialistMode.Auto
    fun getAutoStrategy(): AutoStrategy = AutoStrategy.FreeOnly
    fun isFreeOnly(): Boolean = true
    fun isFailoverEnabled(): Boolean = true
    fun getMaxSwitches(): Int = 6
    fun getPreferredProviders(): List<String> = DEFAULT_PROVIDER_ORDER
    fun getHiddenProviders(): Set<String> = emptySet()
    fun getHiddenModels(): Set<String> = emptySet()
    fun getManualRole(role: String): String? = null

    companion object {
        val DEFAULT_PROVIDER_ORDER = listOf(
            "gemini",
            "zai",
            "groq",
            "openrouter",
            "deepseek",
            "kimi",
            "minimax",
            "modelscope",
        )
    }
}

object DefaultRouterPreferences : RouterPreferences
