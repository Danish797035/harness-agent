package com.meenai.mina.i18n

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.icu.util.ULocale
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * App interface language: Follow the system, or select one of the following. In the resources, English
 * is the default (`values/`), Simplified Chinese is at `values-zh/`, Traditional Chinese is at
 * `values-b+zh+Hant/`, and other languages are at `values-<language>/` (Indonesian follows Android’s old
 * code `values-in/`). Android selects resources based on language and text system, so when "Following
 * the System" is used, it will be displayed if the system language has a translation, and if it is not,
 * it will be displayed in English; if a certain language is missing, that one will also be displayed in
 * English.
 *
 * Starting from Android 13, the system's "Application Language" is managed (LocaleManager): the system
 * remembers the selection, applies it to the entire process, and rebuilds the interface. The
 * "Application Language" in the system settings and the switch here are the same value. Android 11 and
 * 12 do not have this item. It is recorded here locally, and is applied to the Context of the interface
 * via [wrap]. via [resources]. After changing it, the current interface is rebuilt.
 */
enum class AppLanguage(val tag: String?, val speechTag: String?) {
    System(null, null),
    English("en", "en-US"),
    Hindi("hi", "hi-IN");

    /** Language code (without script and region); null when following a system. */
    val language: String? get() = tag?.let { Locale.forLanguageTag(it).language.let(::modernCode) }

    companion object {
        private const val PREFS = "app_language"
        private const val KEY_TAG = "tag"

        /** The selected language for Android 12 and earlier; null means follow the system. Read once in the process and update when changing. */
        @Volatile
        private var legacyTag: String? = null
        @Volatile
        private var legacyLoaded = false

        /** Resources created in Android 12 and earlier according to the selected language will be changed according to the language. */
        @Volatile
        private var cached: Pair<String, Resources>? = null

        private val systemManaged get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        fun current(context: Context): AppLanguage = fromTag(
            if (systemManaged) {
                context.getSystemService(LocaleManager::class.java).applicationLocales.takeUnless { it.isEmpty }?.get(0)?.toLanguageTag()
            } else {
                legacy(context)
            },
        )

        /** Which item the language label corresponds to: see [fromTag]; if the text system is not written in the label, fill it in according to the common writing method of ICU. */
        fun fromTag(tag: String?): AppLanguage = fromTag(tag) { ULocale.addLikelySubtags(ULocale.forLanguageTag(it)).script }

        /**
         * Chinese is divided according to the writing system: Hant written or [likelyScript] is
         * Traditional Chinese, other Chinese is Simplified Chinese; other languages match the
         * previous item according to the language code (regardless of region), and the ones that do not
         * match are English. The zh-Hans saved by selecting "Chinese" before are still in Simplified
         * Chinese.
         */
        internal fun fromTag(tag: String?, likelyScript: (String) -> String): AppLanguage {
            if (tag.isNullOrEmpty()) return System
            val l = Locale.forLanguageTag(tag)
            val code = modernCode(l.language)
            return entries.firstOrNull { it.tag != null && it.language == code } ?: English
        }

        /** On Android, java.util.Locale still uses the old codes for several languages (Indonesian is in, Hebrew is iw...), and they are unified into the current codes for comparison. */
        internal fun modernCode(language: String): String = when (language) {
            "in" -> "id"
            "iw" -> "he"
            "ji" -> "yi"
            else -> language
        }

        /** Change the language and rebuild the interface. Hand it over to the system starting from Android 13 (the system rebuilds itself); previously recorded locally, rebuild the interface where [context] is located. */
        fun set(context: Context, language: AppLanguage) {
            if (systemManaged) {
                context.getSystemService(LocaleManager::class.java).applicationLocales =
                    language.tag?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
                return
            }
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_TAG, language.tag).commit()
            legacyTag = language.tag
            legacyLoaded = true
            cached = null
            applyDefaultLocale(context)
            context.activity()?.recreate()
        }

        /** Adjust when app starts: When selecting a language in Android 12 and before, change the default Locale of the process (date and number format) to it. */
        fun applyDefaultLocale(context: Context) {
            if (systemManaged) return
            val locales = legacyLocales(context) ?: Resources.getSystem().configuration.locales
            Locale.setDefault(locales[0])
        }

        /** Apply App language Context: Used in Activity's attachBaseContext and non-Activity interfaces. Returns as is since Android 13. */
        fun wrap(base: Context): Context {
            val locales = legacyLocales(base) ?: return base
            return base.createConfigurationContext(Configuration().apply { setLocales(locales) })
        }

        /**
         * Resources for fetching text outside the interface: Follow the App language. Starting from
         * Android 13, another copy is created according to the application language recorded by the
         * system, and the resources of Application are not directly used: when the process is not
         * started normally by the system (such as test startup), the resource is still in the system
         * language.
         */
        fun resources(app: Context): Resources {
            val tag = if (systemManaged) {
                app.getSystemService(LocaleManager::class.java).applicationLocales.takeUnless { it.isEmpty }?.toLanguageTags()
            } else {
                legacy(app)
            }
            if (tag == null) return app.resources
            cached?.takeIf { it.first == tag }?.let { return it.second }
            val r = app.createConfigurationContext(Configuration().apply { setLocales(LocaleList.forLanguageTags(tag)) }).resources
            cached = tag to r
            return r
        }

        private fun legacyLocales(context: Context): LocaleList? =
            if (systemManaged) null else legacy(context)?.let(LocaleList::forLanguageTags)

        private fun legacy(context: Context): String? {
            if (!legacyLoaded) {
                legacyTag = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_TAG, null)
                legacyLoaded = true
            }
            return legacyTag
        }

        private fun Context.activity(): Activity? {
            var c: Context? = this
            while (c is ContextWrapper) {
                if (c is Activity) return c
                c = c.baseContext
            }
            return null
        }
    }
}
