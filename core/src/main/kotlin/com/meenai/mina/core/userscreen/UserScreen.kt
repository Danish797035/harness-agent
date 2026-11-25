package com.meenai.mina.core.userscreen

/** A line of words on the screen: [x]/[y] is its center, and the same set of 0–999 coordinates as the click (upper left 0,0). */
data class ScreenLine(val text: String, val x: Int, val y: Int)

/**
 * The "screen at the moment" sent by the user: the screen the user is looking at on the home screen when
 * double-clicking the screenshot button in the capsule or card. It’s not the same thing as a picture
 * picked from an album or file: it says “I’m looking at this now”, with the front-end app, the captured
 * moment and the words on the screen. [packageName]/[appLabel] is the front-end App (cannot be read as
 * null); [blocked] does not allow screenshots (anti-screenshot), there is no screen, [lines] is also
 * empty; [lines] is the words on the screen (in the accessibility tree, recognized words will be added
 * when the tree is not enough), [fromOcr] contains recognized words.
 */
data class UserScreen(
    val id: String,
    val takenAt: Long,
    val packageName: String?,
    val appLabel: String?,
    val blocked: Boolean = false,
    val lines: List<ScreenLine> = emptyList(),
    val fromOcr: Boolean = false,
) {
    /** How to call that App: If there is a name, use the name, if not use the package name. */
    val app: String get() = appLabel?.takeIf(String::isNotBlank) ?: packageName ?: UNKNOWN_APP

    companion object {
        const val UNKNOWN_APP = "某个 App"
    }
}

/** Get the screen sent by the user in this conversation by number: the description and screenshot may no longer be available (return null if not read). */
interface UserScreenSource {
    suspend fun screen(id: String): UserScreen?

    /** The screenshot (JPEG or PNG); null if the page is not allowed to be screenshot or cannot be read. */
    suspend fun image(id: String): ByteArray?
}

/** A screen shot that has not yet been sent: screen ([jpeg], null when screenshots are not allowed) and description ([screen], the number is determined when saving in the conversation). */
class CapturedScreen(val jpeg: ByteArray?, val screen: UserScreen, val sourcePackages: Set<String> = setOfNotNull(screen.packageName))
