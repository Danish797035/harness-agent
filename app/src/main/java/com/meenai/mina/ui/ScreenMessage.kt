package com.meenai.mina.ui

import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meenai.mina.HarnessApp
import com.meenai.mina.core.agent.QuoteMessage
import com.meenai.mina.core.cap.web.AttachedPage
import com.meenai.mina.core.userscreen.UserScreen
import com.meenai.mina.core.userscreen.UserScreenMessage
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How the screen sent by the user appears on the interface (shared by conversation pages, cards, and screenshot buttons). */
object ScreenCopy {
    val TAG: String get() = tr(R.string.screen_tag)
    val BLOCKED: String get() = tr(R.string.screen_blocked)
    val CAMERA: String get() = tr(R.string.screen_camera)
    val REMOVE: String get() = tr(R.string.screen_remove)
    val SEND_SCREEN: String get() = tr(R.string.screen_send)

    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

    fun clock(at: Long, zone: ZoneId = ZoneId.systemDefault()): String = CLOCK.format(Instant.ofEpochMilli(at).atZone(zone))

    /** Take the line below the screenshot: "Your current screen · Settings · 19:42". */
    fun tag(screen: UserScreen): String = "$TAG · ${screen.app} · ${clock(screen.takenAt)}"

    /** Second line on the attachment bar: "Settings · 19:42". */
    fun detail(screen: UserScreen): String = "${screen.app} · ${clock(screen.takenAt)}"

    /**
     * Use this sentence to call the conversation and write it in the list: PS; when only the screen is
     * sent, it is "your screen at the moment." The quoted lines ([QuoteMessage]) and the web page
     * description ([AttachedPage]) do not count. Returns as-is without screen.
     */
    fun plain(raw: String): String {
        val text = QuoteMessage.words(AttachedPage.words(raw))
        val p = UserScreenMessage.parse(text)
        return if (!p.hasScreen) text else p.words.ifEmpty { TAG }
    }
}

/** A screen to be drawn on the interface: description and thumbnail (null if the picture cannot be read). */
class ShownScreen(val screen: UserScreen, val thumb: ImageBitmap?)

/** Read one screen by dialogue and number; if not read, it is null. The interface test is replaced with a fake one. */
fun interface ScreenShots {
    suspend fun load(conversationId: String, id: String): ShownScreen?
}

/** If given, use it to read the screen (test); if not given, read the copy saved in the app. */
val LocalScreenShots = staticCompositionLocalOf<ScreenShots?> { null }

/** The screen saved in the App: the description is read from the conversation workspace, the thumbnail is reduced to the required width and decoded, and the decoded notes are recorded. */
private class StoredScreenShots(private val context: Context) : ScreenShots {
    override suspend fun load(conversationId: String, id: String): ShownScreen? = withContext(Dispatchers.IO) {
        val key = "$conversationId/$id"
        cache.get(key)?.let { return@withContext it }
        val files = (context.applicationContext as HarnessApp).userScreens
        val screen = files.screen(conversationId, id) ?: return@withContext null
        val thumb = if (screen.blocked) null else files.image(conversationId, id)?.let(::screenThumbnail)
        ShownScreen(screen, thumb).also { cache.put(key, it) }
    }

    companion object {
        val cache = LruCache<String, ShownScreen>(24)

        fun cached(conversationId: String, id: String): ShownScreen? = cache.get("$conversationId/$id")
    }
}

/** Thumbnails should be as wide as this (in pixels): the maximum width in the conversation page is 110dp. */
private const val THUMB_PX = 360

/** The screenshots are decomposed into thumbnails (press [THUMB_PX] to zoom out, not the whole picture); if not decoded, it is null. Adjusted in background thread. */
fun screenThumbnail(bytes: ByteArray): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= THUMB_PX) sample *= 2
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
}.getOrNull()

/** Read one screen (in the background); it is null when it has not been read or cannot be read. */
@Composable
fun rememberShownScreen(conversationId: String?, id: String): ShownScreen? {
    val context = LocalContext.current
    val customShots = LocalScreenShots.current
    val shots = customShots ?: remember(context) { StoredScreenShots(context) }
    val initial = remember(customShots, conversationId, id) {
        if (customShots == null && conversationId != null) StoredScreenShots.cached(conversationId, id) else null
    }
    val shown by produceState<ShownScreen?>(initial, shots, conversationId, id) {
        if (initial != null) {
            value = initial
            return@produceState
        }
        value = conversationId?.let { runCatching { shots.load(it, id) }.getOrNull() }
    }
    return shown
}

/**
 * A word from the user: When the screen is taken, it is a screenshot thumbnail ([thumbWidth] wide) and
 * the line below "Your screen · App · Moment", with a postscript below in another bubble ([bubble]);
 * when the screenshot is not allowed, the line is "not captured"; when the screenshot is no longer
 * there, there is only that line of text. Without screen it is [bubble].
 */
@Composable
fun UserMessage(conversationId: String?, raw: String, thumbWidth: Dp, modifier: Modifier = Modifier, bubble: @Composable (String) -> Unit) {
    // When asked about a page on the browser page: the lines of the page description are not displayed
    // (on the dialogue page, draw "Web Page·Website" on top); when only the selected words are included,
    // those lines are displayed.
    val text = AttachedPage.parse(raw).let { pg -> pg.words.ifEmpty { pg.page?.selection?.let { "“$it”" }.orEmpty() } }
    val p = UserScreenMessage.parse(text)
    if (!p.hasScreen) {
        bubble(text)
        return
    }
    Column(modifier, horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        p.screens.forEach { id -> ScreenItem(rememberShownScreen(conversationId, id), thumbWidth) }
        if (p.words.isNotEmpty()) bubble(p.words)
    }
}

private val ThumbShape = RoundedCornerShape(10.dp)
private val TagText = TextStyle(fontSize = 11.5.sp, lineHeight = 15.sp)

/** One screen: thumbnail and the line below; occupy the space before reading. */
@Composable
private fun ScreenItem(shown: ShownScreen?, width: Dp) {
    val c = MinaTheme.colors
    val screen = shown?.screen
    if (screen?.blocked == true) {
        Row(
            Modifier.testTag("screen-blocked").background(c.ink.copy(alpha = 0.05f), ThumbShape).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(MinaIcons.Lock, null, Modifier.size(15.dp), tint = c.ink2)
            Text(ScreenCopy.BLOCKED, style = TextStyle(fontSize = 12.sp, lineHeight = 16.sp), color = c.ink2)
        }
        return
    }
    val tag = screen?.let(ScreenCopy::tag) ?: ScreenCopy.TAG
    Column(Modifier.semantics(mergeDescendants = true) { contentDescription = tag }, horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ScreenThumb(shown?.thumb, width, Modifier.testTag("screen-thumb"))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(MinaIcons.Phone, null, Modifier.size(13.dp), tint = c.ink3)
            Text(tag, Modifier.testTag("screen-tag"), style = TagText, color = c.ink3)
        }
    }
}

/** Screenshot thumbnail: According to the aspect ratio of the picture, the corners are rounded and the edges are thin; when there is no picture, it is a piece of light gray of the same size. */
@Composable
fun ScreenThumb(thumb: ImageBitmap?, width: Dp, modifier: Modifier = Modifier) {
    val c = MinaTheme.colors
    val ratio = thumb?.let { (it.width.toFloat() / it.height.coerceAtLeast(1)).coerceIn(0.3f, 1.8f) } ?: DEFAULT_RATIO
    Box(modifier.width(width).aspectRatio(ratio).clip(ThumbShape).border(1.dp, c.glassEdge, ThumbShape).background(c.ink.copy(alpha = 0.05f))) {
        thumb?.let { Image(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop) }
    }
}

/** Before I read the picture, I occupied it according to the aspect ratio of common mobile phone screens. */
private const val DEFAULT_RATIO = 0.46f
