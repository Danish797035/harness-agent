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
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import com.meenai.mina.ui.components.AiLogo
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType
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

fun formatImageModelName(model: String): String {
    val clean = model.substringAfterLast("/")
    return when {
        clean.contains("seedream", ignoreCase = true) -> "seedream-5.0-lite"
        clean.contains("flux", ignoreCase = true) -> "FLUX.1-schnell"
        clean.contains("qwen-image-2.1", ignoreCase = true) -> "Qwen-Image-2.1"
        clean.contains("qwen-image", ignoreCase = true) -> "Qwen-Image"
        clean.contains("cogview", ignoreCase = true) -> "CogView-3-Plus"
        else -> clean.ifBlank { "seedream-5.0-lite" }
    }
}

@Composable
fun GeneratedImageCard(
    imageUi: GeneratedImageUi,
    onSave: (File) -> Unit,
    onShare: (File) -> Unit,
    onRegenerate: (String) -> Unit,
    onRegenerateWithModel: ((prompt: String, model: String) -> Unit)? = null,
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
            val displayModel = imageUi.model.ifBlank { "seedream-5.0-lite" }
            var showModelMenu by remember { mutableStateOf(false) }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AiLogo(
                        providerOrModel = displayModel,
                        size = 18.dp,
                        showContainer = true,
                    )
                    Text(
                        text = tr(R.string.image_generated_by, formatImageModelName(displayModel)),
                        style = MinaType.Hint.copy(fontWeight = FontWeight.Medium, fontSize = 12.sp),
                        color = MinaTheme.colors.ink2,
                    )
                }

                Box {
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { showModelMenu = true }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = tr(R.string.image_switch_model),
                            style = MinaType.Hint.copy(fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp),
                            color = MinaTheme.colors.accent,
                        )
                        Icon(
                            imageVector = MinaIcons.ChevronDown,
                            contentDescription = null,
                            tint = MinaTheme.colors.accent,
                            modifier = Modifier.size(12.dp),
                        )
                    }

                    DropdownMenu(
                        expanded = showModelMenu,
                        onDismissRequest = { showModelMenu = false },
                    ) {
                        listOf(
                            "seedream-5.0-lite" to "seedream-5.0-lite (Fast / Free)",
                            "Qwen/Qwen-Image-2.1" to "Qwen-Image-2.1 (Free)",
                            "black-forest-labs/flux-1-schnell" to "FLUX.1-schnell (Quality)",
                        ).forEach { (modelId, label) ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        AiLogo(providerOrModel = modelId, size = 16.dp, showContainer = false)
                                        Text(label, fontSize = 13.sp)
                                    }
                                },
                                onClick = {
                                    showModelMenu = false
                                    if (onRegenerateWithModel != null) {
                                        onRegenerateWithModel(imageUi.prompt, modelId)
                                    } else {
                                        onRegenerate("Generate with $modelId: ${imageUi.prompt}")
                                    }
                                }
                            )
                        }
                    }
                }
            }
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
