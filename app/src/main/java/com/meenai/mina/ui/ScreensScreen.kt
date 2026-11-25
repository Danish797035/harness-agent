package com.meenai.mina.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.meenai.mina.R
import com.meenai.mina.cap.phone.PhoneServices
import com.meenai.mina.capsule.previewFrames
import com.meenai.mina.capsule.sameFrame
import com.meenai.mina.confirm.ConfirmationNotifier
import com.meenai.mina.core.agent.TaskState
import com.meenai.mina.i18n.tr
import com.meenai.mina.task.TaskCenterState
import com.meenai.mina.ui.components.EmptyNote
import com.meenai.mina.ui.components.FooterNote
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.ListRow
import com.meenai.mina.ui.components.PageScaffold
import com.meenai.mina.ui.components.RowValue
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext

/**
 * A task and its virtual screen are listed on this page: [displayId] is -1, which means the screen has
 * not been created yet (it is in the queue, or it is a pure conversation, and the app has not been
 * opened yet). [foreground] is the display name of the App at the top of the screen; it is null when it
 * has not been found or the mobile phone operation is not connected. When [web] is true, the built-in
 * browser is used. The screen number is only used for sorting and taking pictures, and is not shown to
 * the user.
 */
data class ScreenRow(
    val key: String,
    val conversationId: String,
    val goal: String,
    val displayId: Int,
    val state: TaskState,
    val foreground: String? = null,
    val web: Boolean = false,
    /** This task has been done several times so far; if it changes, recheck the top App. */
    val acted: Int = 0,
)

/** The order of the list: Those with screens built are at the front (according to the order of screen creation), and those with screens not yet built are at the back (keep the original order). */
fun orderedScreens(rows: List<ScreenRow>): List<ScreenRow> =
    rows.filter { it.displayId >= 0 }.sortedBy { it.displayId } + rows.filter { it.displayId < 0 }

/**
 * The "Backstage Screen" page that comes in next to "In Progress" in the sidebar: Each running task has
 * a card, writing what it is doing, which App it is using, and its status. You can expand it to see the
 * real-time screen. Read-only - stop the task, return to the dialogue, or do it in the dialogue page and
 * capsule; click the card to return to the dialogue where the task is located.
 *
 * The data is taken from each task's own stream (status, screen, picture, and done things), not from the
 * "displayed tasks" that serve the outside world: the screens that are not displayed in parallel are
 * still real. The top App only rechecks when the task does another thing, without polling.
 */
@Composable
fun ScreensScreen(taskState: TaskCenterState, onBack: () -> Unit, onOpenConversation: (String) -> Unit) {
    val context = LocalContext.current
    val runs by taskState.runs.collectAsState()
    var rows by remember { mutableStateOf<List<ScreenRow>>(emptyList()) }
    LaunchedEffect(runs) {
        val list = runs
        if (list.isEmpty()) {
            rows = emptyList()
            return@LaunchedEffect
        }
        // Each task's status, screen, browser, and things done have their own flows: together, any
        // changes will be rearranged.
        combine(list.map { run ->
            combine(run.state, run.displayId, run.webOpen, run.activities) { s, id, web, acts ->
                ScreenRow(run.key, run.conversationId, run.goal, id, s, web = web, acted = acts.size)
            }
        }) { it.toList() }.collect { rows = it }
    }

    // Tasks with screens and how many things they have done: If any one of them does something again,
    // the app on the top of each screen will be asked again.
    val probe = remember(rows) { rows.filter { it.displayId >= 0 }.map { it.displayId to it.acted } }
    var foreground by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    LaunchedEffect(probe) { foreground = foregroundOf(context, probe.map { it.first }) }

    ScreensContent(
        rows = orderedScreens(rows).map { r -> r.copy(foreground = foreground[r.displayId]) },
        onBack = onBack,
        onOpenConversation = onOpenConversation,
        frames = { key -> runs.firstOrNull { it.key == key }?.frames },
    )
}

/**
 * The page without status is convenient for testing: [rows] has been sorted and the top App has been
 * filled in. [frames] gives the picture of that screen according to the task number; it will be
 * subscribed when it is expanded.
 */
@Composable
fun ScreensContent(
    rows: List<ScreenRow>,
    onBack: () -> Unit,
    onOpenConversation: (String) -> Unit,
    frames: (String) -> StateFlow<Bitmap?>? = { null },
) {
    PageScaffold(ScreensCopy.TITLE, onBack) {
        if (rows.isEmpty()) {
            EmptyNote(ScreensCopy.EMPTY)
            return@PageScaffold
        }
        rows.forEach { row -> ScreenCard(row, frames(row.key), onOpenConversation) }
        FooterNote(ScreensCopy.NOTE)
    }
}

/** A task: what you are doing, which app you are using, and status; click this line to return to its dialogue, and click "View Screen" to expand the real-time screen. */
@Composable
private fun ScreenCard(row: ScreenRow, frames: StateFlow<Bitmap?>?, onOpenConversation: (String) -> Unit) {
    val c = MinaTheme.colors
    var open by rememberSaveable(row.key) { mutableStateOf(false) }
    GroupCard(Modifier.padding(top = 8.dp)) {
        ListRow(
            MinaIcons.Screens, row.goal.oneLine(), ScreensCopy.place(row),
            first = true, onClick = { onOpenConversation(row.conversationId) },
        ) { RowValue(ScreensCopy.state(row.state)) }
        if (row.displayId >= 0 || row.web) {
            Text(
                if (open) ScreensCopy.HIDE_PREVIEW else ScreensCopy.PREVIEW,
                Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
                    .clickable { open = !open }.testTag("screens-preview-${row.key}"),
                fontSize = 13.sp, color = c.accent,
            )
            if (open && frames != null) LiveFrame(frames)
        }
    }
}

/**
 * Real-time pictures of this screen: up to 5 pictures per second, the same as the previous picture (see
 * [previewFrames]). Collapse without subscribing. Click to enlarge to the full screen, and you can pinch
 * to zoom and drag - the interface with small fonts cannot be seen clearly in this small area.
 */
@Composable
private fun LiveFrame(source: StateFlow<Bitmap?>) {
    val frames = remember(source) { source.filterNotNull().previewFrames(same = ::sameFrame) }
    val frame by frames.collectAsState(initial = null)
    val bmp = frame ?: return
    var zoom by remember { mutableStateOf(false) }
    Image(
        bmp.asImageBitmap(), stringResource(R.string.screens_zoom),
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            .height(260.dp).clip(RoundedCornerShape(10.dp))
            .clickable { zoom = true }.testTag("screens-zoom"),
        contentScale = ContentScale.Fit,
    )
    if (zoom) FrameZoom(bmp) { zoom = false }
}

/** Magnified screen: full screen, pinch to zoom (1-6 times), drag to look at corners; click outside or press Return to close. */
@Composable
private fun FrameZoom(frame: Bitmap, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 6f)
        offset = if (scale <= 1f) Offset.Zero else offset + panChange
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(MinaTheme.colors.bg.copy(alpha = 0.96f)).clickable { onDismiss() }
                .testTag("screens-zoom-dialog"),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                frame.asImageBitmap(), null,
                Modifier.fillMaxWidth().transformable(state)
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
                contentScale = ContentScale.Fit,
            )
        }
    }
}

/** Ask the built-in assistant which app is on top of each screen, and then change it to the display name. The assistant will not check if it is not connected (this only affects the foreground row). */
private suspend fun foregroundOf(context: Context, ids: List<Int>): Map<Int, String> {
    if (ids.isEmpty()) return emptyMap()
    val hub = runCatching { PhoneServices.of(context).hub }.getOrNull() ?: return emptyMap()
    return withContext(Dispatchers.IO) {
        ids.mapNotNull { id ->
            val pkg = runCatching {
                val shell = hub.shell.value ?: return@runCatching null
                val task = shell.topTaskId(id)
                if (task < 0) null else shell.packageOfTask(task)
            }.getOrNull()
            pkg?.let { id to ConfirmationNotifier.appLabel(context, it) }
        }.toMap()
    }
}

/** The target occupies only one line in the list: newlines and consecutive whitespace are combined into one space. */
private fun String.oneLine(): String = replace(Regex("\\s+"), " ").trim()

/** All the text on the page: do not write things such as screen number, pixels, density, etc. for development, just talk about what the task is doing, what is being used, and what step it has reached. */
object ScreensCopy {
    val TITLE: String get() = tr(R.string.screens_title)
    val EMPTY: String get() = tr(R.string.screens_empty)
    val NOTE: String get() = tr(R.string.screens_note)
    val PREVIEW: String get() = tr(R.string.screens_preview)
    val HIDE_PREVIEW: String get() = tr(R.string.screens_hide_preview)

    /** The second line of the card: Which App (or browser) you are using; there is a sentence for each app when it has not been opened or cannot be seen temporarily. */
    fun place(row: ScreenRow): String = when {
        row.web -> tr(R.string.screens_using_browser)
        row.displayId < 0 -> tr(R.string.screens_no_app)
        row.foreground != null -> tr(R.string.screens_using_app, row.foreground)
        else -> tr(R.string.screens_app_unknown)
    }

    fun state(state: TaskState): String = when (state) {
        TaskState.Idle -> tr(R.string.screens_waiting)
        is TaskState.Running -> tr(R.string.task_running)
        // Just waiting for conditions (lock screen, assistant reconnection) does not require you to deal
        // with it; it does require you to move your hands on the phone.
        is TaskState.Held -> if (state.reason.needsYou) tr(R.string.screens_needs_you) else tr(R.string.screens_waiting)
        is TaskState.WaitingTakeover, is TaskState.WaitingInput, is TaskState.WaitingConfirmation -> tr(R.string.screens_needs_you)
        is TaskState.Succeeded -> tr(R.string.task_done)
        is TaskState.Failed -> tr(R.string.task_not_done)
        TaskState.Cancelled -> tr(R.string.task_stopped)
    }
}
