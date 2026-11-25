package com.meenai.mina.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import com.meenai.mina.settings.FeatureFlags
import com.meenai.mina.settings.ThemeMode

/** Animation: duration and curve. When the system turns off the animation ([HarnessTheme.reduceMotion]), it will be in place directly. */
object HarnessMotion {
    const val PAGE_MS = 220
    const val SWITCH_MS = 200
    /** Deceleration curve: fast start, slow landing. */
    val Decelerate: Easing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

    fun <T> page(reduceMotion: Boolean): AnimationSpec<T> = if (reduceMotion) snap() else tween(PAGE_MS, easing = Decelerate)
    fun <T> switch(reduceMotion: Boolean): AnimationSpec<T> = if (reduceMotion) snap() else tween(SWITCH_MS, easing = Decelerate)
}

typealias MinaMotion = HarnessMotion

/** True when the system "removes animation" (animation duration scaled to 0). */
fun animationsOff(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

val LocalReduceMotion = staticCompositionLocalOf { false }

object HarnessTheme {
    val colors: HarnessColors
        @Composable @ReadOnlyComposable get() = LocalHarnessColors.current

    val reduceMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReduceMotion.current
}

typealias MinaTheme = HarnessTheme

/**
 * Appearance in settings: Look at the system when following the system, and look at the settings when
 * always light/dark. Once the settings are changed (changed in the settings page or task), the settings
 * are changed on the spot without rebuilding the interface, so the page, input, and scroll position are
 * not lost.
 */
@Composable
fun appearanceDark(): Boolean {
    val context = LocalContext.current
    val flags = remember(context) { FeatureFlags.get(context) }
    val mode by remember(flags) { flags.themeModeChanges() }.collectAsState(flags.themeMode())
    return when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
}

/** The icons in the status bar and navigation bar have the same depth as the interface: when the interface is forced to be light and the system is dark, the icons must also be dark to be visible. */
fun ComponentActivity.systemBarsFor(dark: Boolean) {
    val transparent = android.graphics.Color.TRANSPARENT
    // The navigation bar on the old system cannot be completely transparent, so it must be padded with a
    // semi-transparent bottom; the value is consistent with the default value of enableEdgeToEdge.
    val lightScrim = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
    val darkScrim = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    enableEdgeToEdge(
        statusBarStyle = if (dark) SystemBarStyle.dark(transparent) else SystemBarStyle.light(transparent, transparent),
        navigationBarStyle = if (dark) SystemBarStyle.dark(darkScrim) else SystemBarStyle.light(lightScrim, darkScrim),
    )
}

/** The dark and light colors are according to the appearance in the settings ([appearanceDark]); at the same time, match the colors of the Material components (pop-up windows, input boxes, bottom panels). */
@Composable
fun HarnessTheme(dark: Boolean = appearanceDark(), reduceMotion: Boolean = false, content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    val scheme = if (dark) DarkScheme else LightScheme
    CompositionLocalProvider(LocalHarnessColors provides colors, LocalReduceMotion provides reduceMotion) {
        MaterialTheme(colorScheme = scheme) {
            CompositionLocalProvider(LocalContentColor provides colors.ink, content = content)
        }
    }
}

@Composable
fun MinaTheme(dark: Boolean = appearanceDark(), reduceMotion: Boolean = false, content: @Composable () -> Unit) {
    HarnessTheme(dark = dark, reduceMotion = reduceMotion, content = content)
}

private fun scheme(c: HarnessColors): ColorScheme {
    val base = if (c.isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft, onPrimaryContainer = c.accent,
        secondary = c.accent, onSecondary = c.onAccent,
        background = c.bg, onBackground = c.ink,
        surface = c.surface, onSurface = c.ink, surfaceVariant = c.fill, onSurfaceVariant = c.ink2,
        surfaceContainerLowest = c.surface, surfaceContainerLow = c.surface, surfaceContainer = c.surface,
        surfaceContainerHigh = c.surface, surfaceContainerHighest = c.surface, surfaceTint = c.surface,
        outline = c.ink3, outlineVariant = c.hair,
        error = c.bad, onError = c.onAccent,
    )
}

private val LightScheme = scheme(LightColors)
private val DarkScheme = scheme(DarkColors)
