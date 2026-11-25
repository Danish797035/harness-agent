package com.meenai.mina.cap.web.cdp

import com.meenai.mina.i18n.AppLanguage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * In which language the web page is requested. When the user selects the interface language in Mina, the
 * web page also responds to this request (Accept-Language request header, the kernel also allows
 * navigator.language(s) to follow it); when following the system, it does not move, and the kernel sends
 * the string given by the system. After `Emulation.setUserAgentOverride`'s acceptLanguage setting: the
 * desktop version is added to the desktop identity ([withLanguage]), and the mobile version needs to be
 * replaced by one that copies the default mobile identity with only additional language coverage
 * ([mobileOverride]). UA and UA-CH remain unchanged. The Intl format of JS does not change
 * (`Emulation.setLocaleOverride` is a copy of the entire rendering process, each label rushes to set it
 * up, and it is removed when a label is disconnected), and it still depends on the system.
 */
object PageLanguage {
    /**
     * The language list corresponding to the language tag (completed, such as en-Latn-US): with region,
     * write "language-region, language", without writing the writing system (many websites only
     * recognize "language-region"); without region, just write language. Other languages will not be
     * added later. Only give the list, do not write the q value: the kernel sets navigator.languages
     * according to this list at the same time, and adds the q value to the request header one by one. If
     * you write it, it will be repeated. Android's Locale for Indonesian and other languages uses the
     * old code (in), and the website recognizes the current code (id), which should be changed before
     * writing.
     */
    fun acceptLanguage(tag: String): String {
        val l = Locale.forLanguageTag(tag)
        val lang = AppLanguage.modernCode(l.language.ifEmpty { return tag })
        return if (l.country.isEmpty()) lang else "$lang-${l.country},$lang"
    }

    /** Adds the language to a UA coverage; returns [acceptLanguage] unchanged if null (following the system). */
    fun withLanguage(override: JsonObject, acceptLanguage: String?): JsonObject =
        if (acceptLanguage == null) override else JsonObject(override + ("acceptLanguage" to JsonPrimitive(acceptLanguage)))

    /**
     * `Emulation.setUserAgentOverride` parameters of the mobile version. When following the system, the
     * UA is empty, that is, canceling the override and returning to the default identity in the WebView
     * settings; when the language is selected, the default identity is copied: [defaultUa] is the
     * default UA of WebView, and [metadata] is the default UA-CH (like CDP) obtained before changing the
     * settings. When the system cannot obtain the default UA-CH (old WebView), only UA is provided. At
     * this time, the UA-CH read by the page may be incomplete.
     */
    fun mobileOverride(defaultUa: String, metadata: JsonObject?, acceptLanguage: String?): JsonObject = buildJsonObject {
        if (acceptLanguage == null || defaultUa.isEmpty()) {
            put("userAgent", "")
            return@buildJsonObject
        }
        put("userAgent", defaultUa)
        put("acceptLanguage", acceptLanguage)
        metadata?.let { put("userAgentMetadata", it) }
    }
}
