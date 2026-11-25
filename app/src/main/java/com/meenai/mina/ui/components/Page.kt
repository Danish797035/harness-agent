package com.meenai.mina.ui.components

import com.meenai.mina.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaSpace

/**
 * The skeleton of the subpage: bottom of the page + top bar with return + a column of scrollable content
 * (leave [MinaSpace.Page] on the left and right, avoid the navigation bar). Put [GroupLabel] /
 * [GroupCard] / [FooterNote] directly in the content.
 */
@Composable
fun PageScaffold(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    PageBackground(modifier) {
        Column(Modifier.fillMaxSize()) {
            TopBar(title, MinaIcons.Back, onBack, stringResource(R.string.back))
            Column(
                Modifier.weight(1f).fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = MinaSpace.Page),
            ) {
                Spacer(Modifier.height(MinaSpace.S4))
                content()
                Spacer(Modifier.height(MinaSpace.S24))
            }
        }
    }
}
