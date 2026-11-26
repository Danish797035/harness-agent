package com.meenai.mina.ui

import com.meenai.mina.R
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.meenai.mina.ui.components.PageBackground
import com.meenai.mina.ui.image.ImageBitmapCache
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.EmptyNote
import com.meenai.mina.ui.components.TopBar
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaType

/** The source of a reply: each item is "step · where · title", and the following is the picture at that time; those without pictures (webpage text, files, command output read in the background) are excerpts of the original text. */
@Composable
fun SourceScreen(vm: SourceViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ui by produceState<SourceUi?>(null, vm) { value = withContext(Dispatchers.IO) { vm.load() } }
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val c = MinaTheme.colors
    PageBackground {
        Column(Modifier.fillMaxSize()) {
            TopBar(stringResource(R.string.source_label), left = MinaIcons.Back, leftLabel = stringResource(R.string.back), onLeft = onBack)
            ui?.let { source ->
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottom + 16.dp),
                ) {
                    source.legacyText?.takeIf(String::isNotBlank)?.let { legacy ->
                        item(key = "legacy", contentType = "legacy") { GroupCard { Text(legacy, Modifier.padding(16.dp), style = MinaType.Body, color = c.ink) } }
                    }
                    source.entries.forEachIndexed { index, entry ->
                        item(key = "source-$index", contentType = "source_entry") {
                            GroupCard {
                                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
                                    Text(
                                        listOfNotNull(stringResource(R.string.step_number, entry.step), entry.place, entry.title).joinToString(" · "),
                                        style = MinaType.BodyStrong, color = c.ink,
                                    )
                                    // If there is a picture, only the picture will be given: the
                                    // recognized text and picture will be repeated; if there is no
                                    // picture, the excerpt will be the only basis.
                                    if (entry.screenshots.isEmpty()) {
                                        entry.text?.let { Text(it, Modifier.padding(top = 6.dp), style = MinaType.Caption, color = c.ink2) }
                                    }
                                    entry.screenshots.forEach { id -> SourceShot(vm, id) }
                                }
                            }
                        }
                    }
                    if (source.legacyText.isNullOrBlank() && source.entries.isEmpty()) {
                        item(key = "empty", contentType = "empty") { EmptyNote(stringResource(R.string.source_none)) }
                    }
                }
            }
        }
    }
}

private val SourceShotShape = RoundedCornerShape(12.dp)

@Composable
private fun SourceShot(vm: SourceViewModel, id: Int) {
    val image by produceState<ImageBitmap?>(null, vm, id) {
        value = withContext(Dispatchers.IO) { vm.screen(id)?.let { ImageBitmapCache.loadFromFileSync(it.path) } }
    }
    var zoomed by remember { mutableStateOf(false) }
    image?.let {
        Image(it, stringResource(R.string.source_image), Modifier.fillMaxWidth().padding(top = 10.dp).clip(SourceShotShape)
            .clickable(onClickLabel = stringResource(R.string.zoom_in)) { zoomed = true },
            contentScale = ContentScale.FillWidth)
        if (zoomed) ZoomedShot(it) { zoomed = false }
    }
}

/** To view a picture in full screen, click or press Return to close it. */
@Composable
private fun ZoomedShot(image: ImageBitmap, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier.fillMaxSize().background(Color.Black).clickable(onClickLabel = stringResource(R.string.collapse), onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Image(image, stringResource(R.string.source_image), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
    }
}
