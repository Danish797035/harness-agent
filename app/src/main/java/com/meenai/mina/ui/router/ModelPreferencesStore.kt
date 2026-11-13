package com.meenai.mina.ui.router

import android.content.Context
import android.content.SharedPreferences

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

    companion object {
        const val PREFS_NAME = "mina_model_picker_prefs"
        private const val KEY_FAVORITES = "favorite_models"
        private const val KEY_RECENTS = "recent_models"
        const val MAX_RECENTS = 5

        fun get(context: Context): ModelPreferencesStore {
            val sp = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return ModelPreferencesStore(sp)
        }
    }
}
