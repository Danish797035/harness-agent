package com.meenai.mina.core.agent

import com.meenai.mina.core.model.Part
import com.meenai.mina.core.userscreen.UserScreen
import com.meenai.mina.core.userscreen.UserScreenMessage
import com.meenai.mina.core.userscreen.UserScreenPrompt
import com.meenai.mina.core.userscreen.UserScreenSource
import kotlinx.coroutines.CancellationException
import java.time.ZoneId

/**
 * Screens sent by users in a task: read out the instructions and screenshots, and replace the sentence
 * with the screen with the one for the model (see [UserScreenPrompt]). The screen in this sentence
 * (original words at the beginning, interjection) is full: that sentence, screenshot (at the time of
 * [seesImages]), and details; only one line from the previous rounds is left. When there is no
 * [source](This entrance does not have a user screen), the row is left as it is.
 */
internal class UserScreenTurns(
    private val source: UserScreenSource?,
    private val zone: ZoneId,
    private val seesImages: () -> Boolean,
    /** Can you please tell me the details about the view model (there is view_user_screen and there is a view model, but the main model cannot see the image). */
    private val canAskVision: () -> Boolean,
    private val image: (ByteArray) -> Part.Image,
    /** This sentence gives Quan a full screen (recorded in the evidence ledger). */
    private val seen: (UserScreen) -> Unit = {},
) {
    /**
     * Change the sentence: [said] is the user’s sentence (the original words given to the model),
     * [images] is connected to it, [details] is the following details (source → text); [words] is the
     * words typed by the user (only look at it for memory citations, intention judgment, language
     * prompts and language checks of answers).
     */
    class Rendered(val said: String, val images: List<Part>, val details: List<Pair<String, String>>, val words: String)

    private val cache = HashMap<String, UserScreen?>()

    /** When this sentence is accompanied by the screen, it is replaced by the appearance given to the model; when it is not provided, it is null. */
    suspend fun render(raw: String, attachImages: Boolean = seesImages()): Rendered? {
        val p = UserScreenMessage.parse(raw)
        if (!p.hasScreen) return null
        val screens = p.screens.map { it to load(it) }
        screens.forEach { (_, s) -> s?.let(seen) }
        val said = screens.mapIndexed { i, (id, s) -> UserScreenPrompt.said(id, s, if (i == screens.lastIndex) p.words else "", zone) }
            .joinToString("\n")
        val attachedIds = HashSet<String>()
        val images = if (!attachImages) emptyList() else screens.mapNotNull { (id, s) ->
            if (s == null || s.blocked) null else read { source?.image(id) }?.let(image)?.also { attachedIds += id }
        }
        val askVision = canAskVision()
        val details = screens.mapNotNull { (id, s) ->
            s?.let {
                "user's screen · ${it.app}" to UserScreenPrompt.detail(
                    screen = it,
                    noWords = p.words.isEmpty(),
                    askVision = askVision && !it.blocked,
                    zone = zone,
                    imageAttached = id in attachedIds,
                )
            }
        }
        return Rendered(said, images, details, p.words)
    }

    /** What the user said in previous rounds (original words and interjections): Each line on the screen receives a line of explanation. */
    suspend fun collapse(turns: List<Turn>): List<Turn> {
        if (turns.none { t -> UserScreenMessage.has(t.userText) || t.steers.any(UserScreenMessage::has) }) return turns
        turns.flatMap { listOf(it.userText) + it.steers }.flatMap { UserScreenMessage.parse(it).screens }.distinct().forEach { load(it) }
        fun one(text: String) = UserScreenPrompt.collapse(text, { cache[it] }, zone)
        return turns.map { t -> t.copy(userText = one(t.userText), steers = t.steers.map(::one)) }
    }

    /** When answering with the screen (Mina double-clicked the capsule when asking you): Change it to a paragraph of text, and use the review tool to view the screenshot. */
    suspend fun asText(raw: String): String {
        val r = render(raw, attachImages = false) ?: return raw
        return (listOf(r.said) + r.details.map { it.second }).joinToString("\n\n")
    }

    private suspend fun load(id: String): UserScreen? = cache.getOrPut(id) { read { source?.screen(id) } }

    private inline fun <T> read(block: () -> T?): T? =
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
}
