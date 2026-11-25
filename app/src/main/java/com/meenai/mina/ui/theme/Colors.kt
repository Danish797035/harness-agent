package com.meenai.mina.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * All colors used in the interface. The page is always taken from [MinaTheme.colors], no dead color
 * value is written. Solid colors are only used for emphasis (main button, selection, switch, user
 * message) and status (success, need you, error); icon blocks are always neutral.
 */
@Immutable
class HarnessColors(
    /** Page background. */
    val bg: Color,
    /** Cards, groups. */
    val surface: Color,
    /** Shallow fill: sub-button, segmented control bottom, code block bottom. */
    val fill: Color,
    /** Three levels of text: main text, secondary text, and auxiliary text. */
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    /** Divider. */
    val hair: Color,
    /** Card strokes. */
    val edge: Color,
    val accent: Color,
    val accentSoft: Color,
    /** Text and icons on accent colors. */
    val onAccent: Color,
    val ok: Color,
    val okSoft: Color,
    /** "Need you": [warn] is used for text, [warnSolid] is used for dots and strokes. */
    val warn: Color,
    val warnSolid: Color,
    val warnSoft: Color,
    val bad: Color,
    val badSoft: Color,
    /** The bottom of the icon block. */
    val neutralSoft: Color,
    /** The bottom line of Mina's answer: a gray that is a little darker than the page background, and is distinguished from the user's blue bubble. */
    val answer: Color,
    /** Gray capsule, closed switch. */
    val greySoft: Color,
    /** The bottom and stroke of floating glass (input fields, buttons floating on content). */
    val glass: Color,
    val glassEdge: Color,
    /** Card shadow; in dark colors, the card only relies on strokes and is transparent. */
    val cardShadow: Color,
    /** The shadow of the suspended element. */
    val floatShadow: Color,
    /** Switch round button. */
    val thumb: Color,
    /**
     * The colors classified in the chart (for example, one for each service provider) are taken in a
     * fixed order; any excess colors are classified into [seriesOther]. Only color the graphics, and use
     * ink level 3 for all text.
     */
    val series: List<Color>,
    val seriesOther: Color,
    /** Chart grid lines. */
    val grid: Color,
    val isDark: Boolean,
)

typealias MinaColors = HarnessColors

val LightColors = HarnessColors(
    bg = Color(0xFFF7F7F8),
    surface = Color(0xFFFFFFFF),
    fill = Color(0x0A000000),
    ink = Color(0xFF1C1C1E),
    ink2 = Color(0xFF5F6068),
    ink3 = Color(0xFF9A9BA3),
    hair = Color(0x12000000),
    edge = Color(0x0D000000),
    accent = Color(0xFF0A7CFF),
    accentSoft = Color(0x1A0A7CFF),
    onAccent = Color(0xFFFFFFFF),
    ok = Color(0xFF1F9D55),
    okSoft = Color(0x1F1F9D55),
    warn = Color(0xFFB86E00),
    warnSolid = Color(0xFFFF9F0A),
    warnSoft = Color(0x1FFF9F0A),
    bad = Color(0xFFE5483D),
    badSoft = Color(0x1AE5483D),
    neutralSoft = Color(0x0B000000),
    answer = Color(0x09000000),
    greySoft = Color(0x1F767680),
    glass = Color(0xB8FFFFFF),
    glassEdge = Color(0x0F000000),
    cardShadow = Color(0xFF000000),
    floatShadow = Color(0xFF000000),
    thumb = Color(0xFFFFFFFF),
    series = listOf(Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A)),
    seriesOther = Color(0xFFB4B5BC),
    grid = Color(0x0F000000),
    isDark = false,
)

val DarkColors = HarnessColors(
    bg = Color(0xFF0E0E10),
    surface = Color(0xFF1A1A1D),
    fill = Color(0x0FFFFFFF),
    ink = Color(0xFFF2F2F4),
    ink2 = Color(0xFFA4A5AD),
    ink3 = Color(0xFF6C6D75),
    hair = Color(0x14FFFFFF),
    edge = Color(0x0FFFFFFF),
    accent = Color(0xFF3D97FF),
    accentSoft = Color(0x293D97FF),
    onAccent = Color(0xFFFFFFFF),
    ok = Color(0xFF3CCF7A),
    okSoft = Color(0x243CCF7A),
    warn = Color(0xFFFFB340),
    warnSolid = Color(0xFFFF9F0A),
    warnSoft = Color(0x29FF9F0A),
    bad = Color(0xFFFF6961),
    badSoft = Color(0x24FF6961),
    neutralSoft = Color(0x12FFFFFF),
    answer = Color(0x0DFFFFFF),
    greySoft = Color(0x38767680),
    glass = Color(0xB81A1A1D),
    glassEdge = Color(0x14FFFFFF),
    cardShadow = Color.Transparent,
    floatShadow = Color(0xFF000000),
    thumb = Color(0xFFFFFFFF),
    series = listOf(Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70)),
    seriesOther = Color(0xFF5A5B62),
    grid = Color(0x12FFFFFF),
    isDark = true,
)

val LocalHarnessColors = staticCompositionLocalOf { LightColors }
val LocalMinaColors = LocalHarnessColors
