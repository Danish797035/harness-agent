package com.meenai.mina.core.run

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.selects.select
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

/**
 * In this run, the signal (given by [RunInbox]) that "a user has interrupted but has not yet been handed
 * over to the model" is installed in the running coroutine context. Calls that won't lose anything if
 * you remove them (pure waiting, pauses and intervals before actions, just asking to see the picture
 * model) are wrapped by [waitOrSteer], and end early as soon as the interrupt arrives, allowing the
 * model to read this sentence immediately; actions that have already started are not affected by it.
 */
class SteerSignal internal constructor(internal val pending: StateFlow<Boolean>) : AbstractCoroutineContextElement(SteerSignal) {
    companion object Key : CoroutineContext.Key<SteerSignal>
}

/** Error code for the canceled call to the model: Nothing was done this time (the wait was cut off, the pause before the action was not completed), and the user's news was read first. */
const val SKIPPED_FOR_NEW_MESSAGE = "skipped_for_new_message"

/**
 * Nothing will be lost even if you cancel it after running for a while. [block]: The user's intervention
 * is already waiting or arrives while it is in progress, so cancel it and return null; otherwise, return
 * its result. It will run as usual when it is not running (no [SteerSignal]).
 */
suspend fun <T : Any> waitOrSteer(block: suspend () -> T): T? {
    val pending = coroutineContext[SteerSignal]?.pending ?: return block()
    if (pending.value) return null
    return coroutineScope {
        val work = async { block() }
        val steer = async { pending.first { it } }
        select<T?> {
            work.onAwait { steer.cancel(); it }
            steer.onAwait { work.cancel(); null }
        }
    }
}
