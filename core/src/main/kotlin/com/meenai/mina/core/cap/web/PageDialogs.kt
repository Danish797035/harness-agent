package com.meenai.mina.core.cap.web

import com.meenai.mina.core.uistate.PageDialog

/** Reply to the page pop-up box: The browser waits for this sentence before allowing the page to continue running. Each implementation will only be called once. */
interface DialogReply {
    /** OK; [text] is only used for input box pop-up (prompt). */
    fun accept(text: String?)
    fun dismiss()
}

sealed interface DialogAnswer {
    /** There is no pop-up waiting for a reply at this moment. */
    data object NoDialog : DialogAnswer
    /** [textIgnored]: Text is given, but this pop-up box is not an input box pop-up box, and the text is not used. */
    data class Answered(val dialog: PageDialog, val accepted: Boolean, val textIgnored: Boolean) : DialogAnswer
}

/**
 * A page pop-up box with a label waiting for a reply (alert, confirm, prompt, exit page confirmation).
 * When the pop-up box appears, it is only written down, not clicked for the user: the label is in the
 * background, and the model step is not processed. It will still be visible next time when reading the
 * screen, until [answer] or [close]. Note that it does not occupy any threads: the browser only stops
 * the script of this page, and the interface thread remains as usual. There will only be one pop-up
 * frame on the page at the same time; if another one (another frame) comes, press Cancel to get rid of
 * the old one, leaving only the new one. Thread safe.
 */
class PageDialogs {
    private var pending: Pair<PageDialog, DialogReply>? = null

    /** The pop-up box waiting for a reply at this time; if not, it is null. */
    val current: PageDialog? @Synchronized get() = pending?.first

    fun open(dialog: PageDialog, reply: DialogReply) {
        val old = synchronized(this) { pending.also { pending = dialog to reply } }
        old?.second?.dismiss()
    }

    /** Reply and clear; do nothing when there is no pop-up waiting for a reply. */
    fun answer(accept: Boolean, text: String?): DialogAnswer {
        val (dialog, reply) = synchronized(this) { pending.also { pending = null } } ?: return DialogAnswer.NoDialog
        val prompt = dialog.kind == PageDialog.PROMPT
        if (!accept) reply.dismiss()
        else reply.accept(if (prompt) text ?: dialog.defaultText.orEmpty() else null)
        return DialogAnswer.Answered(dialog, accept, textIgnored = text != null && (!prompt || !accept))
    }

    /** When the tab is closed and the page is changed: press Cancel to go back to avoid the page being stuck. */
    fun close() {
        val old = synchronized(this) { pending.also { pending = null } }
        old?.second?.dismiss()
    }
}
