package com.meenai.mina.core.uistate

import com.meenai.mina.core.access.AppAccessDenied

/** The best efforts of screen readers cannot reduce permission denial to no content. */
internal fun Exception.rethrowAppAccess() {
    if (this is AppAccessDenied) throw this
}

/** Ordinary collection failure can be downgraded; permission denial and cancellation must be returned to the caller. */
internal suspend inline fun <T> screenAttempt(body: () -> T): T? = try {
    body()
} catch (e: kotlinx.coroutines.CancellationException) {
    throw e
} catch (e: Exception) {
    e.rethrowAppAccess()
    null
}
