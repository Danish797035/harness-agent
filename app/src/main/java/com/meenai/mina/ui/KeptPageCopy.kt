package com.meenai.mina.ui

import com.meenai.mina.R
import com.meenai.mina.core.cap.web.KeptPageClose
import com.meenai.mina.i18n.tr

/** The page left running in the background says on the interface: how long it is left and why it was closed. */
object KeptPageCopy {
    /** "1 hour and 52 minutes", "2 hours", "8 minutes"; less than one minute is said as one minute. */
    fun duration(ms: Long): String {
        val minutes = ((ms.coerceAtLeast(0) + 59_999) / 60_000).coerceAtLeast(1)
        val h = (minutes / 60).toInt()
        val m = (minutes % 60).toInt()
        return when {
            h == 0 -> tr(R.string.kept_m, m)
            m == 0 -> tr(R.string.kept_h, h)
            else -> tr(R.string.kept_hm, h, m)
        }
    }

    /** There is still the saying of [ms] when it automatically turns off (it is spelled in "Automatically turn off after..."). */
    fun closesIn(ms: Long): String = duration(ms)

    /** The reason for shutting down: when the old record is not recognized (old record, not written down), press "Mina has exited". */
    fun reason(wire: String?): String = when (KeptPageClose.of(wire)) {
        KeptPageClose.Expired -> tr(R.string.kept_reason_expired)
        KeptPageClose.Memory -> tr(R.string.kept_reason_memory)
        KeptPageClose.Crashed -> tr(R.string.kept_reason_crashed)
        KeptPageClose.User -> tr(R.string.kept_reason_user)
        KeptPageClose.Replaced -> tr(R.string.kept_reason_replaced)
        KeptPageClose.AppExit, null -> tr(R.string.kept_reason_app_exit)
    }

    /** The following sentence in that round: "The background webpage has been closed (reason)"; the reason is not mentioned when the reason has not been recorded (it has just been closed, or Mina has exited midway). */
    fun closedNote(wire: String?): String = if (KeptPageClose.of(wire) == null) tr(R.string.kept_closed_plain) else tr(R.string.kept_closed_note, reason(wire))
}
