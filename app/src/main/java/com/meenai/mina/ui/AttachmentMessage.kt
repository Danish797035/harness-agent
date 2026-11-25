package com.meenai.mina.ui

import com.meenai.mina.R
import com.meenai.mina.core.agent.AttachmentLines
import com.meenai.mina.i18n.tr
import com.meenai.mina.i18n.trPlural
import com.meenai.mina.ui.viewer.ViewerKind
import com.meenai.mina.ui.viewer.ViewerKinds

/**
 * Attachments included in the user's words (one line for each attachment at the end of the message, see
 * [AttachmentLines] for writing): The conversation page draws attachments into photo grids and file
 * cards based on this, and the lines of the path are not shown to the user.
 */
object AttachmentMessage {
    /** An attachment: the path in the workspace and the size written in the message ("2 KB"). */
    data class Item(val path: String, val size: String) {
        val name: String get() = path.substringAfterLast('/')
        /** Identify photos by extension; photos are arranged in a grid, and the rest are file cards. */
        val photo: Boolean get() = isPhoto(name)
    }

    /** The split sentence: [photos], [files] in order of appearance, [words] is other than the attachment line (remove the leading and trailing blanks). */
    data class Parsed(val photos: List<Item>, val files: List<Item>, val words: String) {
        val hasAttachments: Boolean get() = photos.isNotEmpty() || files.isNotEmpty()
    }

    fun parse(text: String): Parsed {
        val p = AttachmentLines.parse(text)
        val items = p.files.map { (path, size) -> Item(path, size) }
        return Parsed(items.filter { it.photo }, items.filterNot { it.photo }, p.words)
    }

    /** After removing the attachment line; when only sending the attachment, it is a salutation ([label]). Use it for titles and sidebars. */
    fun plain(text: String): String {
        val p = parse(text)
        return if (!p.hasAttachments) text else p.words.ifEmpty { label(p) }
    }

    /** What to call when only attachments are sent: "photo", "3 photos", "contract.pdf", "contract.pdf and other 2 files", "photos and documents". */
    fun label(p: Parsed): String = when {
        p.photos.isNotEmpty() && p.files.isNotEmpty() -> tr(R.string.attach_photos_and_files)
        p.photos.size == 1 -> tr(R.string.attach_photo)
        p.photos.isNotEmpty() -> trPlural(R.plurals.attach_n_photos, p.photos.size)
        p.files.size == 1 -> p.files[0].name
        p.files.isNotEmpty() -> tr(R.string.attach_file_and_more, p.files[0].name, p.files.size)
        else -> ""
    }

    fun isPhoto(name: String): Boolean = ViewerKinds.of(null, name) == ViewerKind.Image
}

/** How to arrange the photos in a grid: 1 photo to a larger size (maintaining the proportion), 2 photos side by side, 3 photos in three columns, and if there are more photos, wrap them in new lines. */
object PhotoGrid {
    fun columns(count: Int): Int = when {
        count <= 1 -> 1
        count == 2 -> 2
        else -> 3
    }

    fun rows(count: Int): Int = if (count <= 0) 0 else (count + columns(count) - 1) / columns(count)
}

/** Type identifier on the file card: extension (up to 4 letters) and color grouping. */
object FileBadge {
    enum class Kind { Pdf, Sheet, Doc, Slides, Archive, Other }

    fun text(name: String): String = ext(name).uppercase().take(4).ifEmpty { tr(R.string.attach_files) }

    fun kind(name: String): Kind = when (ext(name)) {
        "pdf" -> Kind.Pdf
        "xls", "xlsx", "csv", "tsv", "numbers", "ods" -> Kind.Sheet
        "doc", "docx", "txt", "md", "rtf", "pages", "odt" -> Kind.Doc
        "ppt", "pptx", "key", "odp" -> Kind.Slides
        "zip", "rar", "7z", "gz", "tar", "tgz" -> Kind.Archive
        else -> Kind.Other
    }

    private fun ext(name: String): String = name.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }
}

/** Attachments to be sent above the input box: up to [LIMIT] (photos and documents are counted together), the same address is only counted once. */
object AttachTray {
    const val LIMIT = 9

    /** Add in: existing items will be skipped, items exceeding the upper limit will not be collected; a new list will be returned and the next number will be confiscated. */
    fun add(current: List<PendingAttachment>, items: List<PendingAttachment>): Pair<List<PendingAttachment>, Int> {
        val out = current.toMutableList()
        var dropped = 0
        for (i in items) {
            if (out.any { it.uri == i.uri }) continue
            if (out.size >= LIMIT) dropped++ else out += i
        }
        return out to dropped
    }

    /** Click: remove the selected ones and add the unselected ones (don’t add if it is full, [Toggle.full] is true). */
    fun toggle(current: List<PendingAttachment>, item: PendingAttachment): Toggle =
        if (current.any { it.uri == item.uri }) Toggle(current.filterNot { it.uri == item.uri }, full = false)
        else if (current.size >= LIMIT) Toggle(current, full = true)
        else Toggle(current + item, full = false)

    data class Toggle(val items: List<PendingAttachment>, val full: Boolean)

    /** What is the photo number in the to-be-posted list (starting from 1, only counting photos); if not selected, null. The serial number in the upper right corner of the photo grid. */
    fun order(current: List<PendingAttachment>, uri: String): Int? =
        current.filter { it.photo }.indexOfFirst { it.uri == uri }.takeIf { it >= 0 }?.plus(1)

    /** There are words or attachments, but they cannot be sent when copying. */
    fun canSend(text: String, items: List<PendingAttachment>, importing: Boolean): Boolean =
        (text.isNotBlank() || items.isNotEmpty()) && !importing
}
