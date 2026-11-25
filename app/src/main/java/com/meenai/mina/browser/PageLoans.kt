package com.meenai.mina.browser

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The page you are looking at on the browser page is lent to the task (the user asked Mina on this
 * page): When the user asks, he first "agrees" to lend it to that conversation ([offer]); when the task
 * of that conversation starts running, he will get it ([claim]). After getting it, the label will be the
 * current web page label of the task, and the task side will be connected ([bind]). Only one copy can be
 * borrowed at the same time (only one visible page). Life and death still belong to the browser page:
 * - - Task end ([end]): Return, the page remains in front of the user;
 * - - The visible label has been changed ([swapped], the window that pops up when the page is opened or
 * closed): the borrowed one will be replaced with the visible one;
 * - - The user closes the page ([closing]): The page is handed over to the task for further use (the
 * browser page will not be destroyed after it is accepted), and the browser page will be closed when it
 * cannot be accepted;
 * - - The user touched the page ([touched]): tell the task (the task is paused accordingly).
 * See [Borrower] on the mission side. [T] is the label (WebTab in the interface), change it to something
 * else for testing. Only called on the main thread.
 */
class PageLoans<T : Any> {
    /** Borrow the task side of the label to do. */
    interface Borrower<T : Any> {
        /** Use it as the current label (borrowed). Returns false if borrowing is not possible. */
        fun take(tab: T): Boolean
        /** Return it (no longer use it). */
        fun giveBack(tab: T)
        /** The borrowed one was replaced by [next]. */
        fun swap(prev: T, next: T)
        /** The user closes the page: accept [tab] and use it as its own label; if it cannot be accepted, it returns false. */
        fun adopt(tab: T): Boolean
        /** The user touched this page with their own hands. */
        fun touched()
    }

    /** A loaned copy: The task of running [runKey] in the conversation [conversationId] is borrowed from [tab]; [touches] is touched several times by the user during the loan period. */
    class Loan<T : Any> internal constructor(val conversationId: String, val runKey: String, tab: T) {
        var tab: T = tab
            internal set
        var touches: Int = 0
            internal set
        internal var borrower: Borrower<T>? = null
    }

    private var offered: String? = null
    private var loan: Loan<T>? = null

    private val _lentTo = MutableStateFlow<String?>(null)
    /** The dialog that is borrowing the page at this time; if it is not borrowed, it is null. */
    val lentTo: StateFlow<String?> = _lentTo.asStateFlow()

    /** The share that is lent out at this time; is not null. */
    val current: Loan<T>? get() = loan

    /** The user asked on the page and agreed to lend the next task to the conversation [conversationId] (replacing the previously agreed one). */
    fun offer(conversationId: String) {
        offered = conversationId
    }

    /** That conversation that was promised and hasn’t been taken away yet. */
    val offeredTo: String? get() = offered

    /** The page is gone, and what was promised is invalid. */
    fun withdraw() {
        offered = null
    }

    /**
     * The task [runKey] of the dialogue [conversationId] comes to pick it up when it starts running: if
     * you promised to this dialogue, there is a visible page [tab] at this time, and it is not lent to
     * others, the promised will be used accordingly; otherwise, it is null.
     */
    fun claim(conversationId: String, runKey: String, tab: T?): Loan<T>? {
        if (offered != conversationId || tab == null || loan != null) return null
        offered = null
        return Loan(conversationId, runKey, tab).also {
            loan = it
            _lentTo.value = conversationId
        }
    }

    /**
     * The task side is ready: connect [borrower] and borrow the current tag into it. If the loan has
     * been withdrawn (the page has been closed before), or if the loan cannot be borrowed, false is
     * returned, and the portion that cannot be borrowed will be invalidated.
     */
    fun bind(l: Loan<T>, borrower: Borrower<T>): Boolean {
        if (loan !== l) return false
        if (!borrower.take(l.tab)) {
            clear()
            return false
        }
        l.borrower = borrower
        return true
    }

    /** Task [runKey] ends: Return what you borrowed. It’s not that it can’t be borrowed. */
    fun end(runKey: String) {
        val l = loan?.takeIf { it.runKey == runKey } ?: return
        clear()
        l.borrower?.giveBack(l.tab)
    }

    /** The visible label is changed from [prev] to [next]: it is also changed when the borrowed one is [prev]. */
    fun swapped(prev: T, next: T) {
        val l = loan?.takeIf { it.tab == prev } ?: return
        l.tab = next
        l.borrower?.swap(prev, next)
    }

    /**
     * The browser page needs to close the tag [tab]: it will return true if its task is accepted (the
     * caller will no longer destroy it and no longer care about it), otherwise false. Regardless of
     * whether it is collected or not, this one is over; anything promised but not taken away is also
     * invalid.
     */
    fun closing(tab: T): Boolean {
        offered = null
        val l = loan?.takeIf { it.tab == tab } ?: return false
        clear()
        val b = l.borrower ?: return false
        if (b.adopt(tab)) return true
        b.giveBack(tab)
        return false
    }

    /** The user touched the visible label with his own hands [tab]: He knew it through its task. */
    fun touched(tab: T) {
        val l = loan?.takeIf { it.tab == tab } ?: return
        l.touches++
        l.borrower?.touched()
    }

    private fun clear() {
        loan = null
        _lentTo.value = null
    }
}
