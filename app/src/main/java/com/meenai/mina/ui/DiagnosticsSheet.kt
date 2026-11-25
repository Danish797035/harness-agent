package com.meenai.mina.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meenai.mina.about.Diagnostics
import com.meenai.mina.ui.components.ButtonKind
import com.meenai.mina.ui.components.MinaButton
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Preview of diagnostic information: The entire text is shown to the user first, then clicked "Copy" to
 * enter the clipboard, and clicked "Share" to send it to the system's sharing panel; nothing will be
 * sent out by itself. [load] Collect and write that text (to read the disk, run it on a background
 * thread); when an error occurs, only the title and exception category are displayed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsSheet(load: () -> String, onDismiss: () -> Unit) {
    val c = MinaTheme.colors
    val context = LocalContext.current
    // Collect it once when it is opened; after that, the status will not change. What the user sees is
    // what is to be copied.
    val text by produceState<String?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching(load).getOrElse { Diagnostics.sanitize("${Diagnostics.HEADER}\n(failed to collect: ${it.javaClass.simpleName})\n") }
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surface, shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp).testTag("diagnostics-sheet")) {
            Text(AboutCopy.DIAGNOSTICS_TITLE, style = MinaType.Title, color = c.ink)
            Text(AboutCopy.DIAGNOSTICS_NOTE, Modifier.padding(top = 2.dp), style = MinaType.Caption, color = c.ink3)
            Box(
                Modifier.padding(top = 12.dp).fillMaxWidth().heightIn(min = 120.dp, max = 360.dp)
                    .background(c.fill, RoundedCornerShape(12.dp)).verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()).padding(12.dp),
            ) {
                Text(
                    text ?: AboutCopy.DIAGNOSTICS_LOADING, Modifier.testTag("diagnostics-text"),
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = if (text == null) c.ink3 else c.ink2,
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val ready = text != null
                MinaButton(
                    AboutCopy.DIAGNOSTICS_COPY, { text?.let { copyDiagnostics(context, it) } },
                    Modifier.weight(1f).testTag("diagnostics-copy"), enabled = ready,
                )
                MinaButton(
                    AboutCopy.DIAGNOSTICS_SHARE, { text?.let { shareDiagnostics(context, it) } },
                    Modifier.weight(1f).testTag("diagnostics-share"), kind = ButtonKind.Tonal, enabled = ready,
                )
            }
        }
    }
}

private fun copyDiagnostics(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(Diagnostics.HEADER, text))
    Toast.makeText(context, AboutCopy.DIAGNOSTICS_COPIED, Toast.LENGTH_SHORT).show()
}

private fun shareDiagnostics(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    runCatching { context.startActivity(Intent.createChooser(send, AboutCopy.DIAGNOSTICS_TITLE)) }
}
