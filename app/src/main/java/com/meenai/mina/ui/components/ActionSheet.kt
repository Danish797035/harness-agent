package com.meenai.mina.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType

/** An item in the operation panel; when [destructive] is true, the text is in error red. */
class SheetAction(val label: String, val destructive: Boolean = false, val onClick: () -> Unit)

/**
 * The operation panel that rises from the bottom after clicking on a line: the top line explains what
 * operation is being performed, and the bottom line has one line for each item. Clicking on any item
 * will execute it first and then close the panel.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionSheet(title: String?, actions: List<SheetAction>, onDismiss: () -> Unit) {
    val c = MinaTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
            if (!title.isNullOrEmpty()) {
                Text(
                    title, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MinaType.Caption, color = c.ink3, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            actions.forEachIndexed { i, a ->
                ListRow(
                    null, a.label, first = i == 0,
                    titleColor = if (a.destructive) c.bad else c.ink,
                    onClick = { a.onClick(); onDismiss() },
                )
            }
        }
    }
}
