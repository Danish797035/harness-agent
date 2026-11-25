package com.meenai.mina.cap.web

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.meenai.mina.core.cap.web.WebUrlPolicy
import com.meenai.mina.takeover.HandoverActivity
import com.meenai.mina.task.TaskService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Web page takeover: Move the **same** WebView from the background host to [WebTakeoverPage] on the home
 * screen for user operation, and put it back after use. Page state (forms, scrolling, one-time tokens in
 * the login process) is retained as is. There can be at least one takeover in the process at the same
 * time.
 *
 * Two usages:
 * - - Task takeover ([show]): The buttons on the interface are handed over to the task service via
 * [Session.onAction] (processed/I finished it myself/cancel the task/leave the return button first);
 * When handing back, the takeover state machine calls [end], gets the number of operations and page
 * title during the takeover, and puts the view back into [Session.home].
 * - - Settings page login ([showLogin]): Open a new visible tab with only "Complete"; when finished,
 * take a look at the page. If you don't see that you are not logged in yet, the website will be recorded
 * as logged in.
 * When the task is taken over and handed back, it must be recorded if it can be seen on the page that
 * you have logged in ([LoginProbe]), otherwise it will not be recorded (the user may only process the
 * verification code).
 */
object WebHandover {
    enum class Action { HandBack, FinishedMyself, Cancel, PutAside, Done }

    /**
     * A takeover. When [home] is null, there is no background host (settings page login), and the label
     * is destroyed at the end. [reason] is null to set the page login mode. [message] is an explanation
     * for the user (bring it along with payment information).
     */
    class Session internal constructor(
        val tab: WebTab,
        val home: WebHost?,
        val reason: String?,
        val message: String?,
        val waitKey: Int,
        val onAction: (Action) -> Unit,
    ) {
        /** The number of user touches and keystrokes on the page in the takeover interface. */
        @Volatile var inputs: Int = 0
            internal set

        /** Take over the page address and title recorded at any time on the main thread of the interface: it may be on another thread at the end and the WebView cannot be read. */
        @Volatile var url: String? = null
            internal set
        @Volatile var title: String? = null
            internal set
        val loginMode: Boolean get() = reason == null
    }

    private val main = Handler(Looper.getMainLooper())
    /** Set the upper limit for page response after the page login point is "completed". */
    private const val CONFIRM_WAIT_MS = 1_500L

    /** The website login of the settings page is open: the task's web page takeover needs to wait until it is closed. */
    val loginOpen: Boolean get() = _session.value?.loginMode == true
    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session.asStateFlow()

    /**
     * Task takeover: Open the takeover interface and move the label over. Returns false if there is
     * already a takeover in progress, the tag does not belong to [taskId], or it is no longer available.
     * [currentKey] gives the waiting number of the task at this moment: when the button is pressed, it
     * has changed (this time the waiting has passed) and there is no action.
     */
    fun show(
        context: Context, tab: WebTab, home: WebHost, reason: String, message: String?, waitKey: Int, taskId: String,
        onAction: (Action) -> Unit = taskActions(context.applicationContext, waitKey, null),
        currentKey: (() -> Int?)? = null,
    ): Boolean {
        if (_session.value != null || tab.crashed || tab.destroyed || home.closed || tab.ownerTaskId != taskId) return false
        val guarded: (Action) -> Unit = { a ->
            val now = currentKey?.invoke()
            // "Cancel task" will count no matter which time you wait.
            if (a != Action.Cancel && currentKey != null && now != waitKey) android.util.Log.i("MinaWebHandover", "ignored $a for a past wait ($waitKey, now $now)")
            else onAction(a)
        }
        _session.value = Session(tab, home, reason, message, waitKey, guarded)
        context.startActivity(WebTakeoverPage.intent(context))
        return true
    }

    /** "Open login" on the settings page: Open a visible tab on the home screen, log in as a user, and click "Finish" to end. Called by the main thread. */
    fun showLogin(context: Context, host: String, onDone: (String) -> Unit = { WebSites.registry(context).markLoggedIn(it) }): Boolean {
        if (_session.value != null) return false
        val url = "https://${host.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')}/"
        if (WebUrlPolicy.checkNavigable(url) != null) return false
        val tab = WebTab(context.applicationContext, "login", "settings")
        tab.view.loadUrl(url)
        _session.value = Session(tab, null, null, null, -1) { action ->
            if (action != Action.Done) {
                end()
                return@Session
            }
            // The button is pressed on the main thread: the user says it is finished, and if it is not
            // visible on the page and is not logged in, write it down; close the tab after reading it.
            val fallback = (tab.view.url ?: _session.value?.url)?.let(WebUrlPolicy::host)
            val decided = java.util.concurrent.atomic.AtomicBoolean(false)
            fun finish(host: String?, state: LoginState) {
                if (!decided.compareAndSet(false, true)) return
                if (host != null && state != LoginState.SignedOut) onDone(host)
                end()
            }
            LoginProbe.probe(tab) { host, state -> finish(host, state) }
            main.postDelayed({ finish(fallback, LoginState.Unknown) }, CONFIRM_WAIT_MS)
        }
        context.startActivity(WebTakeoverPage.intent(context))
        return true
    }

    /**
     * End this takeover: the interface is turned off and the view is returned to the background host
     * (the label is destroyed when logging in to the settings page). Returns the number of operations
     * and page title during the takeover; null if no takeover is in progress. When [loggedIn] (user
     * handed back), put the view back and take a look at the page. You can see that you have logged in
     * and then record it in [registry]. Any thread is adjustable.
     */
    fun end(loggedIn: Boolean = false, registry: SiteRegistry? = null, tab: WebTab? = null): HandoverActivity? {
        val s = _session.value ?: return null
        // If [tab] is given, only the takeover of this tag will be ended (other tasks will not move
        // during parallel operation); only one time will be ended at the same time.
        if (tab != null && s.tab !== tab) return null
        if (!_session.compareAndSet(s, null)) return null
        main.post {
            restore(s)
            if (loggedIn && registry != null) LoginProbe.probe(s.tab) { host, state ->
                if (state == LoginState.SignedIn) registry.markLoggedIn(host)
                else android.util.Log.i("MinaWebHandover", "takeover on $host handed back; sign-in not confirmed ($state), not recorded")
            }
        }
        return HandoverActivity(s.inputs, s.title?.takeIf { it.isNotBlank() })
    }

    /** The view is put back into the background host; if there is no host (settings page login), it is destroyed. */
    internal fun restore(s: Session) {
        if (s.tab.destroyed) return
        val home = s.home
        if (home == null) {
            s.tab.destroy()
            return
        }
        // The tag has crashed or the host has been shut down (the task has ended): no hanging back,
        // leaving the tag pool to clean up.
        if (s.tab.crashed || home.closed) {
            (s.tab.view.parent as? android.view.ViewGroup)?.removeView(s.tab.view)
            return
        }
        home.attach(s.tab)
    }

    /** Task takeover button → task service action, with the waiting number and task number (when multiple tasks are running at the same time, only this one will be affected). */
    fun taskActions(context: Context, waitKey: Int, runKey: String?): (Action) -> Unit = { action ->
        when (action) {
            Action.HandBack, Action.Done -> TaskService.handBack(context, waitKey, runKey)
            Action.FinishedMyself -> TaskService.finishedMyself(context, waitKey, runKey)
            Action.Cancel -> TaskService.cancel(context, runKey)
            Action.PutAside -> TaskService.putAside(context, waitKey, runKey)
        }
    }
}
