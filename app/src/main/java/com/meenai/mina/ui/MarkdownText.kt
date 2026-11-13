package com.meenai.mina.ui

import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.ui.graphics.asImageBitmap
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
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.viewer.FileViewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 按 Markdown 显示一段回答；流式草稿用临时闭合的语法预览。 */
@Composable
fun MarkdownText(markdown: String, style: TextStyle, modifier: Modifier = Modifier, streaming: Boolean = false) {
    // 流式片段更新频繁，在后台解析；上一帧保留到新片段准备好，避免主线程卡顿和文字闪空。
    val colors = MinaTheme.colors
    val styles = remember(colors) { MarkdownModel.Styles.of(colors) }
    val blocks = if (streaming) {
        val parsed by produceState<List<MdBlock>>(emptyList(), markdown, styles) {
            value = withContext(Dispatchers.Default) { MarkdownModel.parseStreaming(markdown, styles) }
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
            Modifier.fillMaxWidth().background(MinaTheme.colors.fill, RoundedCornerShape(10.dp))
                .horizontalScroll(rememberScrollState()).padding(horizontal = 11.dp, vertical = 9.dp),
        ) {
            Text(b.code, style = style.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp), softWrap = false)
        }
        is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(MinaTheme.colors.hair, RoundedCornerShape(2.dp)))
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
    val localPath = if (rawDest.startsWith("file://")) rawDest.removePrefix("file://") else rawDest
    val file = remember(localPath) { File(localPath) }
    val exists = remember(file) { file.exists() && file.isFile && file.canRead() }

    val bitmap = remember(file.absolutePath, if (exists) file.lastModified() else 0L) {
        if (exists) {
            runCatching { decodeSampledBitmap(file.absolutePath, 2048) }.getOrNull()
        } else null
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = image.alt.ifBlank { null },
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .clip(RoundedCornerShape(12.dp))
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
                .background(c.fill, RoundedCornerShape(10.dp))
                .border(0.5.dp, c.hair, RoundedCornerShape(10.dp))
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

private fun decodeSampledBitmap(filePath: String, maxDimension: Int): Bitmap? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(filePath, options)
    if (options.outWidth <= 0 || options.outHeight <= 0) return null
    var sampleSize = 1
    val longest = maxOf(options.outWidth, options.outHeight)
    while (longest / sampleSize > maxDimension) {
        sampleSize *= 2
    }
    val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
    return BitmapFactory.decodeFile(filePath, decodeOptions)
}

/** 表格:各列取本列最宽的格(有上限,超了换行),各行取本行最高的格;整张表可左右滑动。 */
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
            modifier = Modifier.border(0.5.dp, MinaTheme.colors.hair, RoundedCornerShape(2.dp)),
        ) { measurables, _ ->
            val maxCol = 240.dp.roundToPx()
            val minCol = 48.dp.roundToPx()
            val widths = IntArray(cols)
            measurables.forEachIndexed { i, m ->
                val c = i % cols
                widths[c] = maxOf(widths[c], m.maxIntrinsicWidth(Constraints.Infinity).coerceIn(minCol, maxCol))
            }
            val heights = measurables.chunked(cols).map { row -> row.withIndex().maxOf { (c, m) -> m.minIntrinsicHeight(widths[c]) } }
            // 同一行的格一样高,边框和表头底色才对齐
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
