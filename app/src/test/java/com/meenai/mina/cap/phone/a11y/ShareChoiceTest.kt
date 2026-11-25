package com.meenai.mina.cap.phone.a11y

import com.meenai.mina.settings.AppAccessStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The three options for actively sharing the floating layer only change the permissions as agreed: cancel without moving, only share without disabling, and allow this application to clear the sharing permission. */
class ShareChoiceTest {
    @get:Rule val temp = TemporaryFolder()
    private fun store() = AppAccessStore(java.io.File(temp.root, "access.json"))

    @Test fun allowingShareOnlyKeepsTheAppBlocked() {
        val store = store()
        store.setBlocked("app", true)
        assertTrue(applyShareChoice(1, "app", store))
        assertTrue(store.current().canShare("app"))
        assertEquals("app_blocked", store.current().denial("app")?.code)
    }

    @Test fun removingFromBlocklistRestoresCallsAndDropsTheShareGrant() {
        val store = store()
        store.setBlocked("app", true)
        store.setShareAllowed("app", true)
        assertTrue(applyShareChoice(2, "app", store))
        assertNull(store.current().denial("app"))
        // The independent sharing license has been cleared: from now on it will be a normal application
        // and no longer requires separate authorization.
        assertTrue(store.current().shareAllowed.isEmpty())
        assertTrue(store.current().canShare("app"))
    }

    @Test fun cancellingChangesNothing() {
        val store = store()
        store.setBlocked("app", true)
        val before = store.current()
        assertFalse(applyShareChoice(0, "app", store))
        assertFalse(applyShareChoice(7, "app", store))
        assertEquals(before.blocked, store.current().blocked)
        assertEquals(before.shareAllowed, store.current().shareAllowed)
        assertEquals(before.revision, store.current().revision)
    }
}
