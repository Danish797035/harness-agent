package com.meenai.mina.core.agent

import com.meenai.mina.core.tool.ToolDispatcher

/**
 * The user's own words in a sentence (starting, interjecting, answering follow-up questions). The
 * language check for language prompts and answers only looks at the words he typed this time ([typed]);
 * the quotations for memory and skills also look at the words he quoted himself ([said]), and Mina's
 * answers quoted are not counted.
 */
internal object UserWords {
    /**
     * [text] is the sentence after removing the screen description (see [UserScreenTurns]); then remove
     * the page description of the browser page ([ToolDispatcher.userWords]), the first paragraph
     * ([QuoteMessage]) and the attachment line at the end ([AttachmentLines]). If there are none, it
     * means the original words; if only a paragraph is quoted, only words are selected, and only
     * attachments are included, it is empty.
     */
    fun typed(text: String, tools: ToolDispatcher): String = AttachmentLines.words(QuoteMessage.words(tools.userWords(text)))

    /** In addition to [typed], when quoting what the user said himself, the quoted paragraph is counted together (the dialogue page quotes are what he typed at the time) and is placed in front; when quoting Mina's answer, only [typed] is used. */
    fun said(text: String, tools: ToolDispatcher): String {
        val own = QuoteMessage.parse(tools.userWords(text)).quote?.takeIf { it.from == QuoteMessage.From.You }?.text
        return listOfNotNull(own, typed(text, tools)).filter { it.isNotBlank() }.joinToString("\n")
    }
}
