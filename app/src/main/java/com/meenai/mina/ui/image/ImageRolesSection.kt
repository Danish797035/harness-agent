package com.meenai.mina.ui.image

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.meenai.mina.R
import com.meenai.mina.core.image.ImageRole
import com.meenai.mina.i18n.tr
import com.meenai.mina.image.ImageSettings
import com.meenai.mina.ui.components.GroupCard
import com.meenai.mina.ui.components.GroupLabel
import com.meenai.mina.ui.components.ListRow
import com.meenai.mina.ui.components.MinaSwitch
import com.meenai.mina.ui.components.RowChevron
import com.meenai.mina.ui.components.RowValue
import com.meenai.mina.ui.theme.MinaIcons

@Composable
fun ImageRolesSection(
    settings: ImageSettings,
    modifier: Modifier = Modifier,
) {
    val allowPaid by settings.allowPaid.collectAsState()
    val pinnedImage by settings.pinnedImage.collectAsState()
    val pinnedImageEdit by settings.pinnedImageEdit.collectAsState()

    var dialogRole by remember { mutableStateOf<ImageRole?>(null) }

    GroupLabel(tr(R.string.image_roles_title), modifier)
    GroupCard {
        // 1. Image generation role
        val imageModel = settings.todayModel(ImageRole.Image)
        val imageSub = if (pinnedImage == null) {
            "${tr(R.string.image_role_auto)} · $imageModel"
        } else {
            "${tr(R.string.image_role_pinned)} · $imageModel"
        }
        ListRow(
            icon = MinaIcons.Photo,
            title = "${tr(R.string.image_role_image_title)} · ${tr(R.string.image_role_image_what)}",
            subtitle = imageSub,
            first = true,
            onClick = { dialogRole = ImageRole.Image },
        ) {
            RowValue(if (pinnedImage == null) tr(R.string.image_role_auto) else tr(R.string.image_role_pinned))
            RowChevron()
        }

        // 2. Image edit role
        val editModel = settings.todayModel(ImageRole.ImageEdit)
        val editSub = if (pinnedImageEdit == null) {
            "${tr(R.string.image_role_auto)} · $editModel"
        } else {
            "${tr(R.string.image_role_pinned)} · $editModel"
        }
        ListRow(
            icon = MinaIcons.Edit,
            title = "${tr(R.string.image_role_edit_title)} · ${tr(R.string.image_role_edit_what)}",
            subtitle = editSub,
            first = false,
            onClick = { dialogRole = ImageRole.ImageEdit },
        ) {
            RowValue(if (pinnedImageEdit == null) tr(R.string.image_role_auto) else tr(R.string.image_role_pinned))
            RowChevron()
        }

        // 3. Paid switch: "Paid image models allow karo"
        ListRow(
            icon = MinaIcons.Spark,
            title = tr(R.string.image_allow_paid_title),
            subtitle = tr(R.string.image_allow_paid_subtitle),
            first = false,
        ) {
            MinaSwitch(
                checked = allowPaid,
                onCheckedChange = { settings.setAllowPaid(it) },
                modifier = Modifier.testTag("switch-allow-paid-image-models"),
            )
        }
    }

    dialogRole?.let { role ->
        ImageModelPickerDialog(
            role = role,
            currentPinned = if (role == ImageRole.Image) pinnedImage else pinnedImageEdit,
            allowPaid = allowPaid,
            onSelect = { model ->
                settings.setPinnedModel(role, model)
                dialogRole = null
            },
            onDismiss = { dialogRole = null },
        )
    }
}

@Composable
private fun ImageModelPickerDialog(
    role: ImageRole,
    currentPinned: String?,
    allowPaid: Boolean,
    onSelect: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = mutableListOf<Pair<String?, String>>()
    // Auto option
    options.add(null to "${tr(R.string.image_role_auto)} (${tr(R.string.image_role_auto_desc)})")

    when (role) {
        ImageRole.Image -> {
            options.add("Qwen/Qwen-Image-2.1" to "Qwen/Qwen-Image-2.1 (ModelScope)")
            options.add("Qwen/Qwen-Image" to "Qwen/Qwen-Image (ModelScope)")
            if (allowPaid) {
                options.add("black-forest-labs/flux-1-schnell" to "black-forest-labs/flux-1-schnell (OpenRouter)")
            }
        }
        ImageRole.ImageEdit -> {
            options.add("Qwen/Qwen-Image-Edit" to "Qwen/Qwen-Image-Edit (ModelScope)")
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr(R.string.image_model_dialog_title)) },
        text = {
            Column {
                options.forEach { (model, label) ->
                    val isSelected = (currentPinned == null && model == null) || (currentPinned == model)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(model) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = { onSelect(model) },
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(tr(R.string.cancel))
            }
        },
    )
}
