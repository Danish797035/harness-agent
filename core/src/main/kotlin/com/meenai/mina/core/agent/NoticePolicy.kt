package com.meenai.mina.core.agent

import com.meenai.mina.core.guard.DIAGNOSTIC_VERBS
import com.meenai.mina.core.observation.Place
import com.meenai.mina.core.observation.PlaceKind
import com.meenai.mina.core.prompt.Feedback
import com.meenai.mina.core.tool.Verb

/** After a tool results, click on the reference materials it looks for. */
internal sealed interface KnowledgeLookup {
    /** Data of the front-end App. */
    data class App(val pkg: String) : KnowledgeLookup
    /** Website information; [host] has been lowercase and www. has been removed. Each site is only found when entering for the first time. */
    data class Site(val host: String) : KnowledgeLookup
}

/**
 * What follows the result of a tool: which description to accompany the attached picture, and which
 * reference material to look for wherever you go. Pure function.
 */
internal object NoticePolicy {
    /** Look at the interface on the web page, read the page, open the web page: you have arrived at the site. */
    private val SITE_RESULT_VERBS = setOf(Verb.Observe, Verb.ListElements, Verb.Navigate, Verb.ReadPage)

    /** The description of the picture attached to the result: [offScreen] is true, which means that it is an image file read by a tool that does not move the screen, not a screen image. */
    fun imageNote(verb: Verb?, offScreen: Boolean): String = when {
        // Looking back at the reissued picture, I put it here. It is a new picture for cropping.
        verb == Verb.ViewPastScreens -> Feedback["recalled_note"]
        offScreen -> Feedback["file_image_note"]
        verb in DIAGNOSTIC_VERBS -> Feedback["screenshot_note"]
        // The screen automatically attached after the action is successful; only it is the latest,
        // earlier screens will be replaced with placeholders that can be viewed back.
        else -> Feedback["action_frame_note"]
    }

    /**
     * Reference materials to look for after arriving at [place]: Look for the interface of an App and
     * look for this App; look at the interface, read pages and open the web page and look for this site
     * by domain name. Whether the same site has already been found is logged by the caller. [ok]
     * indicates whether the call was successful or not.
     */
    fun knowledgeAfter(verb: Verb?, place: Place?, ok: Boolean): List<KnowledgeLookup> = when {
        place == null -> emptyList()
        place.kind == PlaceKind.App && (verb == Verb.Observe || verb == Verb.ListElements) -> listOf(KnowledgeLookup.App(place.name))
        place.kind == PlaceKind.Site && verb in SITE_RESULT_VERBS && ok -> listOf(KnowledgeLookup.Site(place.name.lowercase().removePrefix("www.")))
        else -> emptyList()
    }
}
