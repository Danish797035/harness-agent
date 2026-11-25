package com.meenai.mina.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import com.meenai.mina.R
import com.meenai.mina.core.agent.QuoteMessage
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.meenai.mina.ui.components.GlassBlurSupported
import com.meenai.mina.ui.components.cardSurface
import com.meenai.mina.ui.theme.MinaIcons
import com.meenai.mina.ui.theme.MinaTheme
import com.meenai.mina.ui.theme.MinaType

/**
 * A message that is long-pressed in the conversation page: [key], which one is it ("Select text" records
 * it), [from], who said it, [text] is the plain text used for copying and quoting (Markdown symbols are
 * removed from the answer, attachments, screens, and quotation lines are removed from the user's words),
 * [bounds] is its position and size in the window, [lifted] The one where the painting is lifted.
 */
internal class MessageTarget(
    val key: String,
    val from: QuoteMessage.From,
    val text: String,
    val bounds: Rect,
    val lifted: @Composable () -> Unit,
)

/** The long-press menu for messages on the conversation page: [selecting] is the one currently in "Select Text", [open] pop-up menu. */
internal class MessageMenuHost(val selecting: String?, val open: (MessageTarget) -> Unit)

/** When there is no message (testing a component alone), the text is still selected by long pressing. */
internal val LocalMessageMenu = compositionLocalOf<MessageMenuHost?> { null }

/**
 * The text part of a message: Long press (vibrate) to pop up the copy/quote/select text menu; the one
 * that selects "Select Text" returns to the system's long press selection ([selectTag] is marked on the
 * selection). Links in the text are still clicked (clicks are processed first by the text inside). File
 * cards and "View Process" are not included. When [text] is empty, the menu will not pop up. [modifier]
 * is the bottom (background, width, padding) of this message. The lifted one is placed according to its
 * position and size; the outer margin is placed outside.
 */
@Composable
internal fun MessageArea(
    key: String,
    from: QuoteMessage.From,
    text: String,
    selectTag: String,
    modifier: Modifier = Modifier,
    lifted: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val host = LocalMessageMenu.current
    if (host == null || host.selecting == key || text.isBlank()) {
        SelectionContainer(modifier.testTag(selectTag)) { content() }
        return
    }
    val haptic = LocalHapticFeedback.current
    val label = stringResource(R.string.chat_message_actions)
    // The position and size in the window (do not cut off the part that rolls out of the list); only
    // read in the callback, does not cause reorganization
    val bounds = remember { mutableStateOf(Rect.Zero) }
    // Gestures always use the latest one (it will be reorganized continuously during streaming
    // responses), and will not be restarted due to reorganization, or the long press in the middle will
    // be lost.
    val open by rememberUpdatedState({ host.open(MessageTarget(key, from, text, bounds.value, lifted)) })
    Box(
        Modifier
            .onGloballyPositioned { bounds.value = Rect(it.positionInWindow(), it.size.toSize()) }
            .then(modifier)
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = {
                    // It vibrates like a long press in the system.
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    open()
                })
            }
            .semantics { onLongClick(label) { open(); true } }
            .testTag("message-$key"),
    ) { content() }
}

/**
 * The menu after long-pressing a message (the same as the long-press conversation in the sidebar, see
 * [LongPressMenu]): The message is lifted, and below are the copy, quote, and text selection. The user's
 * own words are to the right, and the menu is also to the right.
 */
@Composable
internal fun MessageMenu(
    target: MessageTarget,
    progress: () -> Float,
    onCopy: () -> Unit,
    onQuote: () -> Unit,
    onSelect: () -> Unit,
    onDismiss: () -> Unit,
    onEdit: (() -> Unit)? = null,
    onWithdraw: (() -> Unit)? = null,
) {
    LongPressMenu(
        target.bounds, progress, pane = stringResource(R.string.chat_message_actions), tag = "message-menu",
        entries = buildList {
            add(MenuEntry(stringResource(R.string.chat_copy), MinaIcons.Copy, "message-menu-copy", onClick = onCopy))
            add(MenuEntry(stringResource(R.string.chat_quote), MinaIcons.Quote, "message-menu-quote", onClick = onQuote))
            add(MenuEntry(stringResource(R.string.chat_select_text), MinaIcons.SelectText, "message-menu-select", onClick = onSelect))
            onEdit?.let { add(MenuEntry(stringResource(R.string.chat_edit_message), MinaIcons.Edit, "message-menu-edit", onClick = it)) }
            onWithdraw?.let { add(MenuEntry(stringResource(R.string.chat_withdraw_message), MinaIcons.Close, "message-menu-withdraw", onClick = it)) }
        },
        onDismiss = onDismiss,
        alignEnd = target.from == QuoteMessage.From.You,
        lifted = target.lifted,
    )
}

/** When the menu is open, the back layer: starting from Android 12, the progress that appears is blurred ([MenuBlur]), and the menu is accessible and can only be read. Nothing is added when the menu is not open. */
internal fun Modifier.behindMenu(open: Boolean, progress: () -> Float): Modifier =
    if (!open) this else this
        .then(
            if (GlassBlurSupported) Modifier.graphicsLayer {
                val r = MenuBlur.toPx() * progress()
                renderEffect = if (r >= 0.5f) BlurEffect(r, r, TileMode.Clamp) else null
            } else Modifier,
        )
        .clearAndSetSemantics {}

/** Put the plain text of the message into the clipboard and a "Copied" message will appear. */
internal fun copyMessage(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Harness Agent", text))
    Toast.makeText(context, context.getString(R.string.chat_copied), Toast.LENGTH_SHORT).show()
}

/** How to read a quote to a screen reader: "Quote Mina:..." "Quote you:...". */
@Composable
private fun quoteDescription(q: QuoteMessage.Quote): String =
    stringResource(if (q.from == QuoteMessage.From.Mina) R.string.chat_quoting_mina else R.string.chat_quoting_you, q.text)

/** The quotation to be sent above the input box: a vertical line on the left, the quotation content can be up to two lines, and the × on the right is removed. */
@Composable
internal fun QuoteChip(quote: QuoteMessage.Quote, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    val c = MinaTheme.colors
    val remove = stringResource(R.string.chat_quote_remove)
    val said = quoteDescription(quote)
    Row(
        modifier.fillMaxWidth().testTag("quote-chip").cardSurface(c, QuoteShape).padding(start = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).height(30.dp).background(c.accent, CircleShape))
        Text(
            quote.text, Modifier.weight(1f).padding(start = 10.dp).semantics { contentDescription = said },
            style = MinaType.Caption, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Box(
            Modifier.size(40.dp).clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = remove, onClick = onRemove)
                .semantics { contentDescription = remove }
                .testTag("quote-remove"),
            contentAlignment = Alignment.Center,
        ) {
            Icon(MinaIcons.Close, null, Modifier.size(14.dp), tint = c.ink3)
        }
    }
}

/** The one quoted in the user's words: a small area of light color above the bubble, two lines at most. */
@Composable
internal fun SentQuote(quote: QuoteMessage.Quote, maxWidth: Dp) {
    val c = MinaTheme.colors
    val said = quoteDescription(quote)
    Row(
        Modifier.padding(bottom = 4.dp).widthIn(max = maxWidth).background(c.ink.copy(alpha = 0.05f), QuoteShape)
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = said }
            .testTag("sent-quote"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(2.dp).height(16.dp).background(c.ink3, CircleShape))
        Text(quote.text, Modifier.padding(start = 8.dp), style = MinaType.Caption, color = c.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private val QuoteShape = RoundedCornerShape(12.dp)
