package com.meenai.mina.ui.components

import com.meenai.mina.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaShapes
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType

/**
 * The skeleton of the second-level page: bottom of the page + top bar (return to the upper left corner)
 * + scrollable content below (16 on the left and right). If the content is very long and the page needs
 * to be loaded lazily, do not use it. Put LazyColumn under [TopBar] yourself.
 */
@Composable
fun SubPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    right: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    PageBackground {
        Column(Modifier.fillMaxSize()) {
            TopBar(title, MinaIcons.Back, onBack, stringResource(R.string.back), right = right)
            Column(
                modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
                content = content,
            )
        }
    }
}

/** A row of action buttons (small capsules) below the row, aligned with the text of the row. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RowActions(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) { content() }
}

/** Search box: shallow filled bottom, rounded corners 14, height 40, magnifying glass on the left. When [onSubmit] is given, the keyboard says "Go", press it to hand it over. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    onSubmit: (() -> Unit)? = null,
) {
    val c = MinaTheme.colors
    Row(
        Modifier.fillMaxWidth().height(40.dp).keepsKeyboard().background(c.fill, MinaShapes.Button).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(MinaIcons.Search, null, Modifier.size(18.dp), tint = c.ink3)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, style = MinaType.Body, color = c.ink3)
            BasicTextField(
                value, onValueChange, modifier.fillMaxWidth(), singleLine = true,
                textStyle = MinaType.Body.copy(color = c.ink), cursorBrush = SolidColor(c.accent),
                keyboardOptions = if (onSubmit != null) KeyboardOptions(imeAction = ImeAction.Go) else KeyboardOptions.Default,
                keyboardActions = KeyboardActions(onGo = { onSubmit?.invoke() }),
            )
        }
    }
}

/** Empty state: One or two sentences of centered instructions. */
@Composable
fun EmptyNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text, modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 28.dp),
        style = MinaType.Body, color = MinaTheme.colors.ink3, textAlign = TextAlign.Center,
    )
}
