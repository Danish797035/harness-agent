package com.meenai.mina.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue

/** Pages in the App. The dialog is the root page, everything else goes in from it (or from settings), and when you return, you go back to the page you came in from. */
enum class Route { Chat, Settings, Memory, Plans, Browser, Run, Source, PhoneSetup, Models, SpeechModel, Welcome, ModelSetup, About, License, Screens, Vault }

/**
 * Page stack: The bottom is always the conversation page. [push] goes forward one page, [back] goes back
 * to the previous page (returns false when already on the dialog page, and leaves it to the system to
 * exit). The return key and the return button on the page use the same [back], so they are always
 * consistent.
 */
@Stable
class NavStack(initial: List<Route> = listOf(Route.Chat), fromDrawer: Boolean = false) {
    var stack: List<Route> by mutableStateOf(normalize(initial))
        private set

    /** This list of pages is entered from the sidebar: when returning to the conversation page, you must return to the open sidebar. */
    private var fromDrawer = fromDrawer && stack.size > 1

    /** The page you just entered from the sidebar returns to the conversation page, and the sidebar should be reopened; after opening, call [drawerReopened]. */
    var reopenDrawer: Boolean by mutableStateOf(false)
        private set

    val current: Route get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    /** Advance one page; do not push the stack again when already on this page. */
    fun push(route: Route) {
        if (route == Route.Chat) return reset()
        if (current != route) stack = stack + route
    }

    /** Enter a page from the sidebar: When you return all the way to the conversation page later, the sidebar is open. */
    fun pushFromDrawer(route: Route) {
        fromDrawer = true
        push(route)
    }

    fun back(): Boolean {
        if (!canGoBack) return false
        stack = stack.dropLast(1)
        if (!canGoBack && fromDrawer) {
            fromDrawer = false
            reopenDrawer = true
        }
        return true
    }

    fun drawerReopened() {
        reopenDrawer = false
    }

    /** Only two layers are left: the dialogue page and [route] (used for every step of getting started for the first time). */
    fun show(route: Route) {
        fromDrawer = false
        stack = normalize(listOf(route))
    }

    /** Return to the conversation page (when opening a conversation). */
    fun reset() {
        fromDrawer = false
        reopenDrawer = false
        stack = listOf(Route.Chat)
    }

    companion object {
        private const val DRAWER_MARK = "fromDrawer"

        private fun normalize(list: List<Route>): List<Route> =
            listOf(Route.Chat) + list.filter { it != Route.Chat }

        val Saver: Saver<NavStack, ArrayList<String>> = Saver(
            save = { ArrayList(it.stack.map(Route::name) + listOfNotNull(DRAWER_MARK.takeIf { _ -> it.fromDrawer })) },
            restore = { names ->
                NavStack(names.mapNotNull { n -> Route.entries.firstOrNull { it.name == n } }, fromDrawer = DRAWER_MARK in names)
            },
        )
    }
}

@Composable
fun rememberNavStack(): NavStack = rememberSaveable(saver = NavStack.Saver) { NavStack() }
