package com.meenai.mina.task

/**
 * What does the picture-in-picture small window display in the web interface (pure logic, [TaskRun]
 * updates every half second by pressing it).
 *
 * The image of the small window comes from the current tab hanging on the web page display. When the
 * webpage is only read in the background temporary tag (`web_read_page` with address), there is no tag
 * on the webpage display, and the drawing is just a white background: it is not counted on the webpage
 * at this time, the small window does not appear, and the floating capsule is also displayed as a
 * conversation card; once the visible page is opened, it will be displayed as usual. When the page is in
 * the user's hands (being taken over), it is still counted as being on the web page, but the screen is
 * not displayed.
 */
enum class WebPreview(val open: Boolean) {
    /** Not on the webpage: Not on the webpage, the screen does not move (it has been cleared when changing the interface). */
    Off(false),

    /** On the web page, but there is no visible label (only read in the temporary label in the background): It is not counted on the web page, clear the screen. */
    NoPage(false),

    /** On the web page, the page is in the hands of the user: it is counted on the web page and does not include the screen. */
    Hidden(true),

    /** On the web page, there is a visible page: put the latest frame. */
    Frames(true);

    companion object {
        fun of(onWeb: Boolean, hasTab: Boolean, handedOver: Boolean): WebPreview = when {
            !onWeb -> Off
            !hasTab -> NoPage
            handedOver -> Hidden
            else -> Frames
        }
    }
}
