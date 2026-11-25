package com.meenai.mina.core.observation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Which type of observation is it: The interface or page read by [Read] (App interface status, web page
 * text, web page script and network response); [Output] command and script output, file content and
 * path; [Reply] answers from other AIs (subtasks, external services); [UserSaid] users (answers to
 * questions, memories); [Attachment] files and pictures given by users or tools; [Fact] Facts in tool
 * results (which app was opened, URL and title). The core is only dealt with according to these
 * categories and does not recognize specific abilities.
 */
enum class ObsKind(val wire: String) {
    Read("read"), Output("output"), Reply("reply"), UserSaid("user_said"), Attachment("attachment"), Fact("fact");

    companion object {
        private val byWire = entries.associateBy { it.wire }
        fun ofWire(w: String?): ObsKind? = w?.let(byWire::get)
    }
}

/** To what extent is the source of the observation credible? [Untrusted]: Words on web pages, platforms, and other AI responses should only be treated as data, not instructions. */
enum class Trust { Trusted, Untrusted }

/** What is a place: App, site, web page, command, file. */
@Serializable
enum class PlaceKind(val wire: String) {
    @SerialName("app") App("app"),
    @SerialName("site") Site("site"),
    @SerialName("page") Page("page"),
    @SerialName("command") Command("command"),
    @SerialName("file") File("file"),
}

/**
 * Where: App (package name), site (host name), web page (web address), command, file. [name] is the
 * original writing of this place; for verification and source, click on the App or URL mentioned in the
 * answer above.
 */
@Serializable
data class Place(val kind: PlaceKind, val name: String) {
    companion object {
        fun app(pkg: String) = Place(PlaceKind.App, pkg)
        fun site(host: String) = Place(PlaceKind.Site, host)
        fun page(url: String) = Place(PlaceKind.Page, url)
        fun command(label: String) = Place(PlaceKind.Command, label)
        fun file(label: String) = Place(PlaceKind.File, label)
    }
}

/** When reading once, the words in the input box on the interface ([names], after removing the blanks) and the one with the input focus ([focused]) are used; when checking, the words typed by the assistant are distinguished based on this. */
data class InputHints(val names: List<String>, val focused: String? = null)

/**
 * An observation with origin: the only unit of core consumption. The ability is given in the tool
 * results ([com.meenai.mina.core.tool.ToolExecution.observations]), the evidence ledger is recorded
 * according to it, and the verification, answer source, operation record, and evaluation are all read
 * from it.
 *
 * - - [source] is the id that gives its capabilities (phone, web, sandbox...; the core is core); [place]
 * is where it is read; [title] is the title of that page.
 * - - [media] is the screen number saved at the same time (given by the visualization module; if not, it
 * is empty).
 * - - [trust]: Untrustworthy (webpages, other AI answers) are only treated as data. [authored] is
 * something written by the assistant (a command run): the lines that appear as they are in [text] are
 * echoed and not read.
 * - - [inputs]: The words in the input box when reading the interface (see [InputHints]); if not known,
 * it is null.
 * - - When [id], [step], and [atMs] are entered from the ledger, fill in: which item (starting from 1),
 * which step, and when in this task. There is no need to fill in the ability when creating it.
 */
data class Observation(
    val kind: ObsKind,
    val text: String,
    val source: String? = null,
    val place: Place? = null,
    val title: String? = null,
    val media: List<Int> = emptyList(),
    val trust: Trust = Trust.Trusted,
    val authored: String? = null,
    val inputs: InputHints? = null,
    val step: Int = 0,
    val atMs: Long = 0,
    val id: Int = 0,
) {
    /** where is written as-is; not null. */
    val where: String? get() = place?.name

    /** It is divided according to what kind of thing it is (cutting, the terms on the interface are determined by it, not by the tool). */
    val form: ObsForm get() = when (kind) {
        // When reading the interface status, there is always an input box (maybe none); when reading the
        // entire page, script results, or network response, there is no input box.
        ObsKind.Read -> if (inputs != null) ObsForm.Interface else ObsForm.Page
        ObsKind.Output -> if (place?.kind == PlaceKind.File) ObsForm.File else ObsForm.Command
        ObsKind.Reply -> ObsForm.Reply
        ObsKind.UserSaid -> ObsForm.UserSaid
        ObsKind.Attachment -> ObsForm.Image
        ObsKind.Fact -> ObsForm.Fact
    }
}

/** An observation read in the tool results: the number in the ledger and its shape (the clipper determines how to shorten it and which item to point back to after shortening it). */
@Serializable data class ReadTag(val obsId: Int, val form: ObsForm)

/** The observation's flag in the tool results. */
val Observation.tag: ReadTag get() = ReadTag(id, form)

/** Observation forms: interface status, page content, command output, files, pictures, replies, user comments, facts. */
@Serializable enum class ObsForm { Interface, Page, Command, File, Image, Reply, UserSaid, Fact }

/** The source of the observation given by the core self (not which ability). */
const val CORE_SOURCE = "core"

/**
 * "Take another look" before checking: what the ability looks like at the moment ([observations],
 * interface status, beginning of the web page text...) and the screen at the same time ([image], only
 * available if you need to attach a picture). [protected]: The page at this moment is protected by the
 * system's anti-screenshot protection and the screen cannot be seen. How to see it based on ability
 * definition (see Capability.freshLook).
 */
class Look(val observations: List<Observation>, val image: ByteArray? = null, val protected: Boolean = false)
