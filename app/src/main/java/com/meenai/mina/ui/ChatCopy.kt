package com.meenai.mina.ui

import com.meenai.mina.core.agent.QuoteMessage
import com.meenai.mina.core.cap.web.AttachedPage
import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.i18n.trPlural

/** The text calculated based on the status on the dialogue page; do not touch the interface, just measure directly. */
object ChatCopy {
    /** Empty conversational greetings, by time period. */
    fun greeting(hour: Int): String = when (hour) {
        in 5..10 -> tr(R.string.chat_greeting_morning)
        in 11..12 -> tr(R.string.chat_greeting_noon)
        in 13..17 -> tr(R.string.chat_greeting_afternoon)
        in 18..22 -> tr(R.string.chat_greeting_evening)
        else -> tr(R.string.chat_greeting_night)
    }

    val EMPTY_PROMPT get() = tr(R.string.chat_empty_prompt)

    /** Example in an empty dialog; click to fill in the input box, and the user will modify it before sending it. [kind] determines which line icon to match. */
    enum class ExampleKind { App, Web, File, Schedule, Device }
    data class Example(val kind: ExampleKind, val text: String)

    val examples get() = listOf(
        Example(ExampleKind.App, tr(R.string.chat_example_message)),
        Example(ExampleKind.App, tr(R.string.chat_example_group_summary)),
        Example(ExampleKind.Web, tr(R.string.chat_example_jobs)),
        Example(ExampleKind.App, tr(R.string.chat_example_music)),
        Example(ExampleKind.Device, tr(R.string.chat_example_alarms)),
        Example(ExampleKind.App, tr(R.string.chat_example_creator)),
        Example(ExampleKind.App, tr(R.string.chat_example_food)),
        Example(ExampleKind.App, tr(R.string.chat_example_ride)),
    )

    /** The title of the top bar of the conversation page: is always the App name; the subtitle tells what you are doing at the moment. */
    const val APP_TITLE = "Harness Agent"

    /** The name of the dialogue: the first sentence (only the first line is taken, the lines in the attachment are not counted; when only the screen is sent, it is "your current screen"); the empty dialogue is empty. Use it to call conversations in the sidebar. */
    fun title(exchanges: List<Exchange>): String =
        exchanges.firstOrNull { it.goal.isNotBlank() }?.goal?.let(::plainTitle).orEmpty()

    /**
     * How to write a user's quote as a title: remove the webpage description ([AttachedPage]), quotation
     * ([QuoteMessage]), attachment lines (when only attachments are sent, it is "photo", "contract.pdf",
     * etc.) and screen line (when only the screen is sent, it is "your screen at the moment"), and only
     * take the first line.
     */
    fun plainTitle(text: String): String =
        ScreenCopy.plain(AttachmentMessage.plain(QuoteMessage.words(AttachedPage.words(text)))).lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()

    enum class Tone { Accent, Warn, Ok }

    /** Top column subtitle: This conversation leaves one page running in the background (no tasks are running). */
    val KEPT_PAGE: String get() = tr(R.string.kept_chat_sub)

    /** Top bar subtitle: What are you doing (accent color) or waiting for you (orange); null when no task is running. [place] is the operating app or website. */
    fun subtitle(ex: Exchange?, place: String?): Pair<String, Tone>? = when (ex?.status) {
        null -> null
        ExchangeStatus.Working -> (if (place.isNullOrBlank()) tr(R.string.chat_working) else tr(R.string.chat_working_on, place)) to Tone.Accent
        ExchangeStatus.WaitingUnlock -> tr(R.string.chat_wait_unlock) to Tone.Warn
        is ExchangeStatus.Queued -> tr(R.string.chat_queued) to Tone.Accent
        is ExchangeStatus.NeedsTakeover, is ExchangeStatus.NeedsInput,
        is ExchangeStatus.NeedsConfirmation, is ExchangeStatus.Paused -> tr(R.string.chat_waiting_for_you) to Tone.Warn
        else -> null
    }

    /** The progress line: when running, it is the progress at the moment, when it stops and waits for you, it is "paused". */
    fun progressLine(ex: Exchange): String =
        if (paused(ex.status)) tr(R.string.chat_paused) else liveLine(ex) ?: tr(R.string.chat_working)

    /** The task stops and waits for you (requiring you to process, answer, agree, unlock, and click continue). */
    fun paused(status: ExchangeStatus): Boolean =
        status is ExchangeStatus.NeedsTakeover || status is ExchangeStatus.NeedsInput ||
            status is ExchangeStatus.NeedsConfirmation || status is ExchangeStatus.Paused || status == ExchangeStatus.WaitingUnlock

    /** The time on the right side of the progress line: "0:24", "12:05", "1:02:03". */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = s % 3600 / 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
    }

    /** When running, only the most recent steps are listed, and the previous harvest line is "n steps ahead". Click to see them all. */
    const val VISIBLE_STEPS = 5

    fun earlierSteps(n: Int) = trPlural(R.plurals.chat_earlier_steps, n)

    /** The line of small print below the steps when operating the phone in the background. */
    val BACKGROUND_NOTE get() = tr(R.string.chat_background_note)

    /**
     * The result of an action (the effect code in the record → a sentence for people to see). The error
     * code is only meaningful for development, and it will always say "unsuccessful" when viewed by
     * others. The running process page uses the same statement.
     */
    fun effectLabel(effect: String?): String = when (effect) {
        "changed", "done", "place_changed" -> tr(R.string.result_effective)
        "small_change" -> tr(R.string.effect_small_change)
        "unchanged", "no_effect" -> tr(R.string.result_unchanged)
        "completed" -> tr(R.string.result_completed)
        null, "" -> ""
        else -> tr(R.string.step_failed)
    }

    /** After clicking on a step, each thing has one line: what was done, what was done, and the result (do not write when the result is empty). */
    fun actionLine(a: StepAction): String =
        listOfNotNull(a.label, a.target?.takeIf(String::isNotBlank), effectLabel(a.effect).takeIf(String::isNotBlank)).joinToString(" · ")

    fun placeholder(mode: ComposerMode, hasHistory: Boolean): String = when (mode) {
        ComposerMode.Busy -> tr(R.string.chat_placeholder_busy)
        ComposerMode.Answer -> tr(R.string.chat_placeholder_answer)
        ComposerMode.Idle -> if (hasHistory) tr(R.string.chat_placeholder_more) else EMPTY_PROMPT
    }

    /** You can press and hold the gray words when speaking (with a small microphone on the front): it tells the person that you can press and hold to speak and click to type; when the task is running, you can interrupt when speaking. */
    fun voicePlaceholder(mode: ComposerMode): String = when (mode) {
        ComposerMode.Busy -> tr(R.string.voice_placeholder_busy)
        ComposerMode.Answer -> tr(R.string.voice_placeholder_answer)
        ComposerMode.Idle -> tr(R.string.voice_placeholder_idle)
    }

    /** Returns true when the resolved app UI locale tag represents Hindi. */
    fun isHindiLocale(uiLocaleTag: String): Boolean =
        java.util.Locale.forLanguageTag(uiLocaleTag).language.equals("hi", ignoreCase = true)

    /** Model thinking wait label showing elapsed seconds and optional model name (Claude AI style) */
    fun modelWaitLabel(seconds: Int, modelName: String = "", isHindi: Boolean = true): String {
        val s = seconds.coerceAtLeast(0)
        val base = if (isHindi) "Soch raha hai · ${s}s" else "Thinking · ${s}s"
        return if (modelName.isNotBlank()) "$base · $modelName" else base
    }

    /** Real-time live action description (Claude AI style) showing what the agent is actively executing */
    fun liveActionLabel(tool: String?, note: String? = null, isHindi: Boolean = true): String? {
        if (tool.isNullOrBlank()) return null
        val noteLower = note?.lowercase().orEmpty()
        val toolLower = tool.lowercase()

        return when {
            toolLower.contains("launch") || toolLower == "phone_run" -> {
                when {
                    noteLower.contains("youtube") -> if (isHindi) "YouTube khol raha hai…" else "Opening YouTube…"
                    noteLower.contains("instagram") -> if (isHindi) "Instagram khol raha hai…" else "Opening Instagram…"
                    noteLower.contains("whatsapp") -> if (isHindi) "WhatsApp khol raha hai…" else "Opening WhatsApp…"
                    noteLower.contains("chrome") || noteLower.contains("browser") -> if (isHindi) "Browser khol raha hai…" else "Opening Browser…"
                    noteLower.contains("settings") -> if (isHindi) "Settings khol raha hai…" else "Opening Settings…"
                    noteLower.contains("spotify") -> if (isHindi) "Spotify khol raha hai…" else "Opening Spotify…"
                    else -> if (isHindi) "App khol raha hai…" else "Opening app…"
                }
            }
            toolLower.contains("type") -> {
                if (isHindi) "Type kar raha hai…" else "Typing…"
            }
            toolLower.contains("find") || toolLower.contains("locate") -> {
                if (isHindi) "Screen par dhoondh raha hai…" else "Finding on screen…"
            }
            toolLower.contains("observe") || toolLower.contains("screenshot") || toolLower.contains("view_user_screen") -> {
                if (isHindi) "Screen dekh raha hai…" else "Reading screen…"
            }
            toolLower.contains("tap") -> {
                if (isHindi) "Tap kar raha hai…" else "Tapping…"
            }
            toolLower.contains("scroll") || toolLower.contains("swipe") -> {
                if (isHindi) "Scroll kar raha hai…" else "Scrolling…"
            }
            toolLower.contains("back") -> {
                if (isHindi) "Wapas ja raha hai…" else "Going back…"
            }
            toolLower.contains("web_navigate") -> {
                when {
                    noteLower.contains("youtube") -> if (isHindi) "YouTube khol raha hai…" else "Opening YouTube…"
                    noteLower.contains("google") -> if (isHindi) "Google search kar raha hai…" else "Searching Google…"
                    else -> if (isHindi) "Web page khol raha hai…" else "Opening web page…"
                }
            }
            toolLower.contains("generate_image") -> {
                if (isHindi) "Image generate kar raha hai…" else "Generating image…"
            }
            toolLower.contains("edit_image") -> {
                if (isHindi) "Image edit kar raha hai…" else "Editing image…"
            }
            toolLower.contains("command") -> {
                if (isHindi) "Command chala raha hai…" else "Running command…"
            }
            toolLower.contains("ask_user") -> {
                if (isHindi) "Aap se poochh raha hai…" else "Asking question…"
            }
            else -> {
                val clean = tool.removePrefix("phone_").removePrefix("web_").replace('_', ' ')
                if (isHindi) "$clean kar raha hai…" else "${clean.replaceFirstChar { it.uppercase() }}…"
            }
        }
    }

    /** Model response wait label resolved from the active UI locale tag (R.string.ui_locale). */
    fun modelWaitLabelForLocale(
        seconds: Int,
        modelName: String = "",
        uiLocaleTag: String = tr(R.string.ui_locale),
    ): String = modelWaitLabel(seconds, modelName, isHindi = isHindiLocale(uiLocaleTag))
}
