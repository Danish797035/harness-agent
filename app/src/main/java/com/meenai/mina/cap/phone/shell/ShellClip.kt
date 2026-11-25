package com.meenai.mina.cap.phone.shell

/**
 * The judgment of swapping out and restoring the clipboard does not touch the system clipboard, which is
 * convenient for single testing. Reading the clipboard may be silently blocked by the system: the
 * retrieved content is empty, but "Is there any content?" and "Content description" show yes. At this
 * time, it is difficult to distinguish between "it is empty" and "it has content but cannot be seen". It
 * is assumed that it cannot be read and cannot be cleared or overwritten as if it is empty.
 */
internal object ShellClip {
    enum class Read {
        /** It is indeed empty (no content, presence, or description). */
        Empty,
        /** Read the content. */
        Has,
        /** There is content but cannot be read. */
        Unreadable,
    }

    fun read(clipPresent: Boolean, hasPrimaryClip: Boolean, descriptionPresent: Boolean): Read = when {
        clipPresent -> Read.Has
        hasPrimaryClip || descriptionPresent -> Read.Unreadable
        else -> Read.Empty
    }

    /** Restore the current clipboard appearance. */
    enum class Now { Ours, Empty, Other, Unreadable }

    enum class Restore {
        /** Put the user's original back. */
        PutBack,
        /** It turns out to be empty, clear Mina's. */
        Clear,
        /** Not moving: The user copied something else midway, or cannot read it at the moment. */
        Leave,
    }

    /**
     * [priorEmpty] is true, which means that what is read before swapping out is indeed empty (if it
     * cannot be read, it will not be swapped out at all). If the clipboard is cleared by others (for
     * example, the system automatically clears it regularly) and the original content is put back: the
     * empty clipboard is not something newly copied by the user, and putting it back will not cover the
     * user's content; if it is not put back, the user's original content will be lost.
     */
    fun restore(priorEmpty: Boolean, now: Now): Restore = when (now) {
        Now.Ours -> if (priorEmpty) Restore.Clear else Restore.PutBack
        Now.Empty -> if (priorEmpty) Restore.Leave else Restore.PutBack
        Now.Other, Now.Unreadable -> Restore.Leave
    }
}
