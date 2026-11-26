package com.meenai.mina.ui

import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.meenai.mina.ui.image.ImageBitmapCache
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.viewer.FileViewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private val MdCodeShape = RoundedCornerShape(10.dp)
private val MdBarShape = RoundedCornerShape(2.dp)
private val MdImageShape = RoundedCornerShape(12.dp)
private val MdErrorShape = RoundedCornerShape(10.dp)
private val MdTableShape = RoundedCornerShape(2.dp)

/** Display a response in Markdown; streaming draft previews with temporarily closed syntax. */
@Composable
fun MarkdownText(markdown: String, style: TextStyle, modifier: Modifier = Modifier, streaming: Boolean = false) {
    // Streaming clips are frequently updated and parsed in the background; the previous frame is
    // retained until the new clip is ready to avoid main thread lags and text flashes.
    val colors = MinaTheme.colors
    val styles = remember(colors) { MarkdownModel.Styles.of(colors) }
    val blocks = if (streaming) {
        val initial = remember(markdown, styles) { MarkdownModel.cachedStreaming(markdown, styles).orEmpty() }
        val parsed by produceState<List<MdBlock>>(initial, markdown, styles) {
            value = MarkdownModel.cachedStreaming(markdown, styles)
                ?: withContext(Dispatchers.Default) { MarkdownModel.parseStreaming(markdown, styles) }
        }
        parsed
    } else remember(markdown, styles) { MarkdownModel.parse(markdown, styles) }
    val context = LocalContext.current
    val uriHandler = remember(context) {
        object : UriHandler {
            override fun openUri(uri: String) {
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: ActivityNotFoundException) {
                    Toast.makeText(context, tr(R.string.no_app_for_link), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
        Blocks(blocks, style, modifier, depth = 0)
    }
}

@Composable
private fun Blocks(blocks: List<MdBlock>, style: TextStyle, modifier: Modifier = Modifier, depth: Int, gap: Int = 8) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(gap.dp)) {
        blocks.forEach { Block(it, style, depth) }
    }
}

@Composable
private fun Block(b: MdBlock, style: TextStyle, depth: Int) {
    when (b) {
        is MdBlock.Para -> Text(b.text, style = style)
        is MdBlock.Heading -> Text(
            b.text,
            Modifier.padding(top = 2.dp),
            style = style.copy(
                fontSize = when (b.level) { 1 -> 19.sp; 2 -> 17.5.sp; 3 -> 16.sp; else -> style.fontSize },
                lineHeight = when (b.level) { 1 -> 25.sp; 2 -> 23.sp; else -> style.lineHeight },
                fontWeight = FontWeight.SemiBold,
            ),
        )
        is MdBlock.Code -> Box(
            Modifier.fillMaxWidth().background(MinaTheme.colors.fill, MdCodeShape)
                .horizontalScroll(rememberScrollState()).padding(horizontal = 11.dp, vertical = 9.dp),
        ) {
            Text(b.code, style = style.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp), softWrap = false)
        }
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(MinaTheme.colors.hair, MdBarShape))
            Blocks(b.blocks, style.copy(color = MinaTheme.colors.ink2), Modifier.padding(start = 10.dp), depth + 1)
        }
        is MdBlock.ListBlock -> Column(verticalArrangement = Arrangement.spacedBy(if (b.tight) 3.dp else 8.dp)) {
            b.items.forEachIndexed { i, item ->
                Row {
                    val marker = if (b.start != null) "${b.start + i}." else if (depth % 2 == 0) "•" else "◦"
                    Text(marker, Modifier.widthIn(min = 18.dp).padding(end = 6.dp), style = style.copy(color = MinaTheme.colors.ink2))
                    Blocks(item, style, depth = depth + 1, gap = if (b.tight) 3 else 8)
                }
            }
        }
        is MdBlock.Table -> TableBlock(b, style)
        is MdBlock.Image -> MarkdownImage(b, style)
        MdBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 4.dp).height(0.5.dp).background(MinaTheme.colors.hair))
    }
}

@Composable
private fun MarkdownImage(image: MdBlock.Image, style: TextStyle) {
    val context = LocalContext.current
    val rawDest = image.destination.trim()
    val localPath = remember(rawDest) { if (rawDest.startsWith("file://")) rawDest.removePrefix("file://") else rawDest }
    val file = remember(localPath) { File(localPath) }
    val cachedBitmap = remember(localPath) {
        if (localPath.isNotBlank()) ImageBitmapCache.peekByPath(localPath) else null
    }
    val bitmap by produceState<ImageBitmap?>(cachedBitmap, localPath) {
        if (cachedBitmap != null) value = cachedBitmap
        value = if (localPath.isNotBlank()) ImageBitmapCache.loadFromFile(localPath) else null
    }

    val currentBitmap = bitmap
    if (currentBitmap != null) {
        Image(
            bitmap = currentBitmap,
            contentDescription = image.alt.ifBlank { null },
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .clip(MdImageShape)
                .clickable {
                    val uri = try {
                        FileProvider.getUriForFile(context, "${context.packageName}.attachfiles", file)
                    } catch (_: Exception) {
                        Uri.fromFile(file)
                    }
                    FileViewer.open(context, uri, file.name)
                }
        )
    } else {
        // Placeholder / error state — NEVER an empty container!
        val c = MinaTheme.colors
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(c.fill, MdErrorShape)
                .border(0.5.dp, c.hair, MdErrorShape)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = MinaIcons.Exclaim,
                contentDescription = null,
                tint = c.ink3,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                if (image.alt.isNotBlank()) {
                    Text(
                        text = image.alt,
                        style = style.copy(fontSize = 13.5.sp, fontWeight = FontWeight.Medium),
                        color = c.ink2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    text = stringResource(R.string.image_load_failed),
                    style = style.copy(fontSize = 12.sp),
                    color = c.ink3
                )
            }
        }
    }
}

/** Table: Each column takes the widest cell in the column (there is an upper limit, and if it exceeds the line break), each row takes the highest cell in the row; the entire table can be slid left or right. */
@Composable
private fun TableBlock(t: MdBlock.Table, style: TextStyle) {
    val cols = t.header.size
    if (cols == 0) return
    val cellStyle = style.copy(fontSize = 13.5.sp, lineHeight = 19.sp)
    val rows = listOf(t.header) + t.rows
    Box(Modifier.horizontalScroll(rememberScrollState())) {
        Layout(
            content = {
                rows.forEachIndexed { r, row ->
                    row.forEach { cell ->
                        Box(
                            Modifier.border(0.5.dp, MinaTheme.colors.hair)
                                .then(if (r == 0) Modifier.background(MinaTheme.colors.fill) else Modifier)
                                .padding(horizontal = 9.dp, vertical = 6.dp),
                        ) {
                            Text(cell, style = if (r == 0) cellStyle.copy(fontWeight = FontWeight.SemiBold) else cellStyle)
                        }
                    }
                }
            },
            modifier = Modifier.border(0.5.dp, MinaTheme.colors.hair, MdTableShape),
        ) { measurables, _ ->
            val maxCol = 240.dp.roundToPx()
            val minCol = 48.dp.roundToPx()
            val widths = IntArray(cols)
            measurables.forEachIndexed { i, m ->
                val c = i % cols
                widths[c] = maxOf(widths[c], m.maxIntrinsicWidth(Constraints.Infinity).coerceIn(minCol, maxCol))
            }
            val heights = measurables.chunked(cols).map { row -> row.withIndex().maxOf { (c, m) -> m.minIntrinsicHeight(widths[c]) } }
            // The cells in the same row must be of the same height, and the border and header background
            // must be aligned
            val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i % cols], heights[i / cols])) }
            layout(widths.sum(), heights.sum()) {
                var y = 0
                placeables.chunked(cols).forEachIndexed { r, row ->
                    var x = 0
                    row.forEachIndexed { c, p ->
                        p.place(x, y)
                        x += widths[c]
                    }
                    y += heights[r]
                }
            }
        }
    }
}
