package com.meenai.mina.core.uistate

/** `pace` in site knowledge and app knowledge talks about the browsing speed requirements of this website or app (see [com.meenai.mina.core.cap.phone.AppPacing] for the rhythm in the app). */
enum class PlacePace {
    /** There is no special statement. */
    NORMAL,
    /** This website or app limits the browsing speed (if you browse too fast, you will be blocked): each page will be paused longer, pages viewed in one task, and the screen will be smaller. */
    CAREFUL;

    companion object {
        /** The value of the site knowledge header (`careful`; not written as null). */
        fun of(value: String?): PlacePace = if (value == "careful") CAREFUL else NORMAL
    }
}
