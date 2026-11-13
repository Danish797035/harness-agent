package com.meenai.mina.ui.image

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.viewer.FileViewer
import java.io.File

data class GeneratedImageUi(
    val filePath: String? = null,
    val isGenerating: Boolean = false,
    val prompt: String = "",
    val model: String = "",
)

@Composable
fun GeneratedImageCard(
    imageUi: GeneratedImageUi,
    onSave: (File) -> Unit,
    onShare: (File) -> Unit,
    onRegenerate: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    val context = LocalContext.current

    Card(
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            if (imageUi.isGenerating) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(vertical = 16.dp, horizontal = 8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = tr(R.string.image_generating),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val file = remember(imageUi.filePath) { imageUi.filePath?.takeIf { it.isNotBlank() }?.let { File(it) } }
                val bitmap = remember(imageUi.filePath) {
                    if (file?.exists() == true) {
                        BitmapFactory.decodeFile(file.absolutePath)
                    } else null
                }

                if (file != null && bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = imageUi.prompt,
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

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { onSave(file) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text(text = tr(R.string.image_save), fontSize = 13.sp)
                        }

                        OutlinedButton(
                            onClick = { onShare(file) },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text(text = tr(R.string.image_share), fontSize = 13.sp)
                        }

                        Button(
                            onClick = { onRegenerate(imageUi.prompt) },
                            modifier = Modifier.weight(1.3f),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text(text = tr(R.string.image_regenerate), fontSize = 13.sp)
                        }
                    }
                } else {
                    // Failed or missing image state — NEVER an empty container!
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp, horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = MinaIcons.Exclaim,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = tr(R.string.image_generation_failed),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                        if (imageUi.prompt.isNotBlank()) {
                            OutlinedButton(
                                onClick = { onRegenerate(imageUi.prompt) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                Text(text = tr(R.string.image_regenerate), fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
