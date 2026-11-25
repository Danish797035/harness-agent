package com.meenai.mina.i18n

import android.content.res.Resources
import com.meenai.mina.R
import java.util.Locale
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes

/**
 * Where do I get the interface text from? In the App, the Android resources ([ResourceTexts]) are
 * fetched according to the App language; in the JVM single test, it is replaced by the implementation of
 * directly reading the resource file. Where stringResource can be used in Compose, it is used as usual;
 * here it is used for codes that calculate text outside the interface (status copy, notifications,
 * services, and data in suspended capsules).
 */
interface UiTexts {
    fun string(@StringRes id: Int, args: Array<out Any?>): String
    fun plural(@PluralsRes id: Int, count: Int, args: Array<out Any?>): String
}

/** The [UiTexts]:App used in the process is loaded at startup. */
object UiText {
    @Volatile
    var source: UiTexts? = null

    internal fun require(): UiTexts = source ?: error("UiText.source is not installed")
}

/** Take an interface text; if there is [args], fill it in according to the format (same as Resources.getString). */
fun tr(@StringRes id: Int, vararg args: Any?): String = UiText.require().string(id, args)

/** Take an interface text that changes with the quantity; if [args] is not given, fill in [count] as the only parameter. */
fun trPlural(@PluralsRes id: Int, count: Int, vararg args: Any?): String = UiText.require().plural(id, count, args)

/** Android resource implementation; [resources] is retrieved every time, and the language is changed accordingly. */
class ResourceTexts(private val resources: () -> Resources) : UiTexts {
    override fun string(id: Int, args: Array<out Any?>): String =
        if (args.isEmpty()) resources().getString(id) else resources().getString(id, *args)

    override fun plural(id: Int, count: Int, args: Array<out Any?>): String =
        resources().getQuantityString(id, count, *(if (args.isEmpty()) arrayOf(count) else args))
}

/** Locale corresponding to the interface language: write the name of the week and month according to it. */
fun uiLocale(): Locale = Locale.forLanguageTag(tr(R.string.ui_locale))
