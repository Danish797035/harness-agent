package com.meenai.mina.task

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPreviewTest {
    // Only read web pages in temporary tags in the background: there are no tags on the web page
    // display, they are not included in the web page, and the small window does not appear (it used to
    // be a white background)
    @Test fun backgroundReadsAloneDoNotOpenThePreview() {
        val p = WebPreview.of(onWeb = true, hasTab = false, handedOver = false)
        assertEquals(WebPreview.NoPage, p)
        assertFalse(p.open)
    }

    // Open the visible page: put the screen
    @Test fun aVisibleTabShowsItsFrames() {
        val p = WebPreview.of(onWeb = true, hasTab = true, handedOver = false)
        assertEquals(WebPreview.Frames, p)
        assertTrue(p.open)
    }

    // The page is in the hands of the user: it is still on the web page and does not include the screen.
    @Test fun handedOverPageStaysOpenWithoutFrames() {
        val p = WebPreview.of(onWeb = true, hasTab = true, handedOver = true)
        assertEquals(WebPreview.Hidden, p)
        assertTrue(p.open)
    }

    @Test fun notOnTheWebIsOff() {
        assertEquals(WebPreview.Off, WebPreview.of(onWeb = false, hasTab = true, handedOver = false))
        assertEquals(WebPreview.Off, WebPreview.of(onWeb = false, hasTab = false, handedOver = true))
    }
}
