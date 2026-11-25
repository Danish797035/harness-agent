package com.meenai.mina.ui

import com.meenai.mina.cap.web.KeptPage
import com.meenai.mina.i18n.ChineseTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** The line left under the dialogue page: still on, off (with reason or not), and the remaining time. */
class KeptPageCopyTest {
    @get:Rule val texts = ChineseTexts()

    private val page = KeptPage(1, "c1", "r1", "播放课程", "course.example.com", null, null, 0, 7_200_000)
    private fun ex(runId: String?, kept: String? = null, closed: String? = null) =
        Exchange("播放课程", emptyList(), ExchangeStatus.Done("好了", null), live = false, runId = runId, keptPage = kept, keptPageClosed = closed)

    @Test fun rowFollowsTheLivePageAndTheTurnRecord() {
        assertEquals(KeptRow.Live(page), KeptRow.of(ex("r1", kept = "course.example.com"), page))
        // When the round has just ended and the record of the remaining page has not been read back in
        // that round, it will still be displayed according to the page left at the moment.
        assertEquals(KeptRow.Live(page), KeptRow.of(ex("r1"), page))
        assertEquals(KeptRow.Closed("expired"), KeptRow.of(ex("r1", kept = "course.example.com", closed = "expired"), null))
        // Pages left in other rounds will not be attached to this round.
        assertEquals(KeptRow.Closed(null), KeptRow.of(ex("r0", kept = "a.example.com"), page))
        assertNull(KeptRow.of(ex("r2"), page))
        assertNull(KeptRow.of(ex(null), null))
    }
}
