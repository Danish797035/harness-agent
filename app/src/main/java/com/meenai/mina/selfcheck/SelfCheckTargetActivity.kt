package com.meenai.mina.selfcheck

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Display
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.view.View
import com.meenai.mina.R
import com.meenai.mina.i18n.tr
import com.meenai.mina.cap.phone.shell.SystemIntents
import java.lang.ref.WeakReference

/**
 * The page where the compatibility self-test is operated on the Mina virtual screen: a counting button,
 * an input box, a scrollable row, a row of numbers that keep jumping, and a horizontal bar that changes
 * color back and forth (the screen keeps changing, so you can see whether the virtual screen will stop
 * moving after a few seconds). The page states that this is Mina's own test page.
 *
 * Exported from the manifest, but the caller is required to hold INTERACT_ACROSS_USERS_FULL (available
 * in the shell and system, but not available in ordinary apps). There is no intent-filter: only the
 * built-in assistant opens it to Mina's screen by the component name as a shell (see [intent]). If you
 * open the home screen (screen number is 0), turn it off immediately so as not to disturb the user.
 * Controls use ordinary View and resource IDs; text is taken according to the App language ([tr]), and
 * the same text is used to identify controls on the self-test side (controls with text during screen
 * reading do not have resource IDs).
 */
class SelfCheckTargetActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var taps = 0
    private var ticks = 0
    private lateinit var count: TextView
    private lateinit var tick: TextView
    private lateinit var strip: View

    private val ticker = object : Runnable {
        override fun run() {
            ticks++
            tick.text = tr(R.string.selfcheck_target_tick, ticks)
            strip.setBackgroundColor(if (ticks % 2 == 0) STRIP_DARK else STRIP_LIGHT)
            main.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if ((display?.displayId ?: Display.DEFAULT_DISPLAY) == Display.DEFAULT_DISPLAY) {
            finish()
            return
        }
        current = WeakReference(this)
        setContentView(content())
    }

    // When you open it again, start from the beginning: clear the count, clear the input box, and scroll
    // back to the top.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setContentView(content())
    }

    // Keep jumping as long as you can see it (the page on the virtual screen may not be focused, and it
    // does not count if onResume is pressed)
    override fun onStart() {
        super.onStart()
        main.removeCallbacks(ticker)
        if (::tick.isInitialized) main.post(ticker)
    }

    override fun onStop() {
        super.onStop()
        main.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        main.removeCallbacks(ticker)
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    private fun content(): View {
        taps = 0
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.WHITE)
        }
        fun text(id: Int, s: String, size: Float) = TextView(this).apply {
            this.id = id
            text = s
            textSize = size
            setTextColor(Color.BLACK)
            setPadding(0, pad / 4, 0, pad / 4)
        }
        column.addView(text(R.id.selfcheck_title, tr(R.string.selfcheck_target_title), 20f))
        column.addView(text(R.id.selfcheck_note, tr(R.string.selfcheck_target_note), 14f))
        count = text(R.id.selfcheck_count, tr(R.string.selfcheck_target_taps, 0), 16f)
        column.addView(count)
        column.addView(Button(this).apply {
            id = R.id.selfcheck_button
            text = tr(R.string.selfcheck_target_button)
            // By default, the button is displayed in all caps according to the theme, so what you read
            // when reading the screen will change: it will be displayed as written.
            isAllCaps = false
            setOnClickListener {
                taps++
                count.text = tr(R.string.selfcheck_target_taps, taps)
            }
        })
        column.addView(EditText(this).apply {
            id = R.id.selfcheck_input
            hint = tr(R.string.selfcheck_target_input)
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        tick = text(R.id.selfcheck_tick, tr(R.string.selfcheck_target_tick, ticks), 14f)
        column.addView(tick)
        strip = View(this).apply { setBackgroundColor(STRIP_DARK) }
        column.addView(strip, LinearLayout.LayoutParams(MATCH_PARENT, pad * 4))
        val rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (i in 1..ROWS) rows.addView(text(View.NO_ID, tr(R.string.selfcheck_target_row, i), 16f).apply { setPadding(0, pad, 0, pad) })
        column.addView(ScrollView(this).apply {
            id = R.id.selfcheck_list
            addView(rows)
        }, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return column
    }

    companion object {
        private const val TICK_MS = 250L
        private const val ROWS = 60
        private val STRIP_DARK = Color.rgb(0x33, 0x33, 0x33)
        private val STRIP_LIGHT = Color.rgb(0xEE, 0xEE, 0xEE)

        @Volatile private var current: WeakReference<SelfCheckTargetActivity>? = null

        /** The intent used to open this page: indicates the component, a single task, and does not enter the recent tasks. */
        fun intent(context: Context): Intent = Intent()
            .setClassName(context.packageName, SystemIntents.OWN_PAGE_CLASS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** Self-test ended or canceled: Turn off if it is still on. Main thread adjustment. */
        fun finishOpen() {
            current?.get()?.finish()
            current = null
        }
    }
}
