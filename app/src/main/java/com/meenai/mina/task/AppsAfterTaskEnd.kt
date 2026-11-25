package com.meenai.mina.task

import android.util.Log
import com.meenai.mina.cap.phone.shell.AppsAfterTask
import com.meenai.mina.core.uistate.AfterTaskMarks
import com.meenai.mina.shell.IHarnessShell

/**
 * What to do with the App on the Mina screen at the end of the task: Leave the model marked to the
 * built-in assistant ([IHarnessShell.finishDisplay]), and let it stay or close it according to the rules
 * ([AppsAfterTask]), and then release the virtual screen. Adjust before restoring to mute: turn off the
 * ones that are making sounds first, so that the remaining ones will not suddenly sound when restoring.
 * When the assistant is not completed (an error occurs, the screen is not recognized), the screen is
 * still released at the end, and the above App is closed along with the screen.
 */
internal object AppsAfterTaskEnd {
    private const val TAG = "AppsAfterTask"

    /** The result at the end of the latest task in this process (a row for diagnostic information, only the number); if not, it is null. */
    @Volatile var lastLine: String? = null
        private set

    data class Outcome(val kept: List<String>, val closed: List<String>, val note: String?) {
        /** A sentence in the running record: the keys and reasons for leaving and turning off. */
        val detail: String
            get() = listOfNotNull(
                "kept ${kept.joinToString(",").ifEmpty { "-" }}",
                "closed ${closed.joinToString(",").ifEmpty { "-" }}",
                note,
            ).joinToString("; ")

        val diagnostics: String get() = AppsAfterTask.diagnosticsLine(kept.size, closed, note)
    }

    fun finish(shell: IHarnessShell, displayId: Int, marks: AfterTaskMarks?): Outcome {
        val keep = marks?.keepKeys().orEmpty().toTypedArray()
        val close = marks?.closeKeys().orEmpty().toTypedArray()
        val result = try {
            shell.finishDisplay(displayId, keep, close)
        } catch (t: Throwable) {
            Log.w(TAG, "finishDisplay failed; apps close with the display", t)
            null
        }
        val outcome = if (result == null) Outcome(emptyList(), emptyList(), "closed with the display (helper did not sort them)")
        else Outcome(
            result.getStringArray(AppsAfterTask.KEY_KEPT)?.toList().orEmpty(),
            result.getStringArray(AppsAfterTask.KEY_CLOSED)?.toList().orEmpty(),
            result.getString(AppsAfterTask.KEY_NOTE),
        )
        lastLine = outcome.diagnostics
        return outcome
    }
}
