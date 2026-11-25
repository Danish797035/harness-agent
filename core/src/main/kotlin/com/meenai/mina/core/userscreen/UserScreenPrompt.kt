package com.meenai.mina.core.userscreen

import com.meenai.mina.core.observation.Untrusted
import com.meenai.mina.core.prompt.Feedback
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The way to write a sentence with the user screen to the model is only here. Separate from the pictures
 * in the album (attachments, still files):
 * - - The user's sentence itself is written as "The user just took a screenshot of the screen (App,
 * time) he is looking at, without a postscript / postscript:..." (if you are not allowed to take a
 * screenshot, tell the truth that it was not taken);
 * - - The words and numbers on the screen are another context ([detail]). If there is no postscript,
 * please follow the rules of guessing the intention by pressing the screen;
 * - - There is only one line left on the screen in the previous rounds ([earlier]): App, time and the
 * first few words on the screen. If you want to look at it again, press the number to look back.
 */
object UserScreenPrompt {
    /** The beginning of a few words on that screen from previous rounds. */
    const val GIST_CHARS = 40

    private val TIME = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ENGLISH)

    fun time(at: Long, zone: ZoneId): String = TIME.format(Instant.ofEpochMilli(at).atZone(zone))

    /** The user's statement: [screen] is null, which means the screenshot is no longer there. */
    fun said(id: String, screen: UserScreen?, words: String, zone: ZoneId): String {
        if (screen == null) return listOf(Feedback.format("user_screen.missing", "id" to id), words).filter(String::isNotBlank).joinToString("\n")
        val vars = arrayOf("app" to screen.app, "time" to time(screen.takenAt, zone), "words" to words)
        return when {
            screen.blocked && words.isEmpty() -> Feedback.format("user_screen.blocked", *vars)
            screen.blocked -> Feedback.format("user_screen.blocked_with_words", *vars)
            words.isEmpty() -> Feedback.format("user_screen.sent", *vars)
            else -> Feedback.format("user_screen.sent_with_words", *vars)
        }
    }

    /**
     * The details of this screen (context text): number, app, time, words on the screen (put an
     * untrustworthy mark: the words on the screen were written by someone else or the app); when
     * [noWords], the rule of guessing the intention by pressing the screen is followed. When
     * [askVision], if you can’t see the picture, you can ask the picture model. The page that does not
     * allow screenshots only says that the screenshot cannot be taken and how to reply.
     */
    fun detail(screen: UserScreen, noWords: Boolean, askVision: Boolean, zone: ZoneId): String {
        if (screen.blocked) return Feedback["user_screen.blocked_reply"]
        val lines = if (screen.lines.isEmpty()) Feedback["user_screen.no_text"]
            else Untrusted.wrap(screen.lines.joinToString("\n") { "(${it.x},${it.y}) ${it.text}" })
        val head = Feedback.format(
            "user_screen.detail", "id" to screen.id, "app" to screen.app,
            "package" to (screen.packageName?.takeIf { it != screen.app }?.let { "($it)" }.orEmpty()),
            "time" to time(screen.takenAt, zone), "lines" to lines,
        )
        return listOfNotNull(head, Feedback["user_screen.ask_vision"].takeIf { askVision }, Feedback["user_screen.no_words"].takeIf { noWords })
            .joinToString("\n\n")
    }

    /** In previous rounds, one screen yielded one row; the screenshots just said it was gone when it was no longer there. */
    fun earlier(id: String, screen: UserScreen?, zone: ZoneId): String {
        if (screen == null) return Feedback.format("user_screen.missing", "id" to id)
        val vars = arrayOf("id" to id, "app" to screen.app, "time" to time(screen.takenAt, zone))
        if (screen.blocked) return Feedback.format("user_screen.earlier_blocked", *vars)
        val gist = gist(screen) ?: return Feedback.format("user_screen.earlier_blank", *vars)
        return Feedback.format("user_screen.earlier", *vars, "gist" to gist)
    }

    /** The first few words on the screen (skipping very short fragments) are used for the line in previous rounds; no words are null. */
    fun gist(screen: UserScreen): String? {
        val text = screen.lines.map { it.text }.filter { it.length >= 2 }.joinToString(" ").ifEmpty { return null }
        return if (text.length <= GIST_CHARS) text else text.take(GIST_CHARS) + "…"
    }

    /** What the user said in the previous round: Change the line on the screen to [earlier], and the postscript follows (one line). Returns as-is without screen. */
    fun collapse(text: String, lookup: (String) -> UserScreen?, zone: ZoneId): String {
        val p = UserScreenMessage.parse(text)
        if (!p.hasScreen) return text
        return (p.screens.map { earlier(it, lookup(it), zone) } + p.words.replace('\n', ' ')).filter(String::isNotBlank).joinToString(" ")
    }
}
