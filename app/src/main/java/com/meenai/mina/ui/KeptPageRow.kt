package com.meenai.mina.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meenai.mina.R
import com.meenai.mina.cap.web.KeptPage
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType
import kotlinx.coroutines.delay

private val RowShape = RoundedCornerShape(14.dp)
private val CloseShape = RoundedCornerShape(8.dp)
private val KeptSemiBoldStyle = MinaType.Label.copy(fontWeight = FontWeight.SemiBold)

/** How to display the line with pages left under a round: still open (green line, can be turned off), turned off (gray text), or no pages left in this round. */
sealed interface KeptRow {
    data class Live(val page: KeptPage) : KeptRow
    data class Closed(val reason: String?) : KeptRow

    companion object {
        /**
         * [ex] What is displayed under that round: The [live] that is kept at the moment is the one left
         * in this round, which is [Live]; the page that was remembered to be left in this round, but it
         * is no longer there, is [Closed] (if you didn’t note the reason, press Mina to exit); the
         * others are null.
         */
        fun of(ex: Exchange, live: KeptPage?): KeptRow? = when {
            live != null && ex.runId != null && live.runId == ex.runId -> Live(live)
            ex.keptPage != null -> Closed(ex.keptPageClosed)
            else -> null
        }
    }
}

/** The row of pages left below a round (see [KeptRow]). */
internal fun LazyListScope.keptPageRow(ex: Exchange, live: KeptPage?, onClose: () -> Unit) {
    val row = KeptRow.of(ex, live) ?: return
    item(key = "k:${ex.id}", contentType = "kept") {
        when (row) {
            is KeptRow.Live -> KeptLine(row.page, onClose)
            is KeptRow.Closed -> Text(
                KeptPageCopy.closedNote(row.reason), Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 10.dp).testTag("kept-closed"),
                style = MinaType.Hint, color = MinaTheme.colors.ink3,
            )
        }
    }
}

internal fun LazyListScope.keptPageRow(index: Int, ex: Exchange, live: KeptPage?, onClose: () -> Unit) =
    keptPageRow(ex, live, onClose)

/** The green line: "This page will keep running in the background", below it is "Site · How long will it take before it will automatically close", and on the right is "Close". Walk every minute the rest of the time. */
@Composable
fun KeptLine(page: KeptPage, onClose: () -> Unit) {
    val c = MinaTheme.colors
    val now by produceState(System.currentTimeMillis(), page.id) {
        while (true) {
            delay(TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).testTag("kept-row").clip(RowShape).background(c.okSoft, RowShape)
            .border(1.dp, c.ok.copy(alpha = 0.25f), RowShape).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(MinaIcons.Globe, null, Modifier.size(18.dp), tint = c.ok)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.kept_row_title), style = KeptSemiBoldStyle, color = c.ok)
            Text(
                stringResource(R.string.kept_row_detail, page.host, KeptPageCopy.closesIn(page.closesAt - now)),
                style = MinaType.Hint, color = c.ok, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            Modifier.testTag("kept-close").clip(CloseShape).background(c.surface)
                .clickable(role = Role.Button, onClick = onClose).padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Text(stringResource(R.string.kept_row_close), style = KeptSemiBoldStyle, color = c.bad)
        }
    }
}

private const val TICK_MS = 30_000L
