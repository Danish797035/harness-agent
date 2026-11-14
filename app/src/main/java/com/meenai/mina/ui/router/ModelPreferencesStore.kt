package com.meenai.mina.ui.router

import android.content.Context
import android.content.SharedPreferences

enum class SpecialistMode {
    Auto,
    Manual,
}

enum class AutoStrategy {
    FreeOnly,
    BestQuality,
    Fastest,
}

class ModelPreferencesStore(private val prefs: SharedPreferences) {

    fun getFavorites(): Set<String> {
        return prefs.getStringSet(KEY_FAVORITES, emptySet()).orEmpty()
    }

    fun isFavorite(modelId: String): Boolean {
        return getFavorites().contains(modelId)
    }

    fun toggleFavorite(modelId: String): Boolean {
        val current = getFavorites().toMutableSet()
        val newState = if (current.contains(modelId)) {
            current.remove(modelId)
            false
        } else {
            current.add(modelId)
            true
        }
        prefs.edit().putStringSet(KEY_FAVORITES, current).apply()
        return newState
    }

    fun setFavorite(modelId: String, favorite: Boolean) {
        val current = getFavorites().toMutableSet()
        if (favorite) {
            current.add(modelId)
        } else {
            current.remove(modelId)
        }
        prefs.edit().putStringSet(KEY_FAVORITES, current).apply()
    }

    fun getRecentModels(): List<String> {
        val raw = prefs.getString(KEY_RECENTS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(",").filter { it.isNotBlank() }
    }

    fun recordRecent(modelId: String) {
        if (modelId.isBlank()) return
        val current = getRecentModels().toMutableList()
        current.remove(modelId)
        current.add(0, modelId)
        val trimmed = current.take(MAX_RECENTS)
        prefs.edit().putString(KEY_RECENTS, trimmed.joinToString(",")).apply()
    }

    // --- Specialist Mode Settings ---

    fun isSpecialistAutoEnabled(): Boolean =
        prefs.getBoolean(KEY_SPECIALIST_AUTO, true)

    fun setSpecialistAutoEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SPECIALIST_AUTO, enabled).apply()
    }

    fun getSpecialistMode(): SpecialistMode {
        val raw = prefs.getString(KEY_SPECIALIST_MODE, SpecialistMode.Auto.name)
        return runCatching { SpecialistMode.valueOf(raw ?: SpecialistMode.Auto.name) }.getOrDefault(SpecialistMode.Auto)
    }

    fun setSpecialistMode(mode: SpecialistMode) {
        prefs.edit().putString(KEY_SPECIALIST_MODE, mode.name).apply()
    }

    fun getAutoStrategy(): AutoStrategy {
        val raw = prefs.getString(KEY_AUTO_STRATEGY, AutoStrategy.FreeOnly.name)
        return runCatching { AutoStrategy.valueOf(raw ?: AutoStrategy.FreeOnly.name) }.getOrDefault(AutoStrategy.FreeOnly)
    }

    fun setAutoStrategy(strategy: AutoStrategy) {
        prefs.edit().putString(KEY_AUTO_STRATEGY, strategy.name).apply()
    }

    fun isFreeOnly(): Boolean =
        prefs.getBoolean(KEY_FREE_ONLY, true)

    fun setFreeOnly(freeOnly: Boolean) {
        prefs.edit().putBoolean(KEY_FREE_ONLY, freeOnly).apply()
    }

    fun isFailoverEnabled(): Boolean =
        prefs.getBoolean(KEY_FAILOVER_ENABLED, true)

    fun setFailoverEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FAILOVER_ENABLED, enabled).apply()
    }

    fun getMaxSwitches(): Int =
        prefs.getInt(KEY_MAX_SWITCHES, 2)

    fun setMaxSwitches(max: Int) {
        prefs.edit().putInt(KEY_MAX_SWITCHES, max.coerceIn(1, 3)).apply()
    }

    fun getPreferredProviders(): List<String> {
        val raw = prefs.getString(KEY_PREFERRED_PROVIDERS, "") ?: ""
        if (raw.isBlank()) return DEFAULT_PROVIDER_ORDER
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun setPreferredProviders(providers: List<String>) {
        prefs.edit().putString(KEY_PREFERRED_PROVIDERS, providers.joinToString(",")).apply()
    }

    fun getHiddenProviders(): Set<String> =
        prefs.getStringSet(KEY_HIDDEN_PROVIDERS, emptySet()).orEmpty()

    fun setHiddenProviders(providers: Set<String>) {
        prefs.edit().putStringSet(KEY_HIDDEN_PROVIDERS, providers).apply()
    }

    fun getHiddenModels(): Set<String> =
        prefs.getStringSet(KEY_HIDDEN_MODELS, emptySet()).orEmpty()

    fun setHiddenModels(models: Set<String>) {
        prefs.edit().putStringSet(KEY_HIDDEN_MODELS, models).apply()
    }

    fun getManualRole(role: String): String? =
        prefs.getString(KEY_MANUAL_ROLE_PREFIX + role.lowercase(), null)

    fun setManualRole(role: String, modelId: String?) {
        if (modelId.isNullOrBlank()) {
            prefs.edit().remove(KEY_MANUAL_ROLE_PREFIX + role.lowercase()).apply()
        } else {
            prefs.edit().putString(KEY_MANUAL_ROLE_PREFIX + role.lowercase(), modelId).apply()
        }
    }

    companion object {
        const val PREFS_NAME = "mina_model_picker_prefs"
        private const val KEY_FAVORITES = "favorite_models"
        private const val KEY_RECENTS = "recent_models"
        private const val KEY_SPECIALIST_AUTO = "specialist_auto_enabled"
        private const val KEY_SPECIALIST_MODE = "specialist_mode"
        private const val KEY_AUTO_STRATEGY = "auto_strategy"
        private const val KEY_FREE_ONLY = "free_only"
        private const val KEY_FAILOVER_ENABLED = "failover_enabled"
        private const val KEY_MAX_SWITCHES = "max_switches"
        private const val KEY_PREFERRED_PROVIDERS = "preferred_providers"
        private const val KEY_HIDDEN_PROVIDERS = "hidden_providers"
        private const val KEY_HIDDEN_MODELS = "hidden_models"
        private const val KEY_MANUAL_ROLE_PREFIX = "manual_role_"
        const val MAX_RECENTS = 5

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

        fun get(context: Context): ModelPreferencesStore {
            val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return ModelPreferencesStore(sp)
        }
    }
}
