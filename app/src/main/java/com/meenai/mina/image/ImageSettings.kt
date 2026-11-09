package com.meenai.mina.image

import android.content.Context
import android.content.SharedPreferences
import com.meenai.mina.core.image.ImageRole
import com.meenai.mina.settings.FeatureFlags
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings and state for Image and Image Edit roles:
 * - Paid image models switch (default OFF)
 * - Pinned model or Auto for Image (default Auto -> Qwen/Qwen-Image-2.1)
 * - Pinned model or Auto for Image Edit (default Auto -> Qwen/Qwen-Image-Edit)
 */
class ImageSettings(private val prefs: SharedPreferences) {

    private val _allowPaid = MutableStateFlow(prefs.getBoolean(KEY_ALLOW_PAID, false))
    val allowPaid: StateFlow<Boolean> = _allowPaid.asStateFlow()

    private val _pinnedImage = MutableStateFlow(prefs.getString(KEY_PINNED_IMAGE, null))
    val pinnedImage: StateFlow<String?> = _pinnedImage.asStateFlow()

    private val _pinnedImageEdit = MutableStateFlow(prefs.getString(KEY_PINNED_IMAGE_EDIT, null))
    val pinnedImageEdit: StateFlow<String?> = _pinnedImageEdit.asStateFlow()

    fun isPaidAllowed(): Boolean = prefs.getBoolean(KEY_ALLOW_PAID, false)

    fun setAllowPaid(allow: Boolean) {
        prefs.edit().putBoolean(KEY_ALLOW_PAID, allow).apply()
        _allowPaid.value = allow
    }

    fun getPinnedModel(role: ImageRole): String? = when (role) {
        ImageRole.Image -> prefs.getString(KEY_PINNED_IMAGE, null)
        ImageRole.ImageEdit -> prefs.getString(KEY_PINNED_IMAGE_EDIT, null)
    }

    fun setPinnedModel(role: ImageRole, model: String?) {
        val key = when (role) {
            ImageRole.Image -> KEY_PINNED_IMAGE
            ImageRole.ImageEdit -> KEY_PINNED_IMAGE_EDIT
        }
        val edit = prefs.edit()
        val normalized = model?.trim()?.takeIf { it.isNotEmpty() }
        if (normalized == null) {
            edit.remove(key)
        } else {
            edit.putString(key, normalized)
        }
        edit.apply()

        when (role) {
            ImageRole.Image -> _pinnedImage.value = normalized
            ImageRole.ImageEdit -> _pinnedImageEdit.value = normalized
        }
    }

    /**
     * Active model for this role:
     * If pinned, returns the pinned model.
     * Otherwise returns the default top model for today.
     */
    fun todayModel(role: ImageRole): String {
        val pinned = getPinnedModel(role)
        if (!pinned.isNullOrBlank()) return pinned
        return when (role) {
            ImageRole.Image -> "Qwen/Qwen-Image-2.1"
            ImageRole.ImageEdit -> "Qwen/Qwen-Image-Edit"
        }
    }

    fun isAuto(role: ImageRole): Boolean = getPinnedModel(role).isNullOrBlank()

    companion object {
        const val KEY_ALLOW_PAID = "image_allow_paid_models"
        const val KEY_PINNED_IMAGE = "image_pinned_model_generate"
        const val KEY_PINNED_IMAGE_EDIT = "image_pinned_model_edit"

        fun get(context: Context) = ImageSettings(
            context.applicationContext.getSharedPreferences(FeatureFlags.PREFS, Context.MODE_PRIVATE)
        )
    }
}
